package dev.stefan.acpc.core.cartridge

import dev.stefan.acpc.core.api.EmulatorException

/** Thrown for a malformed cartridge image. */
class InvalidCartridgeException(message: String) : EmulatorException(message)

/**
 * A CPC Plus / GX4000 cartridge: up to 32 pages of 16 KB.
 *
 * Page 0 is the lower ROM (the firmware on a system cartridge, the game's
 * boot code on a game cartridge); the other pages are reached through the
 * upper ROM select register and RMR2. Page numbers wrap on the cartridge
 * size rounded up to a power of two, like the address decoding of a real
 * cartridge; pages absent from the image read as &FF.
 *
 * Two file layouts are accepted: the `.cpr` RIFF container (`AMS!` form,
 * chunks `cb00`..`cb31`, each one page) and a raw dump, a multiple of 16 KB.
 */
class Cartridge private constructor(private val pages: Array<ByteArray>, val name: String) {
    /** Number of 16 KB pages present in the image. */
    val pageCount: Int get() = pages.size

    private val wrapMask: Int = run {
        var n = 1
        while (n < pages.size) n = n shl 1
        n - 1
    }

    /** The 16 KB page for a page number 0-31 (wrapped). */
    fun page(number: Int): ByteArray {
        val index = number and 31 and wrapMask
        return if (index < pages.size) pages[index] else EMPTY_PAGE
    }

    /** True when the cartridge carries the Amstrad Plus firmware (a system cartridge). */
    val isSystemCartridge: Boolean by lazy {
        (0 until minOf(4, pages.size)).any { n ->
            val text = String(page(n), Charsets.ISO_8859_1)
            text.contains("Microcomputer") && text.contains("Amstrad") || text.contains("AMSDOS")
        }
    }

    companion object {
        const val PAGE_SIZE = 16 * 1024
        private val EMPTY_PAGE = ByteArray(PAGE_SIZE) { 0xFF.toByte() }

        fun isCpr(bytes: ByteArray): Boolean =
            bytes.size >= 12 && bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
                bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte() &&
                bytes[8] == 'A'.code.toByte() && bytes[9] == 'M'.code.toByte() &&
                bytes[10] == 'S'.code.toByte() && bytes[11] == '!'.code.toByte()

        /** Parses a `.cpr` container or a raw dump. Throws [InvalidCartridgeException]. */
        fun parse(bytes: ByteArray, name: String = "cartridge.cpr"): Cartridge {
            if (isCpr(bytes)) return parseCpr(bytes, name)
            if (bytes.isEmpty() || bytes.size % PAGE_SIZE != 0 || bytes.size > 32 * PAGE_SIZE) {
                throw InvalidCartridgeException("Not a cartridge image: neither a RIFF AMS! file nor a multiple of 16 KB")
            }
            val pages = Array(bytes.size / PAGE_SIZE) { bytes.copyOfRange(it * PAGE_SIZE, (it + 1) * PAGE_SIZE) }
            return Cartridge(pages, name)
        }

        private fun parseCpr(bytes: ByteArray, name: String): Cartridge {
            fun u32(i: Int) = (bytes[i].toInt() and 0xFF) or ((bytes[i + 1].toInt() and 0xFF) shl 8) or
                ((bytes[i + 2].toInt() and 0xFF) shl 16) or ((bytes[i + 3].toInt() and 0xFF) shl 24)
            val found = arrayOfNulls<ByteArray>(32)
            var highest = -1
            var p = 12
            while (p + 8 <= bytes.size) {
                val tag = String(bytes, p, 4, Charsets.ISO_8859_1)
                val length = u32(p + 4)
                p += 8
                if (length < 0 || p + length > bytes.size) throw InvalidCartridgeException("Truncated cartridge chunk $tag")
                if (tag.startsWith("cb")) {
                    val number = tag.substring(2).toIntOrNull()
                        ?: throw InvalidCartridgeException("Bad cartridge chunk name $tag")
                    if (number !in 0..31) throw InvalidCartridgeException("Cartridge page $number out of range")
                    val page = ByteArray(PAGE_SIZE) { 0xFF.toByte() }
                    System.arraycopy(bytes, p, page, 0, minOf(length, PAGE_SIZE))
                    found[number] = page
                    if (number > highest) highest = number
                }
                p += length + (length and 1) // RIFF chunks are word aligned
            }
            if (highest < 0) throw InvalidCartridgeException("Cartridge has no page")
            val pages = Array(highest + 1) { found[it] ?: EMPTY_PAGE }
            return Cartridge(pages, name)
        }
    }
}
