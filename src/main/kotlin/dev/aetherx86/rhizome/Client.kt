package dev.aetherx86.rhizome

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.BitSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class ClientConfig(
    val port: Int = 0,
    val maxPeersPerTorrent: Int = 50,
    val maxUnchoked: Int = 8,
    val connectTimeoutMillis: Int = 5_000,
    val idleTimeoutMillis: Int = 120_000,
    /** Permits trackers, peers and DHT nodes on private addresses. See [AddressPolicy]. */
    val allowPrivateAddresses: Boolean = false,
    val chokeRotationMillis: Long = 10_000,
    /** How often peers are told about other peers (BEP 11). The extension asks for at most once a minute. */
    val pexIntervalMillis: Long = 60_000,
)

/** Sliding window of outstanding requests per peer. One at a time would cap speed by latency. */
private const val MAX_IN_FLIGHT = 16
private const val MAX_PENDING_UPLOADS = 512
private const val MAX_STRIKES = 3
private const val METADATA_PIECE_SIZE = 16 * 1024
private const val MAX_METADATA_SIZE = 32 shl 20
private const val OUR_UT_METADATA_ID = 1
private const val OUR_UT_PEX_ID = 2

/** The most peers taken from, or put in, either list of one peer exchange message (BEP 11). */
private const val MAX_PEX_PEERS = 50
private const val PEX_FLAG_SEED = 0x02
private const val PEX_FLAG_REACHABLE = 0x10

/** A snapshot of a torrent's progress, for display and for `--json` output. */
class SessionStatus(
    val infoHash: String,
    /** Null until the metadata of a magnet link has arrived. */
    val name: String?,
    /** One of `metadata`, `downloading` or `seeding`. */
    val state: String,
    val piecesVerified: Int,
    val pieceCount: Int,
    val progress: Double,
    val peers: Int,
    val downloaded: Long,
    val uploaded: Long,
    val hashFailures: Int,
    val pexPeersLearned: Int,
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "info_hash" to infoHash,
        "name" to name,
        "state" to state,
        "pieces_verified" to piecesVerified,
        "piece_count" to pieceCount,
        "progress" to progress,
        "peers" to peers,
        "downloaded" to downloaded,
        "uploaded" to uploaded,
        "hash_failures" to hashFailures,
        "pex_peers_learned" to pexPeersLearned,
    )
}

/** A running BitTorrent client, listening for peers on one port. */
class RhizomeClient(val config: ClientConfig = ClientConfig(), val dht: DhtNode? = null) : AutoCloseable {
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val server = ServerSocket(config.port)
    private val sessions = ConcurrentHashMap<String, TorrentSession>()
    val peerId: ByteArray = "-RZ0200-".toByteArray() + ByteArray(12).also { SecureRandom().nextBytes(it) }
    val port: Int get() = server.localPort

    init {
        scope.launch { acceptLoop() }
        scope.launch {
            while (isActive) {
                delay(60_000)
                for (session in sessions.values) session.sendKeepAlives()
            }
        }
    }

    private suspend fun acceptLoop() {
        while (scope.isActive && !server.isClosed) {
            val socket = try {
                server.accept()
            } catch (_: java.io.IOException) {
                return
            }
            scope.launch { serveInbound(socket) }
        }
    }

    private suspend fun serveInbound(socket: Socket) {
        try {
            // An unauthenticated stranger has a short time to prove it is a real peer.
            socket.soTimeout = config.connectTimeoutMillis
            val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
            val handshake = Handshake.read(input)
            val session = sessions[handshake.infoHash.toHex()]
            if (session == null) {
                socket.close()
                return
            }
            session.runPeer(socket, input, handshake, weInitiated = false)
        } catch (_: java.io.IOException) {
            runCatching { socket.close() }
        }
    }

    /** Starts a torrent from its metadata. Existing files are verified and resumed. */
    fun add(meta: Metainfo, directory: Path): TorrentSession {
        val session = TorrentSession(this, meta.infoHash, directory, meta)
        check(sessions.putIfAbsent(meta.infoHash.toHex(), session) == null) { "this torrent is already added" }
        session.start()
        return session
    }

