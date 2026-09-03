package dev.stefan.acpc.core.tape

import dev.stefan.acpc.core.api.EmulatorException

/** Thrown for a malformed or unsupported tape image. */
class InvalidTapeException(message: String, cause: Throwable? = null) : EmulatorException(message, cause)

/**
 * Reads CDT tape images (the TZX format as used on the CPC) and turns them
 * into a timeline of signal edges, which is all the cassette input needs.
 *
 * TZX times are in T-states of a 3.5 MHz clock; the CPC clock is 4 MHz, so
 * every duration is scaled by 8/7 to CPC cycles. Supported blocks: 10 11 12
 * 13 14 15 20 21 22 23 24 25 26 27 28 2A 2B 30 31 32 33 34 35 5A. CSW (18)
 * and generalized data (19) blocks are rejected.
 */
object CdtFormat {
    private val MAGIC = "ZXTape!".toByteArray(Charsets.US_ASCII)

    fun isCdt(bytes: ByteArray): Boolean = bytes.size >= 10 && (0 until 7).all { bytes[it] == MAGIC[it] }

    /** A parsed tape: edge times in CPC cycles (level toggles at each), plus block boundaries for a counter. */
    class Image(val edges: LongArray, val totalCycles: Long, val blockStarts: LongArray, val initialLevel: Boolean)

    private class Builder {
        var edges = LongArray(1 shl 16)
        var count = 0
        var time = 0L
        var level = false
        val blockStarts = ArrayList<Long>()

        fun edge() {
            if (count == edges.size) edges = edges.copyOf(edges.size * 2)
            edges[count++] = time
            level = !level
        }

        /** A pulse: hold the current level for [cycles] then toggle. */
        fun pulse(cycles: Long) { time += cycles; edge() }

        fun bit(one: Boolean, zero: Long, oneLen: Long) {
            val len = if (one) oneLen else zero
            pulse(len); pulse(len)
        }

        fun pauseMs(ms: Int) {
            if (ms <= 0) return
            // The level drops after 1 ms (if high), then silence for the pause.
            if (level) { time += MS; edge() }
            time += ms * MS
        }

        fun build(): Image = Image(edges.copyOf(count), time, blockStarts.toLongArray(), false)
    }

    private const val MS = 4000L
    private fun t(tzx: Int): Long = (tzx.toLong() * 8 + 3) / 7

