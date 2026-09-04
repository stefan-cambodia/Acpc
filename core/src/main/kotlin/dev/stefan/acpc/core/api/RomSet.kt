package dev.stefan.acpc.core.api

import dev.stefan.acpc.core.machine.CpcModel

/**
 * The ROM images required to boot a CPC.
 *
 * Amstrad's ROMs are copyrighted: the emulator never bundles them. The
 * front-end asks the user to import them and passes them here.
 *
 * @property lowerRom  16 KB firmware ("OS") ROM.
 * @property basicRom  16 KB BASIC ROM (upper ROM 0).
 * @property amsdosRom optional 16 KB AMSDOS ROM (upper ROM 7). Required for
 *                     disc support.
 * @property extraUpperRoms optional additional upper ROMs keyed by slot number.
 */
class RomSet(
    val lowerRom: ByteArray,
    val basicRom: ByteArray,
    val amsdosRom: ByteArray?,
    val extraUpperRoms: Map<Int, ByteArray> = emptyMap(),
) {
    init {
        require(lowerRom.size == ROM_SIZE) { "Lower ROM must be 16 KB (got ${lowerRom.size})" }
        require(basicRom.size == ROM_SIZE) { "BASIC ROM must be 16 KB (got ${basicRom.size})" }
        require(amsdosRom == null || amsdosRom.size == ROM_SIZE) { "AMSDOS ROM must be 16 KB" }
    }

    companion object {
        const val ROM_SIZE = 16 * 1024

        /**
         * Builds a ROM set from the conventional 32 KB combined system ROM
         * (lower 16 KB = OS, upper 16 KB = BASIC) plus an optional AMSDOS ROM.
         */
        fun fromCombined(systemRom: ByteArray, amsdosRom: ByteArray?): RomSet {
            require(systemRom.size == 2 * ROM_SIZE) { "System ROM must be 32 KB (got ${systemRom.size})" }
            return RomSet(
                lowerRom = systemRom.copyOfRange(0, ROM_SIZE),
                basicRom = systemRom.copyOfRange(ROM_SIZE, 2 * ROM_SIZE),
                amsdosRom = amsdosRom,
            )
        }

        /** Names of the ROM files the user must provide for a model. */
        fun requiredFiles(model: CpcModel): List<String> = when {
            model == CpcModel.GX4000 -> emptyList()
            model.isPlus -> listOf(model.systemRomFile)
            else -> listOf(model.systemRomFile, "amsdos.rom")
        }
    }
}
