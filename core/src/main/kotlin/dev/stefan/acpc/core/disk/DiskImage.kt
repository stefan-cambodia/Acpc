package dev.stefan.acpc.core.disk

import dev.stefan.acpc.core.api.InvalidDiskImageException
import java.io.ByteArrayOutputStream

/**
 * In-memory representation of a floppy disc: sides × tracks × sectors, each
 * sector carrying its ID (C H R N), the status bits recorded by the FDC when
 * the image was made (ST1 / ST2, used by copy protections) and one or more
 * copies of its data ("weak" sectors return a different copy on each read).
 */
class DiskImage(
    val sides: Int,
    val trackCount: Int,
    /** tracks[side][track]; null = unformatted. */
    val tracks: Array<Array<Track?>>,
    var name: String = "disk.dsk",
) {
    /** True after any write; the front-end may then persist [DskFormat.write]. */
    var modified = false

    fun track(side: Int, track: Int): Track? =
        if (side in 0 until sides && track in 0 until trackCount) tracks[side][track] else null

    fun setTrack(side: Int, track: Int, value: Track) {
        require(side in 0 until sides && track in 0 until trackCount)
        tracks[side][track] = value
        modified = true
    }

    class Track(
        val trackNumber: Int,
        val side: Int,
        /** Sector size code N of the track header (128 << N bytes). */
        val sizeCode: Int,
        val gap3: Int,
        val filler: Int,
        val sectors: MutableList<Sector>,
        val dataRate: Int = 0,
        val recordingMode: Int = 0,
    )

    class Sector(
        var c: Int,
        var h: Int,
        var r: Int,
        var n: Int,
        var st1: Int,
        var st2: Int,
        /** All copies of the data, concatenated. */
        var data: ByteArray,
    ) {
        /** Length of one copy. */
        val copyLength: Int
            get() {
                val nominal = 128 shl (n and 7)
                return if (data.size > nominal && data.size % nominal == 0) nominal else data.size
            }

        val copies: Int get() = if (copyLength == 0) 1 else maxOf(1, data.size / copyLength)

        /** Index of the copy returned by the next read (weak sectors). */
        var nextCopy = 0

        /** Returns the data of the next copy, cycling for weak sectors. */
        fun readCopy(): ByteArray {
            val len = copyLength
            if (copies <= 1) return data
            val start = (nextCopy % copies) * len
            nextCopy = (nextCopy + 1) % copies
            return data.copyOfRange(start, start + len)
        }

        fun write(bytes: ByteArray) {
            // Writing collapses a weak sector to a single copy.
            data = bytes.copyOf()
            st1 = 0
            st2 = 0
            nextCopy = 0
        }

        fun deepCopy(): Sector = Sector(c, h, r, n, st1, st2, data.copyOf())
    }

    companion object {
        /** Creates a blank, unformatted image. */
        fun blank(sides: Int = 1, tracks: Int = 40, name: String = "blank.dsk"): DiskImage =
            DiskImage(sides, tracks, Array(sides) { arrayOfNulls<Track>(tracks) }, name)

        /** Creates an image formatted in the AMSDOS DATA format (9 × 512 bytes sectors &C1-&C9, 40 tracks). */
        fun formattedData(name: String = "data.dsk"): DiskImage {
            val image = blank(1, 40, name)
            for (t in 0 until 40) {
                val sectors = ArrayList<Sector>()
                // Standard interleave of AMSDOS: C1 C6 C2 C7 C3 C8 C4 C9 C5
                for (r in intArrayOf(0xC1, 0xC6, 0xC2, 0xC7, 0xC3, 0xC8, 0xC4, 0xC9, 0xC5)) {
                    sectors += Sector(t, 0, r, 2, 0, 0, ByteArray(512) { 0xE5.toByte() })
                }
                image.tracks[0][t] = Track(t, 0, 2, 0x4E, 0xE5, sectors)
            }
            image.modified = false
            return image
        }
    }
}

/**
 * Reader / writer for the CPCEMU "DSK" and "EXTENDED DSK" file formats.
 */
object DskFormat {
    private const val STANDARD_SIGNATURE = "MV - CPC"
    private const val EXTENDED_SIGNATURE = "EXTENDED"
    private const val TRACK_SIGNATURE = "Track-Info"
    private const val MAX_TRACKS = 128
    private const val MAX_SECTORS = 64

    fun isDsk(bytes: ByteArray): Boolean =
        bytes.size >= 256 && (ascii(bytes, 0, 8) == STANDARD_SIGNATURE || ascii(bytes, 0, 8) == EXTENDED_SIGNATURE)

