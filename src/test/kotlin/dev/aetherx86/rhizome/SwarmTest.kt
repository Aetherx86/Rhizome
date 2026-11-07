package dev.aetherx86.rhizome

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val TIMEOUT = 60_000L

class SwarmTest {
    private val temp = ArrayList<Path>()
    private val closers = ArrayList<AutoCloseable>()

    private fun dir(): Path = Files.createTempDirectory("rhizome").also { temp.add(it) }

    private fun client(): RhizomeClient =
        RhizomeClient(ClientConfig(allowPrivateAddresses = true)).also { closers.add(it) }

    @AfterTest
    fun cleanup() {
        for (c in closers.reversed()) runCatching { c.close() }
        for (t in temp) t.toFile().deleteRecursively()
    }

    private fun randomBytes(size: Int, seed: Long = 1): ByteArray = ByteArray(size).also { Random(seed).nextBytes(it) }

    private val loopback: InetAddress = InetAddress.getLoopbackAddress()

    private fun seedDir(meta: Metainfo, contents: List<ByteArray>): Path {
        val root = dir()
        for ((file, data) in meta.files.zip(contents)) {
            var path = root
            for (part in file.path) path = path.resolve(part)
            Files.createDirectories(path.parent)
            Files.write(path, data)
        }
        return root
    }

    private fun assertDownloaded(meta: Metainfo, root: Path, contents: List<ByteArray>) {
        for ((file, data) in meta.files.zip(contents)) {
            var path = root
            for (part in file.path) path = path.resolve(part)
            assertContentEquals(data, Files.readAllBytes(path), "contents of ${file.path}")
        }
    }

    private fun address(c: RhizomeClient) = InetSocketAddress(loopback, c.port)

    @Test
    fun aLeecherDownloadsASingleFileFromASeeder() = runBlocking {
        val data = randomBytes(700_000)
        val meta = Metainfo.parse(Metainfo.create("data.bin", listOf(listOf("data.bin") to data), 32 * 1024))
        val seeder = client()
        val seed = seeder.add(meta, seedDir(meta, listOf(data)))
        withTimeout(TIMEOUT) { seed.awaitComplete() }

        val leecher = client()
        val target = dir()
        val leech = leecher.add(meta, target)
        assertFalse(leech.isComplete)
        leech.connect(address(seeder))
        withTimeout(TIMEOUT) { leech.awaitComplete() }

        assertDownloaded(meta, target, listOf(data))
        assertEquals(data.size.toLong(), leech.downloaded)
        assertEquals(0, leech.hashFailures)
        assertEquals(1.0, leech.progress())
        assertTrue(seed.uploaded >= data.size, "the seeder should count what it sent: ${seed.uploaded}")
    }

    @Test
    fun aPieceSmallerThanOneBlockAndAFileSmallerThanOnePieceWork() = runBlocking {
        val data = randomBytes(5)
        val meta = Metainfo.parse(Metainfo.create("tiny", listOf(listOf("tiny") to data), 16 * 1024))
        val seeder = client()
        seeder.add(meta, seedDir(meta, listOf(data)))
        val target = dir()
        val leech = client().add(meta, target)
        leech.connect(address(seeder))
        withTimeout(TIMEOUT) { leech.awaitComplete() }
        assertDownloaded(meta, target, listOf(data))
    }

    @Test
    fun filesSpanningPieceBoundariesAreReassembledExactly() = runBlocking {
        // Sizes chosen so that files start and end in the middle of pieces, and an empty file sits
        // between two others.
        val contents = listOf(randomBytes(40_000, 1), randomBytes(1, 2), ByteArray(0), randomBytes(90_001, 3), randomBytes(16_384, 4))
        val paths = listOf(listOf("a.bin"), listOf("deep", "er", "b.bin"), listOf("empty"), listOf("c.bin"), listOf("deep", "d.bin"))
        val meta = Metainfo.parse(Metainfo.create("multi", paths.zip(contents), 32 * 1024, singleFile = false))
        val seeder = client()
        val seed = seeder.add(meta, seedDir(meta, contents))
        withTimeout(TIMEOUT) { seed.awaitComplete() }
        val target = dir()
        val leech = client().add(meta, target)
        leech.connect(address(seeder))
        withTimeout(TIMEOUT) { leech.awaitComplete() }
        assertDownloaded(meta, target, contents)
    }

