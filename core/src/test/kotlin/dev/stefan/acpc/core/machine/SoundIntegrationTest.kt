package dev.stefan.acpc.core.machine

import dev.stefan.acpc.core.TestRoms
import dev.stefan.acpc.core.api.AudioSink
import dev.stefan.acpc.core.api.CpcEmulator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Drives the AY through the firmware: BASIC `SOUND` commands must produce
 * tones at the documented frequency (f = 1 MHz / (16 × period)) on the
 * documented stereo channel (A = left, C = right).
 */
class SoundIntegrationTest {
    private class CapturingSink(override val sampleRate: Int = 44_100) : AudioSink {
        val left = ArrayList<Short>()
        val right = ArrayList<Short>()
        override fun write(samples: ShortArray, frames: Int) {
            for (i in 0 until frames) {
                left += samples[i * 2]
                right += samples[i * 2 + 1]
            }
        }
    }

    private fun goertzel(samples: List<Short>, freq: Double, rate: Int): Double {
        val n = samples.size
        val k = 2.0 * Math.PI * freq / rate
        var s1 = 0.0
        var s2 = 0.0
        val coeff = 2 * cos(k)
        for (x in samples) {
            val s0 = x.toDouble() + coeff * s1 - s2
            s2 = s1
            s1 = s0
        }
        val re = s1 - s2 * cos(k)
        val im = s2 * sin(k)
        return sqrt(re * re + im * im) / n
    }

    private fun dominantFrequency(samples: List<Short>, rate: Int): Double {
        var best = 0.0
        var bestPower = -1.0
        var f = 50.0
        while (f < 2000.0) {
            val p = goertzel(samples, f, rate)
            if (p > bestPower) { bestPower = p; best = f }
            f += 1.0
        }
        return best
    }

    @Test
    fun `SOUND command produces the documented frequency on the documented channel`() {
        assumeTrue(TestRoms.realAvailable(CpcModel.CPC6128), "ROMs not available")
        val sink = CapturingSink()
        val emu = CpcEmulator.createMachine(CpcModel.CPC6128, TestRoms.real(CpcModel.CPC6128), sink)
        repeat(150) { emu.runFrame() }
        sink.left.clear(); sink.right.clear()
        // Channel A (bit 0 -> value 1), period 478 -> 1 000 000 / (16 * 478) = 130.75 Hz, 4 seconds, volume 15.
        emu.typeText("SOUND 1,478,400,15\n")
        repeat(60) { emu.runFrame() } // typing + start
        sink.left.clear(); sink.right.clear()
        repeat(100) { emu.runFrame() } // 2 seconds of tone
        assertEquals(sink.left.size, sink.right.size)
        assertTrue(sink.left.size in 85_000..91_000, "expected ~88 200 frames for 2 s, got ${sink.left.size}")
        val fl = dominantFrequency(sink.left, sink.sampleRate)
        assertTrue(abs(fl - 130.75) <= 2.0, "left channel frequency $fl Hz, expected 130.75 Hz")
        val rmsL = sqrt(sink.left.sumOf { it.toDouble() * it } / sink.left.size)
        val rmsR = sqrt(sink.right.sumOf { it.toDouble() * it } / sink.right.size)
        assertTrue(rmsL > 500, "left channel is silent (rms $rmsL)")
        assertTrue(rmsR < rmsL * 0.2, "channel A must be on the left only (L rms $rmsL, R rms $rmsR)")

        // Channel C (value 4), period 239 -> 261.5 Hz, on the right.
        repeat(150) { emu.runFrame() }
        emu.typeText("SOUND 4,239,400,15\n")
        repeat(60) { emu.runFrame() }
        sink.left.clear(); sink.right.clear()
        repeat(100) { emu.runFrame() }
        val fr = dominantFrequency(sink.right, sink.sampleRate)
        assertTrue(abs(fr - 261.5) <= 3.0, "right channel frequency $fr Hz, expected 261.5 Hz")
        val rmsL2 = sqrt(sink.left.sumOf { it.toDouble() * it } / sink.left.size)
        val rmsR2 = sqrt(sink.right.sumOf { it.toDouble() * it } / sink.right.size)
        assertTrue(rmsL2 < rmsR2 * 0.2, "channel C must be on the right only (L rms $rmsL2, R rms $rmsR2)")
    }

    @Test
    fun `silence is produced when nothing plays`() {
        assumeTrue(TestRoms.realAvailable(CpcModel.CPC6128), "ROMs not available")
        val sink = CapturingSink()
        val emu = CpcEmulator.createMachine(CpcModel.CPC6128, TestRoms.real(CpcModel.CPC6128), sink)
        repeat(200) { emu.runFrame() }
        sink.left.clear(); sink.right.clear()
        repeat(50) { emu.runFrame() }
        assertTrue(sink.left.all { it.toInt() == 0 } && sink.right.all { it.toInt() == 0 }, "expected silence")
    }
}