    fun parse(bytes: ByteArray): Image {
        if (!isCdt(bytes)) throw InvalidTapeException("Not a CDT/TZX tape image")
        val b = Builder()
        var p = 10
        val loopStack = ArrayDeque<Pair<Int, Int>>()   // (position of loop start, remaining iterations)
        val callStack = ArrayDeque<Int>()
        fun u8(i: Int) = bytes[i].toInt() and 0xFF
        fun u16(i: Int) = u8(i) or (u8(i + 1) shl 8)
        fun u24(i: Int) = u16(i) or (u8(i + 2) shl 16)
        fun u32(i: Int) = u16(i) or (u16(i + 2) shl 16)
        fun need(i: Int, n: Int) { if (i + n > bytes.size) throw InvalidTapeException("Truncated tape block at offset $i") }

        fun data(zero: Long, one: Long, lastBits: Int, start: Int, length: Int) {
            need(start, length)
            for (i in 0 until length) {
                val v = u8(start + i)
                val bits = if (i == length - 1 && lastBits in 1..7) lastBits else 8
                for (k in 0 until bits) b.bit(v and (0x80 ushr k) != 0, zero, one)
            }
        }

        var blocks = 0
        while (p < bytes.size) {
            val id = u8(p)
            p++
            b.blockStarts += b.time
            blocks++
            when (id) {
                0x10 -> {
                    need(p, 4); val pause = u16(p); val len = u16(p + 2); p += 4
                    need(p, len)
                    val flag = if (len > 0) u8(p) else 0
                    repeat(if (flag < 128) 8063 else 3223) { b.pulse(t(2168)) }
                    b.pulse(t(667)); b.pulse(t(735))
                    data(t(855), t(1710), 8, p, len)
                    p += len
                    b.pauseMs(pause)
                }
                0x11 -> {
                    need(p, 18)
                    val pilot = t(u16(p)); val sync1 = t(u16(p + 2)); val sync2 = t(u16(p + 4))
                    val zero = t(u16(p + 6)); val one = t(u16(p + 8)); val pilotCount = u16(p + 10)
                    val lastBits = u8(p + 12); val pause = u16(p + 13); val len = u24(p + 15)
                    p += 18
                    repeat(pilotCount) { b.pulse(pilot) }
                    b.pulse(sync1); b.pulse(sync2)
                    data(zero, one, lastBits, p, len)
                    p += len
                    b.pauseMs(pause)
                }
                0x12 -> { need(p, 4); val len = t(u16(p)); val n = u16(p + 2); p += 4; repeat(n) { b.pulse(len) } }
                0x13 -> { need(p, 1); val n = u8(p); p++; need(p, n * 2); repeat(n) { b.pulse(t(u16(p))); p += 2 } }
                0x14 -> {
                    need(p, 10)
                    val zero = t(u16(p)); val one = t(u16(p + 2)); val lastBits = u8(p + 4); val pause = u16(p + 5); val len = u24(p + 7)
                    p += 10
                    data(zero, one, lastBits, p, len)
                    p += len
                    b.pauseMs(pause)
                }
                0x15 -> {
                    need(p, 8)
                    val perSample = t(u16(p)); val pause = u16(p + 2); val lastBits = u8(p + 4); val len = u24(p + 5)
                    p += 8
                    need(p, len)
                    for (i in 0 until len) {
                        val v = u8(p + i)
                        val bits = if (i == len - 1 && lastBits in 1..7) lastBits else 8
                        for (k in 0 until bits) {
                            val high = v and (0x80 ushr k) != 0
                            if (high != b.level) b.edge()
                            b.time += perSample
                        }
                    }
                    p += len
                    b.pauseMs(pause)
                }
                0x18 -> throw InvalidTapeException("CSW recording blocks are not supported")
                0x19 -> throw InvalidTapeException("Generalized data blocks are not supported")
                0x20 -> { need(p, 2); val ms = u16(p); p += 2; if (ms == 0) b.pauseMs(1000) else b.pauseMs(ms) }
                0x21 -> { need(p, 1); p += 1 + u8(p) }
                0x22 -> Unit
                0x23 -> { need(p, 2); val rel = u16(p).toShort().toInt(); p += 2; p = jumpBlocks(bytes, p - 3, rel) }
                0x24 -> { need(p, 2); val n = u16(p); p += 2; loopStack.addLast(p to n) }
                0x25 -> {
                    val top = loopStack.removeLastOrNull()
                    if (top != null && top.second > 1) { loopStack.addLast(top.first to top.second - 1); p = top.first }
                }
                0x26 -> { need(p, 2); val n = u16(p); p += 2 + n * 2 }             // call sequences: rare, skipped
                0x27 -> Unit
                0x28 -> { need(p, 2); p += 2 + u16(p) }
                0x2A -> { need(p, 4); p += 4 + u32(p) }
                0x2B -> { need(p, 5); val lvl = u8(p + 4) != 0; p += 5; if (lvl != b.level) b.edge() }
                0x30 -> { need(p, 1); p += 1 + u8(p) }
                0x31 -> { need(p, 2); p += 2 + u8(p + 1) }
                0x32 -> { need(p, 2); p += 2 + u16(p) }
                0x33 -> { need(p, 1); p += 1 + u8(p) * 3 }
                0x34 -> p += 8
                0x35 -> { need(p, 20); p += 20 + u32(p + 16) }
                0x40 -> { need(p, 4); p += 4 + u24(p + 1) }
                0x5A -> p += 9
                else -> {
                    // Unknown block: TZX 1.20 rule, 4-byte length follows the id.
                    need(p, 4); p += 4 + u32(p)
                }
            }
        }
        if (blocks == 0) throw InvalidTapeException("Empty tape")
        return b.build()
    }

    /** Jump relative in blocks (block 0x23); only forward jumps are honoured, backward ones would loop forever. */
    private fun jumpBlocks(bytes: ByteArray, from: Int, rel: Int): Int {
        if (rel <= 0) return from + 3
        // Re-scan block lengths from the jump block: simplest is to parse sizes by re-walking.
        var p = from + 3
        var remaining = rel - 1
        while (remaining > 0 && p < bytes.size) { p = skipBlock(bytes, p); remaining-- }
        return p
    }

    private fun skipBlock(bytes: ByteArray, start: Int): Int {
        fun u8(i: Int) = bytes[i].toInt() and 0xFF
        fun u16(i: Int) = u8(i) or (u8(i + 1) shl 8)
        fun u24(i: Int) = u16(i) or (u8(i + 2) shl 16)
        fun u32(i: Int) = u16(i) or (u16(i + 2) shl 16)
        val p = start + 1
        return when (u8(start)) {
            0x10 -> p + 4 + u16(p + 2)
            0x11 -> p + 18 + u24(p + 15)
            0x12 -> p + 4
            0x13 -> p + 1 + u8(p) * 2
            0x14 -> p + 10 + u24(p + 7)
            0x15 -> p + 8 + u24(p + 5)
            0x20 -> p + 2
            0x21 -> p + 1 + u8(p)
            0x22, 0x25, 0x27 -> p
            0x23, 0x24 -> p + 2
            0x26 -> p + 2 + u16(p) * 2
            0x28, 0x32 -> p + 2 + u16(p)
            0x2A, 0x18, 0x19 -> p + 4 + u32(p)
            0x2B -> p + 5
            0x30 -> p + 1 + u8(p)
            0x31 -> p + 2 + u8(p + 1)
            0x33 -> p + 1 + u8(p) * 3
            0x34 -> p + 8
            0x35 -> p + 20 + u32(p + 16)
            0x40 -> p + 4 + u24(p + 1)
            0x5A -> p + 9
            else -> p + 4 + u32(p)
        }
    }
}
