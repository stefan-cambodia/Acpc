package dev.stefan.acpc.core.machine

import dev.stefan.acpc.core.TestRoms
import dev.stefan.acpc.core.api.CpcEmulator
import dev.stefan.acpc.core.api.NullAudioSink
import dev.stefan.acpc.core.api.VideoFrame
import dev.stefan.acpc.core.disk.AmsdosCatalog
import dev.stefan.acpc.core.disk.DskFormat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Compatibility harness: boots every disc image found in the test disc
 * directory (Gradle property testDiskDir, default ~/.acpc/testdisks), types
 * the auto-start command, runs for a while and dumps screenshots + a short
 * report to compatOut (default ~/.acpc/compat-out) so a human can check the
 * result. Optional extra input can be given per disc through a
 * `<name>.keys` file (text typed after boot).
 *
 * Tagged "slow": run with `./gradlew :core:test -PslowTests --tests '*CompatibilityRunTest*'`.
 * Per-disc options: `<name>.cmd` (command to type instead of auto-start),
 * `<name>.keys` (text typed after 8 s), `<name>.secs` (duration), `<name>.464` (use a CPC 464).
 */
@Tag("slow")
class CompatibilityRunTest {
    private val diskDir = File(System.getProperty("acpc.testDiskDir") ?: (System.getProperty("user.home") + "/.acpc/testdisks"))
    private val outDir = File(System.getProperty("acpc.compatOut") ?: (System.getProperty("user.home") + "/.acpc/compat-out")).apply { mkdirs() }

    @Test
    fun runAllDiscs() {
        assumeTrue(TestRoms.realAvailable(CpcModel.CPC6128), "ROMs not available")
        val disks = diskDir.listFiles { f -> f.name.lowercase().endsWith(".dsk") }?.sortedBy { it.name } ?: emptyList()
        assumeTrue(disks.isNotEmpty(), "no test discs in $diskDir")
        val report = StringBuilder()
        for (disk in disks) {
            val line = runDisc(disk)
            println(line)
            report.append(line).append('\n')
        }
        File(outDir, "report.txt").writeText(report.toString())
    }

    private fun runDisc(disk: File): String {
        val name = disk.nameWithoutExtension
        val model = if (File(diskDir, "$name.464").exists()) CpcModel.CPC464 else CpcModel.CPC6128
        val emu = CpcEmulator.createMachine(model, TestRoms.real(model), NullAudioSink())
        val bytes = disk.readBytes()
        val image = try {
            DskFormat.read(bytes, disk.name)
        } catch (e: Exception) {
            return "$name: INVALID DSK (${e.message})"
        }
        emu.loadDisk(0, bytes, disk.name)
        val command = File(diskDir, "$name.cmd").takeIf { it.exists() }?.readText() ?: AmsdosCatalog.autoStartCommand(image)
        repeat(130) { emu.runFrame() }
        if (command != null) emu.typeText(command)
        val extra = File(diskDir, "$name.keys").takeIf { it.exists() }?.readText()
        val seconds = (File(diskDir, "$name.secs").takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull()) ?: 40
        var frame: VideoFrame = emu.runFrame()
        val pcSamples = HashSet<Int>()
        var lastHash = 0
        var changes = 0
        val start = System.nanoTime()
        for (second in 1..seconds) {
            repeat(50) {
                frame = emu.runFrame()
                pcSamples += emu.machine.cpu.pc
            }
            val h = frame.pixels.contentHashCode()
            if (h != lastHash) changes++
            lastHash = h
            if (second == 8 && extra != null) emu.typeText(extra)
            if (second % 10 == 0 || second == seconds) savePng(frame, File(outDir, "$name-${second}s.png"))
        }
        val elapsed = (System.nanoTime() - start) / 1e9
        val info = emu.debugInfo()
        return "%s: %ds emulated in %.1fs (%.0fx realtime), cmd=%s, distinct PCs=%d, frame changes=%d, mode=%d, fdc=%s".format(
            name, seconds, elapsed, seconds / elapsed, command?.trim(), pcSamples.size, changes, info.gaMode, info.fdcStatus,
        )
    }

    companion object {
        fun savePng(frame: VideoFrame, file: File) {
            val w = frame.visibleWidth
            val h = frame.visibleHeight * 2
            val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            for (y in 0 until frame.visibleHeight) {
                val row = (frame.visibleY + y) * frame.stride + frame.visibleX
                img.setRGB(0, y * 2, w, 1, frame.pixels, row, frame.stride)
                img.setRGB(0, y * 2 + 1, w, 1, frame.pixels, row, frame.stride)
            }
            ImageIO.write(img, "png", file)
        }
    }
}
