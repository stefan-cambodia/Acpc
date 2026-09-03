package dev.stefan.acpc.storage

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dev.stefan.acpc.core.disk.DskFormat
import dev.stefan.acpc.core.snapshot.SnaFormat
import dev.stefan.acpc.core.tape.CdtFormat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** One disc image or snapshot known to the library. */
data class GameEntry(
    val id: String,
    var title: String,
    val fileName: String,
    val size: Long,
    val addedAt: Long,
    var lastPlayed: Long = 0,
    var favorite: Boolean = false,
    var sourceUrl: String? = null,
    var modelOverride: String? = null,
    var autoStart: Boolean? = null,
    var playCount: Int = 0,
    /** "dsk" (disc image), "sna" (snapshot) or "cdt" (tape). */
    val kind: String = KIND_DSK,
) {
    val isSnapshot: Boolean get() = kind == KIND_SNA
    val isTape: Boolean get() = kind == KIND_CDT
    val isDisc: Boolean get() = kind == KIND_DSK

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("title", title); put("fileName", fileName); put("size", size); put("kind", kind)
        put("addedAt", addedAt); put("lastPlayed", lastPlayed); put("favorite", favorite)
        put("sourceUrl", sourceUrl); put("modelOverride", modelOverride)
        if (autoStart != null) put("autoStart", autoStart)
        put("playCount", playCount)
    }

    companion object {
        fun fromJson(o: JSONObject): GameEntry = GameEntry(
            id = o.getString("id"), title = o.getString("title"), fileName = o.getString("fileName"),
            size = o.optLong("size"), addedAt = o.optLong("addedAt"), lastPlayed = o.optLong("lastPlayed"),
            favorite = o.optBoolean("favorite"), sourceUrl = o.optString("sourceUrl").takeIf { it.isNotEmpty() && it != "null" },
            modelOverride = o.optString("modelOverride").takeIf { it.isNotEmpty() && it != "null" },
            autoStart = if (o.has("autoStart")) o.getBoolean("autoStart") else null,
            playCount = o.optInt("playCount"),
            kind = o.optString("kind").ifEmpty { KIND_DSK },
        )

        const val KIND_DSK = "dsk"
        const val KIND_SNA = "sna"
        const val KIND_CDT = "cdt"
    }
}

/**
 * The game library: disc images and snapshots copied into private storage
 * (`files/disks/`) plus a JSON index. Files are stored as-is; a ZIP archive
 * is unpacked to its first `.dsk` or `.sna` member.
 */
class GameLibrary(context: Context) {
    private val app = context.applicationContext
    val disksDir: File = File(app.filesDir, "disks").apply { mkdirs() }
    val statesDir: File = File(app.filesDir, "states").apply { mkdirs() }
    val thumbsDir: File = File(app.filesDir, "thumbs").apply { mkdirs() }

    /** Small PNG of the last screen seen for the entry (written when the emulator pauses). */
    fun thumbFile(entry: GameEntry): File = File(thumbsDir, entry.id + ".png")
    private val indexFile = File(app.filesDir, "library.json")
    private val entries = LinkedHashMap<String, GameEntry>()

    init {
        load()
    }

    @Synchronized
    private fun load() {
        entries.clear()
        if (!indexFile.exists()) return
        runCatching {
            val arr = JSONArray(indexFile.readText())
            for (i in 0 until arr.length()) {
                val e = GameEntry.fromJson(arr.getJSONObject(i))
                if (File(disksDir, e.fileName).exists()) entries[e.id] = e
            }
        }
    }

