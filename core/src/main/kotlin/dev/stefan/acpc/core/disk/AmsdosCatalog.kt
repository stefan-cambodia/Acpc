package dev.stefan.acpc.core.disk

/**
 * Reads the AMSDOS / CP/M directory of a disc image, and picks the file an
 * "auto start" should run.
 */
object AmsdosCatalog {

    enum class Format(val firstSectorId: Int, val reservedTracks: Int) {
        DATA(0xC1, 0), SYSTEM(0x41, 2), IBM(0x01, 1)
    }

    class Entry(val user: Int, val name: String, val extension: String, val readOnly: Boolean, val system: Boolean, val sizeKb: Int) {
        val fileName: String get() = if (extension.isEmpty()) name else "$name.$extension"
        override fun toString(): String = "$fileName (${sizeKb}K)"
    }

    /** Detects the disc format from the sector IDs of track 0. */
    fun detectFormat(image: DiskImage): Format? {
        val track = image.track(0, 0) ?: return null
        val ids = track.sectors.map { it.r }
        return when {
            ids.any { it in 0xC1..0xC9 } -> Format.DATA
            ids.any { it in 0x41..0x49 } -> Format.SYSTEM
            ids.any { it in 0x01..0x09 } -> Format.IBM
            else -> null
        }
    }

    /** Lists the files (user 0 by default, one entry per file). */
    fun list(image: DiskImage, user: Int = 0): List<Entry> {
        val format = detectFormat(image) ?: return emptyList()
        val dirTrack = image.track(0, format.reservedTracks) ?: return emptyList()
        val sorted = dirTrack.sectors.sortedBy { it.r }.take(4)
        val dir = java.io.ByteArrayOutputStream()
        for (s in sorted) dir.write(s.readCopy())
        val bytes = dir.toByteArray()
        val files = LinkedHashMap<String, Entry>()
        var offset = 0
        while (offset + 32 <= bytes.size) {
            val u = bytes[offset].toInt() and 0xFF
            if (u == user) {
                val name = String(CharArray(8) { ((bytes[offset + 1 + it].toInt() and 0x7F)).toChar() }).trimEnd()
                val ext = String(CharArray(3) { ((bytes[offset + 9 + it].toInt() and 0x7F)).toChar() }).trimEnd()
                val readOnly = bytes[offset + 9].toInt() and 0x80 != 0
                val system = bytes[offset + 10].toInt() and 0x80 != 0
                val extent = (bytes[offset + 12].toInt() and 0x1F) or ((bytes[offset + 14].toInt() and 0x3F) shl 5)
                val records = bytes[offset + 15].toInt() and 0xFF
                val sizeKb = (records + 7) / 8
                val key = "$name.$ext"
                if (name.isNotEmpty() && name.all { it.code in 32..126 }) {
                    val existing = files[key]
                    if (existing == null) {
                        files[key] = Entry(u, name, ext, readOnly, system, sizeKb + extent * 16)
                    } else if (extent == 0) {
                        // keep first
                    } else {
                        files[key] = Entry(u, name, ext, readOnly, system, existing.sizeKb + sizeKb)
                    }
                }
            }
            offset += 32
        }
        return files.values.toList()
    }

    /**
     * Chooses the file to run automatically, or null when no sensible
     * candidate exists. Returns the command to type (e.g. `RUN"DISC`).
     */
    fun autoStartCommand(image: DiskImage): String? {
        val format = detectFormat(image)
        val files = list(image)
        if (files.isEmpty()) {
            // CP/M system disc with a boot sector: |CPM
            return if (format == Format.SYSTEM) "|CPM\n" else null
        }
        val candidates = files.filter { it.extension.uppercase() !in EXCLUDED_EXTENSIONS }
        if (candidates.isEmpty()) return null
        val discName = normalise(image.name.substringBeforeLast('.'))
        val best = candidates.maxByOrNull { score(it, discName) } ?: return null
        val name = best.fileName
        return "RUN\"$name\n"
    }

    private fun normalise(s: String): String = s.uppercase().replace(Regex("[^A-Z0-9]"), "")

    private fun score(e: Entry, discName: String): Int {
        val name = e.name.uppercase()
        val ext = e.extension.uppercase()
        var score = 0
        score += when (ext) {
            "BAS" -> 400
            "" -> 300
            "BIN" -> 200
            else -> 0
        }
        PREFERRED_NAMES.forEachIndexed { i, pattern ->
            if (name == pattern) score += 100 - i
            else if (name.startsWith(pattern)) score += 50 - i
        }
        // A file named like the disc image ("zaxon.dsk" -> ZAXON) is very likely the loader.
        val n = normalise(name)
        if (discName.isNotEmpty() && n.length >= 3) {
            if (discName == n) score += 150
            else if (discName.startsWith(n) || n.startsWith(discName)) score += 90
            else if (discName.take(4) == n.take(4)) score += 40
        }
        // Development tools and single-letter names are rarely the game.
        if (name in TOOL_NAMES) score -= 250
        if (name.length <= 1) score -= 60
        if (name.all { it.isDigit() }) score -= 100
        if (e.readOnly) score += 1
        return score
    }

    private val PREFERRED_NAMES = listOf("DISC", "DISK", "MENU", "LOADER", "LOAD", "RUN", "START", "BOOT", "GAME", "PLAY", "AUTO", "INTRO")
    private val TOOL_NAMES = setOf("DAMS", "TAB", "DEBTAB", "MAXAM", "PROTEXT", "DISCKIT", "DISCKIT3", "DISCOPY", "FORMAT", "CPM", "SETUP", "AMSDOS", "ODDJOB", "HELP", "README", "LISEZMOI")
    private val EXCLUDED_EXTENSIONS = setOf("DAT", "SCR", "BAK", "TXT", "DOC", "SCN", "PIC", "MUS", "SNG", "OVL", "$$$", "TMP", "LVL", "MAP", "DEF", "CFG", "SAV")
}
