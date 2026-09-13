package dev.stefan.acpc.core.crtc

import dev.stefan.acpc.core.machine.CrtcType

/**
 * Motorola 6845 compatible CRT controller, as used in the Amstrad CPC.
 *
 * The CRTC is clocked at 1 MHz: [tick] and [advance] together emulate one
 * character (1 µs): the counters, the display enable, HSYNC / VSYNC and the
 * memory address (MA) / raster address (RA) the Gate Array reads video RAM
 * with.
 *
 * ## Counters and comparisons
 * The chip does not compare its counters with the registers at the moment
 * a row or a frame ends: it latches "this is the last line of the row"
 * (counter RA equal to R9) and "this is the last row of the frame" (VCC
 * equal to R4) when a counter reaches the register or when the register is
 * written, and acts on the latch at the end of the line. Split screen tricks
 * ("ruptures") that rewrite R4, R9, R6 and R7 while the beam runs depend on
 * these details:
 *  - a VCC already past a lowered R4 never matches: the counter runs on to
 *    127 and wraps (Turrican switches between two CRTC frames this way);
 *  - a row that did match R4 but ends with R4 changed resets VCC to 0 on
 *    the HD6845S instead of starting the vertical total adjust;
 *  - on the HD6845S a write to R4 during the last line of a row does not
 *    change the latch, and a write to R9 keeps a latched last line while
 *    the last row is latched (Pinball Dreams' in-game split);
 *  - the vertical total adjust lines keep counting rows, so R6 and R7 can
 *    still trigger inside them;
 *  - R12/R13 are taken at the start of row 0 (on the UM6845R at every line
 *    of row 0).
 *
 * Type-dependent details: R3 widths (the UM6845R has a fixed 16-line VSYNC),
 * register read-back, the UM6845R status register, what hides the display
 * (R8 skew bits on the HD6845S, R6 = 0 on the UM6845R). The cursor, light
 * pen and interlace are not emulated.
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

    /** Vertical character row counter (7 bits). */
    var vcc = 0
        private set

    /** Raster line counter inside the current character row (5 bits). */
    var rlc = 0
        private set

    private var vtac = 0           // vertical total adjust line counter
    private var inAdjust = false   // counting vertical total adjust lines

    /** Latched: the current line is the last one of its row (RA matched R9). */
    private var lastLine = false

    /** Latched: the current row is the last one of the frame (VCC matched R4). */
    private var lastRow = false

    /** Memory address counter (14 bits). */
    var ma = 0
        private set

    private var maLine = 0         // MA every line of the current row starts from
    private var vOffDelay = 0      // lines before the display turns off (R6 match)

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
    val displayEnabled: Boolean
        get() = hDisplay && vDisplay && when (type) {
            CrtcType.TYPE0_HD6845S -> regs[8] and 0x30 != 0x30   // display skew "11" = no display
            CrtcType.TYPE1_UM6845R -> regs[6] != 0
        }

    // ---- Events of the last tick -----------------------------------------

    var hsyncStarted = false
        private set
    var hsyncEnded = false
        private set
    var vsyncStarted = false
        private set
    var vsyncEnded = false
        private set

    /** Set for one tick when a new CRTC frame started (end of the vertical total adjust). */
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
        lastLine = false; lastRow = false; vOffDelay = 0
        ma = 0; maLine = 0
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
        var v = value and MASKS[r]
        if (r == 0 && v == 0 && type == CrtcType.TYPE0_HD6845S) v = 1   // the HD6845S cannot count a line of one character
        regs[r] = v
        // Comparisons the new value makes true (or false) at once.
        when (r) {
            3 -> if (hcc == regs[2] && !hsync && hsyncWidth() != 0) startHsync()
            4 -> when (type) {
                CrtcType.TYPE0_HD6845S -> if (!lastLine) lastRow = vcc == v
                CrtcType.TYPE1_UM6845R -> lastRow = vcc == v
            }
            6 -> if (vcc == v) vDisplay = false
            7 -> if (vcc == v) startVsync()
            9 -> when (type) {
                CrtcType.TYPE0_HD6845S -> lastLine = rlc == v || (lastLine && lastRow && hcc > 1)
                CrtcType.TYPE1_UM6845R -> lastLine = rlc == v
            }
        }
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
        CrtcType.TYPE1_UM6845R -> if (vcc >= regs[6]) 0x20 else 0x00
    }

    /** Video RAM address of the first byte of the current character. */
    fun videoAddress(): Int =
        ((ma and 0x3000) shl 2) or ((rlc and 7) shl 11) or ((ma and 0x3FF) shl 1)

    private fun clearEvents() {
        hsyncStarted = false; hsyncEnded = false
        vsyncStarted = false; vsyncEnded = false
        frameStarted = false
    }

    /** HSYNC width in characters; 0 = no HSYNC on both types emulated. */
    private fun hsyncWidth(): Int = regs[3] and 0x0F

    /** VSYNC height in lines. */
    private fun vsyncHeight(): Int = when (type) {
        CrtcType.TYPE0_HD6845S -> ((regs[3] ushr 4) and 0x0F).let { if (it == 0) 16 else it }
        CrtcType.TYPE1_UM6845R -> 16
    }

    private fun startHsync() {
        hsync = true
        hsyncCount = 0
        hsyncStarted = true
    }

    private fun startVsync() {
        if (!vsync) vsyncStarted = true
        vsync = true
        vsyncLines = 0
    }

    /**
     * First half of the character clock: horizontal comparisons for the
     * character about to be output. Call [videoAddress], [displayEnabled],
     * [hsync] and [vsync] afterwards to know what the Gate Array must output.
     */
    fun tick() {
        // Horizontal sync end / start.
        if (hsync) {
            hsyncCount++
            if (hsyncCount >= hsyncWidth()) {
                hsync = false
                hsyncEnded = true
            }
        }
        if (hcc == regs[2] && !hsync && hsyncWidth() != 0) startHsync()

        // Horizontal display end; on the last line of a row the next row starts here.
        if (hcc == regs[1]) {
            hDisplay = false
            if (lastLine) maLine = ma
        }
    }

    /** Second half of the character clock: advance the counters after the character was output. */
    fun advance() {
        clearEvents()
        ma = (ma + 1) and 0x3FFF
        // A counter already past a lowered R0 runs on to 255 and wraps.
        if (hcc == regs[0]) {
            hcc = 0
            endOfLine()
        } else {
            hcc = (hcc + 1) and 0xFF
        }
    }

    private fun endOfLine() {
        hDisplay = true
        if (vsync) {
            vsyncLines++
            if (vsyncLines >= vsyncHeight()) {
                vsync = false
                vsyncEnded = true
            }
        }

        if (lastLine) {
            rlc = 0
            lastLine = regs[9] == 0
            if (lastRow) {
                lastRow = regs[4] == 0
                if (regs[4] == vcc || type == CrtcType.TYPE1_UM6845R) {
                    inAdjust = true
                    vtac = 0
                    if (regs[5] != 0) vcc = (vcc + 1) and 0x7F
                } else {
                    // R4 changed after it matched: the HD6845S starts counting rows again.
                    vcc = 0
                }
            } else {
                vcc = (vcc + 1) and 0x7F
            }
        } else {
            rlc = (rlc + 1) and 0x1F
            if (rlc == regs[9]) lastLine = true
        }
        if (vcc == regs[4]) lastRow = true

        if (inAdjust) {
            if (vtac == regs[5]) startFrame() else vtac = (vtac + 1) and 0x1F
        }

        if (vcc == 0 && (rlc == 0 || (type == CrtcType.TYPE1_UM6845R && (regs[5] == 0 || regs[4] != 0)))) {
            maLine = ((regs[12] shl 8) or regs[13]) and 0x3FFF
        }
        if (rlc == 0) {
            // The HD6845S hides the display one line late when R6 = 0.
            if (vcc == regs[6]) vOffDelay = if (regs[6] == 0 && type == CrtcType.TYPE0_HD6845S) 2 else 1
            if (vcc == regs[7] && (type != CrtcType.TYPE1_UM6845R || (regs[4] or regs[5]) != 0)) startVsync()
        }
        if (vOffDelay > 0 && --vOffDelay == 0) vDisplay = false

        ma = maLine
        applySplit()
    }

    private fun applySplit() {
        if (pendingSplit < 0) return
        maLine = pendingSplit
        ma = maLine
        pendingSplit = -1
    }

    private fun startFrame() {
        inAdjust = false
        vtac = 0
        vcc = 0
        rlc = 0
        vDisplay = true
        lastRow = regs[4] == 0
        lastLine = regs[9] == 0
        pendingSplit = -1
        frameStarted = true
    }

    // ---- State -------------------------------------------------------------

    fun exportState(): IntArray = intArrayOf(
        selectedRegister, hcc, vcc, rlc, vtac, if (inAdjust) 1 else 0, ma, maLine, maLine,
        if (hsync) 1 else 0, hsyncCount, if (vsync) 1 else 0, vsyncLines,
        if (hDisplay) 1 else 0, if (vDisplay) 1 else 0,
    ) + regs + intArrayOf(if (lastLine) 1 else 0, if (lastRow) 1 else 0, vOffDelay)

    fun importState(s: IntArray) {
        require(s.size >= 15 + 18) { "Invalid CRTC state" }
        selectedRegister = s[0]; hcc = s[1]; vcc = s[2]; rlc = s[3]; vtac = s[4]; inAdjust = s[5] != 0
        ma = s[6]; maLine = s[7]
        hsync = s[9] != 0; hsyncCount = s[10]; vsync = s[11] != 0; vsyncLines = s[12]
        hDisplay = s[13] != 0; vDisplay = s[14] != 0
        System.arraycopy(s, 15, regs, 0, 18)
        if (s.size >= 15 + 18 + 3) {
            lastLine = s[33] != 0; lastRow = s[34] != 0; vOffDelay = s[35]
        } else {
            // States saved before the latches existed: derive them from the counters.
            lastLine = rlc == regs[9]; lastRow = vcc == regs[4]; vOffDelay = 0
        }
        clearEvents()
    }

    companion object {
        private val MASKS = intArrayOf(
            0xFF, 0xFF, 0xFF, 0xFF, 0x7F, 0x1F, 0x7F, 0x7F, 0xFF, 0x1F,
            0x7F, 0x1F, 0x3F, 0xFF, 0x3F, 0xFF, 0x3F, 0xFF,
        )
    }
}
