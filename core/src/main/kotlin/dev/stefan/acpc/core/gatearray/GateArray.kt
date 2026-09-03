package dev.stefan.acpc.core.gatearray

import dev.stefan.acpc.core.api.VideoFrame
import dev.stefan.acpc.core.crtc.Crtc
import dev.stefan.acpc.core.memory.CpcMemory

/**
 * The Amstrad Gate Array (40007 / 40008 / 40010).
 *
 * Responsibilities modelled here:
 *  - pen / border colour registers and the current screen mode,
 *  - pixel generation: every microsecond the CRTC supplies an address and the
 *    Gate Array converts the two bytes found there into 16 pixels according
 *    to the mode (mode changes are latched at HSYNC),
 *  - ROM enable bits (delegated to [CpcMemory]),
 *  - the 300 Hz interrupt generator (52 HSYNC counter, reset 2 HSYNCs after
 *    VSYNC, bit 5 cleared on acknowledge),
 *  - a simple monitor model that turns the sync signals into a stable raster
 *    ([VideoFrame]).
 */
class GateArray(
    private val memory: CpcMemory,
    private val crtc: Crtc,
    private val interruptSink: InterruptSink,
) {
    /** Where the interrupt request line goes (the Z80). */
    fun interface InterruptSink {
        fun setInterrupt(asserted: Boolean)
    }

    /** Hardware colour number of pens 0-15 and of the border (index 16). */
    val pens = IntArray(17)
    private val penArgb = IntArray(17)

    var selectedPen = 0
        private set

    /** Current screen mode (0-2, 3 = undocumented 2-bit-per-pixel mode with mode 0 pixel width). */
    var mode = 1
        private set
    private var pendingMode = 1

    /** Last value written to the RMR register (for read-back in save states). */
    var rmr = 0
        private set

    /** 52-HSYNC interrupt counter (R52). */
    var hsyncCounter = 0
        private set
    private var vsyncHsyncDelay = 0
    var interruptRequested = false
        private set

    // ---- Monitor / raster --------------------------------------------------

    /** Raster being drawn (1024 x 312, one entry per CPC scan line). */
    private val raster = VideoFrame(RASTER_WIDTH, RASTER_LINES, VISIBLE_X, VISIBLE_Y, VISIBLE_WIDTH, VISIBLE_HEIGHT)

    /** Last completed frame, presented to the front-end. */
    val frame = VideoFrame(RASTER_WIDTH, RASTER_LINES, VISIBLE_X, VISIBLE_Y, VISIBLE_WIDTH, VISIBLE_HEIGHT)

    private var rasterX = 0     // microseconds since the last HSYNC
    private var rasterY = 0     // lines since the last VSYNC
    private var frameCounter = 0L

    /** Set when a frame was completed during the last [tick]; cleared by [takeFrame]. */
    var frameReady = false
        private set

    init {
        reset()
    }

    fun reset() {
        pens.fill(0)
        for (i in pens.indices) penArgb[i] = CpcPalette.ARGB[0]
        selectedPen = 0
        mode = 1
        pendingMode = 1
        rmr = 0
        hsyncCounter = 0
        vsyncHsyncDelay = 0
        interruptRequested = false
        interruptSink.setInterrupt(false)
        rasterX = 0
        rasterY = 0
        raster.pixels.fill(CpcPalette.BLACK)
        frame.pixels.fill(CpcPalette.BLACK)
    }

    // ---- CPU interface (port &7Fxx) ----------------------------------------

    fun write(value: Int) {
        when (value and 0xC0) {
            0x00 -> selectedPen = if (value and 0x10 != 0) 16 else value and 0x0F
            0x40 -> {
                pens[selectedPen] = value and 0x1F
                penArgb[selectedPen] = CpcPalette.ARGB[value and 0x1F]
            }
            0x80 -> {
                rmr = value
                pendingMode = value and 0x03
                memory.setRomEnables(lowerEnabled = value and 0x04 == 0, upperEnabled = value and 0x08 == 0)
                if (value and 0x10 != 0) {
                    hsyncCounter = 0
                    setInterrupt(false)
                }
            }
            else -> memory.setRamConfig(value)
        }
    }

    /** Called by the CPU during the interrupt acknowledge cycle. */
    fun acknowledgeInterrupt() {
        hsyncCounter = hsyncCounter and 0x1F
        setInterrupt(false)
    }

    private fun setInterrupt(asserted: Boolean) {
        if (interruptRequested != asserted) {
            interruptRequested = asserted
            interruptSink.setInterrupt(asserted)
        }
    }

    // ---- Video generation --------------------------------------------------

    /** Emulates one microsecond: one CRTC character. */
    fun tick() {
        crtc.tick()

        if (crtc.hsyncStarted) {
            rasterX = 0
            rasterY++
        }
        if (crtc.vsyncStarted) {
            completeFrame()
            vsyncHsyncDelay = 2
        }

        // Pixel output for this character.
        if (rasterY < RASTER_LINES && rasterX < RASTER_US) {
            val offset = rasterY * RASTER_WIDTH + rasterX * 16
            val pixels = raster.pixels
            if (crtc.hsync || crtc.vsync) {
                fillBlack(pixels, offset)
            } else if (crtc.displayEnabled) {
                val address = crtc.videoAddress()
                renderCharacter(pixels, offset, memory.videoRead(address), memory.videoRead(address + 1))
            } else {
                val border = penArgb[16]
                for (i in 0 until 16) pixels[offset + i] = border
            }
        }
        rasterX++

        if (crtc.hsyncEnded) {
            // The mode change requested by RMR takes effect on the next HSYNC.
            mode = pendingMode
            // Interrupt counter.
            hsyncCounter++
            if (hsyncCounter >= 52) {
                hsyncCounter = 0
                setInterrupt(true)
            }
            if (vsyncHsyncDelay > 0) {
                vsyncHsyncDelay--
                if (vsyncHsyncDelay == 0) {
                    if (hsyncCounter >= 32) setInterrupt(true)
                    hsyncCounter = 0
                }
            }
        }

        crtc.advance()
    }

    private fun fillBlack(pixels: IntArray, offset: Int) {
        for (i in 0 until 16) pixels[offset + i] = CpcPalette.BLACK
    }

    private fun renderCharacter(pixels: IntArray, offset: Int, byte0: Int, byte1: Int) {
        when (mode) {
            0 -> {
                val p = MODE0_PENS
                var o = offset
                var c = penArgb[p[byte0 * 2]]
                pixels[o++] = c; pixels[o++] = c; pixels[o++] = c; pixels[o++] = c
                c = penArgb[p[byte0 * 2 + 1]]
                pixels[o++] = c; pixels[o++] = c; pixels[o++] = c; pixels[o++] = c
                c = penArgb[p[byte1 * 2]]
                pixels[o++] = c; pixels[o++] = c; pixels[o++] = c; pixels[o++] = c
                c = penArgb[p[byte1 * 2 + 1]]
                pixels[o++] = c; pixels[o++] = c; pixels[o++] = c; pixels[o] = c
            }
            1 -> {
                val p = MODE1_PENS
                var o = offset
                var base = byte0 * 4
                for (i in 0 until 4) {
                    val c = penArgb[p[base + i]]
                    pixels[o++] = c; pixels[o++] = c
                }
                base = byte1 * 4
                for (i in 0 until 4) {
                    val c = penArgb[p[base + i]]
                    pixels[o++] = c; pixels[o++] = c
                }
            }
            2 -> {
                var o = offset
                for (i in 7 downTo 0) pixels[o++] = penArgb[(byte0 ushr i) and 1]
                for (i in 7 downTo 0) pixels[o++] = penArgb[(byte1 ushr i) and 1]
            }
            else -> {
                val p = MODE3_PENS
                var o = offset
                var c = penArgb[p[byte0 * 2]]
                pixels[o++] = c; pixels[o++] = c; pixels[o++] = c; pixels[o++] = c
                c = penArgb[p[byte0 * 2 + 1]]
                pixels[o++] = c; pixels[o++] = c; pixels[o++] = c; pixels[o++] = c
                c = penArgb[p[byte1 * 2]]
                pixels[o++] = c; pixels[o++] = c; pixels[o++] = c; pixels[o++] = c
                c = penArgb[p[byte1 * 2 + 1]]
                pixels[o++] = c; pixels[o++] = c; pixels[o++] = c; pixels[o] = c
            }
        }
    }

    private fun completeFrame() {
        frameCounter++
        frame.copyFrom(raster)
        frame.frameNumber = frameCounter
        frameReady = true
        rasterY = 0
        // Lines that were not reached this frame (short frames) are cleared so
        // stale content does not linger at the bottom of the picture.
    }

    /** Returns the last completed frame and clears [frameReady]. */
    fun takeFrame(): VideoFrame {
        frameReady = false
        return frame
    }

    /** True when the CRTC is currently producing VSYNC (PPI port B bit 0). */
    val vsyncActive: Boolean get() = crtc.vsync

    // ---- State -------------------------------------------------------------

    fun exportState(): IntArray = intArrayOf(
        selectedPen, mode, pendingMode, rmr, hsyncCounter, vsyncHsyncDelay,
        if (interruptRequested) 1 else 0, rasterX, rasterY,
    ) + pens

    fun importState(s: IntArray) {
        require(s.size >= 9 + 17) { "Invalid Gate Array state" }
        selectedPen = s[0]; mode = s[1]; pendingMode = s[2]; rmr = s[3]
        hsyncCounter = s[4]; vsyncHsyncDelay = s[5]
        interruptRequested = s[6] != 0
        interruptSink.setInterrupt(interruptRequested)
        rasterX = s[7]; rasterY = s[8]
        System.arraycopy(s, 9, pens, 0, 17)
        for (i in 0 until 17) penArgb[i] = CpcPalette.ARGB[pens[i] and 0x1F]
    }

    companion object {
        /** Microseconds per raster line kept in the frame buffer. */
        const val RASTER_US = 64
        const val RASTER_WIDTH = RASTER_US * 16
        const val RASTER_LINES = 312

        /** Visible window: 48 µs (768 pixels) × 272 lines, centred on the default firmware screen. */
        const val VISIBLE_X = 14 * 16
        const val VISIBLE_Y = 36
        const val VISIBLE_WIDTH = 48 * 16
        const val VISIBLE_HEIGHT = 272

        /** Pen indices of the 2 mode 0 pixels of each byte value. */
        private val MODE0_PENS = IntArray(512).also { t ->
            for (b in 0 until 256) {
                t[b * 2] = ((b ushr 7) and 1) or ((b ushr 2) and 2) or ((b ushr 3) and 4) or ((b shl 2) and 8)
                t[b * 2 + 1] = ((b ushr 6) and 1) or ((b ushr 1) and 2) or ((b ushr 2) and 4) or ((b shl 3) and 8)
            }
        }

        /** Pen indices of the 4 mode 1 pixels of each byte value. */
        private val MODE1_PENS = IntArray(1024).also { t ->
            for (b in 0 until 256) {
                for (n in 0 until 4) {
                    t[b * 4 + n] = ((b ushr (7 - n)) and 1) or (((b ushr (3 - n)) and 1) shl 1)
                }
            }
        }

        /** Pen indices of the 2 mode 3 pixels of each byte value. */
        private val MODE3_PENS = IntArray(512).also { t ->
            for (b in 0 until 256) {
                t[b * 2] = ((b ushr 7) and 1) or ((b ushr 2) and 2)
                t[b * 2 + 1] = ((b ushr 6) and 1) or ((b ushr 1) and 2)
            }
        }
    }
}
