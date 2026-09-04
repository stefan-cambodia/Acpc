package dev.stefan.acpc.core.memory

import dev.stefan.acpc.core.api.RomSet
import dev.stefan.acpc.core.asic.Asic
import dev.stefan.acpc.core.cartridge.Cartridge
import dev.stefan.acpc.core.machine.CpcModel

/**
 * The CPC memory system: RAM, lower (firmware) ROM, upper ROMs and the
 * Gate Array / PAL memory mapping.
 *
 * The 64 KB Z80 address space is divided in four 16 KB blocks. Each block is
 * backed by a page of physical RAM chosen by the RAM configuration register
 * (6128 only), and reads of block 0 / block 3 may be redirected to the lower
 * ROM / the selected upper ROM when they are enabled. Writes always go to RAM.
 *
 * The video hardware (CRTC + Gate Array) always reads the first 64 KB of
 * physical RAM regardless of the CPU mapping; see [videoRead].
 *
 * RAM configurations (6128 memory expansion, standard PAL):
 * ```
 *  config  block0 block1 block2 block3   (physical 16 KB pages, 4-7 = expansion bank)
 *    0       0      1      2      3
 *    1       0      1      2      7
 *    2       4      5      6      7
 *    3       0      3      2      7
 *    4       0      4      2      3
 *    5       0      5      2      3
 *    6       0      6      2      3
 *    7       0      7      2      3
 * ```
 *
 * On the Plus range the ROMs come from a [Cartridge]: page 0 (or the page
 * chosen by RMR2, at the position it selects) is the lower ROM, the upper
 * ROM select register picks a cartridge page (ROM 7 = page 3, ROMs 128-159 =
 * pages 0-31, anything else = page 1, BASIC), and the [Asic] I/O page
 * replaces block 1 when RMR2 asks for it.
 */
