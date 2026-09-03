package dev.stefan.acpc.ui.settings

import android.os.Bundle
import android.view.KeyEvent
import android.widget.ArrayAdapter
import android.widget.ListView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import dev.stefan.acpc.R
import dev.stefan.acpc.core.joystick.JoystickButton
import dev.stefan.acpc.core.keyboard.CpcKey
import dev.stefan.acpc.input.GamepadMapper
import dev.stefan.acpc.input.KeyMapper
import dev.stefan.acpc.settings.AppSettings

/**
 * Remapping screen: pick a CPC key (or joystick function), then press the
 * physical key / gamepad button to assign.
 */
class KeyMappingActivity : AppCompatActivity() {
    private lateinit var settings: AppSettings
    private var gamepad = false
    private lateinit var keyMapper: KeyMapper
    private lateinit var gamepadMapper: GamepadMapper
    private lateinit var list: ListView
    private var capture: ((KeyEvent) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = AppSettings(this)
        gamepad = intent.getBooleanExtra(EXTRA_GAMEPAD, false)
        keyMapper = KeyMapper(settings.physicalKeyMap)
        gamepadMapper = GamepadMapper(settings.gamepadMap)
        title = getString(if (gamepad) R.string.pref_gamepad_map else R.string.pref_physical_keymap)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        list = ListView(this)
        setContentView(list)
        refresh()
        list.setOnItemClickListener { _, _, position, _ -> onItemSelected(position) }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    private fun targets(): List<Pair<String, Any>> {
        val items = ArrayList<Pair<String, Any>>()
        if (gamepad) {
            for (b in JoystickButton.entries) items += "Joystick ${b.name}" to GamepadMapper.Target.Joy(b)
            items += getString(R.string.menu_keyboard) to GamepadMapper.Target.ToggleKeyboard
            items += "Menu" to GamepadMapper.Target.Menu
            for (k in CpcKey.entries) if (!k.name.startsWith("JOY")) items += "${k.label} (${k.name})" to GamepadMapper.Target.Key(k)
        } else {
            for (k in CpcKey.entries) if (!k.name.startsWith("JOY")) items += "${k.label} (${k.name})" to k
        }
        return items
    }

    private fun refresh() {
        val labels = targets().map { (label, target) ->
            val assigned = if (gamepad) {
                gamepadMapper.entries().filter { it.value == target }.keys
            } else {
                keyMapper.entries().filter { it.value == target }.keys
            }
            val names = assigned.joinToString { KeyEvent.keyCodeToString(it).removePrefix("KEYCODE_") }
            "$label\n    → ${names.ifEmpty { "-" }}"
        }
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
    }

    private fun onItemSelected(position: Int) {
        val (label, target) = targets()[position]
        val dialog = AlertDialog.Builder(this)
            .setTitle(label)
            .setMessage(R.string.keymap_press_key)
            .setNegativeButton(android.R.string.cancel) { _, _ -> capture = null }
            .setNeutralButton(R.string.keymap_clear) { _, _ ->
                if (gamepad) gamepadMapper.entries().filter { it.value == target }.keys.toList().forEach { gamepadMapper.set(it, null) }
                else keyMapper.entries().filter { it.value == target }.keys.toList().forEach { keyMapper.set(it, null) }
                persist(); refresh(); capture = null
            }
            .create()
        capture = { event ->
            if (gamepad) gamepadMapper.set(event.keyCode, target as GamepadMapper.Target) else keyMapper.set(event.keyCode, target as CpcKey)
            persist()
            refresh()
            capture = null
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun persist() {
        if (gamepad) settings.gamepadMap = gamepadMapper.toJson() else settings.physicalKeyMap = keyMapper.toJson()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val c = capture
        if (c != null && event.action == KeyEvent.ACTION_DOWN && event.keyCode != KeyEvent.KEYCODE_BACK) {
            c(event)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    companion object {
        const val EXTRA_GAMEPAD = "gamepad"
    }
}
