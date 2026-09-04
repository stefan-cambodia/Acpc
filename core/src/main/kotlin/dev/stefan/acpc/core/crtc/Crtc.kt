package dev.stefan.acpc.core.crtc

import dev.stefan.acpc.core.machine.CrtcType

/**
 * Motorola 6845 compatible CRT controller, as used in the Amstrad CPC.
 *
 * The CRTC is clocked at 1 MHz: [tick] advances by one character (1 µs) and
 * updates the horizontal / vertical counters, the display enable, HSYNC and
 * VSYNC outputs and the memory address (MA) / raster address (RA) that the
 * Gate Array uses to fetch video RAM.
 *
 * Only the behaviour relevant to CPC software is modelled: the counters, the
 * sync generation (including vertical total adjust and programmable sync
 * widths) and the type-dependent register masks / read-back rules. The
 * cursor and light pen are not emulated.
 */
class Crtc(val type: CrtcType) {

    /** R0-R17. */
    val regs = IntArray(18)

    /** Register selected through port &BCxx. */
    var selectedRegister = 0
        private set

    // ---- Counters ----------------------------------------------------------

    /** Horizontal character counter (0..R0). */
    var hcc = 0
        private set

    /** Vertical character row counter (0..R4). */
    var vcc = 0
        private set

    /** Raster line counter inside the current character row (0..R9). */
    var rlc = 0
        private set

    private var vtac = 0           // vertical total adjust line counter
    private var inAdjust = false   // currently in the vertical total adjust lines

    /** Memory address counter (14 bits). */
    var ma = 0
        private set

    private var maLine = 0         // MA at the start of the current character row
    private var maNextLine = 0     // MA latched at hcc == R1 on the last raster line

    // ---- Outputs -----------------------------------------------------------

    var hsync = false
        private set
    private var hsyncCount = 0

    var vsync = false
        private set
    private var vsyncLines = 0

    private var hDisplay = false
    private var vDisplay = false

    /** True while the current character must be drawn from video RAM. */
    val displayEnabled: Boolean get() = hDisplay && vDisplay

    // ---- Events of the last tick -----------------------------------------

    var hsyncStarted = false
        private set
    var hsyncEnded = false
        private set
    var vsyncStarted = false
        private set
    var vsyncEnded = false
        private set

    /** Set for one tick when a new CRTC frame started (VCC and RLC back to 0). */
    var frameStarted = false
        private set

    private var pendingSplit = -1

    init {
        reset()
    }

    fun reset() {
        regs.fill(0)
        // Values programmed by the firmware, so the display is stable even before boot.
        regs[0] = 63; regs[1] = 40; regs[2] = 46; regs[3] = 0x8E
        regs[4] = 38; regs[5] = 0; regs[6] = 25; regs[7] = 30
        regs[8] = 0; regs[9] = 7; regs[12] = 0x30; regs[13] = 0
        selectedRegister = 0
        hcc = 0; vcc = 0; rlc = 0; vtac = 0; inAdjust = false
        ma = 0; maLine = 0; maNextLine = 0
        hsync = false; hsyncCount = 0
        vsync = false; vsyncLines = 0
        hDisplay = true; vDisplay = true
        pendingSplit = -1
        clearEvents()
    }

    /**
     * ASIC split screen: the next scan line (and the rest of its character
     * row) starts at [address] instead of the current row address.
     */
    fun splitAt(address: Int) {
        pendingSplit = address and 0x3FFF
    }

    // ---- CPU interface -----------------------------------------------------

    fun selectRegister(value: Int) {
        selectedRegister = value and 0x1F
    }

    fun writeRegister(value: Int) {
        val r = selectedRegister
        if (r > 17) return
        regs[r] = value and MASKS[r]
    }

    /** Read-back of the selected register (port &BFxx). */
    fun readRegister(): Int {
        val r = selectedRegister
        return when (type) {
            CrtcType.TYPE0_HD6845S -> if (r in 12..17) regs[r] else 0
            CrtcType.TYPE1_UM6845R -> when (r) {
                in 14..17 -> regs[r]
                31 -> 0xFF
                else -> 0
            }
        }
    }

    /** Status register (port &BExx). Only the UM6845R has one. */
    fun readStatus(): Int = when (type) {
        CrtcType.TYPE0_HD6845S -> 0xFF
        // Bit 5 = vertical blanking (set outside the display area), bit 6 = light pen, bit 7 = update ready.
        CrtcType.TYPE1_UM6845R -> if (vDisplay) 0x00 else 0x20
    }

    /** Video RAM address of the first byte of the current character. */
    fun videoAddress(): Int =
        ((ma and 0x3000) shl 2) or ((rlc and 7) shl 11) or ((ma and 0x3FF) shl 1)

