package dev.stefan.acpc.ui.library

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import dev.stefan.acpc.R
import dev.stefan.acpc.databinding.ItemGameBinding
import dev.stefan.acpc.storage.GameEntry
import java.text.DateFormat
import java.util.Date

class GameAdapter(
    private val thumbFile: (GameEntry) -> java.io.File,
    private val onClick: (GameEntry) -> Unit,
    private val onLongClick: (GameEntry, View) -> Unit,
    private val onFavorite: (GameEntry) -> Unit,
) : RecyclerView.Adapter<GameAdapter.Holder>() {
    private var items: List<GameEntry> = emptyList()

    fun submit(newItems: List<GameEntry>) {
        val old = items
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = newItems.size
            override fun areItemsTheSame(a: Int, b: Int) = old[a].id == newItems[b].id
            override fun areContentsTheSame(a: Int, b: Int) = old[a] == newItems[b]
        })
        items = newItems.map { it.copy() }
        diff.dispatchUpdatesTo(this)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemGameBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

    inner class Holder(private val b: ItemGameBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(entry: GameEntry) {
            b.title.text = entry.title
            b.badge.text = when { entry.isSnapshot -> "SNA"; entry.isTape -> "CDT"; entry.isCartridge -> "CPR"; else -> "DSK" }
            val thumb = thumbFile(entry)
            if (thumb.exists()) {
                b.thumb.setImageBitmap(android.graphics.BitmapFactory.decodeFile(thumb.path))
                b.thumb.visibility = View.VISIBLE
                b.badge.visibility = View.GONE
            } else {
                b.thumb.visibility = View.GONE
                b.badge.visibility = View.VISIBLE
            }
            val ctx = b.root.context
            val played = if (entry.lastPlayed > 0) DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(entry.lastPlayed)) else ctx.getString(R.string.never_played)
            b.subtitle.text = ctx.getString(R.string.game_subtitle, entry.fileName.substringAfter('_'), entry.size / 1024, played)
            b.favorite.setImageResource(if (entry.favorite) android.R.drawable.btn_star_big_on else android.R.drawable.btn_star_big_off)
            b.favorite.setOnClickListener { onFavorite(entry) }
            b.root.setOnClickListener { onClick(entry) }
            b.root.setOnLongClickListener { onLongClick(entry, it); true }
            b.source.text = if (entry.sourceUrl != null) ctx.getString(R.string.source_remote) else ctx.getString(R.string.source_local)
        }
    }
}
