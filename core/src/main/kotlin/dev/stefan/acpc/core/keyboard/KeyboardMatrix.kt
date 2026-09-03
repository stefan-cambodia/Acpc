package dev.stefan.acpc.core.keyboard

/**
 * State of the 10×8 CPC keyboard matrix as seen by the hardware.
 *
 * A pressed key pulls its bit LOW (active low): a line with no key pressed
 * reads as 0xFF. Multiple keys may be pressed at the same time; the matrix
 * has no ghosting model (the real keyboard has diodes on some lines only, but
 * games never rely on ghosting).
 *
 * Thread-safety: the front-end may call [press]/[release] from the UI thread
 * while the emulation thread reads [readLine]. Each line is a single volatile
 * int write, so no locking is required.
 */
class KeyboardMatrix {
    @Volatile
    private var lines: IntArray = IntArray(10) { 0xFF }

    fun press(key: CpcKey) = set(key.line, key.bit, true)
    fun release(key: CpcKey) = set(key.line, key.bit, false)

    fun set(line: Int, bit: Int, pressed: Boolean) {
        val copy = lines.copyOf()
        copy[line] = if (pressed) copy[line] and (1 shl bit).inv() else copy[line] or (1 shl bit)
        lines = copy
    }

    fun isPressed(key: CpcKey): Boolean = (lines[key.line] and (1 shl key.bit)) == 0

    /** Reads the state of a matrix line (0-9). Lines 10-15 read as 0xFF. */
    fun readLine(line: Int): Int = if (line in 0..9) lines[line] and 0xFF else 0xFF

    fun releaseAll() {
        lines = IntArray(10) { 0xFF }
    }

    /** Snapshot of the matrix, for save states. */
    fun snapshot(): IntArray = lines.copyOf()
    fun restore(state: IntArray) {
        require(state.size == 10)
        lines = state.copyOf()
    }
}
