package dev.stefan.acpc.core.machine

import dev.stefan.acpc.core.TestRoms
import dev.stefan.acpc.core.api.CpcEmulator
import dev.stefan.acpc.core.api.NullAudioSink
import dev.stefan.acpc.core.tape.CdtFormat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Loads every CDT found in ~/.acpc/tapes (Gradle property tapeDir) on a CPC
 * 464 through the firmware loader, at full emulation speed, and dumps
 * screenshots and a report to compatOut/tapes. Tagged slow.
 */
@Tag("slow")
class TapeIntegrationTest {
    private val tapeDir = File(System.getProperty("acpc.tapeDir") ?: (System.getProperty("user.home") + "/.acpc/tapes"))
    private val outDir = File(System.getProperty("acpc.compatOut") ?: (System.getProperty("user.home") + "/.acpc/compat-out"), "tapes").apply { mkdirs() }

    @Test
    fun loadAllTapes() {
        assumeTrue(TestRoms.realAvailable(CpcModel.CPC464), "ROMs not available")
        val tapes = tapeDir.walkTopDown().filter { it.isFile && it.name.lowercase().endsWith(".cdt") }.sortedBy { it.name }.toList()
        assumeTrue(tapes.isNotEmpty(), "no tapes in $tapeDir")
        val report = StringBuilder()
        for (t in tapes) {
            val line = runTape(t)
            println(line)
            report.append(line).append('\n')
        }
        File(outDir, "report.txt").writeText(report.toString())
    }

    private fun runTape(file: File): String {
        val name = file.nameWithoutExtension.replace(Regex("[^A-Za-z0-9._-]"), "_").take(40)
        val bytes = file.readBytes()
        val image = try { CdtFormat.parse(bytes) } catch (e: Exception) { return "$name: INVALID CDT (${e.message})" }
        val model = if (File(tapeDir, "$name.6128").exists()) CpcModel.CPC6128 else CpcModel.CPC464
        val roms = TestRoms.real(model)
        val emu = CpcEmulator.createMachine(model, roms, NullAudioSink())
        emu.insertTape(bytes, file.name)
        repeat(130) { emu.runFrame() }
        if (roms.amsdosRom != null) { emu.typeText("|tape\n"); repeat(25) { emu.runFrame() } }   // AMSDOS redirects RUN" to disc
        emu.typeText("run\"\n")
        repeat(50) { emu.runFrame() }
        emu.typeText(" ")
        val tapeSeconds = (image.totalCycles / 4_000_000L).toInt()
        val maxSeconds = minOf(tapeSeconds + 60, 900)
        var lastHash = 0
        var changes = 0
        var loadedAt = -1
        var idleSeconds = 0
        val start = System.nanoTime()
        var frame = emu.runFrame()
        var second = 0
        while (second < maxSeconds) {
            second++
            repeat(50) { frame = emu.runFrame() }
            val h = frame.pixels.contentHashCode()
            if (h != lastHash) changes++
            lastHash = h
            val status = emu.tapeStatus()!!
            idleSeconds = if (status.moving) 0 else idleSeconds + 1
            // Loaded: tape at its end, or the motor left off for 12 s after some loading (loaders pause it between blocks).
            if (loadedAt < 0 && (status.atEnd || (idleSeconds >= 12 && status.positionSeconds > 5))) loadedAt = second
            if (second % 60 == 0) CompatibilityRunTest.savePng(frame, File(outDir, "$name-${second}s.png"))
            if (loadedAt > 0 && second >= loadedAt + 15) break
        }
        CompatibilityRunTest.savePng(frame, File(outDir, "$name-final.png"))
        val elapsed = (System.nanoTime() - start) / 1e9
        val st = emu.tapeStatus()!!
        return "%s: tape %ds, ran %ds in %.1fs, motor stopped at %s, position %.0f/%.0f s, frame changes=%d, mode=%d".format(
            name, tapeSeconds, second, elapsed, if (loadedAt > 0) "${loadedAt}s" else "never", st.positionSeconds, st.lengthSeconds, changes, emu.debugInfo().gaMode,
        )
    }
}
