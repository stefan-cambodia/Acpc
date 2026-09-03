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
    roms: RomSet,
    private val settings: AppSettings,
) {
    /** Receives completed frames on the emulation thread. */
    fun interface FrameListener {
        fun onFrame(frame: VideoFrame)
    }

    private val audio = AndroidAudioSink(AndroidAudioSink.preferredSampleRate(), settings.audioLatencyFrames)
    val emulator: CpcEmulator = CpcEmulator.createMachine(model, roms, audio, crtcType)

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
        if (autoStart) {
            val image = DskFormat.read(bytes, name)
            val command = AmsdosCatalog.autoStartCommand(image)
            if (command != null) {
                // Give the firmware time to boot (about 2.5 s) before typing.
                pendingAutoStart = command
                autoStartAtFrame = emulator.machine.frameCount + (if (resetFirst) 130 else 5)
            }
        }
    }

    /** Loads a ".sna" snapshot: replaces the whole machine state (the disc stays in the drive). */
    fun loadSnapshot(bytes: ByteArray) {
        pendingAutoStart = null
        emulator.releaseAllKeys()
        emulator.loadSnapshot(bytes)
    }

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
                emulator.runFrame()
            } catch (e: Throwable) {
                Log.e(TAG, "Emulation error", e)
                pause()
                continue
            }
            frameListener?.onFrame(frame)
            val blocked = audio.blockedNanos
            audio.blockedNanos = 0
            val work = System.nanoTime() - start - blocked
            statWork += work
            statFrames++
            // Pacing: the blocking audio write already paces us when audio is
            // available; otherwise sleep to real time. With a speed multiplier
            // other than 1 the audio buffer is disabled and we pace by clock.
            val useClock = !audio.available || speed != 1f
            audio.enabled = speed == 1f
            if (useClock) {
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
