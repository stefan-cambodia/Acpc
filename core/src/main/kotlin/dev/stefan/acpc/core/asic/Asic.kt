package dev.stefan.acpc.core.asic

import dev.stefan.acpc.core.gatearray.CpcPalette

/**
 * The custom chip of the CPC Plus range and the GX4000 ("Arnold V ASIC").
 *
 * The ASIC integrates the Gate Array and the CRTC and adds features that a
 * program reaches through a 16 KB I/O page mapped at &4000-&7FFF once the
 * chip is unlocked (a 17-byte sequence written to the CRTC register select
 * port) and RMR2 bits 4-3 are set:
 *
 * ```
 * &4000-&4FFF  pixel data of the 16 hardware sprites (16 × 16 nibbles each)
 * &6000-&607F  sprite attributes, 8 bytes each: X (16 bits), Y (16 bits), magnification
 * &6400-&643F  palette: 16 pens, border, 15 sprite colours; 12 bits (RRRRBBBB, 0000GGGG)
 * &6800        PRI  programmable raster interrupt line (0 = off)
 * &6801        SPLT screen split line (0 = off), &6802-&6803 split address (MSB, LSB)
 * &6804        SSCR soft scroll: bits 0-3 horizontal delay, bits 4-6 vertical, bit 7 border extension
 * &6805        IVR  interrupt vector (bits 7-3), bit 0 = DMA interrupts cleared by DCSR only
 * &6808-&680F  analogue inputs
 * &6C00-&6C0B  DMA channels: address (LSB, MSB), prescaler
 * &6C0F        DCSR: bits 0-2 channel enables, bits 4-6 DMA interrupt flags, bit 7 raster interrupt
 * ```
 *
 * The video side (sprites, scroll, split, raster interrupt) is applied by
 * the Gate Array from the values decoded here; the memory side (RMR2, the
 * cartridge paging and the I/O page) by [dev.stefan.acpc.core.memory.CpcMemory].
 * DMA lists are executed once per scan line by [dmaTick]. Sound register
 * writes go to [psgWrite].
 */
class Asic(private val psgWrite: (register: Int, value: Int) -> Unit) {

    /** Image of the I/O page, for read-back. */
    val ram = ByteArray(0x4000)

    var locked = true
        private set
    private var unlockPosition = 0
    private var previousByte = 0

    /** Last RMR2 value (bits 7-5 = 101): cartridge page for the lower ROM and its position. */
    var rmr2 = 0
        private set

    /** Called when the lock state or RMR2 changes, so the memory mapping is recomputed. */
    var onMappingChanged: (() -> Unit)? = null

    // ---- Palette -----------------------------------------------------------

    /** ARGB colours of the 32 palette entries: pens 0-15, border (16), sprite colours 1-15 (17-31). */
    val paletteArgb = IntArray(32)

    // ---- Sprites -----------------------------------------------------------

    /** Sprite pixel data: 16 sprites × 256 pens (row-major 16 × 16), 0 = transparent. */
    val spriteData = ByteArray(16 * 256)
    val spriteX = IntArray(16)
    val spriteY = IntArray(16)
    /** Magnification per axis: 0 (hidden), 1, 2 or 4. */
    val spriteMagX = IntArray(16)
    val spriteMagY = IntArray(16)

    // ---- Video registers ---------------------------------------------------

    var pri = 0
        private set
    var splt = 0
        private set
    var spltAddress = 0
        private set
    var sscr = 0
        private set
    val hscroll: Int get() = sscr and 0x0F
    val vscroll: Int get() = (sscr ushr 4) and 7
    val extendBorder: Boolean get() = sscr and 0x80 != 0
    var ivr = 0
        private set

    /** DCSR bit 7: the last acknowledged interrupt was a raster interrupt. */
    private var rasterAcknowledged = false

    // ---- DMA ---------------------------------------------------------------

