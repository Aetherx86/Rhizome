package dev.aetherx86.rhizome

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Routing table upkeep: replacement caches, dead contacts, and bucket refresh (BEP 5). */
class MaintenanceTest {
    private val nodes = ArrayList<AutoCloseable>()
    private val loopback: InetAddress = InetAddress.getLoopbackAddress()
    private val random = Random(7)

    @AfterTest
    fun cleanup() {
        for (node in nodes.reversed()) runCatching { node.close() }
    }

    private fun randomId(): ByteArray = ByteArray(20).also { random.nextBytes(it) }

    private fun idWith(first: Int): ByteArray = ByteArray(20).also { it[0] = first.toByte() }

    private fun fake(port: Int) = InetSocketAddress(loopback, port)

    private fun node(id: ByteArray = randomId(), refreshMillis: Long = 15L * 60 * 1000): DhtNode =
        DhtNode(0, id, true, queryTimeoutMillis = 300, bucketRefreshMillis = refreshMillis).also { nodes.add(it) }

    @Test
    fun randomIdsLandInTheRequestedBucket() {
        val own = randomId()
        for (index in 0 until 160) {
            repeat(25) {
                assertEquals(index, Xor.bucketIndex(own, Xor.randomIdInBucket(own, index, random)), "bucket $index")
            }
        }
        assertFailsWith<IllegalArgumentException> { Xor.randomIdInBucket(own, 160, random) }
        assertFailsWith<IllegalArgumentException> { Xor.randomIdInBucket(own, -1, random) }
    }

    @Test
    fun aFullBucketCachesNewcomersUntilAContactDies() {
        val table = RoutingTable(ByteArray(20), k = 2, staleAfterNanos = 1_000, maxFailures = 2)
        assertEquals(RoutingTable.Insert.ADDED, table.insert(idWith(0x80), fake(1), 0, replaceStale = false))
        assertEquals(RoutingTable.Insert.ADDED, table.insert(idWith(0x81), fake(2), 0, replaceStale = false))
        assertEquals(RoutingTable.Insert.CACHED, table.insert(idWith(0x82), fake(3), 0, replaceStale = false))
        assertEquals(2, table.bucketSize(159))
        assertEquals(1, table.replacementCount(159))

        // A good contact survives one missed query, and the second one proves it gone.
        assertFalse(table.recordFailure(fake(1), 0))
        assertTrue(table.contains(idWith(0x80)))
        assertTrue(table.recordFailure(fake(1), 0))
        assertFalse(table.contains(idWith(0x80)))
        assertTrue(table.contains(idWith(0x82)), "the waiting replacement takes the free place")
        assertEquals(0, table.replacementCount(159))
        assertEquals(2, table.bucketSize(159))
    }

    @Test
    fun aQuestionableContactMakesWayAfterOneMissedQueryOnlyIfSomeoneIsWaiting() {
        val table = RoutingTable(ByteArray(20), k = 2, staleAfterNanos = 1_000, maxFailures = 2)
        table.insert(idWith(0x80), fake(1), 0, replaceStale = false)
        table.insert(idWith(0x81), fake(2), 0, replaceStale = false)
        assertFalse(table.recordFailure(fake(1), 5_000), "nobody is waiting, so one miss is forgiven")
        table.insert(idWith(0x82), fake(3), 5_000, replaceStale = false)
        assertTrue(table.recordFailure(fake(2), 5_000), "questionable, silent, and someone is waiting")
        assertTrue(table.contains(idWith(0x82)))
        assertFalse(table.recordFailure(fake(99), 5_000), "an unknown address changes nothing")
    }

    @Test
    fun anAnswerClearsEarlierFailures() {
        val table = RoutingTable(ByteArray(20), k = 8, staleAfterNanos = 1_000, maxFailures = 2)
        table.insert(idWith(0x80), fake(1), 0, replaceStale = false)
        assertFalse(table.recordFailure(fake(1), 0))
        assertEquals(RoutingTable.Insert.UPDATED, table.insert(idWith(0x80), fake(1), 10, replaceStale = false))
        assertFalse(table.recordFailure(fake(1), 10), "the count restarted when the node answered")
        assertTrue(table.contains(idWith(0x80)))
        assertEquals(RoutingTable.Insert.IGNORED, table.insert(ByteArray(20), fake(2), 10, replaceStale = false))
    }

