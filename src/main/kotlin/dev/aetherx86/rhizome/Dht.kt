package dev.aetherx86.rhizome

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** The XOR metric of Kademlia, and the routing structure built on it. */
object Xor {
    /** Number of leading zero bits in `a xor b`, which is 160 when they are equal. */
    fun commonPrefix(a: ByteArray, b: ByteArray): Int {
        for (i in 0 until 20) {
            val x = (a[i].toInt() xor b[i].toInt()) and 0xFF
            if (x != 0) return i * 8 + Integer.numberOfLeadingZeros(x) - 24
        }
        return 160
    }

    /**
     * The bucket for a node, which is the position of the highest set bit of the XOR distance.
     * Returns -1 for an identical id.
     */
    fun bucketIndex(own: ByteArray, other: ByteArray): Int = 159 - commonPrefix(own, other)

    /** Orders ids by distance to [target], comparing the XOR as a big-endian number. */
    fun compareDistance(target: ByteArray, a: ByteArray, b: ByteArray): Int {
        for (i in 0 until 20) {
            val da = (a[i].toInt() xor target[i].toInt()) and 0xFF
            val db = (b[i].toInt() xor target[i].toInt()) and 0xFF
            if (da != db) return da - db
        }
        return 0
    }
}

class Contact(val id: ByteArray, val address: InetSocketAddress) {
    var lastSeen: Long = System.nanoTime()
}

/** Kademlia routing table: 160 buckets of at most [k] contacts each. */
class RoutingTable(private val own: ByteArray, private val k: Int = 8) {
    private val buckets = Array(160) { ArrayList<Contact>() }

    /** Forgets contacts silent this long, but only to make room for a live one. */
    private val staleAfterNanos = 15L * 60 * 1_000_000_000

    @get:Synchronized
    val size: Int get() = buckets.sumOf { it.size }

    @Synchronized
    fun add(id: ByteArray, address: InetSocketAddress, now: Long = System.nanoTime()) {
        val index = Xor.bucketIndex(own, id)
        if (index < 0) return
        val bucket = buckets[index]
        val existing = bucket.firstOrNull { it.id.contentEquals(id) }
        if (existing != null) {
            existing.lastSeen = now
            return
        }
        if (bucket.size >= k) {
            // Long-lived contacts are the most reliable, so a newcomer only replaces a dead one.
            // This also stops a flood of fake ids from pushing out real nodes.
            val oldest = bucket.minBy { it.lastSeen }
            if (now - oldest.lastSeen < staleAfterNanos) return
            bucket.remove(oldest)
        }
        bucket.add(Contact(id, address).also { it.lastSeen = now })
    }

    @Synchronized
    fun contains(id: ByteArray): Boolean {
        val index = Xor.bucketIndex(own, id)
        return index >= 0 && buckets[index].any { it.id.contentEquals(id) }
    }

    @Synchronized
    fun remove(id: ByteArray) {
        val index = Xor.bucketIndex(own, id)
        if (index >= 0) buckets[index].removeAll { it.id.contentEquals(id) }
    }

    @Synchronized
    fun closest(target: ByteArray, count: Int): List<Contact> =
        buckets.flatMap { it }.sortedWith { a, b -> Xor.compareDistance(target, a.id, b.id) }.take(count)

    @Synchronized
    fun bucketSize(index: Int): Int = buckets[index].size
}

class LookupResult(
    val peers: List<InetSocketAddress>,
    /** The closest nodes that answered, with the tokens that allow announcing to them. */
    val closest: List<Pair<Contact, ByteArray>>,
)

