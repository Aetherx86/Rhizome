package dev.aetherx86.rhizome

import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.util.BitSet

/**
 * Maps the torrent's continuous byte stream onto files under [root].
 *
 * Metadata has already rejected unsafe names. This class checks again against the real filesystem,
 * since a folder inside the download directory could be a symbolic link pointing somewhere else.
 */
class PieceStore(private val meta: Metainfo, private val root: Path) : AutoCloseable {
    private val realRoot: Path
    private val handles = HashMap<Int, RandomAccessFile>()

    init {
        Files.createDirectories(root)
        realRoot = root.toRealPath()
    }

    private fun pathOf(file: TorrentFile): Path {
        var path = realRoot
        for (part in file.path) path = path.resolve(part)
        if (!path.normalize().startsWith(realRoot)) throw IOException("path escapes the download folder: $path")
        return path
    }

    /** Refuses to go through a symbolic link, whichever part of the path it is. */
    private fun checkNoLinks(path: Path) {
        var current = realRoot
        for (part in realRoot.relativize(path)) {
            current = current.resolve(part)
            if (Files.isSymbolicLink(current)) throw IOException("refusing to follow a symbolic link: $current")
        }
    }

    private fun open(index: Int, forWriting: Boolean): RandomAccessFile? {
        handles[index]?.let { return it }
        val path = pathOf(meta.files[index])
        checkNoLinks(path)
        if (!forWriting && !Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null
        Files.createDirectories(path.parent)
        checkNoLinks(path)
        val file = RandomAccessFile(path.toFile(), "rw")
        handles[index] = file
        return file
    }

    private inline fun forEachSpan(piece: Int, begin: Int, length: Int, action: (Int, Long, Int, Int) -> Unit) {
        var position = piece.toLong() * meta.pieceLength + begin
        var bufferOffset = 0
        var remaining = length
        for ((index, file) in meta.files.withIndex()) {
            if (remaining == 0) return
            if (file.length == 0L || position >= file.offset + file.length) continue
            val inFile = position - file.offset
            val take = minOf(remaining.toLong(), file.length - inFile).toInt()
            action(index, inFile, bufferOffset, take)
            position += take
            bufferOffset += take
            remaining -= take
        }
    }

    /** Creates empty files, so that a zero-length file in the torrent still exists on disk. */
    fun createEmptyFiles() {
        for ((index, file) in meta.files.withIndex()) {
            if (file.length == 0L) open(index, true)
        }
    }

    @Synchronized
    fun write(piece: Int, data: ByteArray) {
        forEachSpan(piece, 0, data.size) { index, inFile, offset, take ->
            val file = requireNotNull(open(index, true))
            file.seek(inFile)
            file.write(data, offset, take)
        }
    }

    /** Reads bytes, returning null if any part is missing or too short. */
    @Synchronized
    fun read(piece: Int, begin: Int, length: Int): ByteArray? {
        val out = ByteArray(length)
        forEachSpan(piece, begin, length) { index, inFile, offset, take ->
            val file = open(index, false) ?: return null
            if (file.length() < inFile + take) return null
            file.seek(inFile)
            file.readFully(out, offset, take)
        }
        return out
    }

    /** Hashes whatever is already on disk, to resume a download without fetching it again. */
    fun verifyExisting(): BitSet {
        val verified = BitSet(meta.pieceCount)
        for (piece in 0 until meta.pieceCount) {
            val data = read(piece, 0, meta.pieceSize(piece)) ?: continue
            if (MessageDigest.isEqual(sha1(data), meta.pieceHash(piece))) verified.set(piece)
        }
        return verified
    }

    @Synchronized
    override fun close() {
        for (file in handles.values) runCatching { file.close() }
        handles.clear()
    }
}

/**
 * Decides which block to ask each peer for next.
 *
 * - Partly downloaded pieces come first, so that partial buffers finish and free their memory.
 * - After that the rarest piece comes first, which keeps every piece available in the swarm.
 * - When every remaining block is already requested, endgame lets another peer race for it, so a
 *   slow peer cannot hold up the very end of a download.
 */
class PiecePicker(private val meta: Metainfo, have: BitSet, private val maxPartial: Int = 32) {
    class Partial(size: Int, blocks: Int) {
        val buffer = ByteArray(size)
        val received = BitSet(blocks)
        val requests = IntArray(blocks)
        val contributors = HashSet<Any>()
    }

    data class Block(val piece: Int, val begin: Int, val length: Int)

    val verified: BitSet = have.clone() as BitSet
    private val availability = IntArray(meta.pieceCount)
    private val partials = LinkedHashMap<Int, Partial>()
    private val random = java.util.Random()