    private val dmaAddress = IntArray(3)
    private val dmaPrescaler = IntArray(3)
    private val dmaEnabled = BooleanArray(3)
    private val dmaInterrupt = BooleanArray(3)
    private val dmaPause = IntArray(3)
    private val dmaPrescaleCount = IntArray(3)
    private val dmaLoopAddress = IntArray(3)
    private val dmaLoopCount = IntArray(3)

    /** True while a DMA channel holds an interrupt request. */
    val dmaInterruptPending: Boolean get() = dmaInterrupt[0] || dmaInterrupt[1] || dmaInterrupt[2]

    init {
        reset()
    }

    fun reset() {
        ram.fill(0)
        locked = true
        unlockPosition = 0
        previousByte = 0
        rmr2 = 0
        spriteData.fill(0)
        spriteX.fill(0); spriteY.fill(0); spriteMagX.fill(0); spriteMagY.fill(0)
        pri = 0; splt = 0; spltAddress = 0; sscr = 0; ivr = 0
        rasterAcknowledged = false
        for (c in 0 until 3) {
            dmaAddress[c] = 0; dmaPrescaler[c] = 0; dmaEnabled[c] = false; dmaInterrupt[c] = false
            dmaPause[c] = 0; dmaPrescaleCount[c] = 0; dmaLoopAddress[c] = 0; dmaLoopCount[c] = 0
        }
        // Pens start black like the Gate Array's; the read-back image follows.
        for (i in 0 until 32) setPaletteEntry(i, 0, 0, 0)
        onMappingChanged?.invoke()
    }

    // ---- Lock sequence -----------------------------------------------------

    /**
     * Every byte written to the CRTC register select port (&BCxx) goes
     * through here. The sequence is synchronised by a non-zero byte followed
     * by zero, then &FF &77 &B3 &51 &A8 &D4 &62 &39 &9C &46 &2B &15 &8A &CD;
     * a 15th byte of &EE unlocks the ASIC, anything else locks it.
     */
    fun lockSequenceByte(value: Int) {
        val v = value and 0xFF
        if (unlockPosition == WAITING_FINAL_BYTE) {
            setLocked(v != 0xEE)
            unlockPosition = IDLE
        }
        if (v == 0 && previousByte != 0) unlockPosition = 0   // a non-zero byte then zero synchronises
        previousByte = v
        if (unlockPosition < UNLOCK_SEQUENCE.size) {
            if (v == UNLOCK_SEQUENCE[unlockPosition]) {
                unlockPosition++
                if (unlockPosition == UNLOCK_SEQUENCE.size) unlockPosition = WAITING_FINAL_BYTE
            } else if (unlockPosition > 0) {
                unlockPosition = IDLE                          // out of sequence: wait for a new sync
            }
        }
    }

    private fun setLocked(value: Boolean) {
        if (locked == value) return
        locked = value
        onMappingChanged?.invoke()
    }

    /** RMR2 write (Gate Array port, value bits 7-5 = 101), only honoured while unlocked. */
    fun writeRmr2(value: Int) {
        if (locked) return
        rmr2 = value and 0x1F
        onMappingChanged?.invoke()
    }

    /** True when the I/O page must appear at &4000-&7FFF. */
    val pageMapped: Boolean get() = !locked && (rmr2 and 0x18) == 0x18

    // ---- I/O page ----------------------------------------------------------

    fun read(offset: Int): Int {
        val o = offset and 0x3FFF
        if (o in 0x2808..0x280F) return analogueInput(o)
        if (o == 0x2C0F) return dcsr()
        return ram[o].toInt() and 0xFF
    }

