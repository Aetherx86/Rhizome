package dev.aetherx86.rhizome

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.time.Duration

class TrackerError(message: String) : IOException(message)

class TrackerRequest(
    val infoHash: ByteArray,
    val peerId: ByteArray,
    val port: Int,
    val uploaded: Long,
    val downloaded: Long,
    val left: Long,
    val event: String?,
)

class TrackerResponse(val intervalSeconds: Int, val peers: List<InetSocketAddress>)

/**
 * Decides whether an address taken from an untrusted file is safe to contact.
 *
 * A torrent chooses its own tracker URLs. Without this check, opening a malicious torrent would
 * make this machine send requests to services only it can reach, such as a router's admin page or
 * a cloud metadata endpoint. Peers and DHT nodes get the same treatment, because a malicious
 * tracker or node chooses those.
 */
object AddressPolicy {
    fun isPublic(address: InetAddress?): Boolean {
        if (address == null) return false
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress
        ) {
            return false
        }
        val bytes = address.address
        if (bytes.size == 4) {
            val a = bytes[0].toInt() and 0xFF
            val b = bytes[1].toInt() and 0xFF
            // Carrier-grade NAT, benchmarking and reserved ranges, which Java does not classify.
            if (a == 0 || a >= 240 || (a == 100 && b in 64..127) || (a == 198 && b in 18..19)) return false
        } else {
            // Unique local IPv6 addresses (fc00::/7), and IPv4 mapped into IPv6 is checked above.
            if ((bytes[0].toInt() and 0xFE) == 0xFC) return false
        }
        return true
    }
}

object TrackerClient {
    private const val MAX_RESPONSE_BYTES = 1 shl 20
    private const val UDP_PROTOCOL_ID = 0x41727101980L
    private const val UDP_CONNECT = 0
    private const val UDP_ANNOUNCE = 1
    private const val UDP_ERROR = 3
    private val random = SecureRandom()

    // Redirects stay off, since one could point a checked public URL at a private address.
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    suspend fun announce(url: String, request: TrackerRequest, allowPrivate: Boolean): TrackerResponse =
        withContext(Dispatchers.IO) {
            val uri = try {
                URI(url)
            } catch (e: java.net.URISyntaxException) {
                throw TrackerError("invalid tracker URL: $url")
            }
            val scheme = uri.scheme?.lowercase()
            if (scheme != "http" && scheme != "https" && scheme != "udp") {
                throw TrackerError("unsupported tracker scheme: $scheme")
            }
            val host = uri.host ?: throw TrackerError("a tracker URL has no host: $url")
            val addresses = try {
                InetAddress.getAllByName(host)
            } catch (e: java.net.UnknownHostException) {
                throw TrackerError("cannot resolve tracker host $host")
            }
            if (!allowPrivate && addresses.any { !AddressPolicy.isPublic(it) }) {
                throw TrackerError("refusing to contact a tracker on a private address: $host")
            }
            if (scheme == "udp") {
                val port = uri.port.takeIf { it in 1..65535 } ?: throw TrackerError("a UDP tracker needs a port")
                announceUdp(InetSocketAddress(addresses[0], port), request)
            } else {
                announceHttp(uri, request)
            }
        }

    /** Percent-encodes raw bytes, because hashes and peer ids are not text. */
    private fun encodeBytes(bytes: ByteArray): String = bytes.joinToString("") { "%%%02X".format(it) }

