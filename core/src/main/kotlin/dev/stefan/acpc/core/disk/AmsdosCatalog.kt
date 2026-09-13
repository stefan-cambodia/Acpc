package dev.stefan.acpc.core.disk

/**
 * Reads the AMSDOS / CP/M directory of a disc image, and picks the file an
 * "auto start" should run.
 */
object AmsdosCatalog {

    enum class Format(val firstSectorId: Int, val reservedTracks: Int, val sectorsPerTrack: Int) {
        DATA(0xC1, 0, 9), SYSTEM(0x41, 2, 9), IBM(0x01, 1, 8)
    }

    /**
     * The 128-byte header AMSDOS writes in front of BASIC and binary files.
     * [type]: bit 0 = protected, bits 1-3 = 0 BASIC, 1 binary, 2 screen, 3 ASCII.
     */
    class FileHeader(val type: Int, val loadAddress: Int, val length: Int, val execAddress: Int) {
        val isBasic: Boolean get() = (type ushr 1) and 7 == 0
        val isBinary: Boolean get() = (type ushr 1) and 7 == 1
    }

    class Entry(
        val user: Int,
        val name: String,
        val extension: String,
        val readOnly: Boolean,
        val system: Boolean,
        val sizeKb: Int,
        /** The AMSDOS header, or null for a headerless file (ASCII text, raw data). */
        val header: FileHeader? = null,
        /** True for a headerless file that reads as a BASIC listing (`10 ...`). */
        val asciiBasic: Boolean = false,
        /** False when the first block could not be read (missing or copy-protected), so nothing is known of the content. */
        val contentKnown: Boolean = false,
    ) {
        val fileName: String get() = if (extension.isEmpty()) name else "$name.$extension"

        /**
         * Whether `RUN"name` starts something: a BASIC program, or a binary
         * with an entry address. A binary whose entry is 0 resets the machine
         * (it is data or the second stage of a loader).
         */
        val runnable: Boolean
            get() = header?.let { it.isBasic || (it.isBinary && it.execAddress != 0) } ?: asciiBasic

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
        // A disc that only borrows the format on its boot track (CP/M games on a
        // custom format) has no directory where AMSDOS would look for it.
        val ids = format.firstSectorId until format.firstSectorId + format.sectorsPerTrack
        val sorted = dirTrack.sectors.filter { it.r in ids }.sortedBy { it.r }.take(4)
        if (sorted.size < 4) return emptyList()
        val dir = java.io.ByteArrayOutputStream()
        for (s in sorted) dir.write(firstCopy(s), 0, minOf(512, firstCopy(s).size))
        val bytes = dir.toByteArray()

        class Pending(val user: Int, val name: String, val ext: String, val readOnly: Boolean, val system: Boolean, var sizeKb: Int, var firstBlock: Int)
        val files = LinkedHashMap<String, Pending>()
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
                val block = bytes[offset + 16].toInt() and 0xFF
                val key = "$name.$ext"
                if (validName(name) && ext.all { it.code in 33..126 && it !in INVALID_NAME_CHARS }) {
                    val existing = files[key]
                    if (existing == null) {
                        files[key] = Pending(u, name, ext, readOnly, system, sizeKb + extent * 16, if (extent == 0) block else -1)
                    } else {
                        existing.sizeKb += sizeKb
                        if (extent == 0) existing.firstBlock = block
                    }
                }
            }
            offset += 32
        }
        return files.values.map { p ->
            val start = if (p.firstBlock > 0) blockStart(image, format, p.firstBlock) else null
            val header = start?.let { parseHeader(it) }
            Entry(p.user, p.name, p.ext, p.readOnly, p.system, p.sizeKb, header, header == null && start != null && looksLikeBasicListing(start), start != null)
        }
    }

    private fun validName(name: String): Boolean =
        name.isNotEmpty() && name.all { it.code in 33..126 && it !in INVALID_NAME_CHARS }

    private fun firstCopy(sector: DiskImage.Sector): ByteArray =
        if (sector.copies > 1) sector.data.copyOfRange(0, sector.copyLength) else sector.data

    /** The first sector (512 bytes) of a directory block, or null when the image does not have it. */
    private fun blockStart(image: DiskImage, format: Format, block: Int): ByteArray? {
        val index = block * 2
        val track = image.track(0, format.reservedTracks + index / format.sectorsPerTrack) ?: return null
        val id = format.firstSectorId + index % format.sectorsPerTrack
        val sector = track.sectors.firstOrNull { it.r == id } ?: return null
        return firstCopy(sector).takeIf { it.size >= 128 }
    }

    /** Parses an AMSDOS header (checksum over bytes 0-66 stored in 67-68). */
    fun parseHeader(record: ByteArray): FileHeader? {
        if (record.size < 69) return null
        var sum = 0
        var nonZero = false
        for (i in 0 until 67) {
            val b = record[i].toInt() and 0xFF
            sum += b
            if (b != 0) nonZero = true
        }
        val stored = (record[67].toInt() and 0xFF) or ((record[68].toInt() and 0xFF) shl 8)
        if (!nonZero || sum and 0xFFFF != stored) return null
        fun word(o: Int) = (record[o].toInt() and 0xFF) or ((record[o + 1].toInt() and 0xFF) shl 8)
        val length = word(64) or ((record[66].toInt() and 0xFF) shl 16)
        return FileHeader(record[18].toInt() and 0xFF, word(21), length, word(26))
    }

    /** A headerless ASCII BASIC file starts with a line number. */
    private fun looksLikeBasicListing(data: ByteArray): Boolean {
        var i = 0
        while (i < data.size && (data[i] == ' '.code.toByte() || data[i] == '\r'.code.toByte() || data[i] == '\n'.code.toByte())) i++
        val digitsStart = i
        while (i < data.size && data[i] in '0'.code.toByte()..'9'.code.toByte()) i++
        if (i == digitsStart || i - digitsStart > 5) return false
        // Text up to the end-of-file mark (&1A) or the end of the first 64 bytes.
        for (k in digitsStart until minOf(data.size, 64)) {
            val c = data[k].toInt() and 0xFF
            if (c == 0x1A || c == 0) return k > i
            if (c != 9 && c != 10 && c != 13 && c !in 32..126) return false
        }
        return true
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
        // Nothing on a system disc can be run from BASIC: it boots from its boot sector.
        if (!best.runnable && best.contentKnown && format == Format.SYSTEM) return "|CPM\n"
        val name = best.fileName
        return "RUN\"$name\n"
    }

    private fun normalise(s: String): String = s.uppercase().replace(Regex("[^A-Z0-9]"), "")

    private fun score(e: Entry, discName: String): Int {
        val name = e.name.uppercase()
        val ext = e.extension.uppercase()
        var score = 0
        val header = e.header
        score += when {
            // What the file is matters more than what it is called: a ".BIN" can be a
            // BASIC loader, and a binary with no entry address only resets the machine.
            header != null && header.isBasic -> 400
            header != null && header.isBinary && header.execAddress != 0 -> 200
            header != null -> -1000
            e.asciiBasic -> 350
            e.contentKnown -> -500
            // Unreadable (copy-protected) first block: judge by the name alone.
            ext == "BAS" || ext == "" || ext == "BIN" -> 0
            else -> -100
        }
        // `RUN"NAME` finds "NAME.", "NAME.BAS" or "NAME.BIN": loaders are meant to be
        // typed like that. ".BI2", ".N01" and the like are later stages or tools.
        score += when (ext) {
            "BAS" -> 20
            "" -> 10
            "BIN" -> 5
            else -> -200
        }
        score += PREFERRED_NAMES.withIndex().maxOfOrNull { (i, pattern) ->
            when {
                name == pattern -> 100 - i
                name.startsWith(pattern) -> 50 - i
                else -> 0
            }
        } ?: 0
        // A file named like the disc image ("zaxon.dsk" -> ZAXON) is very likely the loader.
        val n = normalise(name)
        if (discName.isNotEmpty() && n.length >= 3) {
            if (discName == n) score += 150
            else if (discName.startsWith(n) || n.startsWith(discName)) score += 90
            else if (n.length >= 4 && discName.contains(n)) score += 70
            else if (discName.take(4) == n.take(4)) score += 40
        }
        // Development tools and single-letter names are rarely the game.
        if (name in TOOL_NAMES) score -= 250
        if (name.length <= 1) score -= 60
        if (name.all { it.isDigit() }) score -= 100
        if (e.readOnly) score += 1
        return score
    }

    private const val INVALID_NAME_CHARS = "<>.,;:=?*[]\"|"
    private val PREFERRED_NAMES = listOf("DISC", "DISK", "MENU", "LOADER", "LOAD", "RUN", "START", "BOOT", "GAME", "PLAY", "AUTO", "INTRO")
    private val TOOL_NAMES = setOf("DAMS", "TAB", "DEBTAB", "MAXAM", "PROTEXT", "DISCKIT", "DISCKIT3", "DISCOPY", "FORMAT", "CPM", "SETUP", "AMSDOS", "ODDJOB", "HELP", "README", "LISEZMOI")
    private val EXCLUDED_EXTENSIONS = setOf("DAT", "SCR", "BAK", "TXT", "DOC", "SCN", "PIC", "MUS", "SNG", "OVL", "$$$", "TMP", "LVL", "MAP", "DEF", "CFG", "SAV")
}