    fun write(offset: Int, value: Int) {
        val o = offset and 0x3FFF
        val v = value and 0xFF
        when {
            o < 0x1000 -> {
                ram[o] = (v and 0x0F).toByte()
                spriteData[o] = (v and 0x0F).toByte()
            }
            o in 0x2000..0x207F -> {
                ram[o] = v.toByte()
                val sprite = (o - 0x2000) ushr 3
                when (o and 7) {
                    0 -> spriteX[sprite] = signed16((spriteX[sprite] and 0xFF00) or v)
                    1 -> spriteX[sprite] = signed16((spriteX[sprite] and 0x00FF) or (v shl 8))
                    2 -> spriteY[sprite] = signed16((spriteY[sprite] and 0xFF00) or v)
                    3 -> spriteY[sprite] = signed16((spriteY[sprite] and 0x00FF) or (v shl 8))
                    4 -> {
                        spriteMagX[sprite] = MAGNIFICATION[(v ushr 2) and 3]
                        spriteMagY[sprite] = MAGNIFICATION[v and 3]
                    }
                    else -> Unit
                }
            }
            o in 0x2400..0x243F -> {
                val entry = (o - 0x2400) ushr 1
                if (o and 1 == 0) {
                    ram[o] = v.toByte()
                } else {
                    ram[o] = (v and 0x0F).toByte()
                }
                val even = ram[o and 0x3FFE].toInt() and 0xFF
                val odd = ram[o or 1].toInt() and 0x0F
                paletteArgb[entry] = argb((even ushr 4) and 0x0F, odd, even and 0x0F)
            }
            o == 0x2800 -> { ram[o] = v.toByte(); pri = v }
            o == 0x2801 -> { ram[o] = v.toByte(); splt = v }
            o == 0x2802 -> { ram[o] = (v and 0x3F).toByte(); spltAddress = ((v and 0x3F) shl 8) or (spltAddress and 0xFF) }
            o == 0x2803 -> { ram[o] = v.toByte(); spltAddress = (spltAddress and 0x3F00) or v }
            o == 0x2804 -> { ram[o] = v.toByte(); sscr = v }
            o == 0x2805 -> { ram[o] = (v and 0xF9).toByte(); ivr = v and 0xF9 }
            o in 0x2C00..0x2C0B -> {
                val c = (o - 0x2C00) ushr 2
                when (o and 3) {
                    0 -> { ram[o] = (v and 0xFE).toByte(); dmaAddress[c] = (dmaAddress[c] and 0xFF00) or (v and 0xFE) }
                    1 -> { ram[o] = v.toByte(); dmaAddress[c] = (dmaAddress[c] and 0x00FF) or (v shl 8) }
                    2 -> { ram[o] = v.toByte(); dmaPrescaler[c] = v }
                    else -> Unit
                }
            }
            o == 0x2C0F -> {
                for (c in 0 until 3) {
                    dmaEnabled[c] = v and (1 shl c) != 0
                    if (v and (0x40 ushr c) != 0) dmaInterrupt[c] = false
                }
                ram[o] = dcsr().toByte()
            }
            else -> ram[o] = v.toByte()
        }
    }

    private fun dcsr(): Int {
        var v = 0
        for (c in 0 until 3) {
            if (dmaEnabled[c]) v = v or (1 shl c)
            if (dmaInterrupt[c]) v = v or (0x40 ushr c)
        }
        if (rasterAcknowledged) v = v or 0x80
        return v
    }

    private fun analogueInput(o: Int): Int = when (o) {
        0x2808, 0x2809, 0x280A, 0x280B -> 0x3F // no paddle connected: inputs float high
        0x280C, 0x280E -> 0x3F
        else -> 0x00
    }

    // ---- Palette -----------------------------------------------------------

    /**
     * A pen or border write through the Gate Array (hardware colour 0-31)
     * also sets the corresponding ASIC palette entry.
     */
    fun setPenHardwareColour(entry: Int, hardwareColour: Int) {
        val c = CpcPalette.ARGB[hardwareColour and 0x1F]
        setPaletteEntry(entry, ((c ushr 16) and 0xFF) ushr 4, ((c ushr 8) and 0xFF) ushr 4, (c and 0xFF) ushr 4)
    }

