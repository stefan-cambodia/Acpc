package dev.stefan.acpc.storage

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Games sold on several discs or disc sides, recognised from the TOSEC-style
 * names of game collections: "Out Run (1988)(US Gold)(Disk 1 of 2)",
 * "Game (Side A)", "Game (Disk 1 of 2 Side B)", and tapes named
 * "Gemini_Wings__Side_A". Pure string logic, so the in-game menu can offer
 * the other discs or tape sides of the set.
 */
object DiscSet {
    private val DISK_SIDE = Regex("""\((Dis[ck]) (\d+) of (\d+) Side ([A-D])\)""", RegexOption.IGNORE_CASE)
    private val DISK = Regex("""\((Dis[ck]) (\d+) of (\d+)\)""", RegexOption.IGNORE_CASE)
    private val SIDE = Regex("""\(Side ([A-D])\)""", RegexOption.IGNORE_CASE)
    /** Tape collections write "Gemini_Wings__Side_A.cdt", shown as "Gemini Wings  Side A". */
    private val SIDE_BARE = Regex("""(?<![A-Za-z])(Side)([ _]+)([A-D])(?![A-Za-z0-9])""", RegexOption.IGNORE_CASE)

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
            val other = otherSide(m.groupValues[1]) ?: return emptyList()
            return listOf(name.replaceRange(m.range, "(Side $other)"))
        }
        SIDE_BARE.findAll(name).lastOrNull()?.let { m ->
            val other = otherSide(m.groupValues[3]) ?: return emptyList()
            return listOf(name.replaceRange(m.range, m.groupValues[1] + m.groupValues[2] + other))
        }
        return emptyList()
    }

    private fun otherSide(side: String): String? = when (side.uppercase()) {
        "A" -> "B"
        "B" -> "A"
        else -> null
    }

    /**
     * The URLs of the other members of the set, next to [url], the file a
     * game was downloaded from, in the order of [siblings]: the last path
     * segment is decoded, renamed and encoded again. Empty when the file
     * name carries no set.
     */
    fun siblingUrls(url: String): List<String> {
        val slash = url.lastIndexOf('/')
        if (slash < 0 || slash == url.length - 1) return emptyList()
        val segment = url.substring(slash + 1).substringBefore('?')
        val decoded = runCatching { URLDecoder.decode(segment, "UTF-8") }.getOrNull() ?: return emptyList()
        val extension = decoded.substringAfterLast('.', "")
        val base = if (extension.isEmpty()) decoded else decoded.substringBeforeLast('.')
        return siblings(base).map { name ->
            val renamed = if (extension.isEmpty()) name else "$name.$extension"
            url.substring(0, slash + 1) + URLEncoder.encode(renamed, "UTF-8").replace("+", "%20")
        }
    }

    private const val MAX_DISCS = 8
}