class CpcMemory(
    val model: CpcModel,
    roms: RomSet?,
    val cartridge: Cartridge? = null,
    private val asic: Asic? = null,
    ramSize: Int = model.ramSize,
) {

    /** Physical RAM. 64 KB (464/664) or 128 KB (6128); larger sizes model a memory expansion. */
    val ram: ByteArray = ByteArray(ramSize)

    /** The firmware ROM (page 0 of the cartridge on a Plus). */
    val lowerRom: ByteArray = roms?.lowerRom ?: cartridge?.page(0)
        ?: throw IllegalArgumentException("A ROM set or a cartridge is required")
    private val upperRoms: Array<ByteArray?> = arrayOfNulls(256)

    /** ROM select register (port &DFxx). */
    var upperRomNumber: Int = 0
        private set

    /** Gate Array RMR bit 2 (0 = lower ROM enabled). */
    var lowerRomEnabled: Boolean = true
        private set

    /** Gate Array RMR bit 3 (0 = upper ROM enabled). */
    var upperRomEnabled: Boolean = true
        private set

    /** RAM configuration: bits 0-2 = configuration, bits 3-5 = 64 KB bank. */
    var ramConfig: Int = 0
        private set

    /** True while the ASIC I/O page is mapped at &4000-&7FFF. */
    var asicPageMapped: Boolean = false
        private set

    // Mapping tables: one entry per 16 KB block.
    private val readSource = arrayOfNulls<ByteArray>(4)
    private val readOffset = IntArray(4)
    private val writeOffset = IntArray(4)

    init {
        if (roms != null) {
            upperRoms[0] = roms.basicRom
            roms.amsdosRom?.let { upperRoms[7] = it }
            for ((slot, rom) in roms.extraUpperRoms) {
                require(rom.size == RomSet.ROM_SIZE) { "Upper ROM $slot must be 16 KB" }
                upperRoms[slot and 0xFF] = rom
            }
        }
        reset()
    }

    fun reset() {
        upperRomNumber = 0
        lowerRomEnabled = true
        upperRomEnabled = true
        ramConfig = 0
        remap()
    }

    /** Fills the RAM with the pattern a real CPC shows at power on (alternating 0x00/0xFF blocks). */
    fun fillPowerOnPattern() {
        // Real DRAM comes up with a fairly regular pattern; games never rely on it.
        for (i in ram.indices) ram[i] = if ((i and 0x80) != 0) 0xFF.toByte() else 0
    }

    // ---- Configuration -----------------------------------------------------

    fun selectUpperRom(number: Int) {
        upperRomNumber = number and 0xFF
        remap()
    }

    fun setRomEnables(lowerEnabled: Boolean, upperEnabled: Boolean) {
        lowerRomEnabled = lowerEnabled
        upperRomEnabled = upperEnabled
        remap()
    }

    /**
     * Writes the RAM configuration register (Gate Array port, bits 7-6 = 11).
     * Ignored on machines with only 64 KB, exactly like a real 464/664 without
     * an expansion.
     */
    fun setRamConfig(value: Int) {
        if (ram.size <= 64 * 1024) return
        ramConfig = value and 0x3F
        remap()
    }

    fun hasUpperRom(number: Int): Boolean =
        if (cartridge != null) true else upperRoms[number and 0xFF] != null

    /** Recomputes the mapping after an ASIC change (lock state, RMR2). */
    fun remapPlus() = remap()

    private fun remap() {
        val config = ramConfig and 7
        val bank = (ramConfig ushr 3) and 7
        val expansionBase = 4 + bank * 4 // physical page of the selected 64 KB expansion bank
        val pages = IntArray(4)
        when (config) {
            0 -> { pages[0] = 0; pages[1] = 1; pages[2] = 2; pages[3] = 3 }
            1 -> { pages[0] = 0; pages[1] = 1; pages[2] = 2; pages[3] = expansionBase + 3 }
            2 -> { pages[0] = expansionBase; pages[1] = expansionBase + 1; pages[2] = expansionBase + 2; pages[3] = expansionBase + 3 }
            3 -> { pages[0] = 0; pages[1] = 3; pages[2] = 2; pages[3] = expansionBase + 3 }
            else -> { pages[0] = 0; pages[1] = expansionBase + (config - 4); pages[2] = 2; pages[3] = 3 }
        }
        val totalPages = ram.size / 0x4000
        for (block in 0 until 4) {
            // A missing expansion bank wraps onto the existing RAM (open bus on a real machine).
            val page = pages[block] % totalPages
            val offset = page * 0x4000
            writeOffset[block] = offset
            readSource[block] = ram
            readOffset[block] = offset
        }
        val cart = cartridge
        if (cart == null) {
            asicPageMapped = false
            if (lowerRomEnabled) {
                readSource[0] = lowerRom
                readOffset[0] = 0
            }
            if (upperRomEnabled) {
                // ROM numbers with no physical ROM fall back to BASIC (ROM 0),
                // which is what the Gate Array / ROM board does on a real CPC.
                val rom = upperRoms[upperRomNumber] ?: upperRoms[0]
                readSource[3] = rom
                readOffset[3] = 0
            }
        } else {
            val asic = this.asic
            val unlocked = asic != null && !asic.locked
            val rmr2 = if (unlocked) asic!!.rmr2 else 0
            asicPageMapped = unlocked && (rmr2 and 0x18) == 0x18
            if (lowerRomEnabled) {
                val block = when (rmr2 and 0x18) {
                    0x08 -> 1
                    0x10 -> 2
                    else -> 0
                }
                readSource[block] = cart.page(rmr2 and 7)
                readOffset[block] = 0
            }
            if (upperRomEnabled) {
                readSource[3] = cart.page(cartridgePageForRom(upperRomNumber))
                readOffset[3] = 0
            }
        }
    }

    // ---- CPU access --------------------------------------------------------

    fun read(address: Int): Int {
        val block = (address ushr 14) and 3
        if (block == 1 && asicPageMapped) return asic!!.read(address and 0x3FFF)
        return readSource[block]!![readOffset[block] + (address and 0x3FFF)].toInt() and 0xFF
    }

    fun write(address: Int, value: Int) {
        val block = (address ushr 14) and 3
        if (block == 1 && asicPageMapped) {
            asic!!.write(address and 0x3FFF, value)
            return
        }
        ram[writeOffset[block] + (address and 0x3FFF)] = value.toByte()
    }

    /** Reads the RAM currently mapped at [address] ignoring ROMs (for debugging / auto-start heuristics). */
    fun readRam(address: Int): Int {
        val block = (address ushr 14) and 3
        return ram[writeOffset[block] + (address and 0x3FFF)].toInt() and 0xFF
    }

    // ---- Video access ------------------------------------------------------

    /** The video hardware always reads the base 64 KB. */
    fun videoRead(address: Int): Int = ram[address and 0xFFFF].toInt() and 0xFF

    // ---- State -------------------------------------------------------------

    fun restoreConfig(upperRom: Int, lowerEnabled: Boolean, upperEnabled: Boolean, ramConfig: Int) {
        this.upperRomNumber = upperRom and 0xFF
        this.lowerRomEnabled = lowerEnabled
        this.upperRomEnabled = upperEnabled
        this.ramConfig = ramConfig and 0x3F
        remap()
    }

    companion object {
        /** Cartridge page selected by an upper ROM number on the Plus. */
        fun cartridgePageForRom(romNumber: Int): Int = when {
            romNumber == 7 -> 3
            romNumber >= 128 -> romNumber and 31
            else -> 1
        }
    }
}