    /** Starts a torrent from a magnet link. Metadata is fetched from peers and then verified. */
    fun addMagnet(magnet: Magnet, directory: Path): TorrentSession {
        val session = TorrentSession(this, magnet.infoHash, directory, null, magnet.trackers)
        check(sessions.putIfAbsent(magnet.infoHash.toHex(), session) == null) { "this torrent is already added" }
        session.start()
        return session
    }

    override fun close() {
        runCatching { server.close() }
        for (session in sessions.values) session.close()
        scope.cancel()
    }
}

internal class PeerConnection(
    val socket: Socket,
    val remoteId: ByteArray,
    val supportsExtensions: Boolean,
    val weInitiated: Boolean,
) {
    /**
     * Where this peer accepts connections: the address we dialed, or for a peer that dialed us,
     * its address with the port from its extension handshake. Only these are shared through PEX.
     */
    var listenAddress: InetSocketAddress? =
        if (weInitiated) socket.remoteSocketAddress as? InetSocketAddress else null
    var peerUtPexId = 0
    val pexAdvertised = HashSet<InetSocketAddress>()
    var lastPexReceived = 0L

    val outgoing = Channel<ByteArray>(1024)
    var amChoking = true
    var amInterested = false
    var peerChoking = true
    var peerInterested = false
    val has = BitSet()
    var receivedBitfield = false
    var pendingBitfield: ByteArray? = null
    val inFlight = HashSet<PiecePicker.Block>()
    val pendingUploads = AtomicInteger()
    var strikes = 0
    var peerUtMetadataId = 0
    var unchokedAt = 0L

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun Channel<ByteArray>.tryPeek(): Boolean = !isEmpty

    fun send(message: PeerMessage) {
        // Control traffic never waits. A peer too slow to drain this much is dropped.
        if (outgoing.trySend(PeerMessage.encode(message)).isFailure) close()
    }

    fun close() {
        outgoing.close()
        runCatching { socket.close() }
    }
}

