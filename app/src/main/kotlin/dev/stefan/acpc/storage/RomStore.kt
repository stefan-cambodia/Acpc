package dev.stefan.acpc.storage

import android.content.Context
import android.net.Uri
import dev.stefan.acpc.core.api.RomSet
import dev.stefan.acpc.core.machine.CpcModel
import java.io.File

/**
 * Stores the user-provided Amstrad ROMs in the app's private storage
 * (`files/roms/`). ROMs are recognised by their content, so the original
 * file name does not matter.
 */
class RomStore(context: Context) {
    val dir: File = File(context.filesDir, "roms").apply { mkdirs() }

    fun file(name: String): File = File(dir, name)

    fun hasSystemRom(model: CpcModel): Boolean = file(model.systemRomFile).length() == 32 * 1024L
    fun hasAmsdos(): Boolean = file(AMSDOS).length() == 16 * 1024L

    /** True when the model can be booted (system ROM present; AMSDOS is optional but recommended). */
    fun canBoot(model: CpcModel): Boolean = hasSystemRom(model)

    fun load(model: CpcModel): RomSet? {
        if (!hasSystemRom(model)) return null
        val system = file(model.systemRomFile).readBytes()
        val amsdos = if (hasAmsdos()) file(AMSDOS).readBytes() else null
        return RomSet.fromCombined(system, amsdos)
    }

    /** Names of the files still missing for [model]. */
    fun missing(model: CpcModel): List<String> = buildList {
        if (!hasSystemRom(model)) add(model.systemRomFile)
        if (!hasAmsdos()) add(AMSDOS)
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
    }
}