    private fun announceHttp(uri: URI, request: TrackerRequest): TrackerResponse {
        val query = buildString {
            append("info_hash=").append(encodeBytes(request.infoHash))
            append("&peer_id=").append(encodeBytes(request.peerId))
            append("&port=").append(request.port)
            append("&uploaded=").append(request.uploaded)
            append("&downloaded=").append(request.downloaded)
            append("&left=").append(request.left)
            append("&compact=1")
            request.event?.let { append("&event=").append(URLEncoder.encode(it, Charsets.UTF_8)) }
        }
        val separator = if (uri.rawQuery == null) "?" else "&"
        val target = URI.create(uri.toString() + separator + query)
        val response = try {
            http.send(
                HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(15)).GET().build(),
                HttpResponse.BodyHandlers.ofInputStream(),
            )
        } catch (e: IOException) {
            throw TrackerError("the tracker request failed: ${e.message}")
        }
        if (response.statusCode() != 200) {
            response.body().close()
            throw TrackerError("the tracker answered with status ${response.statusCode()}")
        }
        val body = response.body().use { it.readNBytes(MAX_RESPONSE_BYTES + 1) }
        if (body.size > MAX_RESPONSE_BYTES) throw TrackerError("the tracker response is too large")
        return parseHttpResponse(body)
    }

    internal fun parseHttpResponse(body: ByteArray): TrackerResponse {
        val dict = try {
            Bencode.decode(body) as? BDict
        } catch (e: BencodeError) {
            throw TrackerError("the tracker sent a malformed response: ${e.message}")
        } ?: throw TrackerError("the tracker response is not a dictionary")
        dict.text("failure reason")?.let { throw TrackerError("the tracker refused: $it") }
        val interval = dict.int("interval")?.coerceIn(1, 86_400)?.toInt() ?: 1800
        val peers = when (val value = dict["peers"]) {
            is BBytes -> parseCompactPeers(value.bytes)
            is BList -> value.items.mapNotNull { item ->
                val peer = item as? BDict ?: return@mapNotNull null
                val ip = peer.text("ip") ?: return@mapNotNull null
                val port = peer.int("port")?.takeIf { it in 1..65535 } ?: return@mapNotNull null
                // Only literal addresses. A hostname here would trigger a lookup chosen by the tracker.
                val address = parseIpLiteral(ip) ?: return@mapNotNull null
                InetSocketAddress(address, port.toInt())
            }
            else -> emptyList()
        }
        return TrackerResponse(interval, peers)
    }

    private fun parseIpLiteral(text: String): InetAddress? {
        val parts = text.split('.')
        if (parts.size != 4) return null
        val bytes = ByteArray(4)
        for ((i, part) in parts.withIndex()) {
            val value = part.toIntOrNull() ?: return null
            if (value !in 0..255) return null
            bytes[i] = value.toByte()
        }
        return InetAddress.getByAddress(bytes)
    }

    /** Each peer is four bytes of IPv4 address and two of port. */
    fun parseCompactPeers(bytes: ByteArray): List<InetSocketAddress> {
        if (bytes.size % 6 != 0) throw TrackerError("a compact peer list has an invalid length")
        val out = ArrayList<InetSocketAddress>(bytes.size / 6)
        for (at in bytes.indices step 6) {
            val port = ((bytes[at + 4].toInt() and 0xFF) shl 8) or (bytes[at + 5].toInt() and 0xFF)
            if (port == 0) continue
            out.add(InetSocketAddress(Inet4Address.getByAddress(bytes.copyOfRange(at, at + 4)), port))
        }
        return out
    }

    private fun exchange(
        socket: DatagramSocket,
        target: InetSocketAddress,
        payload: ByteArray,
        transaction: Int,
    ): ByteBuffer {
        val buffer = ByteArray(65536)
        // Retries with a growing timeout, which BEP 15 calls for, kept short for interactive use.
        for (attempt in 0..2) {
            socket.soTimeout = 2_000 shl attempt
            socket.send(DatagramPacket(payload, payload.size, target))
            val deadline = System.nanoTime() + (2_000L shl attempt) * 1_000_000
            while (System.nanoTime() < deadline) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    break
                }
                // A reply must come from the tracker and carry our transaction id, which makes
                // spoofing one much harder.
                if (packet.socketAddress != target || packet.length < 8) continue
                val reply = ByteBuffer.wrap(buffer, 0, packet.length)
                val action = reply.getInt()
                if (reply.getInt() != transaction) continue
                if (action == UDP_ERROR) {
                    val message = String(buffer, 8, packet.length - 8, Charsets.UTF_8)
                    throw TrackerError("the tracker refused: $message")
                }
                reply.position(0)
                return reply
            }
        }
        throw TrackerError("the UDP tracker did not answer")
    }

    private fun announceUdp(target: InetSocketAddress, request: TrackerRequest): TrackerResponse {
        DatagramSocket().use { socket ->
            val connectTransaction = random.nextInt()
            val connect = ByteBuffer.allocate(16)
                .putLong(UDP_PROTOCOL_ID)
                .putInt(UDP_CONNECT)
                .putInt(connectTransaction)
                .array()
            val connected = exchange(socket, target, connect, connectTransaction)
            if (connected.remaining() < 16 || connected.getInt() != UDP_CONNECT) {
                throw TrackerError("the UDP tracker sent a malformed connect reply")
            }
            connected.getInt()
            val connectionId = connected.getLong()

            val transaction = random.nextInt()
            val event = when (request.event) {
                "completed" -> 1
                "started" -> 2
                "stopped" -> 3
                else -> 0
            }
            val announce = ByteBuffer.allocate(98)
                .putLong(connectionId)
                .putInt(UDP_ANNOUNCE)
                .putInt(transaction)
                .put(request.infoHash)
                .put(request.peerId)
                .putLong(request.downloaded)
                .putLong(request.left)
                .putLong(request.uploaded)
                .putInt(event)
                .putInt(0)
                .putInt(random.nextInt())
                .putInt(-1)
                .putShort(request.port.toShort())
                .array()
            val reply = exchange(socket, target, announce, transaction)
            if (reply.remaining() < 20 || reply.getInt() != UDP_ANNOUNCE) {
                throw TrackerError("the UDP tracker sent a malformed announce reply")
            }
            reply.getInt()
            val interval = reply.getInt().coerceIn(1, 86_400)
            reply.getInt()
            reply.getInt()
            val compact = ByteArray(reply.remaining())
            reply.get(compact)
            return TrackerResponse(interval, parseCompactPeers(compact))
        }
    }
}
