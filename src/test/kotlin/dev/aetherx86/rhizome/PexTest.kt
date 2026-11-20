package dev.aetherx86.rhizome

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.Random
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Peer exchange (BEP 11) between real clients on loopback, and against scripted hostile peers. */
class PexTest {
    private val temp = ArrayList<Path>()
    private val closers = ArrayList<AutoCloseable>()
    private val loopback: InetAddress = InetAddress.getLoopbackAddress()

    @AfterTest
    fun cleanup() {
        for (c in closers.reversed()) runCatching { c.close() }
        for (t in temp) t.toFile().deleteRecursively()
    }

    private fun dir(): Path = Files.createTempDirectory("rhizome-pex").also { temp.add(it) }

    private fun client(pexMillis: Long = 200, allowPrivate: Boolean = true): RhizomeClient =
        RhizomeClient(ClientConfig(allowPrivateAddresses = allowPrivate, pexIntervalMillis = pexMillis)).also { closers.add(it) }

    private fun address(c: RhizomeClient) = InetSocketAddress(loopback, c.port)

    private fun compact(vararg addresses: InetSocketAddress): ByteArray {
        val out = ByteArrayOutputStream()
        for (a in addresses) {
            out.writeBytes(a.address.address)
            out.write(a.port ushr 8)
            out.write(a.port and 0xFF)
        }
        return out.toByteArray()
    }

    /** A listening socket that counts the connections it gets. */
    private fun watcher(): Pair<ServerSocket, AtomicInteger> {
        val server = ServerSocket(0, 50, loopback)
        closers.add(server)
        val count = AtomicInteger()
        thread(isDaemon = true) {
            while (!server.isClosed) {
                runCatching { server.accept().close() }.onSuccess { count.incrementAndGet() }
            }
        }
        return server to count
    }

    private fun torrent(size: Int = 300_000, isPrivate: Boolean = false): Pair<Metainfo, ByteArray> {
        val data = ByteArray(size).also { Random(5).nextBytes(it) }
        val bytes = Metainfo.create("p.bin", listOf(listOf("p.bin") to data), 32 * 1024, isPrivate = isPrivate)
        return Metainfo.parse(bytes) to data
    }

    @Test
    fun peersIntroduceEachOtherAndTheSwarmClosesTheGap() = runBlocking {
        val (meta, data) = torrent()
        val seedRoot = dir()
        Files.write(seedRoot.resolve("p.bin"), data)
        val seeder = client()
        val seed = seeder.add(meta, seedRoot)
        withTimeout(30_000) { seed.awaitComplete() }

        // The first leecher knows the seeder. The second knows only the first leecher.
        val first = client()
        first.add(meta, dir()).connect(address(seeder))
        val target = dir()
        val second = client()
        val leech = second.add(meta, target)
        leech.connect(address(first))

        withTimeout(30_000) {
            while (seed.peerCount < 2 || leech.peerCount < 2) delay(20)
        }
        assertTrue(seed.pexPeersLearned + leech.pexPeersLearned >= 1, "the introduction came through peer exchange")
        withTimeout(30_000) { leech.awaitComplete() }
        assertContentEquals(data, Files.readAllBytes(target.resolve("p.bin")))

        // If both sides dialed each other, exactly one of the two connections survives.
        delay(1_000)
        assertEquals(2, seed.peerCount)
        assertEquals(2, leech.peerCount)
        assertEquals(2, leech.status().peers)
    }

    @Test
    fun theExtensionHandshakeAdvertisesPexAndTheListeningPort() = runBlocking {
        for (isPrivate in listOf(false, true)) {
            val (meta, _) = torrent(1000, isPrivate)
            val handshake = java.util.concurrent.atomic.AtomicReference<BDict?>()
            val evil = EvilPeer(meta.infoHash) { message, _ ->
                if (message is PeerMessage.Extended && message.id == 0) handshake.set(Bencode.decode(message.payload) as BDict)
            }
            closers.add(evil)
            val leecher = client(allowPrivate = false)
            leecher.add(meta, dir()).connect(evil.address)
            withTimeout(10_000) {
                while (handshake.get() == null) delay(10)
            }
            val received = assertNotNull(handshake.get())
            assertEquals(leecher.port.toLong(), received.int("p"))
            assertEquals(1L, received.dict("m")?.int("ut_metadata"))
            if (isPrivate) {
                assertEquals(null, received.dict("m")?.int("ut_pex"), "private torrents must not use peer exchange")
            } else {
                assertEquals(2L, received.dict("m")?.int("ut_pex"))
            }
            assertEquals("Rhizome $RHIZOME_VERSION", received.text("v"))
        }
    }

