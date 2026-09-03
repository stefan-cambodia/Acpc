package dev.stefan.acpc.core.tape

/**
 * A cassette in the (virtual) recorder: plays the edge timeline of a
 * [CdtFormat.Image] against the CPC clock whenever the motor relay is on,
 * as if PLAY were always pressed.
 */
class Tape(val image: CdtFormat.Image, val name: String) {
    /** Position on the tape in CPC cycles. */
    var position = 0L
        private set
    private var index = 0                  // edges passed at [position]
    private var motorOn = false
    private var motorStartCycles = 0L
    private var motorStartPosition = 0L

    val lengthCycles: Long get() = image.totalCycles
    val atEnd: Boolean get() = position >= image.totalCycles
    val isMoving: Boolean get() = motorOn && !atEnd

    /** Called when the PPI motor relay changes, with the CPU clock. */
    fun setMotor(on: Boolean, cycles: Long) {
        if (on == motorOn) return
        if (on) {
            motorStartCycles = cycles
            motorStartPosition = position
        } else {
            advanceTo(cycles)
        }
        motorOn = on
    }

    /** The signal level at the given CPU clock (PPI port B bit 7). */
    fun level(cycles: Long): Boolean {
        advanceTo(cycles)
        return (index and 1) == 1 != image.initialLevel
    }

    private fun advanceTo(cycles: Long) {
        if (!motorOn) return
        val pos = motorStartPosition + (cycles - motorStartCycles)
        if (pos < position) { index = 0 }
        position = if (pos > image.totalCycles) image.totalCycles else pos
        val edges = image.edges
        while (index < edges.size && edges[index] <= position) index++
    }

    fun rewind() {
        position = 0
        index = 0
        motorStartPosition = 0
        // Keep the motor reference consistent if it is running.
        motorStartCycles = lastCyclesHint
    }

    /** Set by the machine so that a rewind while the motor runs restarts from the right clock. */
    var lastCyclesHint = 0L

    /** Seeks to the start of the block containing [cycles] (or the next one). */
    fun seek(positionCycles: Long) {
        position = positionCycles.coerceIn(0, image.totalCycles)
        index = 0
        val edges = image.edges
        while (index < edges.size && edges[index] <= position) index++
        motorStartPosition = position
        motorStartCycles = lastCyclesHint
    }
}
