package dev.aetherx86.rhizome

import java.util.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** Property and fuzz tests for the encoders and the parsers of untrusted text. */
class EncodingTest {
    private val random = Random(42)

    /** A random value of the kinds [Bencode.encode] accepts, nested at most [depth] deep. */
    private fun randomValue(depth: Int): Any = when (if (depth == 0) random.nextInt(2) else random.nextInt(4)) {
        0 -> when (random.nextInt(4)) {
            0 -> Long.MIN_VALUE
            1 -> Long.MAX_VALUE
            2 -> 0L
            else -> random.nextLong()
        }
        1 -> ByteArray(random.nextInt(40)).also { random.nextBytes(it) }
        2 -> List(random.nextInt(5)) { randomValue(depth - 1) }
        else -> (0 until random.nextInt(5)).associate { _ ->
            String(CharArray(random.nextInt(6)) { random.nextInt(256).toChar() }) to randomValue(depth - 1)
        }
    }

    /** Turns a decoded value back into plain Kotlin values, to compare with what was encoded. */
    private fun plain(value: BValue): Any = when (value) {
        is BInt -> value.value
        is BBytes -> value.bytes.toList()
        is BList -> value.items.map(::plain)
        is BDict -> value.entries.mapValues { plain(it.value) }
    }

    private fun normalise(value: Any): Any = when (value) {
        is ByteArray -> value.toList()
        is List<*> -> value.map { normalise(it!!) }
        is Map<*, *> -> value.entries.associate { (k, v) -> k as String to normalise(v!!) }
        else -> value
    }

    @Test
    fun bencodeRoundTripsAndIsCanonical() {
        repeat(2_000) {
            val value = randomValue(4)
            val encoded = Bencode.encode(value)
            val decoded = Bencode.decode(encoded)
            assertEquals(normalise(value), plain(decoded))
            // What the encoder writes, the decoder reads back to the very same bytes.
            assertContentEquals(encoded, Bencode.encode(rebuild(decoded)))
            assertEquals(0, decoded.start)
            assertEquals(encoded.size, decoded.end)
        }
    }

    private fun rebuild(value: BValue): Any = when (value) {
        is BInt -> value.value
        is BBytes -> value.bytes
        is BList -> value.items.map(::rebuild)
        is BDict -> value.entries.mapValues { rebuild(it.value) }
    }

    @Test
    fun dictionaryKeysOutsideLatin1AreRefusedInsteadOfMangled() {
        // Both keys would once have been written as "?", producing a duplicate key.
        assertFailsWith<IllegalArgumentException> { Bencode.encode(mapOf("ключ" to 1, "ꙮ" to 2)) }
        assertContentEquals("d2:\u00e9\u00e9i1ee".toByteArray(Charsets.ISO_8859_1), Bencode.encode(mapOf("\u00e9\u00e9" to 1)))
    }

    @Test
    fun mutatedTorrentsFailOnlyAsInvalidTorrents() {
        val files = listOf(listOf("a") to ByteArray(3000) { it.toByte() }, listOf("d", "b") to ByteArray(500))
        val valid = Metainfo.create("t", files, 1024, listOf("http://tracker.example/a"), singleFile = false)
        var accepted = 0
        repeat(5_000) {
            val bytes = valid.copyOf()
            val mutated = when (random.nextInt(3)) {
                0 -> bytes.also { b -> repeat(1 + random.nextInt(4)) { b[random.nextInt(b.size)] = random.nextInt(256).toByte() } }
                1 -> bytes.copyOf(random.nextInt(bytes.size))
                else -> {
                    val at = random.nextInt(bytes.size)
                    bytes.copyOfRange(0, at) + ByteArray(1 + random.nextInt(8)).also { random.nextBytes(it) } + bytes.copyOfRange(at, bytes.size)
                }
            }
            try {
                Metainfo.parse(mutated)
                accepted++
            } catch (_: InvalidTorrent) {
            } catch (e: Exception) {
                fail("${e::class.simpleName} escaped the parser: ${e.message}")
            }
        }
        assertTrue(accepted < 5_000)
    }

    @Test
    fun fileLengthsThatOverflowAreRejected() {
        val info = mapOf(
            "name" to "t",
            "piece length" to 16384,
            "pieces" to ByteArray(20),
            "files" to listOf(
                mapOf("length" to Long.MAX_VALUE, "path" to listOf("a")),
                mapOf("length" to Long.MAX_VALUE, "path" to listOf("b")),
            ),
        )
        val error = assertFailsWith<InvalidTorrent> { Metainfo.parse(Bencode.encode(mapOf("info" to info))) }
        assertTrue("2^63" in error.message!!)
    }

    @Test
    fun magnetLinksFailOnlyAsInvalidTorrents() {
        val alphabet = "abcdefABCDEF0123456789:?=&%+xturnbthiﬃßİ\u0000\uD800 "
        repeat(5_000) {
            val tail = String(CharArray(random.nextInt(60)) { alphabet[random.nextInt(alphabet.length)] })
            val link = listOf("magnet:?xt=urn:btih:", "magnet:?", "magnet:?dn=", "")[random.nextInt(4)] + tail
            try {
                Magnet.parse(link)
            } catch (_: InvalidTorrent) {
            } catch (e: Exception) {
                fail("${e::class.simpleName} escaped for $link")
            }
        }
    }

    @Test
    fun base32HashesWithCharactersThatGrowWhenUppercasedAreRejected() {
        // 'ﬃ' uppercases to "FFI", which once pushed the decoder past the end of its buffer.
        val link = "magnet:?xt=urn:btih:" + "ﬃ".repeat(32)
        assertFailsWith<InvalidTorrent> { Magnet.parse(link) }
        assertFailsWith<InvalidTorrent> { Magnet.parse("magnet:?xt=urn:btih:" + "A".repeat(29) + "ﬃﬃﬃ") }
    }

    @Test
    fun onlyAsciiHexSpellsAHash() {
        assertNull(hexToBytes("\uFF41\uFF42"), "fullwidth letters are not hex")
        assertNull(hexToBytes("\u0663\u0663"), "Arabic-Indic digits are not hex")
        assertContentEquals(byteArrayOf(0xAB.toByte(), 0x0F), hexToBytes("aB0f"))
    }

    @Test
    fun jsonEscapesEverythingThatNeedsIt() {
        assertEquals("\"a\\\"b\\\\c\\n\\t\\u0001\\u007f\\u2028\"", Json.encode("a\"b\\c\n\t\u0001\u007F\u2028"))
        assertEquals("\"\\ud800x\"", Json.encode("\uD800x"), "an unpaired surrogate is escaped")
        assertEquals("\"\uD83D\uDE00\"", Json.encode("\uD83D\uDE00"), "a proper pair passes through")
        assertEquals("{\"a\":[1,2.5,null,true],\"b\":null}", Json.encode(linkedMapOf("a" to listOf(1, 2.5, null, true), "b" to Double.NaN)))
        assertFailsWith<IllegalArgumentException> { Json.encode(Any()) }
    }

    @Test
    fun jsonOfRandomStringsReadsBackExactly() {
        repeat(2_000) {
            val text = String(CharArray(random.nextInt(30)) { random.nextInt(0x3000).toChar() })
            val encoded = Json.encode(mapOf("k" to text))
            @Suppress("UNCHECKED_CAST")
            val decoded = JsonReader(encoded).read() as Map<String, Any?>
            assertEquals(text, decoded["k"])
        }
    }
}
