package dev.stefan.acpc.network

import dev.stefan.acpc.storage.GameLibrary
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * Downloads a disc image over HTTP/HTTPS with progress reporting, size limits
 * and redirect handling. Always call from a background thread.
 */
object HttpDownloader {
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val MAX_REDIRECTS = 5

    class DownloadResult(val bytes: ByteArray, val fileName: String, val finalUrl: String)

    class DownloadException(message: String, cause: Throwable? = null) : IOException(message, cause)

    /** Validates a user supplied URL. Returns the normalised URL or null. */
    fun validate(input: String): String? {
        val text = input.trim()
        val uri = runCatching { URI(text) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        if (uri.host.isNullOrEmpty()) return null
        if (uri.userInfo != null) return null
        return uri.toString()
    }

    fun download(url: String, isCancelled: () -> Boolean = { false }, onProgress: (Long, Long) -> Unit = { _, _ -> }): DownloadResult {
        var current = url
        var redirects = 0
        while (true) {
            val connection = (URL(current).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "Acpc/1.0 (Android Amstrad CPC emulator)")
                setRequestProperty("Accept", "*/*")
            }
            try {
                val code = connection.responseCode
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location") ?: throw DownloadException("Redirection sans destination")
                    val resolved = URL(URL(current), location).toString()
                    if (++redirects > MAX_REDIRECTS) throw DownloadException("Trop de redirections")
                    validate(resolved) ?: throw DownloadException("Redirection vers une URL non supportée")
                    current = resolved
                    continue
                }
                if (code != 200) throw DownloadException("HTTP $code")
                val length = connection.contentLengthLong
                if (length > GameLibrary.MAX_DISK_SIZE) throw DownloadException("Fichier trop volumineux")
                val out = ByteArrayOutputStream()
                connection.inputStream.use { input ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        if (isCancelled()) throw DownloadException("Téléchargement annulé")
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > GameLibrary.MAX_DISK_SIZE) throw DownloadException("Fichier trop volumineux")
                        out.write(buf, 0, n)
                        onProgress(total, length)
                    }
                }
                val name = fileNameFrom(connection.getHeaderField("Content-Disposition"), current)
                return DownloadResult(out.toByteArray(), name, current)
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun fileNameFrom(contentDisposition: String?, url: String): String {
        if (contentDisposition != null) {
            Regex("filename\\*?=\"?([^\";]+)\"?").find(contentDisposition)?.groupValues?.get(1)?.let {
                return it.substringAfterLast('\'').substringAfterLast('/').trim()
            }
        }
        val path = runCatching { URI(url).path }.getOrNull() ?: ""
        val name = path.substringAfterLast('/')
        return if (name.isNotEmpty()) java.net.URLDecoder.decode(name, "UTF-8") else "download.dsk"
    }
}
