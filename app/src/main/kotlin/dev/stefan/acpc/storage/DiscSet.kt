package dev.stefan.acpc.storage

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Games sold on several discs or disc sides, recognised from the TOSEC-style
 * names of game collections: "Out Run (1988)(US Gold)(Disk 1 of 2)",
 * "Game (Side A)", "Game (Disk 1 of 2 Side B)". Pure string logic, so the
 * in-game menu can offer the other discs of the set.
 */
object DiscSet {
    private val DISK_SIDE = Regex("""\((Dis[ck]) (\d+) of (\d+) Side ([A-D])\)""", RegexOption.IGNORE_CASE)
    private val DISK = Regex("""\((Dis[ck]) (\d+) of (\d+)\)""", RegexOption.IGNORE_CASE)
    private val SIDE = Regex("""\(Side ([A-D])\)""", RegexOption.IGNORE_CASE)

    /** Names of the other members of the set [name] belongs to, in order; empty when it is a single disc. */
    fun siblings(name: String): List<String> {
        DISK_SIDE.find(name)?.let { m ->
            val (word, n, total, side) = m.destructured
            val count = total.toInt().coerceAtMost(MAX_DISCS)
            return (1..count).flatMap { d -> listOf("A", "B").map { s -> d to s } }
                .filter { (d, s) -> d != n.toInt() || !s.equals(side, ignoreCase = true) }
                .map { (d, s) -> name.replaceRange(m.range, "($word $d of $total Side $s)") }
        }
        DISK.find(name)?.let { m ->
            val (word, n, total) = m.destructured
            val count = total.toInt().coerceAtMost(MAX_DISCS)
            return (1..count).filter { it != n.toInt() }.map { d -> name.replaceRange(m.range, "($word $d of $total)") }
        }
        SIDE.find(name)?.let { m ->
            val side = m.groupValues[1].uppercase()
            val other = if (side == "A") "B" else if (side == "B") "A" else return emptyList()
            return listOf(name.replaceRange(m.range, "(Side $other)"))
        }
        return emptyList()
    }

    /**
     * The URL of [siblingName]'s file next to [url], the file a game was
     * downloaded from: the last path segment is decoded, renamed and encoded
     * again. Null when the URL does not carry the set's name.
     */
    fun siblingUrl(url: String, siblingName: String): String? {
        val slash = url.lastIndexOf('/')
        if (slash < 0 || slash == url.length - 1) return null
        val segment = url.substring(slash + 1).substringBefore('?')
        val decoded = runCatching { URLDecoder.decode(segment, "UTF-8") }.getOrNull() ?: return null
        val extension = decoded.substringAfterLast('.', "")
        val base = if (extension.isEmpty()) decoded else decoded.substringBeforeLast('.')
        if (siblings(base).none { it == siblingName }) return null
        val renamed = if (extension.isEmpty()) siblingName else "$siblingName.$extension"
        return url.substring(0, slash + 1) + URLEncoder.encode(renamed, "UTF-8").replace("+", "%20")
    }

    private const val MAX_DISCS = 8
}
