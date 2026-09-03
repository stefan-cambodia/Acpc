package dev.stefan.acpc.core.timing

/**
 * Timing constants of the Amstrad CPC.
 *
 * The Z80A is clocked at 4 MHz, but the Gate Array inserts wait states so that
 * every memory or I/O access starts on a 1 µs boundary (4 T-states). As a
 * consequence all instruction durations are multiples of 4 T-states, commonly
 * expressed in "NOPs" (1 NOP = 1 µs = 4 T-states).
 *
 * The CRTC is clocked at 1 MHz: one character (2 bytes of video RAM,
 * 16 pixels at the Gate Array's 16 MHz pixel clock) per microsecond.
 */
object CpcTiming {
    /** Z80 clock in Hz. */
    const val CPU_CLOCK_HZ = 4_000_000

    /** T-states per microsecond (one CRTC character / one NOP). */
    const val TSTATES_PER_US = 4

    /** Gate Array pixel clock: 16 pixels per microsecond. */
    const val PIXELS_PER_US = 16

    /** Standard PAL frame with the default CRTC register set: 64 µs × 312 lines. */
    const val US_PER_LINE = 64
    const val LINES_PER_FRAME = 312
    const val US_PER_FRAME = US_PER_LINE * LINES_PER_FRAME          // 19 968 µs
    const val TSTATES_PER_FRAME = US_PER_FRAME * TSTATES_PER_US     // 79 872 T

    /** Nominal frame rate: 4 000 000 / 79 872 ≈ 50.08 Hz. */
    const val FRAME_RATE_HZ = CPU_CLOCK_HZ.toDouble() / TSTATES_PER_FRAME

    /** AY-3-8912 clock (1 MHz). */
    const val AY_CLOCK_HZ = 1_000_000
}
