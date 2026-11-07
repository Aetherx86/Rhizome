package dev.aetherx86.rhizome

import java.io.DataInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.BitSet

/** Standard block size. Nearly every client requests pieces in units of 16 KiB. */
const val BLOCK_SIZE = 16 * 1024

/** The largest block this client will serve or accept. */
const val MAX_BLOCK_SIZE = 128 * 1024

class ProtocolError(message: String) : IOException(message)

private val PROTOCOL = "BitTorrent protocol".toByteArray(Charsets.ISO_8859_1)

class Handshake(val infoHash: ByteArray, val peerId: ByteArray, val supportsExtensions: Boolean, val supportsDht: Boolean) {
    fun encode(): ByteArray {
        val out = ByteBuffer.allocate(LENGTH)
        out.put(PROTOCOL.size.toByte())
        out.put(PROTOCOL)
        val reserved = ByteArray(8)
        if (supportsExtensions) reserved[5] = 0x10
        if (supportsDht) reserved[7] = 0x01
        out.put(reserved)
        out.put(infoHash)
        out.put(peerId)
        return out.array()
    }

    companion object {
        const val LENGTH = 1 + 19 + 8 + 20 + 20

        fun read(input: DataInputStream): Handshake {
            val raw = ByteArray(LENGTH)
            input.readFully(raw)
            if (raw[0].toInt() != PROTOCOL.size || !raw.copyOfRange(1, 20).contentEquals(PROTOCOL)) {
                throw ProtocolError("the peer does not speak the BitTorrent protocol")
            }
            return Handshake(
                raw.copyOfRange(28, 48),
                raw.copyOfRange(48, 68),
                raw[25].toInt() and 0x10 != 0,
                raw[27].toInt() and 0x01 != 0,
            )
        }
    }
}

sealed interface PeerMessage {
    data object KeepAlive : PeerMessage
    data object Choke : PeerMessage
    data object Unchoke : PeerMessage
    data object Interested : PeerMessage
    data object NotInterested : PeerMessage
    data class Have(val piece: Int) : PeerMessage
    class Bitfield(val bits: ByteArray) : PeerMessage
    data class Request(val piece: Int, val begin: Int, val length: Int) : PeerMessage
    class Piece(val piece: Int, val begin: Int, val block: ByteArray) : PeerMessage
    data class Cancel(val piece: Int, val begin: Int, val length: Int) : PeerMessage
    data class Port(val port: Int) : PeerMessage
    class Extended(val id: Int, val payload: ByteArray) : PeerMessage
    data class Unknown(val id: Int) : PeerMessage

    companion object {
        /** Bigger than any legal message, and small enough that a forged length is harmless. */
        const val MAX_MESSAGE_LENGTH = MAX_BLOCK_SIZE + 64 + (1 shl 21)

        fun encode(message: PeerMessage): ByteArray {
            fun frame(id: Int, payloadSize: Int, fill: ByteBuffer.() -> Unit): ByteArray {
                val out = ByteBuffer.allocate(4 + 1 + payloadSize)
                out.putInt(1 + payloadSize)
                out.put(id.toByte())
                out.fill()
                return out.array()
            }
            return when (message) {
                KeepAlive -> ByteArray(4)
                Choke -> frame(0, 0) {}
                Unchoke -> frame(1, 0) {}
                Interested -> frame(2, 0) {}
                NotInterested -> frame(3, 0) {}
                is Have -> frame(4, 4) { putInt(message.piece) }
                is Bitfield -> frame(5, message.bits.size) { put(message.bits) }
                is Request -> frame(6, 12) {
                    putInt(message.piece)
                    putInt(message.begin)
                    putInt(message.length)
                }
                is Piece -> frame(7, 8 + message.block.size) {
                    putInt(message.piece)
                    putInt(message.begin)
                    put(message.block)
                }
                is Cancel -> frame(8, 12) {
                    putInt(message.piece)
                    putInt(message.begin)
                    putInt(message.length)
                }
                is Port -> frame(9, 2) { putShort(message.port.toShort()) }
                is Extended -> frame(20, 1 + message.payload.size) {
                    put(message.id.toByte())
                    put(message.payload)
                }
                is Unknown -> throw IllegalArgumentException("unknown messages cannot be sent")
            }
        }

        /** Reads one message. The length is checked before anything is allocated for it. */
        fun read(input: DataInputStream): PeerMessage {
            val length = input.readInt()
            if (length == 0) return KeepAlive
            if (length < 0 || length > MAX_MESSAGE_LENGTH) {
                throw ProtocolError("message length $length is out of range")
            }
            val body = ByteArray(length)
            input.readFully(body)
            return decode(body)
        }

        fun decode(body: ByteArray): PeerMessage {
            val buffer = ByteBuffer.wrap(body)
            val id = buffer.get().toInt() and 0xFF
            fun expect(size: Int) {
                if (body.size - 1 != size) throw ProtocolError("message $id has length ${body.size - 1}, not $size")
            }
            return when (id) {
                0 -> { expect(0); Choke }
                1 -> { expect(0); Unchoke }
                2 -> { expect(0); Interested }
                3 -> { expect(0); NotInterested }
                4 -> { expect(4); Have(buffer.getInt()) }
                5 -> Bitfield(body.copyOfRange(1, body.size))
                6 -> { expect(12); Request(buffer.getInt(), buffer.getInt(), buffer.getInt()) }
                7 -> {
                    if (body.size < 9) throw ProtocolError("a piece message is too short")
                    val block = body.size - 9
                    if (block > MAX_BLOCK_SIZE) throw ProtocolError("a block of $block bytes is too large")
                    Piece(buffer.getInt(), buffer.getInt(), body.copyOfRange(9, body.size))
                }
                8 -> { expect(12); Cancel(buffer.getInt(), buffer.getInt(), buffer.getInt()) }
                9 -> { expect(2); Port(buffer.getShort().toInt() and 0xFFFF) }
                20 -> {
                    if (body.size < 2) throw ProtocolError("an extended message is too short")
                    Extended(body[1].toInt() and 0xFF, body.copyOfRange(2, body.size))
                }
                else -> Unknown(id)
            }
        }
    }
}

/** Bit 0 of the first byte is the highest bit, which is piece 0 in the wire format. */
fun BitSet.toWireBitfield(pieces: Int): ByteArray {
    val out = ByteArray((pieces + 7) / 8)
    var i = nextSetBit(0)
    while (i in 0 until pieces) {
        out[i / 8] = (out[i / 8].toInt() or (0x80 ushr (i % 8))).toByte()
        i = nextSetBit(i + 1)
    }
    return out
}

/** Reads a wire bitfield, and rejects one of the wrong size or with stray trailing bits. */
fun parseWireBitfield(bits: ByteArray, pieces: Int): BitSet {
    if (bits.size != (pieces + 7) / 8) {
        throw ProtocolError("a bitfield has ${bits.size} bytes for $pieces pieces")
    }
    val out = BitSet(pieces)
    for (i in 0 until pieces) {
        if (bits[i / 8].toInt() and (0x80 ushr (i % 8)) != 0) out.set(i)
    }
    if (pieces % 8 != 0 && bits.last().toInt() and (0xFF ushr (pieces % 8)) != 0) {
        throw ProtocolError("a bitfield sets bits past the last piece")
    }
    return out
}