    @Test
    fun peerExchangeCannotAimTheClientAtPrivateAddresses() = runBlocking {
        val (meta, _) = torrent(1000)
        val (watch, hits) = watcher()
        val payload = Bencode.encode(
            mapOf(
                "added" to compact(
                    InetSocketAddress(loopback, watch.localPort),
                    InetSocketAddress(InetAddress.getByName("10.0.0.1"), 6881),
                    InetSocketAddress(InetAddress.getByName("192.168.1.1"), 80),
                    InetSocketAddress(InetAddress.getByName("169.254.169.254"), 80),
                ),
            ),
        )
        val evil = EvilPeer(meta.infoHash, greet = { out -> out(PeerMessage.Extended(2, payload)) }) { _, _ -> }
        closers.add(evil)
        val leech = client(allowPrivate = false).add(meta, dir())
        leech.connect(evil.address)
        withTimeout(10_000) {
            while (leech.peerCount == 0) delay(10)
        }
        delay(1_000)
        assertEquals(0, hits.get(), "the client connected to a private address it was told about")
        assertEquals(0, leech.pexPeersLearned)
        assertFalse(evil.disconnected, "a list of refused addresses is not a protocol error")
    }

    @Test
    fun oneMessageCanOnlyAddSoManyPeersAndFloodsAreIgnored() = runBlocking {
        val (meta, _) = torrent(1000)
        val listeners = (0 until 120).map { ServerSocket(0, 50, loopback).also { s -> closers.add(s) } }
        fun message(from: Int, to: Int) = Bencode.encode(
            mapOf("added" to compact(*listeners.subList(from, to).map { InetSocketAddress(loopback, it.localPort) }.toTypedArray())),
        )
        val evil = EvilPeer(meta.infoHash, greet = { out ->
            out(PeerMessage.Extended(2, message(0, 100)))
            // A second list straight after the first breaks the one-a-minute rule, and is dropped.
            out(PeerMessage.Extended(2, message(100, 120)))
        }) { _, _ -> }
        closers.add(evil)
        val leech = client(pexMillis = 60_000).add(meta, dir())
        leech.connect(evil.address)
        withTimeout(10_000) {
            while (leech.pexPeersLearned == 0) delay(10)
        }
        delay(500)
        assertEquals(50, leech.pexPeersLearned)
    }

    @Test
    fun aMalformedPeerListClosesTheConnection() = runBlocking {
        val (meta, _) = torrent(1000)
        for (payload in listOf(Bencode.encode(mapOf("added" to ByteArray(7))), "not bencode".toByteArray())) {
            val evil = EvilPeer(meta.infoHash, greet = { out -> out(PeerMessage.Extended(2, payload)) }) { _, _ -> }
            closers.add(evil)
            val leech = client().add(meta, dir())
            leech.connect(evil.address)
            withTimeout(10_000) {
                while (!evil.disconnected) delay(10)
            }
        }
    }

    @Test
    fun aPrivateTorrentStaysOffTheDht() = runBlocking {
        val network = (0 until 6).map {
            DhtNode(0, ByteArray(20).also { id -> Random(200L + it).nextBytes(id) }, true, queryTimeoutMillis = 500)
                .also { node -> closers.add(node) }
        }
        for (joining in network.drop(1)) joining.bootstrap(listOf(network[0].address))
        val own = DhtNode(0, ByteArray(20).also { Random(300).nextBytes(it) }, true, queryTimeoutMillis = 500)
        closers.add(own)
        own.bootstrap(listOf(network[0].address))
        val client = RhizomeClient(ClientConfig(allowPrivateAddresses = true), own).also { closers.add(it) }

        val (open, _) = torrent(1000)
        val (closed, _) = torrent(2000, isPrivate = true)
        client.add(closed, dir()).startDiscovery()
        client.add(open, dir()).startDiscovery()
        withTimeout(30_000) {
            while (network[2].getPeers(open.infoHash).peers.isEmpty()) delay(100)
        }
        assertEquals(0, network[2].getPeers(closed.infoHash).peers.size, "a private torrent was announced on the DHT")
    }

    @Test
    fun aPrivateTorrentIgnoresPeerExchange() = runBlocking {
        val (meta, _) = torrent(1000, isPrivate = true)
        val (watch, hits) = watcher()
        val payload = Bencode.encode(mapOf("added" to compact(InetSocketAddress(loopback, watch.localPort))))
        val evil = EvilPeer(meta.infoHash, greet = { out -> out(PeerMessage.Extended(2, payload)) }) { _, _ -> }
        closers.add(evil)
        val leech = client().add(meta, dir())
        leech.connect(evil.address)
        withTimeout(10_000) {
            while (leech.peerCount == 0) delay(10)
        }
        delay(1_000)
        assertEquals(0, hits.get())
        assertEquals(0, leech.pexPeersLearned)
    }
}
