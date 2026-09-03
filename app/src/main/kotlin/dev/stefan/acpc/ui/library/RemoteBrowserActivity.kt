package dev.stefan.acpc.ui.library

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dev.stefan.acpc.R
import dev.stefan.acpc.databinding.ActivityRemoteBrowserBinding
import dev.stefan.acpc.databinding.ItemRemoteBinding
import dev.stefan.acpc.emulator.GameLauncher
import dev.stefan.acpc.network.RemoteCatalog
import dev.stefan.acpc.storage.GameLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI

/**
 * Browses a remote directory of disc images (HTTP index or archive.org item):
 * searchable list, sub-directories, one tap to download, cache and play.
 */
class RemoteBrowserActivity : AppCompatActivity() {
    private lateinit var binding: ActivityRemoteBrowserBinding
    private lateinit var library: GameLibrary
    private lateinit var adapter: RemoteAdapter
    private var url: String = ""
    private var listing: RemoteCatalog.Listing? = null
    private var query = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        url = intent.getStringExtra(EXTRA_URL) ?: run { finish(); return }
        binding = ActivityRemoteBrowserBinding.inflate(layoutInflater)
        setContentView(binding.root)
        library = GameLibrary(this)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = displayName(url)

        adapter = RemoteAdapter(library) { file -> open(file) }
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
        binding.search.doAfterTextChanged { query = it?.toString()?.trim() ?: ""; refresh() }
        load(forceRefresh = false)
    }

    override fun onResume() {
        super.onResume()
        adapter.notifyDataSetChanged()   // "cached" badges may have changed
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_REFRESH, 0, R.string.remote_refresh).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        MENU_REFRESH -> { load(forceRefresh = true); true }
        else -> super.onOptionsItemSelected(item)
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    private fun load(forceRefresh: Boolean) {
        binding.progress.visibility = View.VISIBLE
        binding.status.visibility = View.VISIBLE
        binding.status.text = getString(R.string.remote_loading)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val cached = if (forceRefresh) null else RemoteCatalog.loadCached(this@RemoteBrowserActivity, url, CACHE_MAX_AGE_MS)
                    cached ?: RemoteCatalog.fetch(url).also { RemoteCatalog.store(this@RemoteBrowserActivity, it) }
                }
            }
            binding.progress.visibility = View.GONE
            result.onSuccess { l ->
                listing = l
                refresh()
            }.onFailure { e ->
                binding.status.visibility = View.VISIBLE
                binding.status.text = getString(R.string.remote_error, e.message ?: "")
            }
        }
    }

    private fun refresh() {
        val all = listing?.files ?: return
        val words = query.lowercase().split(' ').filter { it.isNotEmpty() }
        val items = if (words.isEmpty()) all else all.filter { f -> val n = f.name.lowercase(); words.all { n.contains(it) } }
        adapter.submit(items)
        binding.status.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        binding.status.text = getString(R.string.remote_empty)
        binding.count.text = getString(R.string.remote_count, items.size, all.size)
    }

    private fun open(file: RemoteCatalog.RemoteFile) {
        if (file.isDirectory) {
            startActivity(Intent(this, RemoteBrowserActivity::class.java).putExtra(EXTRA_URL, file.url))
            return
        }
        DownloadFlow.start(this, library, file.url) { entry ->
            adapter.notifyDataSetChanged()
            GameLauncher.launch(this, entry, library)
        }
    }

    private fun displayName(u: String): String = runCatching {
        val uri = URI(u)
        val path = uri.path.trimEnd('/')
        val last = path.substringAfterLast('/')
        if (last.isEmpty()) uri.host else "${uri.host} · ${java.net.URLDecoder.decode(last, "UTF-8")}"
    }.getOrDefault(u)

    private class RemoteAdapter(private val library: GameLibrary, private val onClick: (RemoteCatalog.RemoteFile) -> Unit) :
        RecyclerView.Adapter<RemoteAdapter.Holder>() {
        private var items: List<RemoteCatalog.RemoteFile> = emptyList()

        fun submit(newItems: List<RemoteCatalog.RemoteFile>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(ItemRemoteBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

        inner class Holder(private val b: ItemRemoteBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(file: RemoteCatalog.RemoteFile) {
                val ctx: Context = b.root.context
                b.title.text = file.name
                b.badge.text = if (file.isDirectory) ctx.getString(R.string.remote_directory_badge) else file.name.substringAfterLast('.').uppercase().take(3)
                val details = ArrayList<String>()
                if (file.isDirectory) details += ctx.getString(R.string.remote_directory)
                if (file.size >= 0) details += ctx.getString(R.string.remote_size, (file.size + 1023) / 1024)
                if (!file.isDirectory && library.findBySourceUrl(file.url) != null) details += ctx.getString(R.string.remote_cached)
                b.subtitle.text = details.joinToString(" · ")
                b.subtitle.visibility = if (details.isEmpty()) View.GONE else View.VISIBLE
                b.root.setOnClickListener { onClick(file) }
            }
        }
    }

    companion object {
        const val EXTRA_URL = "url"
        private const val MENU_REFRESH = 1
        private const val CACHE_MAX_AGE_MS = 24L * 3600 * 1000
    }
}