    /** Parses an image. Throws [InvalidDiskImageException] on any structural problem. */
    fun read(bytes: ByteArray, name: String = "disk.dsk"): DiskImage {
        try {
            if (bytes.size < 256) throw InvalidDiskImageException("File too small to be a DSK image")
            val extended = when (ascii(bytes, 0, 8)) {
                STANDARD_SIGNATURE -> false
                EXTENDED_SIGNATURE -> true
                else -> throw InvalidDiskImageException("Not a DSK image (bad signature)")
            }
            val trackCount = bytes[0x30].toInt() and 0xFF
            val sides = bytes[0x31].toInt() and 0xFF
            if (trackCount == 0 || trackCount > MAX_TRACKS) throw InvalidDiskImageException("Invalid track count $trackCount")
            if (sides !in 1..2) throw InvalidDiskImageException("Invalid side count $sides")
            val standardTrackSize = (bytes[0x32].toInt() and 0xFF) or ((bytes[0x33].toInt() and 0xFF) shl 8)

            val image = DiskImage(sides, trackCount, Array(sides) { arrayOfNulls<DiskImage.Track>(trackCount) }, name)
            var offset = 256
            for (t in 0 until trackCount) {
                for (s in 0 until sides) {
                    val trackSize = if (extended) (bytes[0x34 + t * sides + s].toInt() and 0xFF) * 256 else standardTrackSize
                    if (trackSize == 0) continue // unformatted
                    if (offset + 256 > bytes.size) {
                        // Truncated image: keep what we have.
                        return image
                    }
                    if (ascii(bytes, offset, 10) != TRACK_SIGNATURE) {
                        throw InvalidDiskImageException("Track $t side $s: missing track header at offset $offset")
                    }
                    val trackNumber = bytes[offset + 0x10].toInt() and 0xFF
                    val sideNumber = bytes[offset + 0x11].toInt() and 0xFF
                    val dataRate = bytes[offset + 0x12].toInt() and 0xFF
                    val recordingMode = bytes[offset + 0x13].toInt() and 0xFF
                    val sizeCode = bytes[offset + 0x14].toInt() and 0xFF
                    val sectorCount = bytes[offset + 0x15].toInt() and 0xFF
                    val gap3 = bytes[offset + 0x16].toInt() and 0xFF
                    val filler = bytes[offset + 0x17].toInt() and 0xFF
                    if (sectorCount > MAX_SECTORS) throw InvalidDiskImageException("Track $t: too many sectors ($sectorCount)")
                    val sectors = ArrayList<DiskImage.Sector>(sectorCount)
                    var dataOffset = offset + 256
                    for (i in 0 until sectorCount) {
                        val info = offset + 0x18 + i * 8
                        val c = bytes[info].toInt() and 0xFF
                        val h = bytes[info + 1].toInt() and 0xFF
                        val r = bytes[info + 2].toInt() and 0xFF
                        val n = bytes[info + 3].toInt() and 0xFF
                        val st1 = bytes[info + 4].toInt() and 0xFF
                        val st2 = bytes[info + 5].toInt() and 0xFF
                        val length = if (extended) {
                            (bytes[info + 6].toInt() and 0xFF) or ((bytes[info + 7].toInt() and 0xFF) shl 8)
                        } else {
                            128 shl (n and 7)
                        }
                        val available = (bytes.size - dataOffset).coerceAtLeast(0)
                        val data = ByteArray(length)
                        val toCopy = minOf(length, available)
                        if (toCopy > 0) System.arraycopy(bytes, dataOffset, data, 0, toCopy)
                        sectors += DiskImage.Sector(c, h, r, n, st1, st2, data)
                        dataOffset += length
                    }
                    image.tracks[s][t] = DiskImage.Track(trackNumber, sideNumber, sizeCode, gap3, filler, sectors, dataRate, recordingMode)
                    offset += trackSize
                }
            }
            image.modified = false
            return image
        } catch (e: InvalidDiskImageException) {
            throw e
        } catch (e: Exception) {
            throw InvalidDiskImageException("Corrupted DSK image: ${e.message}", e)
        }
    }

    /** Serialises an image in the extended DSK format. */
    fun write(image: DiskImage): ByteArray {
        val out = ByteArrayOutputStream()
        val header = ByteArray(256)
        putAscii(header, 0, "EXTENDED CPC DSK File\r\nDisk-Info\r\n")
        putAscii(header, 0x22, "Acpc          ")
        header[0x30] = image.trackCount.toByte()
        header[0x31] = image.sides.toByte()
        val trackBlobs = ArrayList<ByteArray>()
        for (t in 0 until image.trackCount) {
            for (s in 0 until image.sides) {
                val track = image.tracks[s][t]
                if (track == null) {
                    header[0x34 + t * image.sides + s] = 0
                    continue
                }
                val blob = encodeTrack(track)
                trackBlobs += blob
                header[0x34 + t * image.sides + s] = (blob.size / 256).toByte()
            }
        }
        out.write(header)
        for (blob in trackBlobs) out.write(blob)
        return out.toByteArray()
    }

    private fun encodeTrack(track: DiskImage.Track): ByteArray {
        val info = ByteArray(256)
        putAscii(info, 0, "Track-Info\r\n")
        info[0x10] = track.trackNumber.toByte()
        info[0x11] = track.side.toByte()
        info[0x12] = track.dataRate.toByte()
        info[0x13] = track.recordingMode.toByte()
        info[0x14] = track.sizeCode.toByte()
        info[0x15] = track.sectors.size.toByte()
        info[0x16] = track.gap3.toByte()
        info[0x17] = track.filler.toByte()
        val data = ByteArrayOutputStream()
        for ((i, sector) in track.sectors.withIndex()) {
            val o = 0x18 + i * 8
            info[o] = sector.c.toByte()
            info[o + 1] = sector.h.toByte()
            info[o + 2] = sector.r.toByte()
            info[o + 3] = sector.n.toByte()
            info[o + 4] = sector.st1.toByte()
            info[o + 5] = sector.st2.toByte()
            info[o + 6] = (sector.data.size and 0xFF).toByte()
            info[o + 7] = ((sector.data.size ushr 8) and 0xFF).toByte()
            data.write(sector.data)
        }
        val body = data.toByteArray()
        // Track blocks are padded to a multiple of 256 bytes.
        val total = ((256 + body.size + 255) / 256) * 256
        val blob = ByteArray(total)
        System.arraycopy(info, 0, blob, 0, 256)
        System.arraycopy(body, 0, blob, 256, body.size)
        return blob
    }

    private fun ascii(bytes: ByteArray, offset: Int, length: Int): String =
        String(bytes, offset, minOf(length, bytes.size - offset), Charsets.ISO_8859_1)

    private fun putAscii(target: ByteArray, offset: Int, text: String) {
        val b = text.toByteArray(Charsets.ISO_8859_1)
        System.arraycopy(b, 0, target, offset, b.size)
    }
}