    @Test
    fun aDownloadResumesWithoutFetchingWhatItAlreadyHas() = runBlocking {
        val data = randomBytes(320_000)
        val pieceLength = 32 * 1024
        val meta = Metainfo.parse(Metainfo.create("r.bin", listOf(listOf("r.bin") to data), pieceLength))
        val seeder = client()
        seeder.add(meta, seedDir(meta, listOf(data)))

        val target = dir()
        // The first half is valid, and a piece in the second half holds garbage.
        val partial = data.copyOf()
        partial.fill(0, 200_000, data.size)
        Files.write(target.resolve("r.bin"), partial)

        val leech = client().add(meta, target)
        leech.connect(address(seeder))
        withTimeout(TIMEOUT) { leech.awaitComplete() }
        assertDownloaded(meta, target, listOf(data))
        assertTrue(leech.downloaded in 1 until data.size, "fetched ${leech.downloaded} of ${data.size}")
    }

    @Test
    fun aCompleteCopyOnDiskNeedsNoPeers() = runBlocking {
        val data = randomBytes(100_000)
        val meta = Metainfo.parse(Metainfo.create("d.bin", listOf(listOf("d.bin") to data), 32 * 1024))
        val session = client().add(meta, seedDir(meta, listOf(data)))
        withTimeout(TIMEOUT) { session.awaitComplete() }
        assertEquals(0L, session.downloaded)
    }

    @Test
    fun manyLeechersShareThroughEachOther() = runBlocking {
        val data = randomBytes(600_000, 5)
        val meta = Metainfo.parse(Metainfo.create("s.bin", listOf(listOf("s.bin") to data), 32 * 1024))
        val seeder = client()
        seeder.add(meta, seedDir(meta, listOf(data)))
        val leechers = (0 until 4).map { client() }
        val sessions = leechers.map { it.add(meta, dir()) }
        for ((i, session) in sessions.withIndex()) {
            session.connect(address(seeder))
            for (j in 0 until i) session.connect(address(leechers[j]))
        }
        withTimeout(TIMEOUT) { sessions.forEach { it.awaitComplete() } }
        for (session in sessions) assertEquals(0, session.hashFailures)
    }

    /** A peer serving bad data costs bandwidth, but it can never corrupt what reaches the disk. */
    @Test
    fun corruptPiecesFromABadPeerAreRejectedAndRefetched() = runBlocking {
        val data = randomBytes(320_000, 7)
        val meta = Metainfo.parse(Metainfo.create("c.bin", listOf(listOf("c.bin") to data), 32 * 1024))

        val evil = EvilPeer(meta.infoHash, meta.pieceCount) { message, out ->
            if (message is PeerMessage.Request) {
                out(PeerMessage.Piece(message.piece, message.begin, ByteArray(message.length) { 0x55 }))
            }
        }
        closers.add(evil)
        val seeder = client()
        seeder.add(meta, seedDir(meta, listOf(data)))

        val target = dir()
        val leech = client().add(meta, target)
        leech.connect(evil.address)
        withTimeout(TIMEOUT) {
            while (leech.hashFailures == 0) delay(10)
        }
        leech.connect(address(seeder))
        withTimeout(TIMEOUT) { leech.awaitComplete() }

        assertTrue(leech.hashFailures > 0, "the bad peer should have been detected")
        assertDownloaded(meta, target, listOf(data))
    }

    @Test
    fun aRepeatOffenderIsDisconnected() = runBlocking {
        val data = randomBytes(320_000, 8)
        val meta = Metainfo.parse(Metainfo.create("o.bin", listOf(listOf("o.bin") to data), 32 * 1024))
        val evil = EvilPeer(meta.infoHash, meta.pieceCount) { message, out ->
            if (message is PeerMessage.Request) {
                out(PeerMessage.Piece(message.piece, message.begin, ByteArray(message.length)))
            }
        }
        closers.add(evil)
        val leech = client().add(meta, dir())
        leech.connect(evil.address)
        withTimeout(TIMEOUT) {
            while (!evil.disconnected) delay(10)
        }
        assertTrue(leech.hashFailures >= 3, "failures: ${leech.hashFailures}")
    }