    val isComplete: Boolean get() = verified.cardinality() == meta.pieceCount

    fun addAvailability(pieces: BitSet) {
        var i = pieces.nextSetBit(0)
        while (i in availability.indices) {
            availability[i]++
            i = pieces.nextSetBit(i + 1)
        }
    }

    fun removeAvailability(pieces: BitSet) {
        var i = pieces.nextSetBit(0)
        while (i in availability.indices) {
            if (availability[i] > 0) availability[i]--
            i = pieces.nextSetBit(i + 1)
        }
    }

    fun addAvailability(piece: Int) {
        if (piece in availability.indices) availability[piece]++
    }

    private fun blockCount(piece: Int): Int = (meta.pieceSize(piece) + BLOCK_SIZE - 1) / BLOCK_SIZE

    private fun blockAt(piece: Int, index: Int): Block {
        val begin = index * BLOCK_SIZE
        return Block(piece, begin, minOf(BLOCK_SIZE, meta.pieceSize(piece) - begin))
    }

    fun wantsAnything(peerHas: BitSet): Boolean {
        var i = peerHas.nextSetBit(0)
        while (i in 0 until meta.pieceCount) {
            if (!verified[i]) return true
            i = peerHas.nextSetBit(i + 1)
        }
        return false
    }

    /** Picks the next block for a peer, or null if it has nothing useful right now. */
    fun next(peerHas: BitSet, alreadyInFlight: Set<Block>): Block? {
        for ((piece, partial) in partials) {
            if (!peerHas[piece]) continue
            for (i in 0 until blockCount(piece)) {
                if (!partial.received[i] && partial.requests[i] == 0) {
                    partial.requests[i]++
                    return blockAt(piece, i)
                }
            }
        }
        if (partials.size < maxPartial) {
            var best = -1
            var bestCount = Int.MAX_VALUE
            var ties = 0
            var i = peerHas.nextSetBit(0)
            while (i in 0 until meta.pieceCount) {
                if (!verified[i] && i !in partials) {
                    val count = availability[i]
                    if (count < bestCount) {
                        best = i
                        bestCount = count
                        ties = 1
                    } else if (count == bestCount && random.nextInt(++ties) == 0) {
                        best = i
                    }
                }
                i = peerHas.nextSetBit(i + 1)
            }
            if (best >= 0) {
                val partial = Partial(meta.pieceSize(best), blockCount(best))
                partials[best] = partial
                partial.requests[0]++
                return blockAt(best, 0)
            }
        }
        // Endgame: everything left is already requested, so race the slower peers for it.
        for ((piece, partial) in partials) {
            if (!peerHas[piece]) continue
            for (i in 0 until blockCount(piece)) {
                if (partial.received[i] || partial.requests[i] >= MAX_DUPLICATE_REQUESTS) continue
                val block = blockAt(piece, i)
                if (block !in alreadyInFlight) {
                    partial.requests[i]++
                    return block
                }
            }
        }
        return null
    }

    /** Gives back a request that will not be fulfilled, so another peer can take it. */
    fun release(block: Block) {
        val partial = partials[block.piece] ?: return
        val i = block.begin / BLOCK_SIZE
        if (partial.requests[i] > 0) partial.requests[i]--
    }

    sealed interface Result {
        /** A duplicate, or a block for a piece no longer wanted. */
        data object Ignored : Result
        data object Progress : Result
        class PieceDone(val piece: Int, val data: ByteArray, val contributors: Set<Any>) : Result
        class PieceBad(val piece: Int, val contributors: Set<Any>) : Result
    }

    /** Records a block that was requested. The caller must have checked that it was. */
    fun receive(piece: Int, begin: Int, data: ByteArray, from: Any): Result {
        val partial = partials[piece] ?: return Result.Ignored
        val i = begin / BLOCK_SIZE
        if (partial.received[i]) return Result.Ignored
        partial.received[i] = true
        partial.contributors.add(from)
        data.copyInto(partial.buffer, begin)
        if (partial.received.cardinality() < blockCount(piece)) return Result.Progress

        partials.remove(piece)
        // Nothing reaches the disk until its hash checks out.
        return if (MessageDigest.isEqual(sha1(partial.buffer), meta.pieceHash(piece))) {
            verified.set(piece)
            Result.PieceDone(piece, partial.buffer, partial.contributors)
        } else {
            Result.PieceBad(piece, partial.contributors)
        }
    }

    private companion object {
        const val MAX_DUPLICATE_REQUESTS = 3
    }
}
