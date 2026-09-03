package dev.stefan.acpc.core.api

/**
 * Receives the PCM audio produced by the emulated AY-3-8912.
 *
 * Samples are 16-bit signed, interleaved stereo (L, R), at [sampleRate] Hz.
 * The core calls [write] from the emulation thread; implementations must be
 * non-blocking or bounded-blocking (they may block briefly to pace the
 * emulation against the audio clock, which is the recommended synchronisation
 * strategy).
 */
interface AudioSink {
    val sampleRate: Int
    fun write(samples: ShortArray, frames: Int)
}

/** Discards audio (used by tests and headless runs). */
class NullAudioSink(override val sampleRate: Int = 44_100) : AudioSink {
    override fun write(samples: ShortArray, frames: Int) = Unit
}