    @Test
    fun unrequestedBlocksAreIgnored() = runBlocking {
        val data = randomBytes(64 * 1024, 9)
        val meta = Metainfo.parse(Metainfo.create("u.bin", listOf(listOf("u.bin") to data), 32 * 1024))
        // It never unchokes, so the leecher never asks it for anything.
        val evil = EvilPeer(meta.infoHash, meta.pieceCount, unchoke = false, greet = { out ->
            // Pushes data nobody asked for, including at positions that are not block boundaries.
            out(PeerMessage.Piece(0, 0, ByteArray(BLOCK_SIZE)))
            out(PeerMessage.Piece(0, 5, ByteArray(100)))
            out(PeerMessage.Piece(1, 16384, ByteArray(BLOCK_SIZE)))
        }) { _, _ -> }
        closers.add(evil)
        val seeder = client()
        seeder.add(meta, seedDir(meta, listOf(data)))
        val target = dir()
        val leech = client().add(meta, target)
        leech.connect(evil.address)
        leech.connect(address(seeder))
        withTimeout(TIMEOUT) { leech.awaitComplete() }
        assertDownloaded(meta, target, listOf(data))
        assertEquals(0, leech.hashFailures, "unsolicited data should not even reach the buffers")
        assertEquals(data.size.toLong(), leech.downloaded)
    }

    /** Sends raw messages to a real client, to check how it reacts. Each opens a fresh connection. */
    private fun probe(
        serverPort: Int,
        infoHash: ByteArray,
        timeoutMillis: Int = 5_000,
        script: (DataInputStream, java.io.OutputStream) -> Unit,
    ) {
        Socket(loopback, serverPort).use { socket ->
            socket.soTimeout = timeoutMillis
            val out = socket.getOutputStream()
            out.write(Handshake(infoHash, ByteArray(20) { 9 }, true, false).encode())
            out.flush()
            val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
            Handshake.read(input)
            script(input, out)
        }
    }

    private fun isClosedByPeer(input: DataInputStream): Boolean {
        return try {
            while (true) PeerMessage.read(input)
            @Suppress("UNREACHABLE_CODE")
            false
        } catch (_: java.io.EOFException) {
            true
        } catch (_: java.net.SocketException) {
            true
        } catch (_: java.net.SocketTimeoutException) {
            false
        }
    }

    @Test
    fun protocolViolationsCloseTheConnectionAndTheClientKeepsWorking() = runBlocking {
        val data = randomBytes(64 * 1024, 11)
        val meta = Metainfo.parse(Metainfo.create("v.bin", listOf(listOf("v.bin") to data), 32 * 1024))
        val seeder = client()
        val seed = seeder.add(meta, seedDir(meta, listOf(data)))
        withTimeout(TIMEOUT) { seed.awaitComplete() }

        val attacks = listOf<(java.io.OutputStream) -> Unit>(
            { it.write(PeerMessage.encode(PeerMessage.Have(9999))) },
            { it.write(PeerMessage.encode(PeerMessage.Have(-1))) },
            { it.write(PeerMessage.encode(PeerMessage.Bitfield(ByteArray(100)))) },
            { it.write(ByteBuffer.allocate(4).putInt(Int.MAX_VALUE).array()) },
            { it.write(ByteBuffer.allocate(4).putInt(-5).array()) },
        )
        for (attack in attacks) {
            probe(seeder.port, meta.infoHash) { input, out ->
                attack(out)
                out.flush()
                assertTrue(isClosedByPeer(input), "the connection should be closed")
            }
        }

        // After all that, a normal peer can still download.
        val target = dir()
        val leech = client().add(meta, target)
        leech.connect(address(seeder))
        withTimeout(TIMEOUT) { leech.awaitComplete() }
        assertDownloaded(meta, target, listOf(data))
    }

