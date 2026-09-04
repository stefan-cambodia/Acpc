package dev.stefan.acpc.emulator

import android.content.Context
import android.util.Log
import dev.stefan.acpc.audio.AndroidAudioSink
import dev.stefan.acpc.core.api.CpcEmulator
import dev.stefan.acpc.core.api.RomSet
import dev.stefan.acpc.core.api.VideoFrame
import dev.stefan.acpc.core.disk.AmsdosCatalog
import dev.stefan.acpc.core.disk.DskFormat
import dev.stefan.acpc.core.machine.CpcModel
import dev.stefan.acpc.core.machine.CrtcType
import dev.stefan.acpc.core.timing.CpcTiming
import dev.stefan.acpc.settings.AppSettings
import dev.stefan.acpc.storage.GameEntry
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Owns a running CPC: the emulator core, the emulation thread and the audio
 * output. Survives activity recreation (held by the application) so that a
 * game keeps running across rotation and can be resumed after the app was in
 * the background.
 *
 * Threading: [runLoop] executes on the emulation thread. Pausing simply
 * parks that thread; the core is never touched concurrently because
 * [CpcEmulator] serialises its own access.
 */
class EmulatorSession(
    context: Context,
    val model: CpcModel,
    crtcType: CrtcType,
    roms: RomSet?,
    private val settings: AppSettings,
    /** The cartridge of a Plus machine: the 6128 Plus system cartridge, or a GX4000 game. */
    val cartridge: dev.stefan.acpc.core.cartridge.Cartridge? = null,
) {
    /** Receives completed frames on the emulation thread. */
    fun interface FrameListener {
        fun onFrame(frame: VideoFrame)
    }

    private val audio = AndroidAudioSink(AndroidAudioSink.preferredSampleRate(), settings.audioLatencyFrames)
    private val hasAmsdos = roms?.amsdosRom != null || (model.isPlus && cartridge?.isSystemCartridge == true)
    val emulator: CpcEmulator = CpcEmulator.createMachine(model, roms, audio, crtcType, cartridge)

    /**
     * Frames the firmware needs after a reset before it accepts typed input.
     * The 6128 Plus boots to an "f1 BASIC / f2 Burnin' Rubber" menu: f1 is
     * pressed then, and typing starts 100 frames later.
     */
    private fun scheduleBoot(resetFirst: Boolean): Long {
        val now = emulator.machine.frameCount
        if (!resetFirst) return now + 5
        if (model == CpcModel.CPC6128PLUS) {
            pendingScript = listOf<Pair<Long, () -> Unit>>((now + 130) to { emulator.machine.keyTyper.typeKey(dev.stefan.acpc.core.keyboard.CpcKey.F1) })
            return now + 230
        }
        return now + 130
    }

    @Volatile var frameListener: FrameListener? = null

    /** Library entry currently loaded in drive A (for save states and statistics). */
    @Volatile var currentEntry: GameEntry? = null

    private val running = AtomicBoolean(false)
    private val pauseLock = ReentrantLock()
    private val pauseCondition = pauseLock.newCondition()
    @Volatile var paused = true
        private set
    private var thread: Thread? = null

    // Statistics for the developer overlay
    @Volatile var fps = 0f
    @Volatile var speedPercent = 0f
    @Volatile var frameTimeMs = 0f
    private var statFrames = 0
    private var statStart = 0L
    private var statWork = 0L

    /** Emulation speed multiplier; 1.0 = real CPC. */
    @Volatile var speed: Float = settings.speedPercent / 100f

    init {
        emulator.setVolume(settings.volume)
        emulator.setMuted(settings.muted)
        emulator.machine.fdc.fastMode = settings.fastDisc
        val t = Thread({ runLoop() }, "cpc-emulation").apply {
            priority = Thread.MAX_PRIORITY - 1
            isDaemon = true
        }
        thread = t
        running.set(true)
        t.start()
    }

    fun resume() {
        pauseLock.withLock {
            if (!paused) return
            paused = false
            audio.start()
            pauseCondition.signalAll()
        }
    }

    fun pause() {
        pauseLock.withLock {
            if (paused) return
            paused = true
        }
        audio.pause()
    }

    fun stop() {
        running.set(false)
        resume()
        thread?.join(2000)
        audio.release()
    }

    fun applySettings() {
        emulator.setVolume(settings.volume)
        emulator.setMuted(settings.muted)
        emulator.machine.fdc.fastMode = settings.fastDisc
        speed = settings.speedPercent / 100f
    }

    /** Inserts a disc and optionally types the auto-start command. */
    fun insertDisk(bytes: ByteArray, name: String, autoStart: Boolean, resetFirst: Boolean) {
        if (resetFirst) {
            emulator.reset()
            emulator.releaseAllKeys()
        }
        emulator.loadDisk(0, bytes, name)
        pendingScript = null
        val ready = scheduleBoot(resetFirst)
        if (autoStart) {
            val image = DskFormat.read(bytes, name)
            val command = AmsdosCatalog.autoStartCommand(image)
            if (command != null) {
                // Give the firmware time to boot (about 2.5 s) before typing.
                pendingAutoStart = command
                autoStartAtFrame = ready
            }
        }
    }

    /**
     * Writes the disc in drive A back to its library file if the program
     * wrote to it (saved games, high scores), like a real disc would keep them.
     */
    fun flushDisk(library: dev.stefan.acpc.storage.GameLibrary) {
        val entry = currentEntry ?: return
        if (entry.isSnapshot) return
        val disk = emulator.machine.fdc.disk(0) ?: return
        if (!disk.modified) return
        val bytes = emulator.exportDisk(0) ?: return
        runCatching { library.diskFile(entry).writeBytes(bytes) }
            .onSuccess { disk.modified = false }
            .onFailure { Log.w(TAG, "Cannot write disc back", it) }
    }

    /** Loads a ".sna" snapshot: replaces the whole machine state (the disc stays in the drive). */
    fun loadSnapshot(bytes: ByteArray) {
        pendingAutoStart = null
        emulator.releaseAllKeys()
        emulator.loadSnapshot(bytes)
    }

    /**
     * Puts a tape in the recorder and, with [autoStart], types the loading
     * commands: `|TAPE` on machines with a disc interface, then `RUN"`, then
     * a key for the firmware's "Press PLAY then any key" prompt.
     */
    fun insertTape(bytes: ByteArray, name: String, autoStart: Boolean, resetFirst: Boolean) {
        if (resetFirst) {
            emulator.reset()
            emulator.releaseAllKeys()
        }
        emulator.insertTape(bytes, name)
        pendingScript = null
        val ready = scheduleBoot(resetFirst)
        if (autoStart) {
            val script = ArrayList<Pair<Long, () -> Unit>>()
            pendingScript?.let { script.addAll(it) }
            if (hasAmsdos) script += ready to { emulator.typeText("|tape\n") }   // AMSDOS redirects RUN" to the disc
            script += (ready + 25) to { emulator.typeText("run\"\n") }
            script += (ready + 75) to { emulator.typeText(" ") }
            pendingScript = script
            pendingAutoStart = "RUN\""
            autoStartAtFrame = Long.MAX_VALUE
        }
    }

    /** True while the tape is turning and fast tape loading is on: the loop then runs unpaced. */
    val tapeTurbo: Boolean get() = settings.fastTape && emulator.machine.tape?.isMoving == true

    @Volatile private var pendingScript: List<Pair<Long, () -> Unit>>? = null
    @Volatile private var pendingAutoStart: String? = null
    @Volatile private var autoStartAtFrame = 0L

    /** The command auto-start typed (or will type), for display. */
    val lastAutoStartCommand: String? get() = pendingAutoStart

    private fun runLoop() {
        var nextFrameNs = System.nanoTime()
        val frameNs = (1_000_000_000.0 / CpcTiming.FRAME_RATE_HZ).toLong()
        statStart = System.nanoTime()
        while (running.get()) {
            pauseLock.withLock {
                while (paused && running.get()) {
                    pauseCondition.await()
                    nextFrameNs = System.nanoTime()
                    statStart = System.nanoTime()
                    statFrames = 0
                    statWork = 0
                }
            }
            if (!running.get()) break
            val start = System.nanoTime()
            val frame = try {
                val cmd = pendingAutoStart
                if (cmd != null && emulator.machine.frameCount >= autoStartAtFrame) {
                    pendingAutoStart = null
                    emulator.typeText(cmd)
                }
                pendingScript?.let { script ->
                    val f = emulator.machine.frameCount
                    val due = script.filter { it.first <= f }
                    if (due.isNotEmpty()) {
                        due.forEach { it.second() }
                        val rest = script - due.toSet()
                        pendingScript = rest.ifEmpty { null }
                        if (rest.isEmpty() && autoStartAtFrame == Long.MAX_VALUE) pendingAutoStart = null
                    }
                }
                emulator.runFrame()
            } catch (e: Throwable) {
                Log.e(TAG, "Emulation error", e)
                pause()
                continue
            }
            val turbo = tapeTurbo
            if (!turbo || statFrames % 5 == 0) frameListener?.onFrame(frame)
            val blocked = audio.blockedNanos
            audio.blockedNanos = 0
            val work = System.nanoTime() - start - blocked
            statWork += work
            statFrames++
            // Pacing: the blocking audio write already paces us when audio is
            // available; otherwise sleep to real time. With a speed multiplier
            // other than 1 the audio buffer is disabled and we pace by clock.
            val useClock = !audio.available || speed != 1f
            audio.enabled = speed == 1f && !turbo
            if (turbo) {
                nextFrameNs = System.nanoTime()
            } else if (useClock) {
                nextFrameNs += (frameNs / speed).toLong()
                val now = System.nanoTime()
                val sleep = nextFrameNs - now
                if (sleep > 0) {
                    try {
                        Thread.sleep(sleep / 1_000_000, (sleep % 1_000_000).toInt())
                    } catch (_: InterruptedException) {
                    }
                } else if (sleep < -200_000_000L) {
                    nextFrameNs = now
                }
            }
            val elapsed = System.nanoTime() - statStart
            if (elapsed >= 1_000_000_000L) {
                fps = statFrames * 1e9f / elapsed
                speedPercent = fps / CpcTiming.FRAME_RATE_HZ.toFloat() * 100f
                frameTimeMs = if (statFrames > 0) statWork / statFrames / 1e6f else 0f
                statFrames = 0
                statWork = 0
                statStart = System.nanoTime()
            }
        }
    }

    companion object {
        private const val TAG = "EmulatorSession"
    }
}
