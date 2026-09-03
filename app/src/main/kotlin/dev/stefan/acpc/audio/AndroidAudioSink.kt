package dev.stefan.acpc.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import dev.stefan.acpc.core.api.AudioSink

/**
 * Streams the emulated AY output to an [AudioTrack].
 *
 * The track is opened in blocking streaming mode with a buffer of a few CPC
 * frames: [write] blocks when the buffer is full, which paces the emulation
 * thread on the audio clock. This is the most robust way to keep audio and
 * emulation in sync without drift or crackling.
 */
class AndroidAudioSink(override val sampleRate: Int, latencyFrames: Int) : AudioSink {
    private var track: AudioTrack? = null
    val bufferFrames: Int

    @Volatile var enabled = true

    init {
        val minBytes = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val wanted = latencyFrames * (sampleRate / 50) * 4
        val bytes = maxOf(minBytes, wanted)
        bufferFrames = bytes / 4
        track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build(),
                )
                .setBufferSizeInBytes(bytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
                .also { if (it.state != AudioTrack.STATE_INITIALIZED) throw IllegalStateException("AudioTrack not initialised") }
        } catch (e: Exception) {
            Log.w(TAG, "AudioTrack unavailable, running without sound", e)
            null
        }
    }

    val available: Boolean get() = track != null

    fun start() {
        runCatching { track?.play() }
    }

    fun pause() {
        runCatching { track?.pause(); track?.flush() }
    }

    fun release() {
        runCatching { track?.stop() }
        runCatching { track?.release() }
        track = null
    }

    /** Nanoseconds spent blocked in [write] (pacing time, excluded from emulation statistics). */
    @Volatile var blockedNanos = 0L

    /** Blocks until the samples are queued. */
    override fun write(samples: ShortArray, frames: Int) {
        val t = track ?: return
        if (!enabled) return
        val start = System.nanoTime()
        try {
            writeBlocking(t, samples, frames)
        } finally {
            blockedNanos += System.nanoTime() - start
        }
    }

    private fun writeBlocking(t: AudioTrack, samples: ShortArray, frames: Int) {
        var offset = 0
        var remaining = frames * 2
        while (remaining > 0) {
            val n = t.write(samples, offset, remaining, AudioTrack.WRITE_BLOCKING)
            if (n <= 0) return
            offset += n
            remaining -= n
        }
    }

    companion object {
        private const val TAG = "AndroidAudioSink"
        fun preferredSampleRate(): Int = 44_100
        fun setVolumeFlags(am: AudioManager?) = Unit
    }
}