    @Test
    fun onlyUntouchedBucketsFromTheClosestOccupiedOneOutwardAreRefreshed() {
        val stale = 1_000_000L
        val table = RoutingTable(ByteArray(20), staleAfterNanos = stale)
        assertEquals(emptyList(), table.bucketsToRefresh(System.nanoTime() + 2 * stale), "an empty table has nothing to refresh")

        val near = ByteArray(20).also { it[1] = 0x40 }
        assertEquals(150, Xor.bucketIndex(ByteArray(20), near))
        table.add(idWith(0x80), fake(1))
        table.add(near, fake(2))
        val later = System.nanoTime() + 2 * stale
        val due = table.bucketsToRefresh(later)
        assertEquals((150..159).toSet(), due.toSet())
        assertEquals(setOf(150, 159), due.takeLast(2).toSet(), "the buckets touched most recently come last")

        table.markRefreshed(155, later)
        assertFalse(155 in table.bucketsToRefresh(later))
        assertEquals(9, table.bucketsToRefresh(later).size)
    }

    @Test
    fun maintenancePingsQuietContactsAndEvictsDeadOnes() = runBlocking {
        val a = node(refreshMillis = 300)
        val b = node()
        val c = node()
        val dead = node()
        for (other in listOf(b, c, dead)) assertTrue(a.ping(other.address))
        assertEquals(3, a.table.size)
        dead.close()
        delay(400)

        val first = a.maintain()
        assertTrue(first.refreshed >= 1, "untouched buckets should have been refreshed")
        // The dead contact misses a refresh lookup and then either a second lookup or the ping
        // that follows, so one round is enough to remove it.
        assertEquals(1, first.evicted)
        assertFalse(a.table.contains(dead.id), "a contact that stopped answering is removed")
        assertTrue(a.table.contains(b.id) && a.table.contains(c.id), "contacts that answer stay")
        assertEquals(1, a.evicted)
    }

    @Test
    fun aFullBucketChecksItsQuietestContactBeforeReplacingIt() = runBlocking {
        val a = node(ByteArray(20), refreshMillis = 300)
        // Every id starts with a set top bit, so all of them share bucket 159 of a.
        val members = (0 until 8).map { node(idWith(0x80 + it)) }
        for (member in members) assertTrue(a.ping(member.address))
        assertEquals(8, a.table.bucketSize(159))
        members[0].close()
        delay(400)

        val newcomer = node(idWith(0x90))
        assertTrue(a.ping(newcomer.address))
        withTimeout(5_000) {
            while (!a.table.contains(newcomer.id)) delay(20)
        }
        assertFalse(a.table.contains(members[0].id), "the dead contact made room")
        assertEquals(8, a.table.bucketSize(159))
        assertEquals(1, a.evicted)

        // This time the quietest contact is alive and answers, so it keeps its place.
        val second = node(idWith(0x91))
        assertTrue(a.ping(second.address))
        delay(1_000)
        assertFalse(a.table.contains(second.id), "a live contact is never pushed out")
        assertTrue(members.drop(1).all { a.table.contains(it.id) })
        assertEquals(1, a.table.replacementCount(159))
    }

    @Test
    fun bucketRefreshFindsNodesThatNoLookupAskedFor() = runBlocking {
        val network = (0 until 12).map { node() }
        for (joining in network.drop(1)) joining.bootstrap(listOf(network[0].address))
        for (joining in network) joining.findNode(joining.id)

        // Pinging one node, unlike bootstrapping, looks nothing up, so the table stays at one.
        val searcher = node(refreshMillis = 300)
        assertTrue(searcher.ping(network[0].address))
        assertEquals(1, searcher.table.size)
        delay(400)
        val report = searcher.maintain()
        assertTrue(report.refreshed >= 1)
        assertTrue(searcher.table.size > 1, "refreshing should have found more of the network")
    }

    @Test
    fun maintenanceRunsOnItsOwn() = runBlocking {
        val network = (0 until 6).map { node() }
        for (joining in network.drop(1)) joining.bootstrap(listOf(network[0].address))
        val searcher = node(refreshMillis = 200)
        assertTrue(searcher.ping(network[0].address))
        val job = searcher.startMaintenance(intervalMillis = 250)
        withTimeout(10_000) {
            while (searcher.table.size < 2) delay(50)
        }
        job.cancel()
    }
}