    private fun clearEvents() {
        hsyncStarted = false; hsyncEnded = false
        vsyncStarted = false; vsyncEnded = false
        frameStarted = false
    }

    private fun vsyncWidth(): Int = when (type) {
        CrtcType.TYPE0_HD6845S -> ((regs[3] ushr 4) and 0x0F).let { if (it == 0) 16 else it }
        CrtcType.TYPE1_UM6845R -> 16
    }

    /**
     * Advances by one character clock (1 µs). Call [videoAddress],
     * [displayEnabled], [hsync] and [vsync] afterwards to know what the
     * Gate Array must output for this character.
     */
    fun tick() {
        val r1 = regs[1]
        val r2 = regs[2]
        val hsyncWidth = regs[3] and 0x0F

        // Horizontal sync end / start.
        if (hsync) {
            hsyncCount++
            if (hsyncCount >= hsyncWidth) {
                hsync = false
                hsyncEnded = true
            }
        }
        if (hcc == r2 && hsyncWidth != 0 && !hsync) {
            hsync = true
            hsyncCount = 0
            hsyncStarted = true
        }

        // Horizontal display end.
        if (hcc == r1) {
            hDisplay = false
            if (rlc == regs[9] || inAdjust) maNextLine = ma
        }
    }

    /** Second half of the character clock: advance the counters after the character was output. */
    fun advance() {
        clearEvents()
        ma = (ma + 1) and 0x3FFF
        hcc = (hcc + 1) and 0xFF
        if (hcc > regs[0]) {
            endOfLine()
        }
    }

    private fun endOfLine() {
        hcc = 0
        hDisplay = true
        if (vsync) {
            vsyncLines++
            if (vsyncLines >= vsyncWidth()) {
                vsync = false
                vsyncEnded = true
            }
        }
        if (inAdjust) {
            vtac++
            if (vtac >= regs[5]) {
                startFrame()
            } else {
                rlc = (rlc + 1) and 0x1F
                ma = maLine
                applySplit()
            }
            return
        }
        if (rlc >= regs[9]) {
            // End of the character row.
            rlc = 0
            maLine = maNextLine
            ma = maLine
            vcc = (vcc + 1) and 0x7F
            if (vcc > regs[4]) {
                if (regs[5] != 0) {
                    inAdjust = true
                    vtac = 0
                } else {
                    startFrame()
                    return
                }
            }
            checkRow()
        } else {
            rlc++
            ma = maLine
        }
        applySplit()
    }

    private fun applySplit() {
        if (pendingSplit < 0) return
        maLine = pendingSplit
        maNextLine = maLine
        ma = maLine
        pendingSplit = -1
    }

    private fun startFrame() {
        inAdjust = false
        vtac = 0
        vcc = 0
        rlc = 0
        maLine = ((regs[12] shl 8) or regs[13]) and 0x3FFF
        maNextLine = maLine
        ma = maLine
        vDisplay = true
        pendingSplit = -1
        frameStarted = true
        checkRow()
    }

    /** Row-dependent comparisons performed when [vcc] changes. */
    private fun checkRow() {
        if (vcc == regs[6]) vDisplay = false
        if (vcc == regs[7] && !vsync) {
            vsync = true
            vsyncLines = 0
            vsyncStarted = true
        }
    }

    // ---- State -------------------------------------------------------------

    fun exportState(): IntArray = intArrayOf(
        selectedRegister, hcc, vcc, rlc, vtac, if (inAdjust) 1 else 0, ma, maLine, maNextLine,
        if (hsync) 1 else 0, hsyncCount, if (vsync) 1 else 0, vsyncLines,
        if (hDisplay) 1 else 0, if (vDisplay) 1 else 0,
    ) + regs

    fun importState(s: IntArray) {
        require(s.size >= 15 + 18) { "Invalid CRTC state" }
        selectedRegister = s[0]; hcc = s[1]; vcc = s[2]; rlc = s[3]; vtac = s[4]; inAdjust = s[5] != 0
        ma = s[6]; maLine = s[7]; maNextLine = s[8]
        hsync = s[9] != 0; hsyncCount = s[10]; vsync = s[11] != 0; vsyncLines = s[12]
        hDisplay = s[13] != 0; vDisplay = s[14] != 0
        System.arraycopy(s, 15, regs, 0, 18)
        clearEvents()
    }

    companion object {
        private val MASKS = intArrayOf(
            0xFF, 0xFF, 0xFF, 0xFF, 0x7F, 0x1F, 0x7F, 0x7F, 0xFF, 0x1F,
            0x7F, 0x1F, 0x3F, 0xFF, 0x3F, 0xFF, 0x3F, 0xFF,
        )
    }
}
