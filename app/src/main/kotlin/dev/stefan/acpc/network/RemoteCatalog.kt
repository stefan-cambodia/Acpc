package dev.stefan.acpc.network

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * Lists the disc images offered by a remote "server": any HTTP directory
 * index (Apache, nginx, Python http.server, ...) or an archive.org item.
 *
 * archive.org is handled through its JSON metadata API, which is far smaller
 * than the HTML listing and carries file sizes. Everything else is parsed
 * from the `href` attributes of the HTML page. Listings are cached on disk
 * so reopening a big collection is instant.
 */
object RemoteCatalog {
    private const val MAX_HTML_BYTES = 16L * 1024 * 1024
    private const val TIMEOUT_MS = 20_000
    private val ARCHIVE_ORG = Regex("^https?://(?:www\\.)?archive\\.org/download/([^/?#]+)/?([^?#]*)$")

    data class RemoteFile(val name: String, val url: String, val size: Long, val isDirectory: Boolean)

    class Listing(val url: String, val files: List<RemoteFile>, val fetchedAt: Long)

    class ListingException(message: String, cause: Throwable? = null) : IOException(message, cause)

    /** True for names the emulator can import directly. */
    fun isDiskFile(name: String): Boolean {
        val n = name.lowercase()
        return n.endsWith(".dsk") || n.endsWith(".zip") || n.endsWith(".sna")
    }

    /** True when the URL points at a file rather than a directory to browse. */
    fun looksLikeFile(url: String): Boolean {
        val path = runCatching { URI(url).path }.getOrNull() ?: return false
        return isDiskFile(path.substringAfterLast('/'))
    }

    /** Fetches the listing from the network (blocking). */
    fun fetch(url: String): Listing {
        ARCHIVE_ORG.find(url)?.let { m -> return fetchArchiveOrg(url, m.groupValues[1], m.groupValues[2].trim('/')) }
        return fetchHtmlIndex(url)
    }

    // ---- archive.org -------------------------------------------------------

    private fun fetchArchiveOrg(url: String, item: String, subdir: String): Listing {
        val json = String(httpGet("https://archive.org/metadata/$item", MAX_HTML_BYTES), Charsets.UTF_8)
        return Listing(url, parseArchiveMetadata(json, item, subdir), System.currentTimeMillis())
    }

    /** Parses the `files` array of an archive.org metadata document (pure function, unit-tested). */
    fun parseArchiveMetadata(json: String, item: String, subdir: String): List<RemoteFile> {
        val files = runCatching { JSONObject(json).optJSONArray("files") ?: JSONArray() }
            .getOrElse { throw ListingException("Réponse archive.org illisible") }
        val prefix = if (subdir.isEmpty()) "" else "$subdir/"
        val base = "https://archive.org/download/$item/"
        val result = LinkedHashMap<String, RemoteFile>()
        for (i in 0 until files.length()) {
            val f = files.getJSONObject(i)
            val name = f.optString("name")
            if (!name.startsWith(prefix)) continue
            val rest = name.removePrefix(prefix)
            val slash = rest.indexOf('/')
            if (slash >= 0) {
                val dir = rest.substring(0, slash)
                val dirUrl = base + encodePath("$prefix$dir") + "/"
                result.getOrPut(dirUrl) { RemoteFile(dir, dirUrl, -1, true) }
            } else if (isDiskFile(rest)) {
                val fileUrl = base + encodePath(name)
                result[fileUrl] = RemoteFile(rest, fileUrl, f.optString("size").toLongOrNull() ?: -1, false)
            }
        }
        return sorted(result.values)
    }

    private fun encodePath(path: String): String =
        path.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    // ---- Generic HTML index ------------------------------------------------

    private val HREF = Regex("href\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')", RegexOption.IGNORE_CASE)

    private fun fetchHtmlIndex(url: String): Listing {
        val (html, finalUrl) = httpGetWithUrl(url, MAX_HTML_BYTES)
        return Listing(url, parseHtmlIndex(String(html, Charsets.UTF_8), finalUrl), System.currentTimeMillis())
    }

