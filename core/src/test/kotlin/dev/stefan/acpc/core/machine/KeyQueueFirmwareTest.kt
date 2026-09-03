package dev.stefan.acpc.core.machine

import dev.stefan.acpc.core.TestRoms
import dev.stefan.acpc.core.api.CpcEmulator
import dev.stefan.acpc.core.api.NullAudioSink
import dev.stefan.acpc.core.keyboard.CpcKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/** Bursts of queued key events, as an Android `input text` sends them, must be read correctly by the firmware. */
class KeyQueueFirmwareTest {
    @Test
    fun `shifted then unshifted characters in a burst`() {
        assumeTrue(TestRoms.realAvailable(CpcModel.CPC6128))
        val emu = CpcEmulator.createMachine(CpcModel.CPC6128, TestRoms.real(CpcModel.CPC6128), NullAudioSink())
        repeat(130) { emu.runFrame() }
        listOf(CpcKey.SHIFT to true, CpcKey.DIGIT_9 to true, CpcKey.DIGIT_9 to false, CpcKey.SHIFT to false,
            CpcKey.SHIFT to true, CpcKey.DIGIT_6 to true, CpcKey.DIGIT_6 to false, CpcKey.SHIFT to false,
            CpcKey.DIGIT_4 to true, CpcKey.DIGIT_4 to false, CpcKey.A to true, CpcKey.A to false).forEach { (k, d) -> emu.queueKey(k, d) }
        repeat(60) { emu.runFrame() }
        val line = ScreenReader.readText(emu.machine).map { it.trim().filter { c -> c.code < 128 } }.first { it.startsWith(")") }
        assertEquals(")&4a", line)
    }
}
