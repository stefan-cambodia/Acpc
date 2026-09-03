package dev.stefan.acpc.ui.library

import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import dev.stefan.acpc.R
import dev.stefan.acpc.network.HttpDownloader
import dev.stefan.acpc.storage.GameEntry
import dev.stefan.acpc.storage.GameLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Downloads a remote disc image with a progress dialog, imports it into the
 * library (cache) and hands the entry back. A URL already in the cache is
 * returned immediately.
 */
object DownloadFlow {
    fun start(activity: AppCompatActivity, library: GameLibrary, url: String, onDone: (GameEntry) -> Unit) {
        library.findBySourceUrl(url)?.let { cached ->
            Toast.makeText(activity, R.string.toast_cache_hit, Toast.LENGTH_SHORT).show()
            onDone(cached)
            return
        }
        val progressView = activity.layoutInflater.inflate(R.layout.dialog_progress, null)
        val progressBar = progressView.findViewById<ProgressBar>(R.id.progress)
        val progressText = progressView.findViewById<TextView>(R.id.progress_text)
        var cancelled = false
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.downloading)
            .setView(progressView)
            .setNegativeButton(android.R.string.cancel) { _, _ -> cancelled = true }
            .setCancelable(false)
            .show()
        activity.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val r = HttpDownloader.download(url, { cancelled }) { done, total ->
                        activity.runOnUiThread {
                            if (total > 0) {
                                progressBar.isIndeterminate = false
                                progressBar.progress = (done * 100 / total).toInt()
                                progressText.text = activity.getString(R.string.progress_bytes, done / 1024, total / 1024)
                            } else {
                                progressText.text = activity.getString(R.string.progress_bytes_unknown, done / 1024)
                            }
                        }
                    }
                    library.importBytes(r.bytes, r.fileName, url)
                }
            }
            dialog.dismiss()
            result.onSuccess { entry ->
                onDone(entry)
            }.onFailure { e ->
                if (!cancelled) {
                    AlertDialog.Builder(activity)
                        .setTitle(R.string.error_download_title)
                        .setMessage(e.message ?: activity.getString(R.string.error_download))
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            }
        }
    }
}