    @Test
    fun outOfRangeRequestsAreRefused() = runBlocking {
        val data = randomBytes(64 * 1024, 12)
        val meta = Metainfo.parse(Metainfo.create("x.bin", listOf(listOf("x.bin") to data), 32 * 1024))
        val seeder = client()
        val seed = seeder.add(meta, seedDir(meta, listOf(data)))
        withTimeout(TIMEOUT) { seed.awaitComplete() }

        val bad = listOf(
            PeerMessage.Request(0, -1, 16384),
            PeerMessage.Request(0, 0, 0),
            PeerMessage.Request(0, 0, -1),
            PeerMessage.Request(0, 32 * 1024, 1),
            PeerMessage.Request(0, 32 * 1024 - 1, 2),
            PeerMessage.Request(0, Int.MAX_VALUE, Int.MAX_VALUE),
            PeerMessage.Request(0, 0, MAX_BLOCK_SIZE + 1),
        )
        for (request in bad) {
            probe(seeder.port, meta.infoHash) { input, out ->
                out.write(PeerMessage.encode(PeerMessage.Interested))
                out.flush()
                var unchoked = false
                while (!unchoked) unchoked = PeerMessage.read(input) == PeerMessage.Unchoke
                out.write(PeerMessage.encode(request))
                out.flush()
                assertTrue(isClosedByPeer(input), "$request should close the connection")
            }
        }
    }

    @Test
    fun aChokedPeerGetsNoData() = runBlocking {
        val data = randomBytes(64 * 1024, 13)
        val meta = Metainfo.parse(Metainfo.create("k.bin", listOf(listOf("k.bin") to data), 32 * 1024))
        val seeder = client()
        val seed = seeder.add(meta, seedDir(meta, listOf(data)))
        withTimeout(TIMEOUT) { seed.awaitComplete() }
        probe(seeder.port, meta.infoHash, timeoutMillis = 1_000) { input, out ->
            out.write(PeerMessage.encode(PeerMessage.Request(0, 0, 16384)))
            out.flush()
            // It never declared interest, so it was never unchoked.
            try {
                while (true) {
                    val message = PeerMessage.read(input)
                    assertFalse(message is PeerMessage.Piece, "a choked peer was sent data")
                }
            } catch (_: java.net.SocketTimeoutException) {
                // Silence is the correct answer.
            }
        }
    }

    @Test
    fun aHandshakeForAnUnknownTorrentIsDropped() = runBlocking {
        val seeder = client()
        Socket(loopback, seeder.port).use { socket ->
            socket.soTimeout = 5_000
            socket.getOutputStream().write(Handshake(ByteArray(20) { 7 }, ByteArray(20), true, false).encode())
            socket.getOutputStream().flush()
            assertEquals(-1, socket.getInputStream().read(), "the connection should just close")
        }
    }

    @Test
    fun aPeerAnsweringForADifferentTorrentIsRejected() = runBlocking {
        val data = randomBytes(1000)
        val meta = Metainfo.parse(Metainfo.create("q.bin", listOf(listOf("q.bin") to data), 16384))
        val evil = EvilPeer(ByteArray(20) { 3 }) { _, _ -> }
        closers.add(evil)
        val leech = client().add(meta, dir())
        leech.connect(evil.address)
        withTimeout(TIMEOUT) {
            while (!evil.disconnected) delay(10)
        }
        assertEquals(0, leech.peerCount)
    }

    @Test
    fun clientsDoNotConnectToThemselves() = runBlocking {
        val data = randomBytes(1000)
        val meta = Metainfo.parse(Metainfo.create("self.bin", listOf(listOf("self.bin") to data), 16384))
        val me = client()
        val session = me.add(meta, dir())
        session.connect(address(me))
        delay(500)
        assertEquals(0, session.peerCount)
    }

    /** A symbolic link inside the download folder must not redirect writes outside of it. */
    @Test
    fun writesDoNotFollowSymbolicLinks() = runBlocking {
        val data = randomBytes(1000)
        val source = Metainfo.create(
            "folder",
            listOf(listOf("sub", "payload") to data),
            16384,
            singleFile = false,
        )
        val meta = Metainfo.parse(source)
        val outside = dir()
        val target = dir()
        Files.createDirectories(target.resolve("folder"))
        Files.createSymbolicLink(target.resolve("folder").resolve("sub"), outside)

        val store = PieceStore(meta, target)
        assertFailsWith<java.io.IOException> { store.write(0, data) }
        store.close()
        assertFalse(Files.exists(outside.resolve("payload")), "a file was written through the link")
    }