/** A node on the Mainline DHT (BEP 5), speaking KRPC over UDP. */
class DhtNode(
    port: Int = 0,
    val id: ByteArray = ByteArray(20).also { SecureRandom().nextBytes(it) },
    private val allowPrivateAddresses: Boolean = false,
    private val queryTimeoutMillis: Long = 1_500,
) : AutoCloseable {
    private class Pending(val address: InetSocketAddress, val result: CompletableDeferred<BDict?>)

    private val socket = DatagramSocket(port)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val random = SecureRandom()
    private val transaction = AtomicInteger(random.nextInt())
    private val pending = ConcurrentHashMap<Int, Pending>()
    val table = RoutingTable(id)

    private val lastVerified = LinkedHashMap<InetSocketAddress, Long>()
    private val sentQueries = AtomicInteger()

    /** Total queries this node has sent, for observing how much traffic it generates. */
    val queriesSent: Int get() = sentQueries.get()

    private val peerStore = HashMap<String, LinkedHashMap<InetSocketAddress, Long>>()
    private var tokenSecret = ByteArray(16).also { random.nextBytes(it) }
    private var previousTokenSecret = tokenSecret
    private var tokenRotatedAt = System.nanoTime()

    val port: Int get() = socket.localPort
    val address: InetSocketAddress get() = InetSocketAddress(InetAddress.getLoopbackAddress(), port)

    init {
        scope.launch { receiveLoop() }
    }

    private fun receiveLoop() {
        val buffer = ByteArray(MAX_PACKET)
        while (!socket.isClosed) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
            } catch (_: java.io.IOException) {
                return
            }
            val from = packet.socketAddress as InetSocketAddress
            val data = buffer.copyOf(packet.length)
            // A malformed packet from a stranger must never take the node down.
            runCatching { handlePacket(data, from) }
        }
    }

    private fun handlePacket(data: ByteArray, from: InetSocketAddress) {
        val message = Bencode.decode(data) as? BDict ?: return
        val tx = message.bytes("t") ?: return
        when (message.text("y")) {
            "q" -> handleQuery(message, tx, from)
            "r" -> resolve(tx, from, message.dict("r"))
            "e" -> resolve(tx, from, null)
        }
    }

    private fun resolve(tx: ByteArray, from: InetSocketAddress, body: BDict?) {
        if (tx.size != 4) return
        val key = ByteBuffer.wrap(tx).getInt()
        val entry = pending[key] ?: return
        // Only the node that was asked can answer, so an attacker has to spoof its address.
        if (entry.address != from) return
        pending.remove(key)
        val responder = body?.bytes("id")
        if (responder == null || responder.size != 20) {
            entry.result.complete(null)
            return
        }
        // A node goes in the routing table only after it answers, because the source address of
        // an incoming query could be forged.
        if (isAllowed(from)) table.add(responder, from)
        entry.result.complete(body)
    }

    private fun isAllowed(address: InetSocketAddress): Boolean =
        address.port in 1..65535 && (allowPrivateAddresses || AddressPolicy.isPublic(address.address))

    private fun send(address: InetSocketAddress, body: Any) {
        val bytes = Bencode.encode(body)
        runCatching { socket.send(DatagramPacket(bytes, bytes.size, address)) }
    }

    private fun reply(tx: ByteArray, to: InetSocketAddress, body: Map<String, Any>) {
        send(to, mapOf("t" to tx, "y" to "r", "r" to body + ("id" to id)))
    }

    private fun replyError(tx: ByteArray, to: InetSocketAddress, code: Int, message: String) {
        send(to, mapOf("t" to tx, "y" to "e", "e" to listOf(code, message)))
    }

    private fun handleQuery(message: BDict, tx: ByteArray, from: InetSocketAddress) {
        val args = message.dict("a")
        val sender = args?.bytes("id")
        if (args == null || sender == null || sender.size != 20) {
            replyError(tx, from, 203, "malformed query")
            return
        }
        when (message.text("q")) {
            "ping" -> reply(tx, from, emptyMap())
            "find_node" -> {
                val target = args.bytes("target")
                if (target == null || target.size != 20) {
                    replyError(tx, from, 203, "malformed target")
                } else {
                    reply(tx, from, mapOf("nodes" to encodeNodes(table.closest(target, K))))
                }
            }
            "get_peers" -> {
                val hash = args.bytes("info_hash")
                if (hash == null || hash.size != 20) {
                    replyError(tx, from, 203, "malformed info_hash")
                    return
                }
                val body = HashMap<String, Any>()
                body["token"] = tokenFor(from.address)
                val peers = storedPeers(hash)
                if (peers.isNotEmpty()) {
                    body["values"] = peers.take(MAX_VALUES).map(::encodePeer)
                }
                body["nodes"] = encodeNodes(table.closest(hash, K))
                reply(tx, from, body)
            }
            "announce_peer" -> handleAnnounce(args, tx, from)
            else -> replyError(tx, from, 204, "unknown method")
        }
        // Learning about nodes that talk to us keeps the table fresh. It only joins the table if
        // it answers this ping, which proves that the source address is real.
        //
        // The ping is itself a query, so it has to happen at most once per address in a while.
        // Otherwise the node it reaches pings back, and the pair trade pings forever.
        if (!table.contains(sender) && shouldVerify(from)) addCandidate(from)
    }

    /** Whether an unknown address is due a verification ping, recording it if so. */
    private fun shouldVerify(address: InetSocketAddress): Boolean {
        if (!isAllowed(address)) return false
        val now = System.nanoTime()
        synchronized(lastVerified) {
            val previous = lastVerified[address]
            if (previous != null && now - previous < VERIFY_INTERVAL_NANOS) return false
            if (lastVerified.size >= MAX_VERIFY_ENTRIES) lastVerified.remove(lastVerified.keys.first())
            lastVerified.remove(address)
            lastVerified[address] = now
        }
        return true
    }

    private fun handleAnnounce(args: BDict, tx: ByteArray, from: InetSocketAddress) {
        val hash = args.bytes("info_hash")
        val token = args.bytes("token")
        if (hash == null || hash.size != 20 || token == null) {
            replyError(tx, from, 203, "malformed announce")
            return
        }
        // The token proves that the sender can receive at the address it claims, so nobody can
        // list a victim's address as a peer to send it unwanted traffic.
        if (!isValidToken(from.address, token)) {
            replyError(tx, from, 203, "bad token")
            return
        }
        val impliedPort = args.int("implied_port") == 1L
        val port = if (impliedPort) from.port.toLong() else args.int("port") ?: 0
        if (port !in 1..65535) {
            replyError(tx, from, 203, "bad port")
            return
        }
        storePeer(hash, InetSocketAddress(from.address, port.toInt()))
        reply(tx, from, emptyMap())
    }

    @Synchronized
    private fun tokenFor(address: InetAddress, secret: ByteArray = currentSecret()): ByteArray =
        sha1(secret + address.address).copyOf(8)

    @Synchronized
    private fun currentSecret(): ByteArray {
        if (System.nanoTime() - tokenRotatedAt > TOKEN_LIFETIME_NANOS) {
            previousTokenSecret = tokenSecret
            tokenSecret = ByteArray(16).also { random.nextBytes(it) }
            tokenRotatedAt = System.nanoTime()
        }
        return tokenSecret
    }

    @Synchronized
    private fun isValidToken(address: InetAddress, token: ByteArray): Boolean {
        val current = tokenFor(address, currentSecret())
        val previous = tokenFor(address, previousTokenSecret)
        return MessageDigest.isEqual(token, current) || MessageDigest.isEqual(token, previous)
    }

    @Synchronized
    private fun storePeer(hash: ByteArray, peer: InetSocketAddress) {
        if (peerStore.size >= MAX_STORED_TORRENTS && hash.toHex() !in peerStore) {
            peerStore.remove(peerStore.keys.first())
        }
        val peers = peerStore.getOrPut(hash.toHex()) { LinkedHashMap() }
        peers.remove(peer)
        peers[peer] = System.nanoTime()
        if (peers.size > MAX_STORED_PEERS) peers.remove(peers.keys.first())
    }

    @Synchronized
    private fun storedPeers(hash: ByteArray): List<InetSocketAddress> {
        val peers = peerStore[hash.toHex()] ?: return emptyList()
        val now = System.nanoTime()
        peers.entries.removeIf { now - it.value > PEER_TTL_NANOS }
        return peers.keys.toList()
    }

    private fun encodePeer(peer: InetSocketAddress): ByteArray =
        ByteBuffer.allocate(6).put(peer.address.address.copyOf(4)).putShort(peer.port.toShort()).array()

    private fun encodeNodes(nodes: List<Contact>): ByteArray {
        val out = ByteBuffer.allocate(nodes.size * 26)
        for (node in nodes) {
            out.put(node.id)
            out.put(node.address.address.address.copyOf(4))
            out.putShort(node.address.port.toShort())
        }
        return out.array().copyOf(out.position())
    }

    private fun decodeNodes(bytes: ByteArray): List<Contact> {
        if (bytes.size % 26 != 0) return emptyList()
        val out = ArrayList<Contact>(bytes.size / 26)
        for (at in bytes.indices step 26) {
            val nodeId = bytes.copyOfRange(at, at + 20)
            val ip = InetAddress.getByAddress(bytes.copyOfRange(at + 20, at + 24))
            val port = ((bytes[at + 24].toInt() and 0xFF) shl 8) or (bytes[at + 25].toInt() and 0xFF)
            val address = InetSocketAddress(ip, port)
            // A hostile node chooses this list, and it must not be able to aim us at private hosts.
            if (isAllowed(address)) out.add(Contact(nodeId, address))
        }
        return out
    }

    /** Sends one query and waits for its answer, returning null on timeout or error. */
    suspend fun query(to: InetSocketAddress, method: String, args: Map<String, Any>): BDict? {
        if (pending.size >= MAX_PENDING) return null
        val key = transaction.incrementAndGet()
        val tx = ByteBuffer.allocate(4).putInt(key).array()
        val entry = Pending(to, CompletableDeferred())
        pending[key] = entry
        sentQueries.incrementAndGet()
        send(to, mapOf("t" to tx, "y" to "q", "q" to method, "a" to args + ("id" to id)))
        try {
            return withTimeoutOrNull(queryTimeoutMillis) { entry.result.await() }
        } finally {
            pending.remove(key)
        }
    }

    suspend fun ping(to: InetSocketAddress): Boolean = query(to, "ping", emptyMap()) != null

    /** Pings an address in the background. It joins the routing table if it answers. */
    fun addCandidate(address: InetSocketAddress) {
        if (!isAllowed(address)) return
        scope.launch { ping(address) }
    }

    /** Joins the network through known nodes, then looks up our own id to find neighbors. */
    suspend fun bootstrap(nodes: List<InetSocketAddress>) {
        coroutineScope {
            nodes.map { async { ping(it) } }.awaitAll()
        }
        findNode(id)
    }

    /** Iterative lookup, returning the closest nodes found to [target]. */
    suspend fun findNode(target: ByteArray): List<Contact> =
        iterate(target, "find_node", mapOf("target" to target)).closest.map { it.first }

    /** Iterative `get_peers`: queries closer and closer nodes, collecting peers as it goes. */
    suspend fun getPeers(infoHash: ByteArray): LookupResult =
        iterate(infoHash, "get_peers", mapOf("info_hash" to infoHash))

    private suspend fun iterate(target: ByteArray, method: String, args: Map<String, Any>): LookupResult {
        val comparator = Comparator<Contact> { a, b -> Xor.compareDistance(target, a.id, b.id) }
        val candidates = java.util.TreeSet<Contact>(comparator)
        candidates.addAll(table.closest(target, K))
        val queried = HashSet<InetSocketAddress>()
        val answered = ArrayList<Pair<Contact, ByteArray>>()
        val peers = LinkedHashSet<InetSocketAddress>()

        var rounds = 0
        while (rounds++ < MAX_ROUNDS) {
            // Takes the closest nodes not yet asked. When the k closest known have all been asked,
            // nothing closer can turn up, and the lookup has converged.
            val nearest = candidates.take(K)
            val batch = nearest.filter { it.address !in queried }.take(ALPHA)
            if (batch.isEmpty()) break
            batch.forEach { queried.add(it.address) }

            val replies = coroutineScope {
                batch.map { contact ->
                    async { contact to query(contact.address, method, args) }
                }.awaitAll()
            }
            for ((contact, body) in replies) {
                if (body == null) {
                    candidates.remove(contact)
                    continue
                }
                body.bytes("token")?.let { answered.add(contact to it) }
                if (method == "find_node") answered.add(contact to ByteArray(0))
                body.list("values")?.items?.forEach { item ->
                    val bytes = (item as? BBytes)?.bytes
                    if (bytes != null && bytes.size == 6) peers.addAll(TrackerClient.parseCompactPeers(bytes))
                }
                body.bytes("nodes")?.let { nodes ->
                    for (node in decodeNodes(nodes)) {
                        if (!node.id.contentEquals(id) && node.address !in queried) candidates.add(node)
                    }
                }
            }
        }
        val sorted = answered.sortedWith { a, b -> comparator.compare(a.first, b.first) }.take(K)
        return LookupResult(peers.toList(), sorted)
    }

    /** Tells the nodes closest to a torrent that this machine has it, using their tokens. */
    suspend fun announce(infoHash: ByteArray, peerPort: Int, lookup: LookupResult? = null): Int {
        val closest = (lookup ?: getPeers(infoHash)).closest
        return coroutineScope {
            closest.map { (contact, token) ->
                async {
                    query(
                        contact.address,
                        "announce_peer",
                        mapOf("info_hash" to infoHash, "port" to peerPort, "token" to token),
                    ) != null
                }
            }.awaitAll().count { it }
        }
    }

    override fun close() {
        socket.close()
        scope.cancel()
    }

    private companion object {
        const val K = 8
        const val ALPHA = 3
        const val MAX_ROUNDS = 64
        const val MAX_PACKET = 2048
        const val MAX_PENDING = 512
        const val MAX_VALUES = 50
        const val MAX_STORED_PEERS = 256
        const val MAX_STORED_TORRENTS = 4096
        const val TOKEN_LIFETIME_NANOS = 5L * 60 * 1_000_000_000
        const val PEER_TTL_NANOS = 30L * 60 * 1_000_000_000
        const val VERIFY_INTERVAL_NANOS = 10L * 60 * 1_000_000_000
        const val MAX_VERIFY_ENTRIES = 8192
    }
}
