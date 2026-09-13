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
    private fun crtc(type: CrtcType = CrtcType.TYPE0_HD6845S): Crtc = Crtc(type).apply {
        set(0, 63); set(9, 0); set(5, 0)
    }

    /** Runs until the counters show row [vcc], line [rlc], character 0. */
    private fun Crtc.runTo(vcc: Int, rlc: Int) {
        var guard = 0
        while (!(this.vcc == vcc && this.rlc == rlc && hcc == 0)) {
            step()
            check(++guard < 10_000_000) { "never reached row $vcc line $rlc" }
        }
    }

    /** Steps to the start of the next line; returns true when a frame started on the way. */
    private fun Crtc.nextLine(): Boolean {
        var started = false
        do {
            step()
            started = started || frameStarted
        } while (hcc != 0)
        return started
    }

    @Test
    fun `R4 changed during the last line of a matched row resets the HD6845S row counter`() {
        val crtc = crtc()
        crtc.set(9, 1); crtc.set(4, 10); crtc.set(5, 4); crtc.set(7, 100)
        crtc.runTo(10, 1)                         // last line of the last row: both latched
        crtc.set(4, 20)                           // ignored by the latch on the last line
        val started = crtc.nextLine()
        assertEquals(0, crtc.vcc)                 // mismatch: back to row 0, no vertical adjust
        assertEquals(false, started)
    }

    @Test
    fun `the UM6845R re-evaluates R4 at once`() {
        val crtc = crtc(CrtcType.TYPE1_UM6845R)
        crtc.set(9, 1); crtc.set(4, 10); crtc.set(5, 0); crtc.set(7, 100)
        crtc.runTo(10, 1)
        crtc.set(4, 20)
        crtc.nextLine()
        assertEquals(11, crtc.vcc)                // not the last row any more: counting goes on
    }

    @Test
    fun `vertical total adjust lines keep counting rows and can trigger VSYNC`() {
        val crtc = crtc()
        crtc.set(9, 1); crtc.set(4, 5); crtc.set(5, 6); crtc.set(7, 7)
        crtc.runTo(5, 1)
        var sawVsync = false
        var lines = 0
        while (true) {
            val started = crtc.nextLine()
            lines++
            if (crtc.vsync) sawVsync = true
            if (started) break
        }
        assertEquals(1 + 6, lines)                // the end of row 5, then 6 adjust lines
        assertEquals(true, sawVsync)              // row 7 was reached inside the adjust
    }

    @Test
    fun `the UM6845R gives no VSYNC while R4 and R5 are both zero`() {
        val crtc = crtc(CrtcType.TYPE1_UM6845R)
        crtc.set(4, 0); crtc.set(5, 0); crtc.set(7, 0)
        var vsync = false
        repeat(400 * 64) { crtc.step(); if (crtc.vsyncStarted) vsync = true }
        assertEquals(false, vsync)
        val hitachi = crtc()
        hitachi.set(4, 0); hitachi.set(5, 0); hitachi.set(7, 0)
        repeat(400 * 64) { hitachi.step(); if (hitachi.vsyncStarted) vsync = true }
        assertEquals(true, vsync)
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

    /** Frames the monitor presents in [lines] scan lines after a second of warm-up, with rows of [r9] + 1 lines. */
    private fun monitor(r9: Int, r4: Int, r7: Int, lines: Int): Long {
        val crtc = crtc()
        crtc.set(9, r9); crtc.set(4, r4); crtc.set(7, r7); crtc.set(2, 46); crtc.set(3, 0x8E)
        val ga = GateArray(CpcMemory(CpcModel.CPC6128, TestRoms.synthetic()), crtc) { }
        repeat(1000 * 64) { ga.tick() }
        val before = ga.frame.frameNumber
        repeat(lines * 64) { ga.tick() }
        return ga.frame.frameNumber - before
    }

    @Test
    fun `the monitor ignores a VSYNC that comes too early in its sweep`() {
        // A VSYNC every 98 lines: only every third one (294 lines) is obeyed.
        assertEquals(10L, monitor(r9 = 1, r4 = 48, r7 = 0, lines = 294 * 10))
        // A normal 312-line frame is obeyed every time.
        assertEquals(10L, monitor(r9 = 3, r4 = 77, r7 = 0, lines = 312 * 10))
    }

    @Test
    fun `without VSYNC the picture flies back on its own`() {
        // R7 beyond R4: no VSYNC at all.
        assertEquals(10L, monitor(r9 = 0, r4 = 100, r7 = 120, lines = GateArray.FREE_RUN_LINES * 10))
    }
}