    @Test
    fun aMagnetLinkFetchesMetadataFromPeersAndThenTheFiles() = runBlocking {
        // Small pieces make for many hashes, so the metadata spans several 16 KiB exchange pieces.
        val data = randomBytes(1_400_000, 21)
        val meta = Metainfo.parse(Metainfo.create("m.bin", listOf(listOf("m.bin") to data), 1024))
        assertTrue(meta.rawInfo.size > 16 * 1024, "metadata should need more than one piece")
        val seeder = client()
        val seed = seeder.add(meta, seedDir(meta, listOf(data)))
        withTimeout(TIMEOUT) { seed.awaitComplete() }

        val target = dir()
        val magnet = Magnet.parse("magnet:?xt=urn:btih:${meta.infoHash.toHex()}")
        val leech = client().addMagnet(magnet, target)
        leech.connect(address(seeder))
        val fetched = withTimeout(TIMEOUT) { leech.awaitMetadata() }
        assertContentEquals(meta.rawInfo, fetched.rawInfo)
        withTimeout(TIMEOUT) { leech.awaitComplete() }
        assertDownloaded(meta, target, listOf(data))
    }

    @Test
    fun metadataWithTheWrongHashIsRejected() = runBlocking {
        val realData = randomBytes(1000, 1)
        val real = Metainfo.parse(Metainfo.create("real.bin", listOf(listOf("real.bin") to realData), 16384))
        val fake = Metainfo.parse(Metainfo.create("fake.bin", listOf(listOf("fake.bin") to ByteArray(9)), 16384))

        val evil = EvilPeer(real.infoHash, greet = { out ->
            val handshake = mapOf("m" to mapOf("ut_metadata" to 1), "metadata_size" to fake.rawInfo.size)
            out(PeerMessage.Extended(0, Bencode.encode(handshake)))
        }) { message, out ->
            if (message is PeerMessage.Extended && message.id == 1) {
                val header = Bencode.encode(mapOf("msg_type" to 1, "piece" to 0, "total_size" to fake.rawInfo.size))
                out(PeerMessage.Extended(1, header + fake.rawInfo))
            }
        }
        closers.add(evil)
        val target = dir()
        val leech = client().addMagnet(Magnet.parse("magnet:?xt=urn:btih:${real.infoHash.toHex()}"), target)
        leech.connect(evil.address)
        withTimeout(TIMEOUT) {
            while (leech.hashFailures == 0) delay(10)
        }
        assertEquals(null, leech.meta, "metadata that fails its hash must not be accepted")
        assertEquals(0, target.toFile().list()!!.size, "nothing should have been written")
    }

    @Test
    fun anAbsurdMetadataSizeIsRefused() = runBlocking {
        // One byte over the limit would otherwise allocate 32 MiB and keep the connection open.
        for (size in listOf((32L shl 20) + 1, Long.MAX_VALUE)) {
            val hash = ByteArray(20) { (size % 100).toByte() }
            val evil = EvilPeer(hash, greet = { out ->
                val handshake = mapOf("m" to mapOf("ut_metadata" to 1), "metadata_size" to size)
                out(PeerMessage.Extended(0, Bencode.encode(handshake)))
            }) { _, _ -> }
            closers.add(evil)
            val magnet = Magnet.parse("magnet:?xt=urn:btih:" + hash.toHex())
            val leech = client().addMagnet(magnet, dir())
            leech.connect(evil.address)
            withTimeout(10_000) {
                while (!evil.disconnected) delay(10)
            }
        }
    }

