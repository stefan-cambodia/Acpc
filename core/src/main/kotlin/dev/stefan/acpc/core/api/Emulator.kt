package dev.stefan.acpc.core.api

import dev.stefan.acpc.core.joystick.JoystickButton
import dev.stefan.acpc.core.keyboard.CpcKey
import dev.stefan.acpc.core.machine.CpcModel
import dev.stefan.acpc.core.machine.CrtcType

/**
 * Public API of the emulator core.
 *
 * This is the only entry point a front-end needs. It knows nothing about
 * Android: video comes out as an ARGB [VideoFrame], audio goes to an
 * [AudioSink], input comes in as CPC matrix keys, files come in as byte
 * arrays.
 *
 * Threading model: the front-end owns an "emulation thread" that repeatedly
 * calls [runFrame]. All other methods are safe to call from any thread: input
 * methods are lock-free, everything else is queued and applied between two
 * frames (see [CpcEmulator]).
 */
interface Emulator {
    val model: CpcModel
    val crtcType: CrtcType

    /** Hard reset (power cycle). Keeps the inserted disk. */
    fun reset()

    /**
     * Emulates one video frame (~19 968 µs of CPC time) and returns the frame
     * rendered during it. The returned buffer is owned by the core and is
     * valid until the next call.
     */
    fun runFrame(): VideoFrame

    // ---- Disk --------------------------------------------------------------

    /** Inserts a disk image into drive A (0) or B (1). Throws [InvalidDiskImageException]. */
    fun loadDisk(drive: Int, image: ByteArray, name: String = "disk.dsk")
    fun ejectDisk(drive: Int)
    fun hasDisk(drive: Int): Boolean

    /** Returns the current image of the disk in [drive] (with any writes applied), or null. */
    fun exportDisk(drive: Int): ByteArray?

    // ---- Tape --------------------------------------------------------------

    /** Puts a CDT tape image in the recorder (rewound). Throws [dev.stefan.acpc.core.tape.InvalidTapeException]. */
    fun insertTape(image: ByteArray, name: String = "tape.cdt")
    fun ejectTape()
    fun hasTape(): Boolean
    fun rewindTape()

    /** Tape position and length in seconds, and whether it is currently moving. */
    fun tapeStatus(): TapeStatus?

    // ---- Input -------------------------------------------------------------

    /** Immediate matrix change (for held controls such as joystick-like keys). */
    fun pressKey(key: CpcKey)
    fun releaseKey(key: CpcKey)

    /**
     * Queued press/release, applied at frame boundaries with minimum hold and
     * release durations so that the software always sees it (typing, taps).
     */
    fun queueKey(key: CpcKey, pressed: Boolean)

    /** Releases every key, immediate and queued. */
    fun releaseAllKeys()
    fun setJoystick(port: Int, button: JoystickButton, pressed: Boolean)
    fun setJoystick(port: Int, up: Boolean, down: Boolean, left: Boolean, right: Boolean, fire1: Boolean, fire2: Boolean)

    /**
     * Types a string as if entered on the keyboard (used by auto-start:
     * `RUN"DISC` + Enter). Characters are injected over several frames so
     * the firmware's keyboard scanner sees every key press and release.
     */
    fun typeText(text: String)

    // ---- Audio -------------------------------------------------------------

    fun setVolume(volume: Float)
    fun setMuted(muted: Boolean)

    // ---- State -------------------------------------------------------------

    /** Serialises the complete machine state. */
    fun saveState(): ByteArray

    /** Restores a state produced by [saveState]. Throws [InvalidStateException]. */
    fun loadState(state: ByteArray)

    /** Loads a standard CPC ".sna" snapshot (versions 1-3). Throws [InvalidStateException]. */
    fun loadSnapshot(sna: ByteArray)

    /** Writes the machine as a standard ".sna" snapshot (version 2). */
    fun saveSnapshot(): ByteArray

    // ---- Diagnostics -------------------------------------------------------

    /** Human readable debug information (registers, FDC state...), for the developer overlay. */
    fun debugInfo(): DebugInfo

    /** Raw 64 KB of base RAM (for tests and debugging tools). */
    fun peekRam(address: Int): Int

    /** Writes a byte into the RAM currently mapped at [address] (POKE cheats). */
    fun pokeRam(address: Int, value: Int)
}

class TapeStatus(val name: String, val positionSeconds: Float, val lengthSeconds: Float, val moving: Boolean, val atEnd: Boolean)

/** Snapshot of diagnostic values, filled by [Emulator.debugInfo]. */
data class DebugInfo(
    val model: String,
    val pc: Int,
    val sp: Int,
    val af: Int,
    val bc: Int,
    val de: Int,
    val hl: Int,
    val ix: Int,
    val iy: Int,
    val iff1: Boolean,
    val im: Int,
    val totalCycles: Long,
    val frame: Long,
    val gaMode: Int,
    val romConfig: String,
    val ramConfig: Int,
    val crtcRegisters: IntArray,
    val fdcStatus: String,
    val diskNames: List<String?>,
)
