package dev.stefan.acpc.core.machine

import dev.stefan.acpc.core.TestRoms
import dev.stefan.acpc.core.api.CpcEmulator
import dev.stefan.acpc.core.api.NullAudioSink
import dev.stefan.acpc.core.gatearray.CpcPalette
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Boots the real firmware. Requires the Amstrad ROMs in ACPC_ROM_DIR
 * (default ~/.acpc/roms): cpc464.rom, cpc664.rom, cpc6128.rom, amsdos.rom.
 */
class MachineBootTest {

    private fun boot(model: CpcModel, frames: Int): CpcEmulator {
        assumeTrue(TestRoms.realAvailable(model), "ROMs for $model not available")
        val emu = CpcEmulator.createMachine(model, TestRoms.real(model), NullAudioSink())
        repeat(frames) { emu.runFrame() }
        return emu
    }

    @Test
    fun `CPC 6128 boots to the BASIC prompt`() {
        val emu = boot(CpcModel.CPC6128, 150)
        val text = ScreenReader.readText(emu.machine)
        println(text.filter { it.isNotEmpty() }.joinToString("\n"))
        assertTrue(text.any { it.contains("Amstrad 128K Microcomputer") }, "boot banner not found")
        assertTrue(text.any { it.contains("BASIC 1.1") }, "BASIC banner not found")
        assertTrue(text.any { it.startsWith("Ready") }, "Ready prompt not found")

        // The rendered frame must show the firmware colours: blue paper/border and yellow ink.
        val frame = emu.runFrame()
        val pixels = frame.pixels
        var blue = 0
        var yellow = 0
        for (y in frame.visibleY until frame.visibleY + frame.visibleHeight) {
            for (x in frame.visibleX until frame.visibleX + frame.visibleWidth) {
                when (pixels[y * frame.stride + x]) {
                    CpcPalette.ARGB[4] -> blue++
                    CpcPalette.ARGB[10] -> yellow++
                }
            }
        }
        val total = frame.visibleWidth * frame.visibleHeight
        assertTrue(blue > total * 0.8, "expected a mostly blue screen, got $blue/$total")
        assertTrue(yellow > 500, "expected yellow text pixels, got $yellow")
        assertEquals(1, emu.machine.gateArray.mode)
    }

    @Test
    fun `CPC 464 boots to the BASIC prompt`() {
        val emu = boot(CpcModel.CPC464, 150)
        val text = ScreenReader.readText(emu.machine)
        assertTrue(text.any { it.contains("Amstrad 64K Microcomputer") }, "boot banner not found: $text")
        assertTrue(text.any { it.contains("BASIC 1.0") }, "BASIC banner not found")
        assertTrue(text.any { it.startsWith("Ready") }, "Ready prompt not found")
    }

    @Test
    fun `CPC 664 boots to the BASIC prompt`() {
        val emu = boot(CpcModel.CPC664, 150)
        val text = ScreenReader.readText(emu.machine)
        assertTrue(text.any { it.contains("Amstrad 64K Microcomputer") }, "boot banner not found: $text")
        assertTrue(text.any { it.contains("BASIC 1.1") }, "BASIC banner not found")
        assertTrue(text.any { it.startsWith("Ready") }, "Ready prompt not found")
    }

    @Test
    fun `typed BASIC program runs`() {
        val emu = boot(CpcModel.CPC6128, 150)
        emu.typeText("PRINT 6*7\n")
        repeat(120) { emu.runFrame() }
        val text = ScreenReader.readText(emu.machine)
        println(text.filter { it.isNotEmpty() }.joinToString("\n"))
        assertTrue(text.any { it.trim() == "42" }, "PRINT result not found: $text")
    }

    @Test
    fun `save and restore state reproduces execution`() {
        val emu = boot(CpcModel.CPC6128, 150)
        emu.typeText("FOR I=1 TO 5:PRINT I*I:NEXT\n")
        repeat(200) { emu.runFrame() }
        val saved = ScreenReader.readText(emu.machine)
        assertTrue(saved.any { it.trim() == "25" }, "program did not run: $saved")
        val state = emu.saveState()

        emu.typeText("PRINT 7*8\n")
        repeat(120) { emu.runFrame() }
        val after = ScreenReader.readText(emu.machine)
        assertTrue(after.any { it.trim() == "56" }, "second program did not run: $after")

        emu.loadState(state)
        assertEquals(saved, ScreenReader.readText(emu.machine))
        emu.typeText("PRINT 7*8\n")
        repeat(120) { emu.runFrame() }
        assertEquals(after, ScreenReader.readText(emu.machine))
    }

    @Test
    fun `loading garbage as a state fails cleanly`() {
        val emu = boot(CpcModel.CPC6128, 10)
        org.junit.jupiter.api.assertThrows<dev.stefan.acpc.core.api.InvalidStateException> {
            emu.loadState(byteArrayOf(1, 2, 3, 4, 5))
        }
    }
}