    /**
     * Extracts the disc images and sub-directories linked from a directory
     * index page. Only direct children of [finalUrl] are kept, so parent
     * links, sort links (`?C=M;O=D`) and external links are ignored.
     */
    fun parseHtmlIndex(html: String, finalUrl: String): List<RemoteFile> {
        val baseText = if (finalUrl.endsWith("/")) finalUrl else "$finalUrl/"
        val base = URL(baseText)
        val result = LinkedHashMap<String, RemoteFile>()
        for (m in HREF.findAll(html)) {
            val href = (m.groupValues[1].ifEmpty { m.groupValues[2] }).trim()
            if (href.isEmpty() || href.startsWith("#") || href.startsWith("?") || href.startsWith("mailto:") || href.startsWith("javascript:")) continue
            val resolved = runCatching { URL(base, href).toString() }.getOrNull() ?: continue
            if (!resolved.startsWith(baseText) || resolved == baseText) continue      // parent / external links
            val relative = resolved.removePrefix(baseText).substringBefore('?').substringBefore('#')
            if (relative.isEmpty()) continue
            val isDir = relative.endsWith("/")
            val segments = relative.trimEnd('/').split('/')
            if (segments.size != 1) continue                                          // only direct children
            val name = runCatching { java.net.URLDecoder.decode(segments[0], "UTF-8") }.getOrDefault(segments[0])
            val cleanUrl = baseText + relative
            if (isDir) {
                result.getOrPut(cleanUrl) { RemoteFile(name, cleanUrl, -1, true) }
            } else if (isDiskFile(name)) {
                result.getOrPut(cleanUrl) { RemoteFile(name, cleanUrl, -1, false) }
            }
        }
        return sorted(result.values)
    }

    private fun sorted(files: Collection<RemoteFile>): List<RemoteFile> =
        files.sortedWith(compareByDescending<RemoteFile> { it.isDirectory }.thenBy { it.name.lowercase() })

    // ---- HTTP --------------------------------------------------------------

    private fun httpGet(url: String, maxBytes: Long): ByteArray = httpGetWithUrl(url, maxBytes).first

    private fun httpGetWithUrl(url: String, maxBytes: Long): Pair<ByteArray, String> {
        var current = url
        var redirects = 0
        while (true) {
            val connection = (URL(current).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "Acpc/1.0 (Android Amstrad CPC emulator)")
                setRequestProperty("Accept", "text/html, application/json, */*")
            }
            try {
                val code = connection.responseCode
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location") ?: throw ListingException("Redirection sans destination")
                    current = URL(URL(current), location).toString()
                    if (++redirects > 5) throw ListingException("Trop de redirections")
                    continue
                }
                if (code != 200) throw ListingException("HTTP $code")
                val out = ByteArrayOutputStream()
                connection.inputStream.use { input ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > maxBytes) throw ListingException("Page trop volumineuse")
                        out.write(buf, 0, n)
                    }
                }
                return out.toByteArray() to current
            } finally {
                connection.disconnect()
            }
        }
    }

    // ---- Disk cache --------------------------------------------------------

    private fun cacheFile(context: Context, url: String): File {
        val dir = File(context.cacheDir, "remote").apply { mkdirs() }
        val digest = MessageDigest.getInstance("SHA-1").digest(url.toByteArray())
        return File(dir, digest.joinToString("") { "%02x".format(it) } + ".json")
    }

    fun loadCached(context: Context, url: String, maxAgeMs: Long): Listing? {
        val f = cacheFile(context, url)
        if (!f.exists()) return null
        return runCatching {
            val o = JSONObject(f.readText())
            val fetchedAt = o.getLong("fetchedAt")
            if (System.currentTimeMillis() - fetchedAt > maxAgeMs) return null
            val arr = o.getJSONArray("files")
            val files = ArrayList<RemoteFile>(arr.length())
            for (i in 0 until arr.length()) {
                val e = arr.getJSONObject(i)
                files += RemoteFile(e.getString("name"), e.getString("url"), e.optLong("size", -1), e.optBoolean("dir", false))
            }
            Listing(url, files, fetchedAt)
        }.getOrNull()
    }

    fun store(context: Context, listing: Listing) {
        runCatching {
            val arr = JSONArray()
            for (f in listing.files) {
                arr.put(JSONObject().apply { put("name", f.name); put("url", f.url); put("size", f.size); put("dir", f.isDirectory) })
            }
            val o = JSONObject().apply { put("url", listing.url); put("fetchedAt", listing.fetchedAt); put("files", arr) }
            cacheFile(context, listing.url).writeText(o.toString())
        }
    }
}
