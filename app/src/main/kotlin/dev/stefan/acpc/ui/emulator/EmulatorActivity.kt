package dev.stefan.acpc.ui.emulator

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import dev.stefan.acpc.R
import dev.stefan.acpc.core.api.EmulatorException
import dev.stefan.acpc.core.disk.AmsdosCatalog
import dev.stefan.acpc.core.disk.DskFormat
import dev.stefan.acpc.core.joystick.JoystickButton
import dev.stefan.acpc.core.keyboard.CpcKey
import dev.stefan.acpc.databinding.ActivityEmulatorBinding
import dev.stefan.acpc.emulator.EmulatorHolder
import dev.stefan.acpc.emulator.EmulatorSession
import dev.stefan.acpc.input.GamepadMapper
import dev.stefan.acpc.input.JoystickOverlayView
import dev.stefan.acpc.input.KeyMapper
import dev.stefan.acpc.input.OverlayLayout
import dev.stefan.acpc.input.PhysicalKeyQueue
import dev.stefan.acpc.input.VirtualKeyboardView
import dev.stefan.acpc.settings.AppSettings
import dev.stefan.acpc.storage.GameLibrary
import dev.stefan.acpc.ui.settings.SettingsActivity
import java.text.DateFormat
import java.util.Date

/**
 * Full-screen emulator: CPC display, touch overlay (joystick / fire / extra
 * keys), virtual keyboard, physical keyboard and gamepad support, in-game
 * menu (reset, discs, save states, layouts).
 */
class EmulatorActivity : AppCompatActivity() {
    private lateinit var binding: ActivityEmulatorBinding
    private lateinit var settings: AppSettings
    private lateinit var library: GameLibrary
    private var session: EmulatorSession? = null
    private lateinit var keyMapper: KeyMapper
    private lateinit var gamepadMapper: GamepadMapper
    private var overlayLayouts: MutableMap<String, OverlayLayout> = mutableMapOf()
    private val handler = Handler(Looper.getMainLooper())
    private val pressedPhysical = HashSet<Int>()
    private val physicalStrokes = HashMap<Int, KeyMapper.Stroke>()
    private var physicalShiftCount = 0
    private val keyQueue by lazy {
        PhysicalKeyQueue(handler, { k -> session?.emulator?.pressKey(k) }, { k -> session?.emulator?.releaseKey(k) })
    }
    private var hatX = 0
    private var hatY = 0
    private var backPressed = false

