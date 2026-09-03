package dev.stefan.acpc.core.machine

import dev.stefan.acpc.core.TestRoms
import dev.stefan.acpc.core.api.CpcEmulator
import dev.stefan.acpc.core.api.NullAudioSink
import dev.stefan.acpc.core.disk.AmsdosCatalog
import dev.stefan.acpc.core.disk.DiskImage
import dev.stefan.acpc.core.disk.DskFormat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * End-to-end disc tests driven by the real AMSDOS: save a BASIC program on a
 * blank formatted disc, catalogue it, reset, load it back and run it.
 */
class DiscIntegrationTest {

    private fun boot(): CpcEmulator {
        assumeTrue(TestRoms.realAvailable(CpcModel.CPC6128), "ROMs not available")
        val emu = CpcEmulator.createMachine(CpcModel.CPC6128, TestRoms.real(CpcModel.CPC6128), NullAudioSink())
        repeat(120) { emu.runFrame() }
        return emu
    }

    private fun runUntil(emu: CpcEmulator, maxFrames: Int, condition: () -> Boolean): Boolean {
        repeat(maxFrames) {
            emu.runFrame()
            if (it % 10 == 0 && condition()) return true
        }
        return condition()
    }

    private fun screen(emu: CpcEmulator) = ScreenReader.readText(emu.machine)

    @Test
    fun `AMSDOS saves, catalogues, loads and runs a program`() {
        val emu = boot()
        emu.machine.fdc.insertDiskImage(0, DiskImage.formattedData("test.dsk"))
        emu.typeText("10 PRINT \"HELLO FROM DISC\"\n20 PRINT 2^10\nSAVE\"HELLO\"\n")
        assertTrue(runUntil(emu, 2000) { screen(emu).count { it.startsWith("Ready") } >= 2 }, "SAVE did not complete: ${screen(emu)}")
        val files = AmsdosCatalog.list(emu.machine.fdc.disk(0)!!)
        assertEquals(listOf("HELLO.BAS"), files.map { it.fileName })
        assertTrue(emu.machine.fdc.disk(0)!!.modified)

        // The image must survive a DSK round trip.
        val bytes = emu.exportDisk(0)!!
        val reloaded = DskFormat.read(bytes)
        assertEquals(listOf("HELLO.BAS"), AmsdosCatalog.list(reloaded).map { it.fileName })

        emu.reset()
        emu.loadDisk(0, bytes, "test.dsk")
        repeat(120) { emu.runFrame() }
        emu.typeText("CAT\n")
        assertTrue(runUntil(emu, 1500) { screen(emu).any { it.contains("HELLO   .BAS") } }, "CAT output not found: ${screen(emu)}")
        emu.typeText("RUN\"HELLO\n")
        assertTrue(runUntil(emu, 1500) { screen(emu).any { it.trim() == "1024" } }, "program output not found: ${screen(emu)}")
        assertTrue(screen(emu).any { it.contains("HELLO FROM DISC") })
        println(screen(emu).filter { it.isNotEmpty() }.joinToString("\n"))
    }

    @Test
    fun `auto start runs the program on the disc`() {
        val emu = boot()
        emu.machine.fdc.insertDiskImage(0, DiskImage.formattedData("auto.dsk"))
        emu.typeText("10 PRINT \"AUTOSTART OK\"\nSAVE\"DISC\"\n")
        assertTrue(runUntil(emu, 2000) { screen(emu).count { it.startsWith("Ready") } >= 2 })
        val bytes = emu.exportDisk(0)!!
        val image = DskFormat.read(bytes)
        val command = AmsdosCatalog.autoStartCommand(image)
        assertEquals("RUN\"DISC.BAS\n", command)

        emu.reset()
        emu.loadDisk(0, bytes)
        repeat(120) { emu.runFrame() }
        emu.typeText(command!!)
        assertTrue(runUntil(emu, 1500) { screen(emu).any { it.contains("AUTOSTART OK") } }, "auto start failed: ${screen(emu)}")
    }

    @Test
    fun `missing disc gives the AMSDOS error instead of hanging`() {
        val emu = boot()
        emu.typeText("CAT\n")
        assertTrue(runUntil(emu, 1500) { screen(emu).any { it.contains("Drive A: disc missing") } }, "no error message: ${screen(emu)}")
    }
}
