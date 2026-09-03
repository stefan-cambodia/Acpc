package dev.stefan.acpc.core.state

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

/**
 * Tiny tagged binary format for save states. Each section is written as
 * (tag, length, payload) so that readers can skip unknown sections and
 * detect truncation.
 */
class StateWriter {
    private val bytes = ByteArrayOutputStream()
    private val out = DataOutputStream(bytes)

    init {
        out.writeInt(MAGIC)
        out.writeInt(VERSION)
    }

    fun ints(tag: String, values: IntArray) {
        section(tag) {
            it.writeInt(values.size)
            for (v in values) it.writeInt(v)
        }
    }

    fun longs(tag: String, values: LongArray) {
        section(tag) {
            it.writeInt(values.size)
            for (v in values) it.writeLong(v)
        }
    }

    fun bytes(tag: String, values: ByteArray) {
        section(tag) {
            it.writeInt(values.size)
            it.write(values)
        }
    }

    fun string(tag: String, value: String) {
        section(tag) { it.writeUTF(value) }
    }

    private fun section(tag: String, body: (DataOutputStream) -> Unit) {
        require(tag.length == 4)
        val payload = ByteArrayOutputStream()
        body(DataOutputStream(payload))
        out.writeBytes(tag)
        out.writeInt(payload.size())
        payload.writeTo(out)
    }

    /** Finishes and returns the compressed state. */
    fun toByteArray(): ByteArray {
        out.flush()
        val compressed = ByteArrayOutputStream()
        DeflaterOutputStream(compressed).use { it.write(bytes.toByteArray()) }
        return compressed.toByteArray()
    }

    companion object {
        const val MAGIC = 0x41435043 // "ACPC"
        const val VERSION = 1
    }
}

class StateReader(compressed: ByteArray) {
    private val sections = LinkedHashMap<String, ByteArray>()
    val version: Int

    init {
        val raw = try {
            InflaterInputStream(ByteArrayInputStream(compressed)).use { it.readBytes() }
        } catch (e: Exception) {
            throw IllegalArgumentException("Corrupted state (not a valid deflate stream)", e)
        }
        val input = DataInputStream(ByteArrayInputStream(raw))
        require(input.readInt() == StateWriter.MAGIC) { "Not an Acpc save state" }
        version = input.readInt()
        require(version in 1..StateWriter.VERSION) { "Unsupported save state version $version" }
        while (input.available() >= 8) {
            val tagBytes = ByteArray(4)
            input.readFully(tagBytes)
            val len = input.readInt()
            require(len >= 0 && len <= input.available()) { "Truncated save state" }
            val payload = ByteArray(len)
            input.readFully(payload)
            sections[String(tagBytes, Charsets.ISO_8859_1)] = payload
        }
    }

    fun has(tag: String): Boolean = sections.containsKey(tag)

    fun ints(tag: String): IntArray {
        val d = data(tag)
        return IntArray(d.readInt()) { d.readInt() }
    }

    fun longs(tag: String): LongArray {
        val d = data(tag)
        return LongArray(d.readInt()) { d.readLong() }
    }

    fun bytes(tag: String): ByteArray {
        val d = data(tag)
        val b = ByteArray(d.readInt())
        d.readFully(b)
        return b
    }

    fun string(tag: String): String = data(tag).readUTF()

    private fun data(tag: String): DataInputStream =
        DataInputStream(ByteArrayInputStream(sections[tag] ?: throw IllegalArgumentException("Missing section $tag")))
}