    @Test
    fun aHttpTrackerSuppliesPeers() = runBlocking {
        val data = randomBytes(64 * 1024, 31)
        val meta = Metainfo.parse(Metainfo.create("t.bin", listOf(listOf("t.bin") to data), 32 * 1024))
        val seeder = client()
        seeder.add(meta, seedDir(meta, listOf(data)))

        var query = ""
        val server = HttpServer.create(InetSocketAddress(loopback, 0), 0)
        server.createContext("/announce") { exchange ->
            query = exchange.requestURI.rawQuery
            val peers = ByteBuffer.allocate(6).put(loopback.address).putShort(seeder.port.toShort()).array()
            val body = Bencode.encode(mapOf("interval" to 60, "peers" to peers))
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        closers.add(AutoCloseable { server.stop(0) })

        val target = dir()
        val leech = client().add(meta, target)
        val url = "http://${loopback.hostAddress}:${server.address.port}/announce"
        val response = leech.announce(url, meta.totalLength, "started")
        assertEquals(60, response.intervalSeconds)
        withTimeout(TIMEOUT) { leech.awaitComplete() }
        assertDownloaded(meta, target, listOf(data))

        // The hash goes as raw percent-encoded bytes, not as text.
        val expected = meta.infoHash.joinToString("") { "%%%02X".format(it) }
        assertTrue("info_hash=$expected" in query, query)
        assertTrue("compact=1" in query && "event=started" in query, query)
    }

    @Test
    fun aUdpTrackerSuppliesPeers() = runBlocking {
        val socket = DatagramSocket(0, loopback)
        closers.add(socket)
        val connectionId = 0x1122334455667788L
        val scope = CoroutineScope(Dispatchers.IO)
        closers.add(AutoCloseable { scope.cancel() })
        scope.launch {
            val buffer = ByteArray(2048)
            while (!socket.isClosed) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: java.io.IOException) {
                    return@launch
                }
                val input = ByteBuffer.wrap(buffer, 0, packet.length)
                val first = input.getLong()
                val action = input.getInt()
                val transaction = input.getInt()
                val reply = if (action == 0) {
                    check(first == 0x41727101980L) { "wrong protocol id" }
                    ByteBuffer.allocate(16).putInt(0).putInt(transaction).putLong(connectionId).array()
                } else {
                    check(first == connectionId) { "wrong connection id" }
                    ByteBuffer.allocate(26)
                        .putInt(1).putInt(transaction).putInt(99).putInt(1).putInt(2)
                        .put(byteArrayOf(1, 2, 3, 4)).putShort(6881.toShort())
                        .array()
                }
                socket.send(DatagramPacket(reply, reply.size, packet.socketAddress))
            }
        }
        val request = TrackerRequest(ByteArray(20), ByteArray(20), 1, 0, 0, 0, "started")
        val response = TrackerClient.announce("udp://${loopback.hostAddress}:${socket.localPort}/announce", request, true)
        assertEquals(99, response.intervalSeconds)
        assertEquals(1, response.peers.size)
        assertEquals("1.2.3.4", response.peers[0].address.hostAddress)
        assertEquals(6881, response.peers[0].port)
    }

    @Test
    fun trackerUrlsPointingAtPrivateHostsAreRefused() = runBlocking {
        val request = TrackerRequest(ByteArray(20), ByteArray(20), 1, 0, 0, 0, null)
        for (url in listOf(
            "http://127.0.0.1/announce",
            "http://localhost/announce",
            "http://169.254.169.254/latest/meta-data",
            "http://10.0.0.1/a",
            "http://192.168.1.1/a",
            "http://172.16.0.1/a",
            "http://[::1]/a",
            "udp://127.0.0.1:80",
        )) {
            val error = assertFailsWith<TrackerError>(url) { TrackerClient.announce(url, request, false) }
            assertTrue("private" in error.message!!, "${error.message} for $url")
        }
        for (url in listOf("file:///etc/passwd", "ftp://example.com/a", "gopher://example.com", "not a url")) {
            assertFailsWith<TrackerError>(url) { TrackerClient.announce(url, request, false) }
        }
    }

    @Test
    fun trackersCannotRedirectAroundThePolicy() = runBlocking {
        var internalHit = false
        val server = HttpServer.create(InetSocketAddress(loopback, 0), 0)
        server.createContext("/announce") { exchange ->
            exchange.responseHeaders.add("Location", "/internal")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.createContext("/internal") { exchange ->
            internalHit = true
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        server.start()
        closers.add(AutoCloseable { server.stop(0) })
        val request = TrackerRequest(ByteArray(20), ByteArray(20), 1, 0, 0, 0, null)
        val url = "http://${loopback.hostAddress}:${server.address.port}/announce"
        assertFailsWith<TrackerError> { TrackerClient.announce(url, request, true) }
        assertFalse(internalHit, "the redirect should not have been followed")
    }

    @Test
    fun hugeTrackerResponsesAreRefused() = runBlocking {
        val server = HttpServer.create(InetSocketAddress(loopback, 0), 0)
        server.createContext("/announce") { exchange ->
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { out ->
                runCatching { repeat(4096) { out.write(ByteArray(1024)) } }
            }
        }
        server.start()
        closers.add(AutoCloseable { server.stop(0) })
        val request = TrackerRequest(ByteArray(20), ByteArray(20), 1, 0, 0, 0, null)
        val url = "http://${loopback.hostAddress}:${server.address.port}/announce"
        val error = assertFailsWith<TrackerError> { TrackerClient.announce(url, request, true) }
        assertTrue("too large" in error.message!!)
    }

    @Test
    fun trackerResponsesParseInBothPeerFormats() {
        val compact = Bencode.encode(mapOf("interval" to 5, "peers" to byteArrayOf(8, 8, 8, 8, 0x1A, 0xE1.toByte())))
        val parsed = TrackerClient.parseHttpResponse(compact)
        assertEquals(5, parsed.intervalSeconds)
        assertEquals(6881, parsed.peers.single().port)

        val listed = Bencode.encode(
            mapOf(
                "peers" to listOf(
                    mapOf("ip" to "8.8.4.4", "port" to 6881),
                    mapOf("ip" to "evil.example.com", "port" to 6881),
                    mapOf("ip" to "8.8.8.8", "port" to 99999),
                ),
            ),
        )
        assertEquals("8.8.4.4", TrackerClient.parseHttpResponse(listed).peers.single().address.hostAddress)

        val failure = Bencode.encode(mapOf("failure reason" to "go away"))
        assertTrue("go away" in assertFailsWith<TrackerError> { TrackerClient.parseHttpResponse(failure) }.message!!)
        assertFailsWith<TrackerError> { TrackerClient.parseHttpResponse("junk".toByteArray()) }
        assertFailsWith<TrackerError> {
            TrackerClient.parseHttpResponse(Bencode.encode(mapOf("peers" to ByteArray(7))))
        }
    }

    @Test
    fun addressPolicyClassifiesAddresses() {
        fun address(text: String) = InetAddress.getByName(text)
        for (safe in listOf("8.8.8.8", "1.1.1.1", "2606:4700:4700::1111")) {
            assertTrue(AddressPolicy.isPublic(address(safe)), safe)
        }
        for (unsafe in listOf(
            "127.0.0.1", "10.1.2.3", "172.16.0.1", "172.31.255.255", "192.168.0.1", "169.254.169.254",
            "0.0.0.0", "100.64.0.1", "224.0.0.1", "240.0.0.1", "255.255.255.255", "198.18.0.1",
            "::1", "fe80::1", "fc00::1", "fd12:3456::1", "::",
        )) {
            assertFalse(AddressPolicy.isPublic(address(unsafe)), unsafe)
        }
        assertFalse(AddressPolicy.isPublic(null))
    }
}

/** A misbehaving peer, scripted by each test. It claims to hold all [pieces] of the torrent. */
private class EvilPeer(
    private val infoHash: ByteArray,
    private val pieces: Int = 0,
    private val unchoke: Boolean = true,
    private val greet: ((PeerMessage) -> Unit) -> Unit = {},
    private val onMessage: (PeerMessage, (PeerMessage) -> Unit) -> Unit,
) : AutoCloseable {
    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    private val scope = CoroutineScope(Dispatchers.IO)

    @Volatile
    var disconnected = false

    val address = InetSocketAddress(InetAddress.getLoopbackAddress(), server.localPort)

    init {
        scope.launch {
            while (!server.isClosed) {
                val socket = try {
                    server.accept()
                } catch (_: java.io.IOException) {
                    return@launch
                }
                scope.launch { serve(socket) }
            }
        }
    }

    private fun serve(socket: Socket) {
        socket.use {
            val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
            val output = socket.getOutputStream()
            val send = { message: PeerMessage ->
                output.write(PeerMessage.encode(message))
                output.flush()
            }
            try {
                val theirs = Handshake.read(input)
                assertNotNull(theirs)
                output.write(Handshake(infoHash, ByteArray(20) { 66 }, true, false).encode())
                output.flush()
                if (pieces > 0) {
                    val all = java.util.BitSet(pieces).apply { set(0, pieces) }
                    send(PeerMessage.Bitfield(all.toWireBitfield(pieces)))
                    if (unchoke) send(PeerMessage.Unchoke)
                }
                greet(send)
                while (true) {
                    onMessage(PeerMessage.read(input), send)
                }
            } catch (_: java.io.IOException) {
                disconnected = true
            }
        }
    }

    override fun close() {
        server.close()
        scope.cancel()
    }
}
