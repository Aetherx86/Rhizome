package dev.aetherx86.rhizome

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.util.BitSet
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FormatTest {
    private fun b(text: String) = text.toByteArray(Charsets.ISO_8859_1)

    @Test
    fun decodesEveryBencodeType() {
        assertEquals(42L, (Bencode.decode(b("i42e")) as BInt).value)
        assertEquals(-7L, (Bencode.decode(b("i-7e")) as BInt).value)
        assertEquals(0L, (Bencode.decode(b("i0e")) as BInt).value)
        assertEquals(Long.MAX_VALUE, (Bencode.decode(b("i9223372036854775807e")) as BInt).value)
        assertEquals("spam", (Bencode.decode(b("4:spam")) as BBytes).text())
        assertEquals("", (Bencode.decode(b("0:")) as BBytes).text())
        val list = Bencode.decode(b("l4:spami42ee")) as BList
        assertEquals(2, list.items.size)
        val dict = Bencode.decode(b("d3:bar4:spam3:fooi42ee")) as BDict
        assertEquals("spam", dict.text("bar"))
        assertEquals(42L, dict.int("foo"))
    }

    @Test
    fun rejectsMalformedBencode() {
        val cases = listOf(
            "", "i", "ie", "i-e", "i--1e", "i03e", "i-0e", "i1", "i1.5e", "i92233720368547758080e",
            "4:spa", "-1:a", "04:spam", "4spam", "l", "li1e", "d", "d3:foo", "d3:fooe",
            "di1ei2ee", "d1:ai1e1:ai2ee", "x", "i1ei2e", "4:spamX",
        )
        for (case in cases) {
            assertFailsWith<BencodeError>("should reject '$case'") { Bencode.decode(b(case)) }
        }
    }

    @Test
    fun hostileInputCannotExhaustMemoryOrTheStack() {
        assertFailsWith<BencodeError> { Bencode.decode(b("99999999999999999999:x")) }
        assertFailsWith<BencodeError> { Bencode.decode(b("2147483647:x")) }
        assertFailsWith<BencodeError> { Bencode.decode(b("l".repeat(1_000_000))) }
        assertFailsWith<BencodeError> { Bencode.decode(b("d1:a".repeat(200_000))) }
    }

    @Test
    fun arbitraryBytesNeverCrashTheDecoder() {
        val random = Random(1)
        val alphabet = b("ilde0123456789:-abc")
        repeat(100_000) {
            val bytes = ByteArray(random.nextInt(40)) {
                if (random.nextBoolean()) alphabet[random.nextInt(alphabet.size)] else random.nextInt().toByte()
            }
            try {
                Bencode.decode(bytes)
            } catch (_: BencodeError) {
                // Rejecting is fine. Anything else fails the test.
            }
        }
    }

    @Test
    fun spansCoverExactSourceBytes() {
        val source = b("d4:infod4:name1:xe5:otheri1ee")
        val root = Bencode.decode(source) as BDict
        val info = root["info"]!!
        assertEquals("d4:name1:xe", String(source, info.start, info.end - info.start, Charsets.ISO_8859_1))
    }

    @Test
    fun encodingSortsKeysAndRoundTrips() {
        val encoded = Bencode.encode(mapOf("zebra" to 1, "apple" to listOf("x", 2L), "mid" to b("raw")))
        assertEquals("d5:applel1:xi2ee3:mid3:raw5:zebrai1ee", String(encoded, Charsets.ISO_8859_1))
        assertIs<BDict>(Bencode.decode(encoded))
    }

    private fun single(data: ByteArray = ByteArray(100_000) { it.toByte() }, pieceLength: Int = 32768): Metainfo =
        Metainfo.parse(Metainfo.create("file.bin", listOf(listOf("file.bin") to data), pieceLength))

    @Test
    fun parsesSingleFileTorrents() {
        val data = ByteArray(100_000) { it.toByte() }
        val meta = single(data)
        assertEquals("file.bin", meta.name)
        assertEquals(100_000L, meta.totalLength)
        assertEquals(4, meta.pieceCount)
        assertEquals(32768, meta.pieceSize(0))
        assertEquals(100_000 - 3 * 32768, meta.pieceSize(3))
        assertContentEquals(sha1(data, 0, 32768), meta.pieceHash(0))
        assertEquals(listOf("file.bin"), meta.files.single().path)
        assertFailsWith<IllegalArgumentException> { meta.pieceSize(4) }
    }

    @Test
    fun parsesMultiFileTorrentsWithOffsets() {
        val source = Metainfo.create(
            "folder",
            listOf(listOf("a.txt") to ByteArray(10), listOf("sub", "b.txt") to ByteArray(20), listOf("empty") to ByteArray(0)),
            16,
            singleFile = false,
        )
        val meta = Metainfo.parse(source)
        assertEquals(30L, meta.totalLength)
        assertEquals(3, meta.files.size)
        assertEquals(listOf("folder", "sub", "b.txt"), meta.files[1].path)
        assertEquals(10L, meta.files[1].offset)
        assertEquals(30L, meta.files[2].offset)
    }

    /**
     * The info hash has to be over the original bytes. This `info` has unsorted keys, so
     * re-encoding it would produce different bytes and therefore the wrong hash.
     */
    @Test
    fun infoHashUsesRawBytesEvenWhenNotCanonical() {
        val hashes = String(sha1(ByteArray(4)), Charsets.ISO_8859_1)
        val info = "d6:lengthi4e4:name1:x12:piece lengthi16384e6:pieces20:${hashes}e"
        val unsorted = "d4:name1:x6:lengthi4e12:piece lengthi16384e6:pieces20:${hashes}e"
        val meta = Metainfo.parse(b("d4:info${unsorted}e"))
        assertContentEquals(sha1(b(unsorted)), meta.infoHash)
        assertFalse(meta.infoHash.contentEquals(sha1(b(info))), "the canonical form hashes differently")
    }

    @Test
    fun trackerListsMergeAndDeduplicate() {
        val source = Metainfo.create(
            "f",
            listOf(listOf("f") to ByteArray(4)),
            16384,
            trackers = listOf("http://a/announce", "udp://b:80/announce"),
        )
        assertEquals(listOf("http://a/announce", "udp://b:80/announce"), Metainfo.parse(source).trackers)
    }

    /** Builds a torrent with arbitrary file paths, which `create` would never produce. */
    private fun withPaths(vararg paths: List<String>, name: String = "root"): ByteArray {
        val hashes = ByteArray(20)
        val info = mapOf(
            "name" to name,
            "piece length" to 16384,
            "pieces" to hashes,
            "files" to paths.mapIndexed { i, path -> mapOf("length" to if (i == 0) 1 else 0, "path" to path) },
        )
        return Bencode.encode(mapOf("info" to info))
    }

    @Test
    fun hostileFileNamesAreRejected() {
        val attacks = listOf(
            listOf(".."), listOf("..", "..", "etc", "passwd"), listOf("a", "..", "..", "b"), listOf("."),
            listOf(""), listOf("/etc/passwd"), listOf("a/b"), listOf("..\\..\\windows"), listOf("a\\b"),
            listOf("C:"), listOf("C:\\Windows"), listOf("file:stream"), listOf("nul\u0000byte"),
            listOf("line\nbreak"), listOf("CON"), listOf("con.txt"), listOf("NUL"), listOf("com1"), listOf("LPT9.log"),
            listOf("trailing."), listOf("trailing "), emptyList(),
        )
        for (attack in attacks) {
            assertFailsWith<InvalidTorrent>("should reject $attack") { Metainfo.parse(withPaths(attack)) }
        }
        for (name in listOf("..", ".", "", "a/b", "../x", "C:\\x", "CON")) {
            assertFailsWith<InvalidTorrent>("should reject name '$name'") {
                Metainfo.parse(withPaths(listOf("ok"), name = name))
            }
        }
    }

    @Test
    fun ordinaryFileNamesAreAccepted() {
        for (name in listOf("a.txt", "Contract.pdf", "console.log", "..hidden", "a..b", "한글.txt", "with space.txt", "com10")) {
            assertTrue(Metainfo.isSafeName(name), name)
        }
    }

    @Test
    fun conflictingFilesAreRejected() {
        assertFailsWith<InvalidTorrent> { Metainfo.parse(withPaths(listOf("a"), listOf("a"))) }
        assertFailsWith<InvalidTorrent> { Metainfo.parse(withPaths(listOf("a"), listOf("a", "b"))) }
    }

    @Test
    fun inconsistentTorrentsAreRejected() {
        val build = { fields: Map<String, Any> -> Bencode.encode(mapOf("info" to fields)) }
        val base = mapOf("name" to "x", "piece length" to 16384, "pieces" to ByteArray(20), "length" to 100)
        val cases = listOf(
            b("not bencode"),
            b("i1e"),
            b("de"),
            build(base - "name"),
            build(base - "piece length"),
            build(base - "pieces"),
            build(base - "length"),
            build(base + ("piece length" to 0)),
            build(base + ("piece length" to -1)),
            build(base + ("piece length" to (Metainfo.MAX_PIECE_LENGTH + 1))),
            build(base + ("pieces" to ByteArray(19))),
            build(base + ("pieces" to ByteArray(0))),
            build(base + ("pieces" to ByteArray(40))),
            build(base + ("length" to 16385)),
            build(base + ("length" to 0)),
            build(base + ("length" to -5)),
            build(base + ("files" to listOf(mapOf("length" to 1, "path" to listOf("a"))))),
            build((base - "length") + ("files" to emptyList<Any>())),
            build((base - "length") + ("files" to listOf(mapOf("length" to -1, "path" to listOf("a"))))),
            build((base - "length") + ("files" to listOf(mapOf("path" to listOf("a"))))),
            build((base - "length") + ("files" to listOf(mapOf("length" to 1)))),
            build((base - "length") + ("files" to listOf("not a dictionary"))),
            build(
                (base - "length") + (
                    "files" to listOf(
                        mapOf("length" to Long.MAX_VALUE, "path" to listOf("a")),
                        mapOf("length" to Long.MAX_VALUE, "path" to listOf("b")),
                    )
                    ),
            ),
        )
        for (case in cases) {
            val failure = runCatching { Metainfo.parse(case) }.exceptionOrNull()
            assertTrue(
                failure is InvalidTorrent || failure is ArithmeticException,
                "got $failure for ${String(case, Charsets.ISO_8859_1).take(100)}",
            )
        }
    }

    @Test
    fun v2OnlyTorrentsExplainThemselves() {
        val source = Bencode.encode(
            mapOf("info" to mapOf("name" to "x", "piece length" to 16384, "meta version" to 2, "length" to 1)),
        )
        val error = assertFailsWith<InvalidTorrent> { Metainfo.parse(source) }
        assertTrue("v2" in error.message!!)
    }

    @Test
    fun magnetLinksParse() {
        val hash = "c12fe1c06bba254a9dc9f519b335aa7c1367a88a"
        val magnet = Magnet.parse(
            "magnet:?xt=urn:btih:$hash&dn=my%20file&tr=http%3A%2F%2Fa%2Fannounce&tr=udp%3A%2F%2Fb%3A80&x.pe=1.2.3.4:5",
        )
        assertEquals(hash, magnet.infoHash.toHex())
        assertEquals("my file", magnet.name)
        assertEquals(listOf("http://a/announce", "udp://b:80"), magnet.trackers)
        assertEquals(listOf("1.2.3.4:5"), magnet.peers)
    }

    @Test
    fun base32MagnetHashesDecode() {
        val magnet = Magnet.parse("magnet:?xt=urn:btih:MFRGGZDFMZTWQ2LKNNWG23TPOBYXE43U")
        assertEquals("abcdefghijklmnopqrst", String(magnet.infoHash))
    }

    @Test
    fun badMagnetLinksAreRejected() {
        for (bad in listOf(
            "http://example.com",
            "magnet:?dn=x",
            "magnet:?xt=urn:btih:zz",
            "magnet:?xt=urn:btih:" + "z".repeat(40),
            "magnet:?xt=urn:btih:" + "1".repeat(32),
            "magnet:?xt=urn:sha1:abc",
            "magnet:?xt=urn:btih:%ZZ",
        )) {
            assertFailsWith<InvalidTorrent>(bad) { Magnet.parse(bad) }
        }
    }

    private fun readBack(bytes: ByteArray) = PeerMessage.read(DataInputStream(ByteArrayInputStream(bytes)))

    @Test
    fun everyMessageRoundTrips() {
        val cases = listOf(
            PeerMessage.Choke, PeerMessage.Unchoke, PeerMessage.Interested, PeerMessage.NotInterested,
            PeerMessage.KeepAlive, PeerMessage.Have(7), PeerMessage.Request(1, 2, 3), PeerMessage.Cancel(4, 5, 6),
            PeerMessage.Port(6881),
        )
        for (message in cases) assertEquals(message, readBack(PeerMessage.encode(message)))

        val piece = readBack(PeerMessage.encode(PeerMessage.Piece(1, 2, b("data")))) as PeerMessage.Piece
        assertEquals(1, piece.piece)
        assertEquals(2, piece.begin)
        assertContentEquals(b("data"), piece.block)

        val bits = readBack(PeerMessage.encode(PeerMessage.Bitfield(byteArrayOf(5)))) as PeerMessage.Bitfield
        assertContentEquals(byteArrayOf(5), bits.bits)

        val extended = readBack(PeerMessage.encode(PeerMessage.Extended(3, b("x")))) as PeerMessage.Extended
        assertEquals(3, extended.id)
        assertContentEquals(b("x"), extended.payload)
    }

    @Test
    fun theHighPortRoundTrips() {
        assertEquals(PeerMessage.Port(65535), readBack(PeerMessage.encode(PeerMessage.Port(65535))))
    }

    @Test
    fun handshakeMatchesTheWireFormat() {
        val hash = ByteArray(20) { 1 }
        val id = ByteArray(20) { 2 }
        val bytes = Handshake(hash, id, supportsExtensions = true, supportsDht = true).encode()
        assertEquals(68, bytes.size)
        assertEquals(19, bytes[0].toInt())
        assertEquals("BitTorrent protocol", String(bytes, 1, 19))
        assertEquals(0x10, bytes[25].toInt())
        assertEquals(0x01, bytes[27].toInt())
        val parsed = Handshake.read(DataInputStream(ByteArrayInputStream(bytes)))
        assertContentEquals(hash, parsed.infoHash)
        assertContentEquals(id, parsed.peerId)
        assertTrue(parsed.supportsExtensions && parsed.supportsDht)

        val other = bytes.clone().also { it[1] = 'X'.code.toByte() }
        assertFailsWith<ProtocolError> { Handshake.read(DataInputStream(ByteArrayInputStream(other))) }
    }

    @Test
    fun malformedMessagesAreRejected() {
        fun frame(length: Int, vararg body: Int) =
            java.nio.ByteBuffer.allocate(4 + body.size).putInt(length).also { b -> body.forEach { b.put(it.toByte()) } }.array()

        assertFailsWith<ProtocolError> { readBack(frame(-1)) }
        assertFailsWith<ProtocolError> { readBack(frame(Int.MAX_VALUE)) }
        assertFailsWith<ProtocolError> { readBack(frame(PeerMessage.MAX_MESSAGE_LENGTH + 1)) }
        assertFailsWith<ProtocolError> { readBack(frame(2, 0, 0)) }
        assertFailsWith<ProtocolError> { readBack(frame(4, 4, 0, 0, 0)) }
        assertFailsWith<ProtocolError> { readBack(frame(5, 6, 0, 0, 0, 0)) }
        assertFailsWith<ProtocolError> { readBack(frame(3, 7, 0, 0)) }
        assertFailsWith<ProtocolError> { readBack(frame(1, 20)) }
        assertIs<PeerMessage.Unknown>(readBack(frame(1, 99)), "unknown ids are skipped, as extensions may add them")
    }

    @Test
    fun oversizedBlocksAreRejected() {
        val body = ByteArray(9 + MAX_BLOCK_SIZE + 1)
        body[0] = 7
        assertFailsWith<ProtocolError> { PeerMessage.decode(body) }
    }

    @Test
    fun bitfieldsUseHighBitFirstAndRejectStrayBits() {
        val set = BitSet().apply {
            set(0)
            set(9)
        }
        val wire = set.toWireBitfield(10)
        assertContentEquals(byteArrayOf(0x80.toByte(), 0x40), wire)
        assertEquals(set, parseWireBitfield(wire, 10))

        assertFailsWith<ProtocolError> { parseWireBitfield(ByteArray(1), 10) }
        assertFailsWith<ProtocolError> { parseWireBitfield(ByteArray(3), 10) }
        assertFailsWith<ProtocolError> { parseWireBitfield(byteArrayOf(0, 0x20), 10) }
        assertEquals(BitSet(), parseWireBitfield(ByteArray(1), 8))
    }

    @Test
    fun hexConversionRejectsBadInput() {
        assertContentEquals(byteArrayOf(0x1F, 0xAB.toByte()), hexToBytes("1fAB"))
        assertNull(hexToBytes("1"))
        assertNull(hexToBytes("zz"))
        assertEquals("1fab", byteArrayOf(0x1F, 0xAB.toByte()).toHex())
    }
}
