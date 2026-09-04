package dev.stefan.acpc.core.machine

import dev.stefan.acpc.core.api.CpcEmulator
import dev.stefan.acpc.core.api.NullAudioSink
import dev.stefan.acpc.core.cartridge.Cartridge
import dev.stefan.acpc.core.joystick.JoystickButton
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Boots every cartridge found in ~/.acpc/carts (Gradle property cartDir) on
 * a GX4000, presses fire on the joystick every few seconds to get past
 * title screens, and dumps screenshots and a report to compatOut/carts.
 * A system cartridge (Plus firmware) is booted as a 6128 Plus instead.
 * Tagged slow.
 */
@Tag("slow")
class CartridgeIntegrationTest {
    private val cartDir = File(System.getProperty("acpc.cartDir") ?: (System.getProperty("user.home") + "/.acpc/carts"))
    private val outDir = File(System.getProperty("acpc.compatOut") ?: (System.getProperty("user.home") + "/.acpc/compat-out"), "carts").apply { mkdirs() }

    @Test
    fun bootAllCartridges() {
        val carts = cartDir.walkTopDown().filter { it.isFile && it.name.lowercase().endsWith(".cpr") }.sortedBy { it.name }.toList()
        assumeTrue(carts.isNotEmpty(), "no cartridges in $cartDir")
        val report = StringBuilder()
        val seconds = (System.getProperty("acpc.cartSeconds") ?: "40").toInt()
        for (c in carts) {
            val line = runCartridge(c, seconds)
            println(line)
            report.append(line).append('\n')
        }
        File(outDir, "report.txt").writeText(report.toString())
    }

    private fun runCartridge(file: File, seconds: Int): String {
        val name = file.nameWithoutExtension.replace(Regex("[^A-Za-z0-9._-]"), "_").take(40)
        val cart = try { Cartridge.parse(file.readBytes(), file.name) } catch (e: Exception) { return "$name: INVALID (${e.message})" }
        val model = if (cart.isSystemCartridge) CpcModel.CPC6128PLUS else CpcModel.GX4000
        val emu = CpcEmulator.createMachine(model, null, NullAudioSink(), cartridge = cart)
        val m = emu.machine
        var lastHash = 0
        var changes = 0
        var unlockedAt = -1
        var spritesAt = -1
        var dmaAt = -1
        var frame = emu.runFrame()
        val start = System.nanoTime()
        val hot = HashMap<Int, Int>()
        val tracing = System.getenv("ACPC_TRACE_HOT") != null
        for (second in 1..seconds) {
            if (tracing && second == seconds) m.instructionHook = { mm -> hot.merge(mm.cpu.pc, 1, Int::plus) }
            repeat(50) {
                frame = emu.runFrame()
                val asic = m.asic!!
                if (unlockedAt < 0 && !asic.locked) unlockedAt = second
                if (spritesAt < 0 && (0 until 16).any { s -> asic.spriteMagX[s] != 0 && asic.spriteMagY[s] != 0 }) spritesAt = second
                if (dmaAt < 0 && (asic.ram[0x2C0F].toInt() and 7) != 0) dmaAt = second
            }
            val h = frame.pixels.contentHashCode()
            if (h != lastHash) changes++
            lastHash = h
            // Fire every 6 s (held for a few frames), like a player at a title screen.
            // The main button of the GX4000 pad is the CPC's "fire 2" (matrix line 9 bit 4).
            if (second % 6 == 3) {
                emu.setJoystick(0, JoystickButton.FIRE2, true)
                repeat(8) { emu.runFrame() }
                emu.setJoystick(0, JoystickButton.FIRE2, false)
            }
            if (second % 10 == 0 || second == seconds) CompatibilityRunTest.savePng(frame, File(outDir, "$name-${second}s.png"))
        }
        val elapsed = (System.nanoTime() - start) / 1e9
        System.getenv("ACPC_TRACE_DUMP")?.split(":")?.map { it.toInt(16) }?.let { (from, len) ->
            File(outDir, "$name-dump.bin").writeBytes(ByteArray(len) { m.memory.read(from + it).toByte() })
        }
        m.instructionHook = null
        if (tracing) println("  hot pcs: " + hot.entries.sortedByDescending { it.value }.take(40).joinToString { "%04X=%d".format(it.key, it.value) })
        val info = emu.debugInfo()
        val asic = m.asic!!
        return "%s: %s, ran %ds in %.1fs, unlocked at %s, sprites at %s, dma at %s, frame changes=%d, mode=%d, pc=%04X, %s, im=%d iff1=%b halted=%b pri=%d splt=%d sscr=%02X ivr=%02X dcsr=%02X acks=%d psg7=%02X psg14=%02X psgSel=%d ppiCtl=%02X".format(
            name, model.name, seconds, elapsed, at(unlockedAt), at(spritesAt), at(dmaAt), changes, info.gaMode, info.pc, info.romConfig,
            info.im, info.iff1, m.cpu.halted, asic.pri, asic.splt, asic.sscr, asic.ivr, asic.read(0x2C0F), m.gateArray.acknowledgeCount, m.psg.regs[7], m.psg.regs[14], m.psg.selectedRegister, m.ppi.control,
        )
    }

    private fun at(second: Int) = if (second < 0) "never" else "${second}s"
}
