package dev.stefan.acpc.core

import dev.stefan.acpc.core.crtc.Crtc
import dev.stefan.acpc.core.gatearray.GateArray
import dev.stefan.acpc.core.machine.CpcModel
import dev.stefan.acpc.core.machine.CrtcType
import dev.stefan.acpc.core.memory.CpcMemory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** CRTC counter behaviour and the monitor's vertical hold, driven one microsecond at a time. */
class VideoTimingTest {

    private fun Crtc.set(register: Int, value: Int) {
        selectRegister(register)
        writeRegister(value)
    }

    private fun Crtc.step() {
        tick()
        advance()
    }

    /** A CRTC with one scan line per character row and 64-character lines. */
    private fun crtc(): Crtc = Crtc(CrtcType.TYPE0_HD6845S).apply {
        set(0, 63); set(9, 0); set(5, 0)
    }

    @Test
    fun `a row counter already past a lowered R4 runs on to its overflow`() {
        val crtc = crtc()
        crtc.set(4, 60); crtc.set(7, 100)
        while (crtc.vcc != 50) crtc.step()
        crtc.set(4, 20)
        var lines = 0
        var sawRow127 = false
        while (true) {
            crtc.step()
            if (crtc.hcc == 0) lines++
            if (crtc.vcc == 127) sawRow127 = true
            if (crtc.frameStarted) break
        }
        // Rows 50-127, then 0-20 after the wrap: the frame does not end at row 50.
        assertEquals(true, sawRow127)
        assertEquals(78 + 21, lines)
    }

    @Test
    fun `a character counter already past a lowered R0 runs on to 255`() {
        val crtc = crtc()
        crtc.set(4, 38); crtc.set(7, 30)
        while (crtc.hcc != 40) crtc.step()
        val row = crtc.vcc
        crtc.set(0, 20)
        var characters = 0
        while (crtc.vcc == row) {
            crtc.step()
            characters++
        }
        // Characters 40-255, then 0-20 after the wrap.
        assertEquals(216 + 21, characters)
    }

    /** Frames the monitor presents in [lines] scan lines, with rows of [r9] + 1 lines. */
    private fun monitor(r9: Int, r4: Int, r7: Int, lines: Int): Long {
        val crtc = crtc()
        crtc.set(9, r9); crtc.set(4, r4); crtc.set(7, r7); crtc.set(2, 46); crtc.set(3, 0x8E)
        val ga = GateArray(CpcMemory(CpcModel.CPC6128, TestRoms.synthetic()), crtc) { }
        repeat(lines * 64) { ga.tick() }
        return ga.frame.frameNumber
    }

    @Test
    fun `the monitor ignores a VSYNC that comes too early in its sweep`() {
        // A VSYNC every 98 lines: only every third one (294 lines) is obeyed.
        assertEquals(10L, monitor(r9 = 1, r4 = 48, r7 = 0, lines = 294 * 10 + 5))
        // A normal 312-line frame is obeyed every time.
        assertEquals(10L, monitor(r9 = 3, r4 = 77, r7 = 0, lines = 312 * 10 + 5))
    }

    @Test
    fun `without VSYNC the picture flies back on its own`() {
        // R7 beyond R4: no VSYNC at all.
        assertEquals(10L, monitor(r9 = 0, r4 = 100, r7 = 120, lines = GateArray.FREE_RUN_LINES * 10 + 5))
    }
}