    private val openDisk = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) insertDiskFromUri(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = AppSettings(this)
        library = GameLibrary(this)
        session = EmulatorHolder.session
        if (session == null) {
            finish()
            return
        }
        binding = ActivityEmulatorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        keyMapper = KeyMapper(settings.physicalKeyMap)
        gamepadMapper = GamepadMapper(settings.gamepadMap)
        overlayLayouts = OverlayLayout.loadAll(settings.overlayLayouts)

        setupDisplay()
        setupOverlay()
        setupKeyboard()
        setupTextInput()
        setupQuickBar()
        applySettings()
    }

    /**
     * Quick-access buttons (keyboard / reset / menu). Kept clear of the display
     * cutout and of the top edge, where taps would open the system bars.
     */
    private fun setupQuickBar() {
        binding.buttonMenu.setOnClickListener { showMenu() }
        binding.buttonKeyboard.setOnClickListener { toggleKeyboard() }
        binding.buttonKeyboard.setOnLongClickListener { showSystemKeyboard(); true }
        binding.buttonReset.setOnClickListener { confirmReset() }
        binding.buttonReset.setOnLongClickListener { performReset(); true }
        val basePadding = binding.quickBar.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(binding.quickBar) { v, insets ->
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            v.updatePadding(top = basePadding + cutout.top, right = basePadding + cutout.right)
            insets
        }
        // Track the soft keyboard: drop the focus of the hidden field when it goes away.
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            if (!insets.isVisible(WindowInsetsCompat.Type.ime()) && binding.textInput.hasFocus()) binding.root.requestFocus()
            insets
        }
        // The root keeps the focus so that the hidden text field never takes it
        // (and pops the soft keyboard) on its own.
        binding.root.requestFocus()
    }

    // ---- Android soft keyboard -> CPC ---------------------------------------

    private var suppressTextWatcher = false

    /**
     * The hidden [android.widget.EditText] receives what the Android keyboard
     * commits; the text is forwarded to the CPC through the key typer and the
     * field is emptied again, so Backspace / Enter arrive as plain key events
     * that go through the physical-keyboard mapping.
     */
    private fun setupTextInput() {
        binding.textInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
                if (suppressTextWatcher || count <= 0) return
                val typed = s.subSequence(start, start + count).toString()
                session?.emulator?.typeText(typed)
            }
            override fun afterTextChanged(s: Editable) {
                if (suppressTextWatcher || s.isEmpty()) return
                suppressTextWatcher = true
                s.clear()
                suppressTextWatcher = false
            }
        })
    }

    private fun showSystemKeyboard() {
        if (binding.keyboard.visibility == View.VISIBLE) toggleKeyboard()
        val input = binding.textInput
        input.requestFocus()
        WindowCompat.getInsetsController(window, input).show(WindowInsetsCompat.Type.ime())
        getSystemService(InputMethodManager::class.java)?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        toast(R.string.toast_system_keyboard)
    }

    private fun hideSystemKeyboard() {
        val input = binding.textInput
        if (!input.hasFocus()) return
        WindowCompat.getInsetsController(window, input).hide(WindowInsetsCompat.Type.ime())
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(input.windowToken, 0)
        binding.root.requestFocus()
    }

    private fun setupDisplay() {
        val s = session ?: return
        s.frameListener = EmulatorSession.FrameListener { frame -> binding.surface.present(frame) }
    }

    private fun setupOverlay() {
        val overlay = binding.overlay
        overlay.listener = object : JoystickOverlayView.Listener {
            override fun onJoystick(up: Boolean, down: Boolean, left: Boolean, right: Boolean) {
                val e = session?.emulator ?: return
                e.setJoystick(0, JoystickButton.UP, up)
                e.setJoystick(0, JoystickButton.DOWN, down)
                e.setJoystick(0, JoystickButton.LEFT, left)
                e.setJoystick(0, JoystickButton.RIGHT, right)
            }

            override fun onFire(button: Int, pressed: Boolean) {
                session?.emulator?.setJoystick(0, if (button == 1) JoystickButton.FIRE1 else JoystickButton.FIRE2, pressed)
            }

            override fun onExtraKey(key: CpcKey, pressed: Boolean) {
                session?.emulator?.let { if (pressed) it.pressKey(key) else it.releaseKey(key) }
            }

            override fun onLayoutChanged(layout: OverlayLayout) {
                overlayLayouts[settings.overlayProfile] = layout
                settings.overlayLayouts = OverlayLayout.saveAll(overlayLayouts)
            }
        }
    }

    private fun setupKeyboard() {
        binding.keyboard.listener = object : VirtualKeyboardView.Listener {
            override fun onKeyDown(key: CpcKey) { session?.emulator?.pressKey(key) }
            override fun onKeyUp(key: CpcKey) { session?.emulator?.releaseKey(key) }
        }
        binding.keyboard.visibility = View.GONE
    }

    private fun applySettings() {
        val s = session ?: return
        s.applySettings()
        binding.surface.scalingMode = settings.scalingMode
        binding.surface.scanlines = settings.scanlines
        binding.surface.smoothing = settings.smoothing
        binding.overlay.opacity = settings.overlayOpacity
        binding.overlay.scale = settings.overlayScale
        binding.overlay.haptic = settings.hapticFeedback
        binding.overlay.joystickVisible = settings.showJoystick
        binding.overlay.layout = overlayLayouts[settings.overlayProfile] ?: OverlayLayout()
        binding.keyboard.haptic = settings.hapticFeedback
        binding.keyboard.opacity = 0.9f
        applyKeyboardHeight()
        applyOrientation()
        binding.debugOverlay.visibility = if (settings.developerOverlay) View.VISIBLE else View.GONE
        keyMapper = KeyMapper(settings.physicalKeyMap)
        gamepadMapper = GamepadMapper(settings.gamepadMap)
    }

    /** The virtual keyboard takes a fraction of the current screen height (portrait or landscape). */
    private fun applyKeyboardHeight() {
        val screenHeight = if (binding.root.height > 0) binding.root.height else resources.displayMetrics.heightPixels
        val lp = binding.keyboard.layoutParams
        val wanted = (screenHeight * settings.keyboardHeightPercent / 100f).toInt()
        if (lp.height != wanted) {
            lp.height = wanted
            binding.keyboard.layoutParams = lp
        }
    }

    private fun applyOrientation() {
        val wanted = when (settings.screenOrientation) {
            AppSettings.Orientation.AUTO -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
            AppSettings.Orientation.SYSTEM -> ActivityInfo.SCREEN_ORIENTATION_FULL_USER
            AppSettings.Orientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            AppSettings.Orientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }
        if (requestedOrientation != wanted) requestedOrientation = wanted
    }

    /** Rotation is handled without recreating the activity (see the manifest): re-fit the overlays. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        binding.overlay.releaseAll()
        binding.keyboard.releaseAll()
        binding.root.post { applyKeyboardHeight() }
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        applySettings()
        session?.resume()
        handler.post(debugUpdater)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(debugUpdater)
        val s = session ?: return
        s.pause()
        keyQueue.releaseAll()
        pressedPhysical.clear()
        physicalStrokes.clear()
        physicalShiftCount = 0
        s.emulator.releaseAllKeys()
        binding.overlay.releaseAll()
        binding.keyboard.releaseAll()
        hideSystemKeyboard()
        // Keep a recovery point in case the process is killed in the background.
        runCatching { library.autoSaveFile().writeBytes(s.emulator.saveState()) }
    }

    override fun onDestroy() {
        session?.frameListener = null
        super.onDestroy()
    }

    private fun hideSystemBars() {
        val controller = WindowInsetsControllerCompat(window, binding.root)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private val debugUpdater = object : Runnable {
        override fun run() {
            val s = session ?: return
            if (settings.developerOverlay) {
                val info = s.emulator.debugInfo()
                binding.debugOverlay.text = String.format(
                    "%s  %.1f fps  %.0f%%  %.2f ms/frame\nPC=%04X SP=%04X AF=%04X BC=%04X DE=%04X HL=%04X IX=%04X IY=%04X IM%d IFF=%d\nmode=%d rom=%s ram=%02X CRTC R1=%d R2=%d R6=%d R7=%d R9=%d R12/13=%02X%02X\nFDC %s",
                    info.model, s.fps, s.speedPercent, s.frameTimeMs,
                    info.pc, info.sp, info.af, info.bc, info.de, info.hl, info.ix, info.iy, info.im, if (info.iff1) 1 else 0,
                    info.gaMode, info.romConfig, info.ramConfig,
                    info.crtcRegisters[1], info.crtcRegisters[2], info.crtcRegisters[6], info.crtcRegisters[7], info.crtcRegisters[9],
                    info.crtcRegisters[12], info.crtcRegisters[13], info.fdcStatus,
                )
            }
            handler.postDelayed(this, 500)
        }
    }

    // ---- Physical keyboard and gamepads --------------------------------------

    private fun isGamepad(event: KeyEvent): Boolean {
        val src = event.device?.sources ?: event.source
        return src and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            src and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK ||
            KeyEvent.isGamepadButton(event.keyCode)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val e = session?.emulator ?: return super.dispatchKeyEvent(event)
        val code = event.keyCode
        if (code == KeyEvent.KEYCODE_BACK) {
            // Open the menu only for a complete press seen by us: when the soft
            // keyboard is showing, it consumes the key-down and we would
            // otherwise react to the stray key-up.
            when (event.action) {
                KeyEvent.ACTION_DOWN -> backPressed = true
                KeyEvent.ACTION_UP -> {
                    val complete = backPressed
                    backPressed = false
                    if (complete && !event.isCanceled) {
                        if (binding.textInput.hasFocus()) hideSystemKeyboard() else showMenu()
                    }
                }
            }
            return true
        }
        if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN) return super.dispatchKeyEvent(event)
        if (isGamepad(event)) {
            val target = gamepadMapper[code] ?: return super.dispatchKeyEvent(event)
            val pressed = event.action == KeyEvent.ACTION_DOWN
            if (event.repeatCount > 0) return true
            when (target) {
                is GamepadMapper.Target.Joy -> e.setJoystick(0, target.button, pressed)
                is GamepadMapper.Target.Key -> if (pressed) e.pressKey(target.key) else e.releaseKey(target.key)
                GamepadMapper.Target.Menu -> if (!pressed) showMenu()
                GamepadMapper.Target.ToggleKeyboard -> if (!pressed) toggleKeyboard()
            }
            return true
        }
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount > 0 || pressedPhysical.contains(code)) return true
                val stroke = keyMapper.resolve(event) ?: return super.dispatchKeyEvent(event)
                if (dev.stefan.acpc.BuildConfig.DEBUG) android.util.Log.d("EmulatorActivity", "key ${KeyEvent.keyCodeToString(code)} char=${event.unicodeChar} meta=${event.metaState} -> ${stroke.key} shift=${stroke.shift}")
                pressedPhysical.add(code)
                physicalStrokes[code] = stroke
                if (code == KeyEvent.KEYCODE_SHIFT_LEFT || code == KeyEvent.KEYCODE_SHIFT_RIGHT) physicalShiftCount++
                val shiftHeld = physicalShiftCount > 0
                // Character-mapped symbols need the CPC SHIFT state of the CPC
                // layout, not the one of the PC layout: adjust it around the key.
                if (stroke.shift && !shiftHeld) keyQueue.keyDown(CpcKey.SHIFT)
                if (stroke.charMapped && !stroke.shift && shiftHeld) keyQueue.keyUp(CpcKey.SHIFT)
                keyQueue.keyDown(stroke.key)
            }
            KeyEvent.ACTION_UP -> {
                if (!pressedPhysical.remove(code)) return super.dispatchKeyEvent(event)
                val stroke = physicalStrokes.remove(code) ?: return true
                if (code == KeyEvent.KEYCODE_SHIFT_LEFT || code == KeyEvent.KEYCODE_SHIFT_RIGHT) physicalShiftCount = (physicalShiftCount - 1).coerceAtLeast(0)
                keyQueue.keyUp(stroke.key)
                val shiftHeld = physicalShiftCount > 0
                if (stroke.shift && !shiftHeld) keyQueue.keyUp(CpcKey.SHIFT)
                if (stroke.charMapped && !stroke.shift && shiftHeld) keyQueue.keyDown(CpcKey.SHIFT)
            }
        }
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        val e = session?.emulator ?: return super.onGenericMotionEvent(event)
        if (event.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK && event.action == MotionEvent.ACTION_MOVE) {
            val x = maxAbs(event.getAxisValue(MotionEvent.AXIS_X), event.getAxisValue(MotionEvent.AXIS_HAT_X))
            val y = maxAbs(event.getAxisValue(MotionEvent.AXIS_Y), event.getAxisValue(MotionEvent.AXIS_HAT_Y))
            val nx = if (x > 0.5f) 1 else if (x < -0.5f) -1 else 0
            val ny = if (y > 0.5f) 1 else if (y < -0.5f) -1 else 0
            if (nx != hatX || ny != hatY) {
                hatX = nx; hatY = ny
                e.setJoystick(0, JoystickButton.LEFT, nx < 0)
                e.setJoystick(0, JoystickButton.RIGHT, nx > 0)
                e.setJoystick(0, JoystickButton.UP, ny < 0)
                e.setJoystick(0, JoystickButton.DOWN, ny > 0)
            }
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    private fun maxAbs(a: Float, b: Float): Float = if (Math.abs(a) >= Math.abs(b)) a else b

    // ---- Menu ---------------------------------------------------------------

    private fun toggleKeyboard() {
        val kb = binding.keyboard
        if (kb.visibility == View.VISIBLE) {
            kb.releaseAll()
            kb.visibility = View.GONE
        } else {
            hideSystemKeyboard()
            kb.visibility = View.VISIBLE
        }
    }

    private fun showMenu() {
        val s = session ?: return
        s.pause()
        val items = arrayOf(
            getString(R.string.menu_resume),
            getString(R.string.menu_keyboard),
            getString(R.string.menu_system_keyboard),
            getString(R.string.menu_save_state),
            getString(R.string.menu_load_state),
            getString(R.string.menu_insert_disk),
            getString(R.string.menu_eject_disk),
            getString(R.string.menu_disk_files),
            getString(R.string.menu_reset),
            getString(R.string.menu_overlay_profile),
            getString(R.string.menu_edit_overlay),
            getString(R.string.menu_orientation),
            getString(if (settings.muted) R.string.menu_unmute else R.string.menu_mute),
            getString(R.string.menu_settings),
            getString(R.string.menu_quit),
        )
        AlertDialog.Builder(this)
            .setTitle(s.currentEntry?.title ?: s.model.displayName)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> s.resume()
                    1 -> { toggleKeyboard(); s.resume() }
                    2 -> { s.resume(); showSystemKeyboard() }
                    3 -> showStateSlots(save = true)
                    4 -> showStateSlots(save = false)
                    5 -> openDisk.launch(arrayOf("*/*"))
                    6 -> { s.emulator.ejectDisk(0); s.currentEntry = null; toast(R.string.toast_disk_ejected); s.resume() }
                    7 -> showDiskFiles()
                    8 -> confirmReset()
                    9 -> chooseOverlayProfile()
                    10 -> { binding.overlay.editMode = true; toast(R.string.toast_edit_overlay); s.resume() }
                    11 -> chooseOrientation()
                    12 -> { settings.muted = !settings.muted; s.applySettings(); s.resume() }
                    13 -> startActivity(Intent(this, SettingsActivity::class.java))
                    14 -> quit()
                }
            }
            .setOnCancelListener { s.resume() }
            .show()
    }

    private fun confirmReset() {
        val s = session ?: return
        s.pause()
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.confirm_reset) + "\n\n" + getString(R.string.hint_reset_long_press))
            .setPositiveButton(R.string.action_reset) { _, _ -> performReset() }
            .setNegativeButton(android.R.string.cancel) { _, _ -> s.resume() }
            .setOnCancelListener { s.resume() }
            .show()
    }

    /** Power-cycles the CPC, releases every held input and re-runs the disc auto-start. */
    private fun performReset() {
        val s = session ?: return
        keyQueue.releaseAll()
        binding.overlay.releaseAll()
        binding.keyboard.releaseAll()
        s.emulator.releaseAllKeys()
        s.emulator.reset()
        s.currentEntry?.let { entry ->
            // Re-run the auto-start on the inserted disc.
            val autoStart = entry.autoStart ?: settings.autoStart
            runCatching {
                val bytes = library.diskFile(entry).readBytes()
                s.insertDisk(bytes, entry.title, autoStart, resetFirst = true)
            }
        }
        toast(R.string.toast_reset_done)
        s.resume()
    }

    private fun quit() {
        val s = session ?: return
        AlertDialog.Builder(this)
            .setMessage(R.string.confirm_quit)
            .setPositiveButton(R.string.action_quit) { _, _ ->
                s.frameListener = null
                EmulatorHolder.stop()
                finish()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> s.resume() }
            .setOnCancelListener { s.resume() }
            .show()
    }

    private fun showStateSlots(save: Boolean) {
        val s = session ?: return
        val entry = s.currentEntry
        val df = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        val labels = (1..4).map { slot ->
            val f = library.stateFile(entry, slot)
            if (f.exists()) getString(R.string.state_slot_used, slot, df.format(Date(f.lastModified()))) else getString(R.string.state_slot_empty, slot)
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(if (save) R.string.menu_save_state else R.string.menu_load_state)
            .setItems(labels) { _, which ->
                val file = library.stateFile(entry, which + 1)
                try {
                    if (save) {
                        file.writeBytes(s.emulator.saveState())
                        toast(R.string.toast_state_saved)
                    } else if (file.exists()) {
                        s.emulator.loadState(file.readBytes())
                        toast(R.string.toast_state_loaded)
                    } else {
                        toast(R.string.toast_state_empty)
                    }
                } catch (e: EmulatorException) {
                    Toast.makeText(this, getString(R.string.error_state, e.message ?: ""), Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(this, getString(R.string.error_state, e.message ?: ""), Toast.LENGTH_LONG).show()
                }
                s.resume()
            }
            .setOnCancelListener { s.resume() }
            .show()
    }

    private fun insertDiskFromUri(uri: android.net.Uri) {
        val s = session ?: return
        Thread {
            val result = runCatching { library.importFromUri(uri) }
            runOnUiThread {
                result.onSuccess { entry ->
                    try {
                        val bytes = library.diskFile(entry).readBytes()
                        s.insertDisk(bytes, entry.title, autoStart = false, resetFirst = false)
                        s.currentEntry = entry
                        entry.lastPlayed = System.currentTimeMillis()
                        library.update(entry)
                        toast(R.string.toast_disk_inserted)
                    } catch (e: Exception) {
                        Toast.makeText(this, getString(R.string.error_cannot_read_dsk), Toast.LENGTH_LONG).show()
                    }
                }.onFailure { e ->
                    Toast.makeText(this, e.message ?: getString(R.string.error_cannot_read_dsk), Toast.LENGTH_LONG).show()
                }
                s.resume()
            }
        }.start()
    }

    private fun showDiskFiles() {
        val s = session ?: return
        val disk = s.emulator.machine.fdc.disk(0)
        if (disk == null) {
            toast(R.string.toast_no_disk)
            s.resume()
            return
        }
        val files = AmsdosCatalog.list(disk)
        val labels = files.map { it.toString() }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_disk_files, disk.name))
            .setItems(if (labels.isEmpty()) arrayOf(getString(R.string.dialog_no_files)) else labels) { _, which ->
                if (files.isNotEmpty()) {
                    s.emulator.typeText("RUN\"${files[which].fileName}\n")
                }
                s.resume()
            }
            .setNeutralButton(R.string.action_cat) { _, _ -> s.emulator.typeText("CAT\n"); s.resume() }
            .setOnCancelListener { s.resume() }
            .show()
    }

    private fun chooseOverlayProfile() {
        val s = session ?: return
        val names = overlayLayouts.keys.toList()
        val labels = names.map { profileLabel(it) }.toTypedArray()
        val current = names.indexOf(settings.overlayProfile).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_overlay_profile)
            .setSingleChoiceItems(labels, current) { d, which ->
                settings.overlayProfile = names[which]
                applySettings()
                d.dismiss()
                s.resume()
            }
            .setOnCancelListener { s.resume() }
            .show()
    }

    private fun chooseOrientation() {
        val s = session ?: return
        val values = AppSettings.Orientation.entries
        val labels = arrayOf(
            getString(R.string.orientation_auto), getString(R.string.orientation_system),
            getString(R.string.orientation_landscape), getString(R.string.orientation_portrait),
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_orientation)
            .setSingleChoiceItems(labels, values.indexOf(settings.screenOrientation)) { d, which ->
                settings.screenOrientation = values[which]
                applyOrientation()
                d.dismiss()
                s.resume()
            }
            .setOnCancelListener { s.resume() }
            .show()
    }

    private fun profileLabel(name: String): String = when (name) {
        "platform" -> getString(R.string.profile_platform)
        "adventure" -> getString(R.string.profile_adventure)
        "shootemup" -> getString(R.string.profile_shootemup)
        "custom" -> getString(R.string.profile_custom)
        else -> name
    }

    private fun toast(id: Int) = Toast.makeText(this, id, Toast.LENGTH_SHORT).show()

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onBackPressed() {
        showMenu()
    }

    @Suppress("unused")
    private fun describeDisk(bytes: ByteArray): String = runCatching { DskFormat.read(bytes).let { "${it.trackCount} tracks" } }.getOrDefault("?")
}
