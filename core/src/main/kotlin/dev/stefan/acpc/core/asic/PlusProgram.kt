package dev.stefan.acpc.core.asic

import dev.stefan.acpc.core.disk.DiskImage

/**
 * Recognises software written for the CPC Plus: to reach the ASIC a program
 * has to send the 17-byte unlock sequence to the CRTC select port, and the
 * sequence is almost always stored as-is. Over 420 discs from a game
 * collection it only appeared in Plus programs (Jet Set Willy+, Fluff).
 */
object PlusProgram {
    val UNLOCK_SEQUENCE = byteArrayOf(
        0xFF.toByte(), 0x00, 0xFF.toByte(), 0x77, 0xB3.toByte(), 0x51, 0xA8.toByte(), 0xD4.toByte(), 0x62,
        0x39, 0x9C.toByte(), 0x46, 0x2B, 0x15, 0x8A.toByte(), 0xCD.toByte(), 0xEE.toByte(),
    )

    /** True when a file (a tape image, a raw dump) contains the unlock sequence. */
    fun containsUnlock(bytes: ByteArray): Boolean = indexOf(bytes, UNLOCK_SEQUENCE) >= 0

    /**
     * True when a disc carries the unlock sequence, in the sectors of a track
     * taken in physical order or in sector number order (the order AMSDOS
     * files follow on an interleaved track).
     */
    fun containsUnlock(image: DiskImage): Boolean {
        for (side in 0 until image.sides) {
            for (t in 0 until image.trackCount) {
                val track = image.track(side, t) ?: continue
                if (containsUnlock(concat(track.sectors))) return true
                if (containsUnlock(concat(track.sectors.sortedBy { it.r }))) return true
            }
        }
        return false
    }

    private fun concat(sectors: List<DiskImage.Sector>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for (s in sectors) out.write(s.data, 0, if (s.copies > 1) s.copyLength else s.data.size)
        return out.toByteArray()
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        val first = needle[0]
        val last = haystack.size - needle.size
        var i = 0
        while (i <= last) {
            if (haystack[i] == first) {
                var k = 1
                while (k < needle.size && haystack[i + k] == needle[k]) k++
                if (k == needle.size) return i
            }
            i++
        }
        return -1
    }
}
