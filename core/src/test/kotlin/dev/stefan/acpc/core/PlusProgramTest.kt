package dev.stefan.acpc.core

import dev.stefan.acpc.core.asic.PlusProgram
import dev.stefan.acpc.core.disk.DiskImage
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PlusProgramTest {

    @Test
    fun `a disc carrying the ASIC unlock sequence is a Plus program`() {
        val image = DiskImage.formattedData()
        assertFalse(PlusProgram.containsUnlock(image))
        val data = ByteArray(512)
        PlusProgram.UNLOCK_SEQUENCE.copyInto(data, 100)
        image.track(0, 5)!!.sectors.first { it.r == 0xC3 }.write(data)
        assertTrue(PlusProgram.containsUnlock(image))
    }

    @Test
    fun `the sequence is found across two sectors of an interleaved track`() {
        val image = DiskImage.formattedData()
        val seq = PlusProgram.UNLOCK_SEQUENCE
        // C1 C6 C2 C7...: sectors C1 and C2 are not neighbours on the track.
        val end = ByteArray(512).also { seq.copyInto(it, 512 - 8, 0, 8) }
        val start = ByteArray(512).also { seq.copyInto(it, 0, 8, seq.size) }
        val sectors = image.track(0, 2)!!.sectors
        sectors.first { it.r == 0xC1 }.write(end)
        sectors.first { it.r == 0xC2 }.write(start)
        assertTrue(PlusProgram.containsUnlock(image))
        assertFalse(PlusProgram.containsUnlock(ByteArray(4096)))
    }
}