/** One torrent, and all the peers exchanging it. Shared state is guarded by [lock]. */
class TorrentSession internal constructor(
    private val client: RhizomeClient,
    val infoHash: ByteArray,
    private val directory: Path,
    initialMeta: Metainfo?,
    private val extraTrackers: List<String> = emptyList(),
) : AutoCloseable {
    private val lock = Mutex()
    private val peers = LinkedHashSet<PeerConnection>()
    private val connecting = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    var meta: Metainfo? = initialMeta
        private set
    private var store: PieceStore? = null
    private var picker: PiecePicker? = null

    /** Pieces safely on disk. Only these are ever served, so peers cannot read a half-written piece. */
    private val servable = BitSet()

    private var metadataSize = 0
    private var metadataBuffer: ByteArray? = null
    private var metadataMissing: BitSet? = null

    private val completed = CompletableDeferred<Unit>()
    private val metadataReady = CompletableDeferred<Metainfo>()

    private val downloadedBytes = AtomicLong()
    private val uploadedBytes = AtomicLong()
    private val rejectedPieces = AtomicInteger()
    private val pexLearned = AtomicInteger()

    val downloaded: Long get() = downloadedBytes.get()
    val uploaded: Long get() = uploadedBytes.get()

    /** How many pieces failed their hash check. */
    val hashFailures: Int get() = rejectedPieces.get()

    val isComplete: Boolean get() = completed.isCompleted

    val peerCount: Int get() = peers.size

    /** Peers this torrent was told about through peer exchange and then tried to connect to. */
    val pexPeersLearned: Int get() = pexLearned.get()

    /** A snapshot of where the torrent stands. */
    fun status(): SessionStatus {
        val current = meta
        val verified = servable.cardinality()
        val state = when {
            current == null -> "metadata"
            isComplete -> "seeding"
            else -> "downloading"
        }
        return SessionStatus(
            infoHash.toHex(), current?.name, state, verified, current?.pieceCount ?: 0, progress(),
            peerCount, downloaded, uploaded, hashFailures, pexPeersLearned,
        )
    }

    suspend fun awaitMetadata(): Metainfo = metadataReady.await()

    suspend fun awaitComplete() = completed.await()

    /** Fraction of pieces verified and on disk, from 0 to 1. */
    fun progress(): Double {
        val current = meta ?: return 0.0
        return servable.cardinality().toDouble() / current.pieceCount
    }

    internal fun start() {
        client.scope.launch {
            meta?.let { current -> lock.withLock { initFromMetadata(current) } }
            rotateChokes()
        }
        client.scope.launch { pexLoop() }
    }

    /** Must be called with the lock held. */
    private fun initFromMetadata(current: Metainfo) {
        val newStore = PieceStore(current, directory)
        newStore.createEmptyFiles()
        val verified = newStore.verifyExisting()
        store = newStore
        servable.or(verified)
        val newPicker = PiecePicker(current, verified)
        picker = newPicker
        meta = current
        metadataReady.complete(current)
        if (newPicker.isComplete) completed.complete(Unit)

        // Peers connected before the metadata arrived can now be understood.
        for (peer in peers.toList()) {
            try {
                peer.pendingBitfield?.let { bits ->
                    peer.has.or(parseWireBitfield(bits, current.pieceCount))
                    peer.pendingBitfield = null
                }
                if (peer.has.length() > current.pieceCount) throw ProtocolError("a peer has a piece that does not exist")
                newPicker.addAvailability(peer.has)
                peer.send(PeerMessage.Bitfield(servable.toWireBitfield(current.pieceCount)))
                updateInterest(peer)
                fill(peer)
            } catch (_: ProtocolError) {
                peer.close()
            }
        }
    }

    /** Connects to a peer. Duplicate addresses and connections over the limit are ignored. */
    fun connect(address: InetSocketAddress) {
        val key = address.toString()
        if (peers.size >= client.config.maxPeersPerTorrent || !connecting.add(key)) return
        client.scope.launch {
            val socket = Socket()
            try {
                if (lock.withLock { peers.any { it.listenAddress == address } }) return@launch
                socket.connect(address, client.config.connectTimeoutMillis)
                socket.soTimeout = client.config.connectTimeoutMillis
                socket.getOutputStream().write(ownHandshake().encode())
                socket.getOutputStream().flush()
                val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
                val theirs = Handshake.read(input)
                if (!MessageDigest.isEqual(theirs.infoHash, infoHash)) {
                    throw ProtocolError("the peer answered for a different torrent")
                }
                runPeer(socket, input, theirs, weInitiated = true)
            } catch (_: java.io.IOException) {
                runCatching { socket.close() }
            } finally {
                connecting.remove(key)
            }
        }
    }

    private fun ownHandshake() = Handshake(infoHash, client.peerId, true, client.dht != null)

    internal suspend fun runPeer(
        socket: Socket,
        input: DataInputStream,
        theirs: Handshake,
        weInitiated: Boolean,
    ) {
        if (theirs.peerId.contentEquals(client.peerId)) {
            socket.close()
            return
        }
        socket.tcpNoDelay = true
        socket.soTimeout = client.config.idleTimeoutMillis
        val peer = PeerConnection(socket, theirs.peerId, theirs.supportsExtensions, weInitiated)
        val writer = client.scope.launch {
            val output = BufferedOutputStream(socket.getOutputStream())
            try {
                if (!weInitiated) output.write(ownHandshake().encode())
                while (true) {
                    val bytes = peer.outgoing.receiveCatching().getOrNull() ?: break
                    output.write(bytes)
                    // Coalesces writes, flushing only once nothing else is waiting to go out.
                    if (!peer.run { outgoing.tryPeek() }) output.flush()
                }
            } catch (_: java.io.IOException) {
                // The read side notices the broken socket and cleans up.
            } finally {
                runCatching { socket.close() }
            }
        }

        val accepted = lock.withLock {
            val duplicate = peers.firstOrNull { it.remoteId.contentEquals(peer.remoteId) }
            if (duplicate != null && !replaces(peer, duplicate)) {
                false
            } else {
                duplicate?.let {
                    disconnect(it)
                    it.close()
                }
                if (peers.size >= client.config.maxPeersPerTorrent) {
                    false
                } else {
                    peers.add(peer)
                    true
                }
            }
        }
        if (!accepted) {
            peer.close()
            writer.cancel()
            return
        }

        try {
            lock.withLock {
                if (peer.supportsExtensions) sendExtensionHandshake(peer)
                val current = meta
                if (current != null && !servable.isEmpty) {
                    peer.send(PeerMessage.Bitfield(servable.toWireBitfield(current.pieceCount)))
                }
            }
            while (true) {
                val message = PeerMessage.read(input)
                handle(peer, message)
            }
        } catch (_: java.io.IOException) {
            // Normal end of a connection, or a peer that broke the protocol.
        } catch (_: java.io.EOFException) {
            // The peer closed the connection.
        } catch (e: CancellationException) {
            throw e
        } catch (_: RuntimeException) {
            // Peers are untrusted, so a bug reached through their input costs one connection.
        } finally {
            lock.withLock { disconnect(peer) }
            peer.close()
        }
    }

    /**
     * Two peers that dial each other at the same moment, which peer exchange makes likely, end up
     * with two connections. Both sides keep the one opened by the peer with the higher id, so they
     * agree on which one to drop without having to talk about it.
     */
    private fun replaces(newer: PeerConnection, existing: PeerConnection): Boolean {
        if (newer.weInitiated == existing.weInitiated) return false
        val weAreHigher = java.util.Arrays.compareUnsigned(client.peerId, newer.remoteId) > 0
        return newer.weInitiated == weAreHigher
    }

    private fun disconnect(peer: PeerConnection) {
        if (!peers.remove(peer)) return
        val currentPicker = picker
        if (currentPicker != null) {
            for (block in peer.inFlight) currentPicker.release(block)
            currentPicker.removeAvailability(peer.has)
        }
        peer.inFlight.clear()
    }

    private fun sendExtensionHandshake(peer: PeerConnection) {
        val body = HashMap<String, Any>()
        val extensions = HashMap<String, Any>()
        extensions["ut_metadata"] = OUR_UT_METADATA_ID
        if (pexAllowed()) extensions["ut_pex"] = OUR_UT_PEX_ID
        body["m"] = extensions
        body["p"] = client.port
        body["v"] = "Rhizome $RHIZOME_VERSION"
        meta?.let { body["metadata_size"] = it.rawInfo.size }
        peer.send(PeerMessage.Extended(0, Bencode.encode(body)))
    }

    private fun checkPiece(piece: Int) {
        val current = meta ?: return
        if (piece !in 0 until current.pieceCount) throw ProtocolError("piece $piece does not exist")
    }

    private suspend fun handle(peer: PeerConnection, message: PeerMessage) {
        when (message) {
            PeerMessage.KeepAlive -> {}
            is PeerMessage.Unknown -> {}
            is PeerMessage.Port -> if (message.port != 0) {
                val host = (peer.socket.remoteSocketAddress as? InetSocketAddress)?.address
                if (host != null) client.dht?.addCandidate(InetSocketAddress(host, message.port))
            }
            PeerMessage.Choke -> lock.withLock {
                peer.peerChoking = true
                // A choke discards what was outstanding, so another peer can take those blocks.
                val currentPicker = picker
                if (currentPicker != null) for (block in peer.inFlight) currentPicker.release(block)
                peer.inFlight.clear()
            }
            PeerMessage.Unchoke -> lock.withLock {
                peer.peerChoking = false
                fill(peer)
            }
            PeerMessage.Interested -> lock.withLock {
                peer.peerInterested = true
                tryUnchoke(peer)
            }
            PeerMessage.NotInterested -> lock.withLock {
                peer.peerInterested = false
                if (!peer.amChoking) {
                    peer.amChoking = true
                    peer.send(PeerMessage.Choke)
                    unchokeWaiters()
                }
            }
            is PeerMessage.Have -> lock.withLock {
                checkPiece(message.piece)
                if (message.piece < 0) throw ProtocolError("a negative piece index")
                if (!peer.has[message.piece]) {
                    peer.has.set(message.piece)
                    picker?.addAvailability(message.piece)
                    updateInterest(peer)
                    fill(peer)
                }
            }
            is PeerMessage.Bitfield -> lock.withLock {
                if (peer.receivedBitfield) throw ProtocolError("a peer sent a second bitfield")
                peer.receivedBitfield = true
                val current = meta
                if (current == null) {
                    if (message.bits.size > MAX_METADATA_SIZE) throw ProtocolError("a bitfield is too large")
                    peer.pendingBitfield = message.bits
                } else {
                    val parsed = parseWireBitfield(message.bits, current.pieceCount)
                    peer.has.or(parsed)
                    picker?.addAvailability(parsed)
                    updateInterest(peer)
                    fill(peer)
                }
            }
            is PeerMessage.Request -> serveRequest(peer, message)
            is PeerMessage.Cancel -> {}
            is PeerMessage.Piece -> receiveBlock(peer, message)
            is PeerMessage.Extended -> handleExtended(peer, message)
        }
    }

    private suspend fun serveRequest(peer: PeerConnection, request: PeerMessage.Request) {
        val current = meta ?: return
        val currentStore = store ?: return
        val allowed = lock.withLock {
            !peer.amChoking && request.piece in 0 until current.pieceCount && servable[request.piece]
        }
        if (!allowed) return
        val size = current.pieceSize(request.piece)
        if (request.begin < 0 || request.length !in 1..MAX_BLOCK_SIZE || request.begin.toLong() + request.length > size) {
            throw ProtocolError("a peer requested a block outside the piece")
        }
        // A peer can only queue so much work on us, and anything beyond that is dropped.
        if (peer.pendingUploads.incrementAndGet() > MAX_PENDING_UPLOADS) {
            peer.pendingUploads.decrementAndGet()
            return
        }
        try {
            val block = currentStore.read(request.piece, request.begin, request.length) ?: return
            val encoded = PeerMessage.encode(PeerMessage.Piece(request.piece, request.begin, block))
            // Suspends when the peer reads slowly, which slows down what it gets to ask for.
            peer.outgoing.send(encoded)
            uploadedBytes.addAndGet(block.size.toLong())
        } finally {
            peer.pendingUploads.decrementAndGet()
        }
    }

    private suspend fun receiveBlock(peer: PeerConnection, message: PeerMessage.Piece) {
        lock.withLock {
            val currentPicker = picker ?: return
            val block = PiecePicker.Block(message.piece, message.begin, message.block.size)
            // Only what was actually asked for is accepted. This also drops late arrivals after
            // a cancel, and blocks of the wrong size or position.
            if (!peer.inFlight.remove(block)) return
            downloadedBytes.addAndGet(message.block.size.toLong())

            // Once a block has arrived, any peer racing for it in endgame can stop.
            for (other in peers) {
                if (other !== peer && other.inFlight.remove(block)) {
                    other.send(PeerMessage.Cancel(block.piece, block.begin, block.length))
                }
            }

            when (val result = currentPicker.receive(message.piece, message.begin, message.block, peer)) {
                PiecePicker.Result.Ignored -> {}
                PiecePicker.Result.Progress -> {}
                is PiecePicker.Result.PieceBad -> {
                    rejectedPieces.incrementAndGet()
                    for (contributor in result.contributors) {
                        val suspect = contributor as PeerConnection
                        if (++suspect.strikes >= MAX_STRIKES) suspect.close()
                    }
                }
                is PiecePicker.Result.PieceDone -> {
                    val currentStore = requireNotNull(store)
                    currentStore.write(result.piece, result.data)
                    servable.set(result.piece)
                    for (other in peers) {
                        other.send(PeerMessage.Have(result.piece))
                        updateInterest(other)
                    }
                    if (currentPicker.isComplete) completed.complete(Unit)
                }
            }
            for (other in peers) fill(other)
        }
    }

    /** Must be called with the lock held. */
    private fun updateInterest(peer: PeerConnection) {
        val currentPicker = picker ?: return
        val wanted = currentPicker.wantsAnything(peer.has)
        if (wanted != peer.amInterested) {
            peer.amInterested = wanted
            peer.send(if (wanted) PeerMessage.Interested else PeerMessage.NotInterested)
        }
    }

    /** Tops up the request window for a peer. Must be called with the lock held. */
    private fun fill(peer: PeerConnection) {
        val currentPicker = picker ?: return
        if (peer.peerChoking || !peer.amInterested) return
        while (peer.inFlight.size < MAX_IN_FLIGHT) {
            val block = currentPicker.next(peer.has, peer.inFlight) ?: return
            peer.inFlight.add(block)
            peer.send(PeerMessage.Request(block.piece, block.begin, block.length))
        }
    }

    private fun unchokedCount(): Int = peers.count { !it.amChoking }

    /** Must be called with the lock held. */
    private fun tryUnchoke(peer: PeerConnection) {
        if (peer.amChoking && peer.peerInterested && unchokedCount() < client.config.maxUnchoked) {
            peer.amChoking = false
            peer.unchokedAt = System.nanoTime()
            peer.send(PeerMessage.Unchoke)
        }
    }

    /** Must be called with the lock held. */
    private fun unchokeWaiters() {
        for (peer in peers) tryUnchoke(peer)
    }

    /** Takes turns, so that waiting peers eventually get served and find out what they can give. */
    private suspend fun rotateChokes() {
        while (client.scope.isActive) {
            delay(client.config.chokeRotationMillis)
            lock.withLock {
                val waiting = peers.any { it.amChoking && it.peerInterested }
                if (waiting && unchokedCount() >= client.config.maxUnchoked) {
                    peers.filter { !it.amChoking }.minByOrNull { it.unchokedAt }?.let { oldest ->
                        oldest.amChoking = true
                        oldest.send(PeerMessage.Choke)
                    }
                    val next = peers.filter { it.amChoking && it.peerInterested }.randomOrNull()
                    next?.let { tryUnchoke(it) }
                } else {
                    unchokeWaiters()
                }
            }
        }
    }

    internal suspend fun sendKeepAlives() {
        lock.withLock { for (peer in peers) peer.send(PeerMessage.KeepAlive) }
    }

    private suspend fun handleExtended(peer: PeerConnection, message: PeerMessage.Extended) {
        when (message.id) {
            0 -> lock.withLock { receiveExtensionHandshake(peer, message.payload) }
            OUR_UT_METADATA_ID -> lock.withLock { receiveMetadataMessage(peer, message.payload) }
            OUR_UT_PEX_ID -> lock.withLock { receivePex(peer, message.payload) }
        }
    }

    /** Must be called with the lock held. */
    private fun receiveExtensionHandshake(peer: PeerConnection, payload: ByteArray) {
        val dict = try {
            Bencode.decode(payload) as? BDict
        } catch (_: BencodeError) {
            null
        } ?: throw ProtocolError("a malformed extension handshake")
        val id = dict.dict("m")?.int("ut_metadata") ?: 0
        val pexId = dict.dict("m")?.int("ut_pex") ?: 0
        if (id !in 0..255 || pexId !in 0..255) throw ProtocolError("an extension id out of range")
        peer.peerUtMetadataId = id.toInt()
        peer.peerUtPexId = pexId.toInt()
        val listenPort = dict.int("p")
        if (!peer.weInitiated && listenPort != null && listenPort in 1..65535) {
            val host = (peer.socket.remoteSocketAddress as? InetSocketAddress)?.address
            if (host != null) peer.listenAddress = InetSocketAddress(host, listenPort.toInt())
        }

        val advertised = dict.int("metadata_size") ?: 0
        if (meta == null && peer.peerUtMetadataId != 0 && advertised > 0) {
            if (advertised > MAX_METADATA_SIZE) throw ProtocolError("metadata of $advertised bytes is too large")
            if (metadataBuffer == null) {
                metadataSize = advertised.toInt()
                metadataBuffer = ByteArray(metadataSize)
                val pieces = (metadataSize + METADATA_PIECE_SIZE - 1) / METADATA_PIECE_SIZE
                metadataMissing = BitSet(pieces).apply { set(0, pieces) }
            }
            requestMetadata(peer)
        }
    }

    /** Must be called with the lock held. */
    private fun requestMetadata(peer: PeerConnection) {
        val missing = metadataMissing ?: return
        val piece = missing.nextSetBit(0)
        if (piece < 0 || peer.peerUtMetadataId == 0) return
        peer.send(
            PeerMessage.Extended(
                peer.peerUtMetadataId,
                Bencode.encode(mapOf("msg_type" to 0, "piece" to piece)),
            ),
        )
    }

    /** Must be called with the lock held. */
    private fun receiveMetadataMessage(peer: PeerConnection, payload: ByteArray) {
        val (value, end) = try {
            Bencode.decodePrefix(payload)
        } catch (_: BencodeError) {
            throw ProtocolError("a malformed metadata message")
        }
        val dict = value as? BDict ?: throw ProtocolError("a malformed metadata message")
        val piece = dict.int("piece") ?: throw ProtocolError("a metadata message has no piece")
        when (dict.int("msg_type")) {
            0L -> serveMetadata(peer, piece)
            1L -> {
                val missing = metadataMissing ?: return
                val buffer = metadataBuffer ?: return
                if (piece !in 0 until ((metadataSize + METADATA_PIECE_SIZE - 1) / METADATA_PIECE_SIZE).toLong()) {
                    throw ProtocolError("metadata piece $piece does not exist")
                }
                val data = payload.copyOfRange(end, payload.size)
                val offset = piece.toInt() * METADATA_PIECE_SIZE
                val expected = minOf(METADATA_PIECE_SIZE, metadataSize - offset)
                if (data.size != expected) throw ProtocolError("metadata piece $piece has the wrong size")
                if (!missing[piece.toInt()]) return
                data.copyInto(buffer, offset)
                missing.clear(piece.toInt())
                if (missing.isEmpty) {
                    finishMetadata(peer, buffer)
                } else {
                    requestMetadata(peer)
                }
            }
            2L -> {}
        }
    }

    /** Must be called with the lock held. */
    private fun finishMetadata(peer: PeerConnection, buffer: ByteArray) {
        // The hash is the only thing that makes this data trustworthy, so it is checked first.
        if (!MessageDigest.isEqual(sha1(buffer), infoHash)) {
            metadataBuffer = null
            metadataMissing = null
            if (++peer.strikes >= MAX_STRIKES) peer.close()
            rejectedPieces.incrementAndGet()
            return
        }
        val parsed = try {
            Metainfo.fromInfo(buffer, extraTrackers)
        } catch (_: InvalidTorrent) {
            // The hash matched, so this is the real metadata and it is simply unusable.
            metadataBuffer = null
            metadataMissing = null
            return
        }
        metadataBuffer = null
        metadataMissing = null
        initFromMetadata(parsed)
    }

    /** Must be called with the lock held. */
    private fun serveMetadata(peer: PeerConnection, piece: Long) {
        if (peer.peerUtMetadataId == 0) return
        val raw = meta?.rawInfo
        val offset = piece * METADATA_PIECE_SIZE
        if (raw == null || piece < 0 || offset >= raw.size) {
            peer.send(
                PeerMessage.Extended(
                    peer.peerUtMetadataId,
                    Bencode.encode(mapOf("msg_type" to 2, "piece" to piece)),
                ),
            )
            return
        }
        val from = offset.toInt()
        val data = raw.copyOfRange(from, minOf(from + METADATA_PIECE_SIZE, raw.size))
        val header = Bencode.encode(mapOf("msg_type" to 1, "piece" to piece, "total_size" to raw.size))
        peer.send(PeerMessage.Extended(peer.peerUtMetadataId, header + data))
    }

    /** Private torrents (BEP 27) only get peers from their trackers, so they never use PEX. */
    private fun pexAllowed(): Boolean = meta?.isPrivate != true

    private suspend fun pexLoop() {
        while (client.scope.isActive) {
            delay(client.config.pexIntervalMillis)
            lock.withLock { sendPex() }
        }
    }

    /** Tells each peer which peers joined and left since the last message. Must be called with the lock held. */
    private fun sendPex() {
        if (!pexAllowed()) return
        val pieceCount = meta?.pieceCount ?: 0
        val known = LinkedHashMap<InetSocketAddress, PeerConnection>()
        for (peer in peers) peer.listenAddress?.let { known[it] = peer }
        for (peer in peers) {
            if (peer.peerUtPexId == 0) continue
            val current = known.filterValues { it !== peer }
            val added = current.keys.filter { it !in peer.pexAdvertised }.take(MAX_PEX_PEERS)
            val dropped = peer.pexAdvertised.filter { it !in current }.take(MAX_PEX_PEERS)
            if (added.isEmpty() && dropped.isEmpty()) continue
            val flags = added.map { address ->
                val other = current.getValue(address)
                var flag = 0
                if (other.weInitiated) flag = flag or PEX_FLAG_REACHABLE
                if (pieceCount > 0 && other.has.cardinality() == pieceCount) flag = flag or PEX_FLAG_SEED
                address to flag
            }
            val body = HashMap<String, Any>()
            for ((suffix, size) in listOf("" to 4, "6" to 16)) {
                val ours = flags.filter { it.first.address.address.size == size }
                val gone = dropped.filter { it.address.address.size == size }
                body["added$suffix"] = encodeCompact(ours.map { it.first })
                body["added$suffix.f"] = ByteArray(ours.size) { ours[it].second.toByte() }
                body["dropped$suffix"] = encodeCompact(gone)
            }
            peer.send(PeerMessage.Extended(peer.peerUtPexId, Bencode.encode(body)))
            peer.pexAdvertised.addAll(added)
            peer.pexAdvertised.removeAll(dropped.toSet())
        }
    }

    private fun encodeCompact(addresses: List<InetSocketAddress>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for (address in addresses) {
            out.writeBytes(address.address.address)
            out.write(address.port ushr 8)
            out.write(address.port and 0xFF)
        }
        return out.toByteArray()
    }

    private fun decodeCompact(bytes: ByteArray?, addressSize: Int): List<InetSocketAddress> {
        if (bytes == null) return emptyList()
        val entry = addressSize + 2
        if (bytes.size % entry != 0) throw ProtocolError("a peer exchange list has an invalid length")
        return (bytes.indices step entry).mapNotNull { at ->
            val port = ((bytes[at + addressSize].toInt() and 0xFF) shl 8) or (bytes[at + addressSize + 1].toInt() and 0xFF)
            if (port == 0) null else InetSocketAddress(java.net.InetAddress.getByAddress(bytes.copyOfRange(at, at + addressSize)), port)
        }
    }

    /**
     * Connects to peers another peer told us about. Must be called with the lock held.
     *
     * The list comes from a stranger, so it gets the same treatment as a tracker's: private
     * addresses are refused, and only so many entries are taken from one message, so that a peer
     * cannot use this client to flood a victim with connections.
     */
    private fun receivePex(peer: PeerConnection, payload: ByteArray) {
        if (!pexAllowed()) return
        val now = System.nanoTime()
        // The extension allows one message a minute. Anything much faster is ignored.
        val minimumGap = client.config.pexIntervalMillis * 1_000_000 / 2
        if (peer.lastPexReceived != 0L && now - peer.lastPexReceived < minimumGap) return
        peer.lastPexReceived = now
        val dict = try {
            Bencode.decode(payload) as? BDict
        } catch (_: BencodeError) {
            null
        } ?: throw ProtocolError("a malformed peer exchange message")
        val added = decodeCompact(dict.bytes("added"), 4) + decodeCompact(dict.bytes("added6"), 16)
        for (address in added.take(MAX_PEX_PEERS)) {
            if (!client.config.allowPrivateAddresses && !AddressPolicy.isPublic(address.address)) continue
            if (peers.any { it.listenAddress == address }) continue
            pexLearned.incrementAndGet()
            connect(address)
        }
    }

    /** Announces to a tracker now, and connects to the peers it returns. */
    suspend fun announce(url: String, left: Long, event: String? = null): TrackerResponse {
        val response = TrackerClient.announce(
            url,
            TrackerRequest(infoHash, client.peerId, client.port, uploaded, downloaded, left, event),
            client.config.allowPrivateAddresses,
        )
        for (address in response.peers) connect(address)
        return response
    }

    /** Runs periodic announces to every tracker and to the DHT, until the client closes. */
    fun startDiscovery() {
        client.scope.launch {
            val current = meta ?: metadataReady.await()
            for (url in (current.trackers + extraTrackers).distinct()) {
                client.scope.launch { trackerLoop(url) }
            }
        }
        val node = client.dht ?: return
        client.scope.launch {
            while (isActive) {
                // A private torrent (BEP 27) gets peers from its trackers only. A magnet link
                // cannot know whether it is private until the metadata arrives.
                if (meta?.isPrivate == true) break
                val found = node.getPeers(infoHash)
                for (peer in found.peers) {
                    if (client.config.allowPrivateAddresses || AddressPolicy.isPublic(peer.address)) connect(peer)
                }
                node.announce(infoHash, client.port, found)
                delay(5 * 60_000)
            }
        }
    }

    private suspend fun trackerLoop(url: String) {
        var event: String? = "started"
        while (client.scope.isActive) {
            val left = meta?.let { current ->
                var remaining = current.totalLength
                for (piece in 0 until current.pieceCount) {
                    if (servable[piece]) remaining -= current.pieceSize(piece)
                }
                remaining
            } ?: 0
            val wait = try {
                announce(url, left, event).intervalSeconds
            } catch (_: TrackerError) {
                120
            } catch (_: java.io.IOException) {
                120
            }
            event = null
            delay(wait.coerceIn(30, 3600) * 1000L)
        }
    }

    override fun close() {
        for (peer in peers.toList()) peer.close()
        store?.close()
    }
}
