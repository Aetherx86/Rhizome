package dev.aetherx86.rhizome

import java.io.ByteArrayOutputStream

class BencodeError(val position: Int, message: String) : Exception("at byte $position: $message")

/**
 * A decoded bencode value. [start] and [end] are its exact span in the source bytes.
 *
 * The span matters: a torrent's identity is the SHA-1 of the raw bytes of its `info` dictionary.
 * Re-encoding a parsed dictionary can produce different bytes if the original was not perfectly
 * canonical, which would silently give a wrong hash and join the wrong swarm.
 */
sealed class BValue {
    var start = 0
        internal set
    var end = 0
        internal set
}

class BInt(val value: Long) : BValue()

class BBytes(val bytes: ByteArray) : BValue() {
    /** The bytes as text, for keys and names. */
    fun text(): String = String(bytes, Charsets.UTF_8)
}

class BList(val items: List<BValue>) : BValue()

/** Keys are held as ISO-8859-1 strings, which map one-to-one onto bytes. */
class BDict(val entries: Map<String, BValue>) : BValue() {
    operator fun get(key: String): BValue? = entries[key]
    fun int(key: String): Long? = (entries[key] as? BInt)?.value
    fun bytes(key: String): ByteArray? = (entries[key] as? BBytes)?.bytes
    fun text(key: String): String? = (entries[key] as? BBytes)?.text()
    fun dict(key: String): BDict? = entries[key] as? BDict
    fun list(key: String): BList? = entries[key] as? BList
}

/** Decoder for untrusted input. Nesting depth is bounded, and no length is trusted. */
class BencodeDecoder(private val source: ByteArray, startAt: Int = 0, private val maxDepth: Int = 64) {
    var position = startAt
        private set

    private fun fail(message: String): Nothing = throw BencodeError(position, message)

    private fun peek(): Int =
        if (position < source.size) source[position].toInt() and 0xFF else fail("unexpected end of input")

    fun decode(): BValue = value(0)

    private fun value(depth: Int): BValue {
        if (depth > maxDepth) fail("nesting exceeds $maxDepth levels")
        val start = position
        val result = when (peek().toChar()) {
            'i' -> integer()
            'l' -> list(depth)
            'd' -> dict(depth)
            in '0'..'9' -> BBytes(bytes())
            else -> fail("unexpected byte 0x%02x".format(peek()))
        }
        result.start = start
        result.end = position
        return result
    }

    private fun integer(): BInt {
        position++
        val start = position
        if (peek() == '-'.code) position++
        val digitsStart = position
        while (peek() in '0'.code..'9'.code) position++
        if (peek() != 'e'.code) fail("integer has no terminator")
        val digits = position - digitsStart
        if (digits == 0) fail("integer has no digits")
        val negative = digitsStart != start
        // The format forbids alternate spellings of a number, such as 03 and -0.
        if (source[digitsStart] == '0'.code.toByte() && (digits > 1 || negative)) fail("non-canonical integer")
        val text = String(source, start, position - start, Charsets.ISO_8859_1)
        val value = text.toLongOrNull() ?: fail("integer does not fit in 64 bits")
        position++
        return BInt(value)
    }

    private fun bytes(): ByteArray {
        var length = 0L
        val start = position
        while (peek() in '0'.code..'9'.code) {
            length = length * 10 + (peek() - '0'.code)
            if (length > source.size) fail("string is longer than the input")
            position++
        }
        if (peek() != ':'.code) fail("string length has no colon")
        if (position - start > 1 && source[start] == '0'.code.toByte()) fail("non-canonical string length")
        position++
        if (length > source.size - position) fail("string is longer than the input")
        val out = source.copyOfRange(position, position + length.toInt())
        position += length.toInt()
        return out
    }

    private fun list(depth: Int): BList {
        position++
        val items = ArrayList<BValue>()
        while (peek() != 'e'.code) items.add(value(depth + 1))
        position++
        return BList(items)
    }

    private fun dict(depth: Int): BDict {
        position++
        val entries = LinkedHashMap<String, BValue>()
        while (peek() != 'e'.code) {
            if (peek() !in '0'.code..'9'.code) fail("dictionary key must be a string")
            val key = String(bytes(), Charsets.ISO_8859_1)
            if (entries.containsKey(key)) fail("duplicate dictionary key")
            entries[key] = value(depth + 1)
        }
        position++
        return BDict(entries)
    }
}

object Bencode {
    /** Decodes exactly one value, and rejects anything left over. */
    fun decode(source: ByteArray): BValue {
        val decoder = BencodeDecoder(source)
        val value = decoder.decode()
        if (decoder.position != source.size) throw BencodeError(decoder.position, "trailing data")
        return value
    }

    /** Decodes one value from the front, returning it and where it ended. */
    fun decodePrefix(source: ByteArray, startAt: Int = 0): Pair<BValue, Int> {
        val decoder = BencodeDecoder(source, startAt)
        return decoder.decode() to decoder.position
    }

    private fun write(out: ByteArrayOutputStream, value: Any) {
        when (value) {
            is Long -> out.writeBytes("i${value}e".toByteArray())
            is Int -> out.writeBytes("i${value}e".toByteArray())
            is ByteArray -> {
                out.writeBytes("${value.size}:".toByteArray())
                out.writeBytes(value)
            }
            is String -> write(out, value.toByteArray(Charsets.UTF_8))
            is List<*> -> {
                out.write('l'.code)
                for (item in value) write(out, requireNotNull(item))
                out.write('e'.code)
            }
            is Map<*, *> -> {
                out.write('d'.code)
                // Keys must sort as raw bytes. ISO-8859-1 strings compare the same way.
                val entries = value.entries
                    .map { (k, v) -> k as String to requireNotNull(v) }
                    .sortedBy { it.first }
                for ((k, v) in entries) {
                    write(out, k.toByteArray(Charsets.ISO_8859_1))
                    write(out, v)
                }
                out.write('e'.code)
            }
            else -> throw IllegalArgumentException("cannot encode ${value::class}")
        }
    }

    /** Encodes Long, Int, String, ByteArray, List and Map, with map keys in ISO-8859-1. */
    fun encode(value: Any): ByteArray {
        val out = ByteArrayOutputStream()
        write(out, value)
        return out.toByteArray()
    }
}
