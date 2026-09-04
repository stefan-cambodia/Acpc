package dev.stefan.acpc.storage

import android.content.Context
import android.net.Uri
import dev.stefan.acpc.core.api.RomSet
import dev.stefan.acpc.core.cartridge.Cartridge
import dev.stefan.acpc.core.machine.CpcModel
import java.io.File

/**
 * Stores the user-provided Amstrad ROMs in the app's private storage
 * (`files/roms/`). ROMs are recognised by their content, so the original
 * file name does not matter. The firmware of the 6128 Plus is a cartridge
 * image (`system.cpr`), recognised the same way.
 */
class RomStore(context: Context) {
    val dir: File = File(context.filesDir, "roms").apply { mkdirs() }

    fun file(name: String): File = File(dir, name)

    fun hasSystemRom(model: CpcModel): Boolean = when {
        model == CpcModel.GX4000 -> true                       // the game cartridge is the firmware
        model.isPlus -> file(SYSTEM_CARTRIDGE).length() > 0
        else -> file(model.systemRomFile).length() == 32 * 1024L
    }
    fun hasAmsdos(): Boolean = file(AMSDOS).length() == 16 * 1024L
    fun hasSystemCartridge(): Boolean = file(SYSTEM_CARTRIDGE).length() > 0

    /** True when the model can be booted (system ROM present; AMSDOS is optional but recommended). */
    fun canBoot(model: CpcModel): Boolean = hasSystemRom(model)

    /** The ROM set of a classic model, or null when missing (and always null for the Plus range). */
    fun load(model: CpcModel): RomSet? {
        if (model.isPlus || !hasSystemRom(model)) return null
        val system = file(model.systemRomFile).readBytes()
        val amsdos = if (hasAmsdos()) file(AMSDOS).readBytes() else null
        return RomSet.fromCombined(system, amsdos)
    }

    /** The 6128 Plus system cartridge, or null when not imported. */
    fun loadSystemCartridge(): Cartridge? {
        if (!hasSystemCartridge()) return null
        return runCatching { Cartridge.parse(file(SYSTEM_CARTRIDGE).readBytes(), SYSTEM_CARTRIDGE) }.getOrNull()
    }

    /** Names of the files still missing for [model]. */
    fun missing(model: CpcModel): List<String> = buildList {
        if (!hasSystemRom(model)) add(model.systemRomFile)
        if (!model.isPlus && !hasAmsdos()) add(AMSDOS)
    }

    /**
     * Imports a ROM from a content URI. Returns the canonical file name the
     * ROM was stored under, or null when the content is not a recognised ROM.
     */
    fun importFrom(context: Context, uri: Uri): String? {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        return importBytes(bytes)
    }

    fun importBytes(bytes: ByteArray): String? {
        val name = identify(bytes) ?: return null
        file(name).writeBytes(bytes)
        return name
    }

    /** Identifies a ROM image by its size and the version banner it contains. */
    fun identify(bytes: ByteArray): String? {
        if (Cartridge.isCpr(bytes)) {
            val cart = runCatching { Cartridge.parse(bytes) }.getOrNull() ?: return null
            return if (cart.isSystemCartridge) SYSTEM_CARTRIDGE else null
        }
        val text = String(bytes, Charsets.ISO_8859_1)
        return when (bytes.size) {
            32 * 1024 -> when {
                text.contains("(v1)") && text.contains("64K Microcomputer") -> CpcModel.CPC464.systemRomFile
                text.contains("(v2)") && text.contains("64K Microcomputer") -> CpcModel.CPC664.systemRomFile
                text.contains("(v3)") && text.contains("128K Microcomputer") -> CpcModel.CPC6128.systemRomFile
                else -> null
            }
            16 * 1024 -> if (text.contains("CPM") || text.contains("AMSDOS")) AMSDOS else null
            else -> null
        }
    }

    companion object {
        const val AMSDOS = "amsdos.rom"
        const val SYSTEM_CARTRIDGE = "system.cpr"
    }
}
