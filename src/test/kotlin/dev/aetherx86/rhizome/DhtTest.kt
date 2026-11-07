package dev.aetherx86.rhizome

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DhtTest {
    private val nodes = ArrayList<AutoCloseable>()
    private val loopback: InetAddress = InetAddress.getLoopbackAddress()

    @AfterTest
    fun cleanup() {
        for (node in nodes.reversed()) runCatching { node.close() }
    }

    private fun node(id: ByteArray = randomId(), allowPrivate: Boolean = true): DhtNode =
        DhtNode(0, id, allowPrivate, queryTimeoutMillis = 500).also { nodes.add(it) }

    private val random = Random(1)

    private fun randomId(): ByteArray = ByteArray(20).also { random.nextBytes(it) }

    private fun idWith(first: Int): ByteArray = ByteArray(20).also { it[0] = first.toByte() }

    private fun fake(port: Int = 1000) = InetSocketAddress(loopback, port)

    /** A socket that sends raw packets, for checking what a node does with hostile traffic. */
    private fun rawQuery(to: DhtNode, payload: ByteArray): BDict? {
        DatagramSocket().use { socket ->
            socket.soTimeout = 1000
            socket.send(DatagramPacket(payload, payload.size, loopback, to.port))
            val buffer = ByteArray(4096)
            val packet = DatagramPacket(buffer, buffer.size)
            return try {
                socket.receive(packet)
                Bencode.decode(buffer.copyOf(packet.length)) as BDict
            } catch (_: java.net.SocketTimeoutException) {
                null
            }
        }
    }

    @Test
    fun xorDistanceHasTheKademliaProperties() {
        val a = randomId()
        val b = randomId()
        assertEquals(160, Xor.commonPrefix(a, a), "a node is at distance zero from itself")
        assertEquals(Xor.commonPrefix(a, b), Xor.commonPrefix(b, a), "the metric is symmetric")
        assertEquals(0, Xor.compareDistance(a, b, b))
        assertTrue(Xor.compareDistance(a, a, b) < 0, "nothing is closer than the target itself")
    }

    @Test
    fun bucketIndexIsThePositionOfTheHighestDifferingBit() {
        val own = ByteArray(20)
        assertEquals(-1, Xor.bucketIndex(own, own), "an identical id has no bucket")
        assertEquals(159, Xor.bucketIndex(own, idWith(0x80)), "the top bit differs")
        assertEquals(158, Xor.bucketIndex(own, idWith(0x40)))
        assertEquals(152, Xor.bucketIndex(own, idWith(0x01)))
        val low = ByteArray(20).also { it[19] = 1 }
        assertEquals(0, Xor.bucketIndex(own, low), "only the lowest bit differs")
    }

    @Test
    fun aBucketHoldsAtMostKContactsAndPrefersOldOnes() {
        val own = ByteArray(20)
        val table = RoutingTable(own, k = 2)
        val first = idWith(0x80)
        val second = idWith(0x81)
        val third = idWith(0x82)
        val now = System.nanoTime()
        table.add(first, fake(1), now)
        table.add(second, fake(2), now)
        table.add(third, fake(3), now)
        assertEquals(2, table.bucketSize(159))
        val ids = table.closest(first, 8).map { it.id.toList() }
        assertTrue(first.toList() in ids && second.toList() in ids, "the live contacts stay")
        assertFalse(third.toList() in ids, "a newcomer does not push out a live contact")

        // Once a contact has been silent for a long time, a newcomer can replace it.
        table.add(third, fake(3), now + 16L * 60 * 1_000_000_000)
        assertTrue(third.toList() in table.closest(third, 8).map { it.id.toList() })
        assertEquals(2, table.bucketSize(159))
    }

    @Test
    fun theTableNeverStoresItself() {
        val own = randomId()
        val table = RoutingTable(own)
        table.add(own, fake())
        assertEquals(0, table.size)
    }

    @Test
    fun closestSortsByXorNotByNumericValue() {
        val own = ByteArray(20)
        val table = RoutingTable(own)
        val ids = listOf(0x80, 0x40, 0xC0, 0x01).map { idWith(it) }
        for ((i, id) in ids.withIndex()) table.add(id, fake(i + 1))
        val target = idWith(0xC1)
        // Distance to 0xC1: 0xC0 is 0x01, 0x80 is 0x41, 0x40 is 0x81, 0x01 is 0xC0.
        assertEquals(
            listOf(0xC0, 0x80, 0x40, 0x01),
            table.closest(target, 4).map { it.id[0].toInt() and 0xFF },
        )
    }

    @Test
    fun pingAddsANodeOnlyWhenItAnswers() = runBlocking {
        val a = node()
        val b = node()
        assertTrue(a.ping(b.address))
        assertEquals(1, a.table.size)
        assertContentEquals(b.id, a.table.closest(b.id, 1).single().id)

        val silent = DatagramSocket(0, loopback)
        nodes.add(silent)
        assertFalse(a.ping(InetSocketAddress(loopback, silent.localPort)))
        assertEquals(1, a.table.size, "no answer means it does not join the table")
    }

    @Test
    fun aQueryDoesNotPutTheSenderInTheTableUntilItProvesItsAddress() = runBlocking {
        val a = node()
        // A forged query: this socket never answers, as a spoofed source address would not.
        val query = Bencode.encode(
            mapOf("t" to "aa", "y" to "q", "q" to "ping", "a" to mapOf("id" to ByteArray(20) { 9 })),
        )
        val reply = rawQuery(a, query)
        assertNotNull(reply)
        delay(800)
        assertEquals(0, a.table.size, "an unverified sender must not join the routing table")

        // A real node that answers the verification ping does join.
        val b = node()
        b.ping(a.address)
        withTimeout(5000) {
            while (a.table.size == 0) delay(10)
        }
        assertContentEquals(b.id, a.table.closest(b.id, 1).single().id)
    }

    /**
     * A verification ping is a query, which can itself trigger a verification ping from the other
     * side. If that is not bounded, two nodes trade pings forever after a single contact.
     */
    @Test
    fun twoNodesDoNotPingEachOtherForever() = runBlocking {
        val a = node()
        val b = node()
        assertTrue(a.ping(b.address))
        delay(300)
        val settled = a.queriesSent + b.queriesSent
        assertTrue(settled <= 4, "a single contact produced $settled queries")
        delay(500)
        assertEquals(settled, a.queriesSent + b.queriesSent, "the nodes should fall silent")
        assertEquals(1, a.table.size)
        assertEquals(1, b.table.size, "both should have learned about each other")
    }

    @Test
    fun aStrangerIsVerifiedOnlyOnceNoMatterHowOftenItAsks() = runBlocking {
        val a = node()
        DatagramSocket(0, loopback).use { socket ->
            socket.soTimeout = 300
            val query = Bencode.encode(
                mapOf("t" to "aa", "y" to "q", "q" to "ping", "a" to mapOf("id" to ByteArray(20) { 8 })),
            )
            repeat(20) { socket.send(DatagramPacket(query, query.size, loopback, a.port)) }
            delay(300)
            var queries = 0
            val buffer = ByteArray(2048)
            try {
                while (true) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    if ((Bencode.decode(buffer.copyOf(packet.length)) as BDict).text("y") == "q") queries++
                }
            } catch (_: java.net.SocketTimeoutException) {
                // Everything has been read.
            }
            assertEquals(1, queries, "one verification ping, not one for each query")
        }
    }

    @Test
    fun anIdleNetworkFallsSilent() = runBlocking {
        val all = network(10)
        delay(500)
        val before = all.sumOf { it.queriesSent }
        delay(1000)
        assertEquals(before, all.sumOf { it.queriesSent }, "an idle network kept sending queries")
    }

    @Test
    fun repliesFromTheWrongAddressAreIgnored() = runBlocking {
        val a = node()
        val target = DatagramSocket(0, loopback)
        val attacker = DatagramSocket(0, loopback)
        nodes.add(target)
        nodes.add(attacker)
        val result = async(Dispatchers.IO) {
            a.query(InetSocketAddress(loopback, target.localPort), "ping", emptyMap())
        }
        val buffer = ByteArray(2048)
        val packet = DatagramPacket(buffer, buffer.size)
        target.soTimeout = 2000
        target.receive(packet)
        val query = Bencode.decode(buffer.copyOf(packet.length)) as BDict
        val forged = Bencode.encode(
            mapOf("t" to query.bytes("t")!!, "y" to "r", "r" to mapOf("id" to ByteArray(20) { 7 })),
        )
        // The attacker guessed the transaction id, but it is not who was asked.
        attacker.send(DatagramPacket(forged, forged.size, loopback, a.port))
        assertNull(result.await(), "a forged reply must not satisfy the query")
        assertEquals(0, a.table.size)
    }

    @Test
    fun malformedPacketsAreSurvived() = runBlocking {
        val a = node()
        val random = Random(4)
        DatagramSocket().use { socket ->
            val garbage = listOf(
                ByteArray(0),
                "not bencode".toByteArray(),
                "de".toByteArray(),
                "i1e".toByteArray(),
                "l".repeat(1500).toByteArray(),
                Bencode.encode(mapOf("y" to "q")),
                Bencode.encode(mapOf("t" to "x", "y" to "r", "r" to "not a dict")),
                Bencode.encode(mapOf("t" to "xxxx", "y" to "r", "r" to mapOf("id" to "short"))),
                Bencode.encode(mapOf("t" to "x", "y" to "q", "q" to 5, "a" to 7)),
            ) + (0 until 300).map { ByteArray(random.nextInt(200)).also(random::nextBytes) }
            for (packet in garbage) {
                socket.send(DatagramPacket(packet, packet.size, loopback, a.port))
            }
        }
        delay(200)
        val b = node()
        assertTrue(a.ping(b.address), "the node should still work after all that")
    }

    @Test
    fun malformedQueriesGetProtocolErrors() {
        val a = node()
        val cases = listOf(
            mapOf("t" to "aa", "y" to "q", "q" to "ping"),
            mapOf("t" to "aa", "y" to "q", "q" to "ping", "a" to mapOf("id" to "short")),
            mapOf("t" to "aa", "y" to "q", "q" to "find_node", "a" to mapOf("id" to ByteArray(20))),
            mapOf("t" to "aa", "y" to "q", "q" to "find_node", "a" to mapOf("id" to ByteArray(20), "target" to "short")),
            mapOf("t" to "aa", "y" to "q", "q" to "get_peers", "a" to mapOf("id" to ByteArray(20))),
            mapOf("t" to "aa", "y" to "q", "q" to "announce_peer", "a" to mapOf("id" to ByteArray(20))),
        )
        for (case in cases) {
            val reply = rawQuery(a, Bencode.encode(case))
            assertEquals("e", reply?.text("y"), "for $case")
            assertEquals(203L, (reply?.list("e")?.items?.get(0) as BInt).value)
        }
        val unknown = rawQuery(
            a,
            Bencode.encode(mapOf("t" to "aa", "y" to "q", "q" to "vote", "a" to mapOf("id" to ByteArray(20)))),
        )
        assertEquals(204L, (unknown?.list("e")?.items?.get(0) as BInt).value)
    }

    @Test
    fun announceRequiresAValidToken() = runBlocking {
        val a = node()
        val hash = ByteArray(20) { 5 }
        val id = ByteArray(20) { 6 }
        val forged = rawQuery(
            a,
            Bencode.encode(
                mapOf(
                    "t" to "aa",
                    "y" to "q",
                    "q" to "announce_peer",
                    "a" to mapOf("id" to id, "info_hash" to hash, "port" to 7777, "token" to "made-up"),
                ),
            ),
        )
        assertEquals("e", forged?.text("y"))

        val client = node()
        val before = client.getPeers(hash)
        assertEquals(0, before.peers.size)
        val listen = rawQuery(
            a,
            Bencode.encode(mapOf("t" to "aa", "y" to "q", "q" to "get_peers", "a" to mapOf("id" to id, "info_hash" to hash))),
        )
        val token = listen?.dict("r")?.bytes("token")
        assertNotNull(token)
        assertEquals(8, token.size)
        // A token works from the address it was issued to, since both packets come from loopback.
        val good = rawQuery(
            a,
            Bencode.encode(
                mapOf(
                    "t" to "ab",
                    "y" to "q",
                    "q" to "announce_peer",
                    "a" to mapOf("id" to id, "info_hash" to hash, "port" to 7777, "token" to token),
                ),
            ),
        )
        assertEquals("r", good?.text("y"))

        client.ping(a.address)
        val after = client.getPeers(hash)
        assertEquals(listOf(7777), after.peers.map { it.port })
    }

    @Test
    fun announcedPortsAreValidated() {
        val a = node()
        val hash = ByteArray(20) { 5 }
        val id = ByteArray(20) { 6 }
        val token = rawQuery(
            a,
            Bencode.encode(mapOf("t" to "aa", "y" to "q", "q" to "get_peers", "a" to mapOf("id" to id, "info_hash" to hash))),
        )!!.dict("r")!!.bytes("token")!!
        for (port in listOf(0, -1, 70000)) {
            val reply = rawQuery(
                a,
                Bencode.encode(
                    mapOf(
                        "t" to "ab",
                        "y" to "q",
                        "q" to "announce_peer",
                        "a" to mapOf("id" to id, "info_hash" to hash, "port" to port, "token" to token),
                    ),
                ),
            )
            assertEquals("e", reply?.text("y"), "port $port")
        }
    }

    @Test
    fun aHostileNodeCannotAimLookupsAtPrivateAddresses() = runBlocking {
        // This node forbids private addresses, as a real deployment does by default. The hostile
        // node gets into its table directly, standing in for a public node that turned bad.
        val strict = node(allowPrivate = false)
        val hostile = DatagramSocket(0, loopback)
        nodes.add(hostile)
        val victim = DatagramSocket(0, loopback)
        nodes.add(victim)
        victim.soTimeout = 300
        strict.table.add(ByteArray(20) { 2 }, InetSocketAddress(loopback, hostile.localPort))

        val lookup = async(Dispatchers.IO) { strict.getPeers(ByteArray(20)) }
        val buffer = ByteArray(2048)
        val packet = DatagramPacket(buffer, buffer.size)
        hostile.soTimeout = 2000
        hostile.receive(packet)
        val query = Bencode.decode(buffer.copyOf(packet.length)) as BDict

        // It answers by naming the victim as a node that is very close to the target.
        val entry = java.nio.ByteBuffer.allocate(26)
            .put(ByteArray(20) { 1 })
            .put(loopback.address)
            .putShort(victim.localPort.toShort())
            .array()
        val reply = Bencode.encode(
            mapOf(
                "t" to query.bytes("t")!!,
                "y" to "r",
                "r" to mapOf("id" to ByteArray(20) { 2 }, "nodes" to entry, "token" to "tt"),
            ),
        )
        hostile.send(DatagramPacket(reply, reply.size, packet.socketAddress))
        lookup.await()

        val received = runCatching { victim.receive(DatagramPacket(buffer, buffer.size)) }
        assertTrue(received.isFailure, "the node sent a packet to a private address it was told about")
    }

    /** Builds a real network on loopback, in which each node starts knowing only the first. */
    private suspend fun network(size: Int): List<DhtNode> {
        val all = (0 until size).map { node() }
        for (joining in all.drop(1)) joining.bootstrap(listOf(all[0].address))
        // A second pass lets early joiners hear about the later ones.
        for (joining in all) joining.findNode(joining.id)
        return all
    }

    @Test
    fun iterativeLookupFindsNodesItHadNeverHeardOf() = runBlocking {
        val all = network(30)
        val searcher = node()
        searcher.bootstrap(listOf(all[0].address))
        val target = randomId()

        val found = searcher.findNode(target)
        assertTrue(found.size >= 4, "found only ${found.size}")

        // The lookup must converge on the truly closest node in the whole network.
        val expected = all.minWith { a, b -> Xor.compareDistance(target, a.id, b.id) }
        assertContentEquals(expected.id, found.first().id, "did not reach the closest node")
        assertTrue(found.size <= 8)
    }

    @Test
    fun announcedPeersCanBeFoundFromAcrossTheNetwork() = runBlocking {
        val all = network(30)
        val hash = randomId()
        val announced = all[5].announce(hash, 51413)
        assertTrue(announced > 0, "nobody accepted the announce")

        val result = all[20].getPeers(hash)
        assertEquals(listOf(51413), result.peers.map { it.port }.distinct())
        assertTrue(result.closest.isNotEmpty())
    }

    @Test
    fun aLookupForAnUnknownTorrentFindsNoPeers() = runBlocking {
        val all = network(10)
        assertEquals(0, all[3].getPeers(randomId()).peers.size)
    }

    @Test
    fun aLookupSurvivesDeadNodes() = runBlocking {
        val all = network(20)
        val hash = randomId()
        all[1].announce(hash, 6000)
        for (i in 10 until 15) all[i].close()
        val result = all[2].getPeers(hash)
        assertTrue(result.peers.isNotEmpty(), "the lookup should route around the dead nodes")
    }

    @Test
    fun aLookupWithAnEmptyTableReturnsAtOnce() = runBlocking {
        val lonely = node()
        val result = withTimeout(2000) { lonely.getPeers(randomId()) }
        assertEquals(0, result.peers.size)
    }

    /** All the way up: two clients with no address for each other meet through the DHT. */
    @Test
    fun clientsDiscoverEachOtherThroughTheDhtAndTransferAFile() = runBlocking {
        val network = network(10)
        val data = ByteArray(200_000).also { Random(3).nextBytes(it) }
        val meta = Metainfo.parse(Metainfo.create("d.bin", listOf(listOf("d.bin") to data), 32 * 1024))

        val seedRoot = Files.createTempDirectory("rhizome-dht")
        val leechRoot = Files.createTempDirectory("rhizome-dht")
        try {
            Files.write(seedRoot.resolve("d.bin"), data)
            val seedNode = node().also { it.bootstrap(listOf(network[0].address)) }
            val seeder = RhizomeClient(ClientConfig(allowPrivateAddresses = true), seedNode).also { nodes.add(it) }
            val seed = seeder.add(meta, seedRoot)
            withTimeout(30_000) { seed.awaitComplete() }
            assertTrue(seedNode.announce(meta.infoHash, seeder.port) > 0)

            val leechNode = node().also { it.bootstrap(listOf(network[1].address)) }
            val leecher = RhizomeClient(ClientConfig(allowPrivateAddresses = true), leechNode).also { nodes.add(it) }
            val leech = leecher.add(meta, leechRoot)
            leech.startDiscovery()
            withTimeout(30_000) { leech.awaitComplete() }
            assertContentEquals(data, Files.readAllBytes(leechRoot.resolve("d.bin")))
        } finally {
            seedRoot.toFile().deleteRecursively()
            leechRoot.toFile().deleteRecursively()
        }
    }
}