    private fun setPaletteEntry(entry: Int, r: Int, g: Int, b: Int) {
        ram[0x2400 + entry * 2] = ((r shl 4) or b).toByte()
        ram[0x2401 + entry * 2] = g.toByte()
        paletteArgb[entry] = argb(r, g, b)
    }

    // ---- Interrupts --------------------------------------------------------

    /**
     * Interrupt acknowledge while unlocked. [rasterRequested] tells whether
     * the Gate Array / raster interrupt is pending; the highest priority
     * source is chosen (raster, then DMA 0, 1, 2) and its vector returned.
     * Returns the source id in bits 2-1 (6 = raster, 4 = DMA 0, 2 = DMA 1,
     * 0 = DMA 2) combined with IVR bits 7-3, and whether the raster request
     * was the one served.
     */
    fun acknowledge(rasterRequested: Boolean): Int {
        val source: Int
        if (rasterRequested) {
            source = 6
            rasterAcknowledged = true
        } else {
            val c = when {
                dmaInterrupt[0] -> 0
                dmaInterrupt[1] -> 1
                else -> 2
            }
            source = 4 - c * 2
            rasterAcknowledged = false
            if (ivr and 1 == 0) dmaInterrupt[c] = false
        }
        ram[0x2C0F] = dcsr().toByte()
        return (ivr and 0xF8) or source
    }

    /** True when the acknowledged source was the raster / Gate Array interrupt. */
    fun lastAcknowledgeWasRaster(): Boolean = rasterAcknowledged

    // ---- DMA ---------------------------------------------------------------

    /**
     * Executes one DMA step on every enabled channel: called once per scan
     * line, at the end of HSYNC. [readRam] reads the base 64 KB. Returns true
     * when a channel raised an interrupt.
     */
    fun dmaTick(readRam: (Int) -> Int): Boolean {
        var raised = false
        for (c in 0 until 3) {
            if (!dmaEnabled[c]) continue
            if (dmaPause[c] > 0) {
                if (dmaPrescaleCount[c] < dmaPrescaler[c]) {
                    dmaPrescaleCount[c]++
                } else {
                    dmaPrescaleCount[c] = 0
                    dmaPause[c]--
                }
                continue
            }
            val address = dmaAddress[c] and 0xFFFE
            val instruction = readRam(address) or (readRam(address + 1) shl 8)
            dmaAddress[c] = (address + 2) and 0xFFFF
            val opcode = (instruction ushr 12) and 7
            if (opcode == 0) {
                psgWrite((instruction ushr 8) and 0x0F, instruction and 0xFF)
            } else {
                if (opcode and 1 != 0) { // PAUSE
                    dmaPause[c] = instruction and 0x0FFF
                    dmaPrescaleCount[c] = 0
                }
                if (opcode and 2 != 0) { // REPEAT
                    dmaLoopCount[c] = instruction and 0x0FFF
                    dmaLoopAddress[c] = dmaAddress[c]
                }
                if (opcode and 4 != 0) {
                    if (instruction and 0x0001 != 0 && dmaLoopCount[c] > 0) { // LOOP
                        dmaLoopCount[c]--
                        dmaAddress[c] = dmaLoopAddress[c]
                    }
                    if (instruction and 0x0010 != 0) { // INT
                        dmaInterrupt[c] = true
                        raised = true
                    }
                    if (instruction and 0x0020 != 0) { // STOP
                        dmaEnabled[c] = false
                    }
                }
            }
            val base = 0x2C00 + c * 4
            ram[base] = (dmaAddress[c] and 0xFE).toByte()
            ram[base + 1] = (dmaAddress[c] ushr 8).toByte()
        }
        ram[0x2C0F] = dcsr().toByte()
        return raised
    }

    // ---- State -------------------------------------------------------------