    @Synchronized
    fun save() {
        val arr = JSONArray()
        entries.values.forEach { arr.put(it.toJson()) }
        val tmp = File(indexFile.path + ".tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(indexFile)
    }

    @Synchronized
    fun all(): List<GameEntry> = entries.values.toList()

    @Synchronized
    fun get(id: String): GameEntry? = entries[id]

    fun diskFile(entry: GameEntry): File = File(disksDir, entry.fileName)

    @Synchronized
    fun update(entry: GameEntry) {
        entries[entry.id] = entry
        save()
    }

    @Synchronized
    fun remove(id: String) {
        val e = entries.remove(id) ?: return
        File(disksDir, e.fileName).delete()
        statesDir.listFiles { f -> f.name.startsWith(e.id + "_") }?.forEach { it.delete() }
        thumbFile(e).delete()
        save()
    }

    /** Finds an entry previously downloaded from [url] (cache hit). */
    @Synchronized
    fun findBySourceUrl(url: String): GameEntry? = entries.values.firstOrNull { it.sourceUrl == url }

    /** Imports a disc image chosen with the Storage Access Framework. */
    fun importFromUri(uri: Uri): GameEntry {
        val name = queryDisplayName(uri) ?: uri.lastPathSegment?.substringAfterLast('/') ?: "disk.dsk"
        val bytes = app.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw ImportException(app.getString(dev.stefan.acpc.R.string.error_cannot_read_file))
        return importBytes(bytes, name, null)
    }

    /** Imports raw bytes (a DSK, a SNA snapshot, or a ZIP containing one). */
    fun importBytes(rawBytes: ByteArray, originalName: String, sourceUrl: String?): GameEntry {
        var bytes = rawBytes
        var name = originalName
        if (isZip(bytes)) {
            val (member, memberBytes) = extractFromZip(bytes, listOf(".dsk", ".sna", ".cdt"))
                ?: throw ImportException(app.getString(zipWithoutDskMessage(bytes)))
            bytes = memberBytes
            name = member
        }
        val kind = when {
            DskFormat.isDsk(bytes) -> GameEntry.KIND_DSK
            SnaFormat.isSna(bytes) -> GameEntry.KIND_SNA
            CdtFormat.isCdt(bytes) -> GameEntry.KIND_CDT
            else -> throw ImportException(app.getString(dev.stefan.acpc.R.string.error_not_a_dsk))
        }
        // Validate the structure now so the emulator never sees a broken image.
        if (kind == GameEntry.KIND_DSK) {
            runCatching { DskFormat.read(bytes, name) }.getOrElse {
                throw ImportException(app.getString(dev.stefan.acpc.R.string.error_invalid_dsk))
            }
        } else if (kind == GameEntry.KIND_SNA) {
            runCatching { SnaFormat.info(bytes) }.getOrElse {
                throw ImportException(app.getString(dev.stefan.acpc.R.string.error_invalid_sna, it.message ?: ""))
            }
        } else {
            runCatching { CdtFormat.parse(bytes) }.getOrElse {
                throw ImportException(app.getString(dev.stefan.acpc.R.string.error_invalid_cdt, it.message ?: ""))
            }
        }
        val id = sha1(bytes).take(16)
        synchronized(this) {
            entries[id]?.let { existing ->
                if (sourceUrl != null && existing.sourceUrl == null) { existing.sourceUrl = sourceUrl; save() }
                return existing
            }
        }
        val safeName = id + "_" + name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(60)
        val file = File(disksDir, safeName)
        file.writeBytes(bytes)
        val entry = GameEntry(
            id = id,
            title = name.substringBeforeLast('.').replace('_', ' ').trim().ifEmpty { name },
            fileName = safeName,
            size = bytes.size.toLong(),
            addedAt = System.currentTimeMillis(),
            sourceUrl = sourceUrl,
            kind = kind,
        )
        synchronized(this) {
            entries[id] = entry
            save()
        }
        return entry
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    fun stateFile(entry: GameEntry?, slot: Int): File =
        File(statesDir, (entry?.id ?: "noentry") + "_slot$slot.state")

    fun autoSaveFile(): File = File(statesDir, "autosave.state")

    companion object {
        const val MAX_DISK_SIZE = 16L * 1024 * 1024

        fun isZip(bytes: ByteArray): Boolean =
            bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() && bytes[2].toInt() == 3 && bytes[3].toInt() == 4

        /** Explains why a ZIP is unusable: cartridge or tape images are common in game collections. */
        fun zipWithoutDskMessage(bytes: ByteArray): Int {
            val names = ArrayList<String>()
            runCatching {
                ZipInputStream(bytes.inputStream()).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) { names += entry.name.lowercase(); entry = zip.nextEntry }
                }
            }
            return when {
                names.any { it.endsWith(".cpr") } -> dev.stefan.acpc.R.string.error_zip_cartridge
                names.any { it.endsWith(".tzx") || it.endsWith(".wav") || it.endsWith(".csw") } -> dev.stefan.acpc.R.string.error_zip_tape
                else -> dev.stefan.acpc.R.string.error_zip_without_dsk
            }
        }

        fun extractFromZip(bytes: ByteArray, extensions: List<String>): Pair<String, ByteArray>? {
            ZipInputStream(bytes.inputStream()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && extensions.any { entry!!.name.lowercase().endsWith(it) }) {
                        val data = readBounded(zip, MAX_DISK_SIZE)
                        return entry.name.substringAfterLast('/') to data
                    }
                    entry = zip.nextEntry
                }
            }
            return null
        }

        fun readBounded(input: InputStream, max: Long): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > max) throw ImportException("File too large")
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }

        fun sha1(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

class ImportException(message: String) : Exception(message)
