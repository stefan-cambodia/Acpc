package dev.stefan.acpc.core

import dev.stefan.acpc.core.api.RomSet
import dev.stefan.acpc.core.machine.CpcModel
import java.io.File

/** Helpers to build ROM sets for tests: synthetic ones, or the real Amstrad ROMs when available. */
object TestRoms {
    val romDir: File = File(System.getProperty("acpc.romDir") ?: System.getenv("ACPC_ROM_DIR") ?: (System.getProperty("user.home") + "/.acpc/roms"))

    /** Synthetic ROM set: lower ROM filled with 0x11, BASIC with 0x22, AMSDOS with 0x77. */
    fun synthetic(): RomSet = RomSet(
        lowerRom = ByteArray(16384) { 0x11 },
        basicRom = ByteArray(16384) { 0x22 },
        amsdosRom = ByteArray(16384) { 0x77 },
    )

    fun realAvailable(model: CpcModel): Boolean = File(romDir, model.systemRomFile).exists()

    fun real(model: CpcModel): RomSet {
        val system = File(romDir, model.systemRomFile).readBytes()
        val amsdos = File(romDir, "amsdos.rom").takeIf { it.exists() }?.readBytes()
        return RomSet.fromCombined(system, amsdos)
    }
}