    fun exportState(): IntArray {
        val s = IntArray(STATE_INTS)
        s[0] = if (locked) 1 else 0
        s[1] = unlockPosition
        s[2] = previousByte
        s[3] = rmr2
        s[4] = if (rasterAcknowledged) 1 else 0
        for (c in 0 until 3) {
            val b = 5 + c * 8
            s[b] = dmaAddress[c]; s[b + 1] = dmaPrescaler[c]; s[b + 2] = if (dmaEnabled[c]) 1 else 0
            s[b + 3] = if (dmaInterrupt[c]) 1 else 0; s[b + 4] = dmaPause[c]; s[b + 5] = dmaPrescaleCount[c]
            s[b + 6] = dmaLoopAddress[c]; s[b + 7] = dmaLoopCount[c]
        }
        return s
    }

    /** Restores the state; [ramImage] is the I/O page image that goes with it. */
    fun importState(s: IntArray, ramImage: ByteArray) {
        require(s.size >= STATE_INTS && ramImage.size == ram.size) { "Invalid ASIC state" }
        System.arraycopy(ramImage, 0, ram, 0, ram.size)
        locked = s[0] != 0
        unlockPosition = s[1]
        previousByte = s[2]
        rmr2 = s[3]
        rasterAcknowledged = s[4] != 0
        for (c in 0 until 3) {
            val b = 5 + c * 8
            dmaAddress[c] = s[b]; dmaPrescaler[c] = s[b + 1]; dmaEnabled[c] = s[b + 2] != 0
            dmaInterrupt[c] = s[b + 3] != 0; dmaPause[c] = s[b + 4]; dmaPrescaleCount[c] = s[b + 5]
            dmaLoopAddress[c] = s[b + 6]; dmaLoopCount[c] = s[b + 7]
        }
        // Decode the registers from the page image.
        for (o in 0 until 0x1000) spriteData[o] = (ram[o].toInt() and 0x0F).toByte()
        for (i in 0 until 16) {
            val b = 0x2000 + i * 8
            spriteX[i] = signed16((ram[b].toInt() and 0xFF) or ((ram[b + 1].toInt() and 0xFF) shl 8))
            spriteY[i] = signed16((ram[b + 2].toInt() and 0xFF) or ((ram[b + 3].toInt() and 0xFF) shl 8))
            spriteMagX[i] = MAGNIFICATION[(ram[b + 4].toInt() ushr 2) and 3]
            spriteMagY[i] = MAGNIFICATION[ram[b + 4].toInt() and 3]
        }
        for (e in 0 until 32) {
            val even = ram[0x2400 + e * 2].toInt() and 0xFF
            val odd = ram[0x2401 + e * 2].toInt() and 0x0F
            paletteArgb[e] = argb((even ushr 4) and 0x0F, odd, even and 0x0F)
        }
        pri = ram[0x2800].toInt() and 0xFF
        splt = ram[0x2801].toInt() and 0xFF
        spltAddress = ((ram[0x2802].toInt() and 0x3F) shl 8) or (ram[0x2803].toInt() and 0xFF)
        sscr = ram[0x2804].toInt() and 0xFF
        ivr = ram[0x2805].toInt() and 0xF9
        onMappingChanged?.invoke()
    }

    companion object {
        const val STATE_INTS = 5 + 3 * 8

        private val UNLOCK_SEQUENCE = intArrayOf(
            0xFF, 0x77, 0xB3, 0x51, 0xA8, 0xD4, 0x62, 0x39, 0x9C, 0x46, 0x2B, 0x15, 0x8A, 0xCD,
        )
        private const val WAITING_FINAL_BYTE = 100
        private const val IDLE = 101
        private val MAGNIFICATION = intArrayOf(0, 1, 2, 4)

        private fun signed16(v: Int): Int = (v and 0xFFFF).toShort().toInt()

        /** 12-bit RGB (4 bits per component) to ARGB. */
        fun argb(r: Int, g: Int, b: Int): Int =
            (0xFF shl 24) or ((r * 17) shl 16) or ((g * 17) shl 8) or (b * 17)
    }
}
