package dev.stefan.acpc.ui.library

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import dev.stefan.acpc.R
import dev.stefan.acpc.core.disk.AmsdosCatalog
import dev.stefan.acpc.core.disk.DskFormat
import dev.stefan.acpc.databinding.ActivityLibraryBinding
import dev.stefan.acpc.emulator.EmulatorHolder
import dev.stefan.acpc.emulator.GameLauncher
import dev.stefan.acpc.network.HttpDownloader
import dev.stefan.acpc.network.RemoteCatalog
import dev.stefan.acpc.settings.AppSettings
import dev.stefan.acpc.storage.GameEntry
import dev.stefan.acpc.storage.GameLibrary
import dev.stefan.acpc.storage.RomStore
import dev.stefan.acpc.ui.roms.RomSetupActivity
import dev.stefan.acpc.ui.settings.SettingsActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Main screen: game library and entry points (open DSK, remote server, settings). */
class LibraryActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLibraryBinding
    private lateinit var library: GameLibrary
    private lateinit var settings: AppSettings
    private lateinit var adapter: GameAdapter
    private var favoritesOnly = false
    private var sortMode = SortMode.RECENT
    private var query = ""

    enum class SortMode { RECENT, NAME, ADDED }

    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importAndPlay(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Tapping the launcher icon while a game runs starts a second library
        // screen on top of the emulator: step aside and reveal the game instead.
        if (!isTaskRoot && EmulatorHolder.session != null && intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_LAUNCHER)) {
            finish()
            return
        }
        binding = ActivityLibraryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        dev.stefan.acpc.ui.common.EdgeToEdge.padSystemBars(binding.root)
        library = GameLibrary(this)
        settings = AppSettings(this)

        adapter = GameAdapter(
            onClick = { entry -> GameLauncher.launch(this, entry, library) },
            onLongClick = { entry, view -> showEntryMenu(entry, view) },
            onFavorite = { entry -> entry.favorite = !entry.favorite; library.update(entry); refresh() },
        )
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter

        binding.buttonOpen.setOnClickListener { openDocument.launch(arrayOf("*/*")) }
        binding.buttonRemote.setOnClickListener { showRemoteDialog() }
        binding.buttonFavorites.setOnClickListener { favoritesOnly = !favoritesOnly; refresh() }
        binding.buttonSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        binding.buttonBoot.setOnClickListener { GameLauncher.launch(this, null, library) }
        binding.buttonResume.setOnClickListener { GameLauncher.launch(this, null, library, resumeExisting = true) }
        binding.buttonRoms.setOnClickListener { startActivity(Intent(this, RomSetupActivity::class.java)) }
        binding.buttonSort.setOnClickListener { showSortMenu() }
        binding.search.doAfterTextChanged { query = it?.toString()?.trim() ?: ""; refresh() }

        handleViewIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleViewIntent(intent)
    }

    private fun handleViewIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) {
            intent.data?.let { importAndPlay(it) }
            intent.action = null
        }
    }

    override fun onResume() {
        super.onResume()
        if (!::binding.isInitialized) return
        refresh()
        val romStore = RomStore(this)
        binding.romWarning.visibility = if (romStore.canBoot(settings.model)) View.GONE else View.VISIBLE
        binding.buttonResume.visibility = if (EmulatorHolder.session != null) View.VISIBLE else View.GONE
    }

    private fun refresh() {
        var items = library.all()
        if (favoritesOnly) items = items.filter { it.favorite }
        if (query.isNotEmpty()) items = items.filter { it.title.contains(query, ignoreCase = true) || it.fileName.contains(query, ignoreCase = true) }
        items = when (sortMode) {
            SortMode.RECENT -> items.sortedWith(compareByDescending<GameEntry> { it.lastPlayed }.thenByDescending { it.addedAt })
            SortMode.NAME -> items.sortedBy { it.title.lowercase() }
            SortMode.ADDED -> items.sortedByDescending { it.addedAt }
        }
        adapter.submit(items)
        binding.empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        binding.buttonFavorites.text = getString(if (favoritesOnly) R.string.library_all_games else R.string.library_favorites)
    }

    private fun showSortMenu() {
        val popup = PopupMenu(this, binding.buttonSort)
        popup.menu.add(0, 0, 0, R.string.sort_recent)
        popup.menu.add(0, 1, 1, R.string.sort_name)
        popup.menu.add(0, 2, 2, R.string.sort_added)
        popup.setOnMenuItemClickListener {
            sortMode = SortMode.entries[it.itemId]
            refresh()
            true
        }
        popup.show()
    }

    private fun importAndPlay(uri: Uri) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { library.importFromUri(uri) } }
            result.onSuccess { entry ->
                refresh()
                GameLauncher.launch(this@LibraryActivity, entry, library)
            }.onFailure { e ->
                Toast.makeText(this@LibraryActivity, e.message ?: getString(R.string.error_cannot_read_dsk), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showEntryMenu(entry: GameEntry, anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 0, 0, R.string.action_play)
        popup.menu.add(0, 1, 1, if (entry.favorite) R.string.action_unfavorite else R.string.action_favorite)
        popup.menu.add(0, 2, 2, R.string.action_rename)
        popup.menu.add(0, 3, 3, R.string.action_details)
        popup.menu.add(0, 4, 4, R.string.action_model)
        popup.menu.add(0, 5, 5, R.string.action_delete)
        popup.setOnMenuItemClickListener {
            when (it.itemId) {
                0 -> GameLauncher.launch(this, entry, library)
                1 -> { entry.favorite = !entry.favorite; library.update(entry); refresh() }
                2 -> rename(entry)
                3 -> showDetails(entry)
                4 -> chooseModel(entry)
                5 -> confirmDelete(entry)
            }
            true
        }
        popup.show()
    }

    private fun rename(entry: GameEntry) {
        val edit = EditText(this).apply { setText(entry.title); setSelection(entry.title.length) }
        AlertDialog.Builder(this)
            .setTitle(R.string.action_rename)
            .setView(edit)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val t = edit.text.toString().trim()
                if (t.isNotEmpty()) { entry.title = t; library.update(entry); refresh() }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showDetails(entry: GameEntry) {
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    val image = DskFormat.read(library.diskFile(entry).readBytes(), entry.title)
                    val format = AmsdosCatalog.detectFormat(image)
                    val files = AmsdosCatalog.list(image)
                    buildString {
                        append(getString(R.string.details_file, entry.fileName)).append('\n')
                        append(getString(R.string.details_size, entry.size / 1024)).append('\n')
                        append(getString(R.string.details_geometry, image.trackCount, image.sides)).append('\n')
                        append(getString(R.string.details_format, format?.name ?: "?")).append('\n')
                        entry.sourceUrl?.let { append(getString(R.string.details_source, it)).append('\n') }
                        append(getString(R.string.details_autostart, AmsdosCatalog.autoStartCommand(image)?.trim() ?: "-")).append('\n')
                        append(getString(R.string.details_played, entry.playCount)).append("\n\n")
                        append(getString(R.string.details_files)).append('\n')
                        files.forEach { append("  ").append(it.toString()).append('\n') }
                    }
                }.getOrElse { getString(R.string.error_cannot_read_dsk) }
            }
            AlertDialog.Builder(this@LibraryActivity).setTitle(entry.title).setMessage(text).setPositiveButton(android.R.string.ok, null).show()
        }
    }

    private fun chooseModel(entry: GameEntry) {
        val labels = arrayOf(getString(R.string.model_default)) + dev.stefan.acpc.core.machine.CpcModel.entries.map { it.displayName }
        val current = dev.stefan.acpc.core.machine.CpcModel.entries.indexOfFirst { it.name == entry.modelOverride } + 1
        AlertDialog.Builder(this)
            .setTitle(R.string.action_model)
            .setSingleChoiceItems(labels, current) { d, which ->
                entry.modelOverride = if (which == 0) null else dev.stefan.acpc.core.machine.CpcModel.entries[which - 1].name
                library.update(entry)
                d.dismiss()
            }
            .show()
    }

    private fun confirmDelete(entry: GameEntry) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.confirm_delete, entry.title))
            .setPositiveButton(R.string.action_delete) { _, _ -> library.remove(entry.id); refresh() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showRemoteDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_remote, null)
        val edit = view.findViewById<EditText>(R.id.url)
        edit.setText(settings.lastRemoteUrl)
        edit.setSelection(edit.text.length)
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.library_remote)
            .setView(view)
            .setPositiveButton(R.string.action_open, null)
            .setNeutralButton(R.string.remote_default_server) { _, _ -> openRemote(AppSettings.DEFAULT_REMOTE_URL) }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val url = HttpDownloader.validate(edit.text.toString())
            if (url == null) {
                edit.error = getString(R.string.error_invalid_url)
                return@setOnClickListener
            }
            dialog.dismiss()
            openRemote(url)
        }
    }

    /** A URL of a disc image is downloaded; any other URL is browsed as a directory. */
    private fun openRemote(url: String) {
        settings.lastRemoteUrl = url
        if (RemoteCatalog.looksLikeFile(url)) {
            DownloadFlow.start(this, library, url) { entry ->
                refresh()
                GameLauncher.launch(this, entry, library)
            }
        } else {
            startActivity(Intent(this, RemoteBrowserActivity::class.java).putExtra(RemoteBrowserActivity.EXTRA_URL, url))
        }
    }
}
