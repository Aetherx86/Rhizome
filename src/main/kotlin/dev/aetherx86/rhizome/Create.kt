package dev.aetherx86.rhizome

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Makes a `.torrent` file from a file or folder on disk.
 *
 * Files are hashed as a stream, a piece at a time, so a torrent of any size needs only one piece
 * of memory. The output is reproducible: files are taken in a fixed order and no creation date is
 * written, so the same input always gives the same info hash.
 */
object TorrentCreator {
    const val MIN_PIECE_LENGTH = 16 * 1024

    /** The largest piece size picked automatically. Larger ones can still be asked for. */
    const val MAX_AUTO_PIECE_LENGTH = 16 shl 20

    /** Automatic sizing aims for at most this many pieces. */
    private const val TARGET_PIECES = 2048

    /** The smallest power of two from 16 KiB up that keeps the piece count near [TARGET_PIECES]. */
    fun choosePieceLength(totalLength: Long): Int {
        var length = MIN_PIECE_LENGTH
        while (length < MAX_AUTO_PIECE_LENGTH && (totalLength + length - 1) / length > TARGET_PIECES) length *= 2
        return length
    }

    private class Entry(val path: Path, val parts: List<String>, val length: Long)

    /** Lists the regular files under [root] in a fixed order, refusing anything that is not one. */
    private fun collect(root: Path): List<Entry> {
        val out = ArrayList<Entry>()
        fun walk(dir: Path, parts: List<String>) {
            val children = Files.list(dir).use { stream -> stream.toList() }.sortedBy { it.fileName.toString() }
            for (child in children) {
                val name = child.fileName.toString()
                if (!Metainfo.isSafeName(name)) throw InvalidTorrent("unsafe file name: ${(parts + name).joinToString("/")}")
                when {
                    Files.isSymbolicLink(child) -> throw IOException("refusing to include a symbolic link: $child")
                    Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS) -> walk(child, parts + name)
                    Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS) ->
                        out.add(Entry(child, parts + name, Files.size(child)))
                    else -> throw IOException("not a regular file: $child")
                }
            }
        }
        walk(root, emptyList())
        return out
    }

    fun create(
        source: Path,
        pieceLength: Int? = null,
        trackers: List<String> = emptyList(),
        isPrivate: Boolean = false,
    ): ByteArray {
        if (Files.isSymbolicLink(source)) throw IOException("refusing to follow a symbolic link: $source")
        if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) throw IOException("no such file or folder: $source")
        val name = source.toAbsolutePath().normalize().fileName?.toString()
            ?: throw IllegalArgumentException("cannot make a torrent of a filesystem root")
        if (!Metainfo.isSafeName(name)) throw InvalidTorrent("unsafe name: $name")

        val single = Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)
        val entries = if (single) {
            listOf(Entry(source, listOf(name), Files.size(source)))
        } else if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
            collect(source)
        } else {
            throw IOException("not a regular file or folder: $source")
        }
        val total = entries.fold(0L) { sum, entry -> Math.addExact(sum, entry.length) }
        if (total <= 0) throw IllegalArgumentException("there is no data to share in $source")

        val length = pieceLength ?: choosePieceLength(total)
        require(length >= MIN_PIECE_LENGTH && length <= Metainfo.MAX_PIECE_LENGTH && length and (length - 1) == 0) {
            "the piece length must be a power of two from 16 KiB to 64 MiB, not $length"
        }

        val hashes = ByteArrayOutputStream()
        val digest = MessageDigest.getInstance("SHA-1")
        var inPiece = 0
        val buffer = ByteArray(64 * 1024)
        for (entry in entries) {
            var read = 0L
            Files.newInputStream(entry.path, LinkOption.NOFOLLOW_LINKS).use { input ->
                while (true) {
                    val want = minOf(buffer.size, length - inPiece)
                    val n = input.read(buffer, 0, want)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                    inPiece += n
                    read += n
                    if (inPiece == length) {
                        hashes.writeBytes(digest.digest())
                        inPiece = 0
                    }
                }
            }
            if (read != entry.length) throw IOException("${entry.path} changed while it was being hashed")
        }
        if (inPiece > 0) hashes.writeBytes(digest.digest())

        val info = HashMap<String, Any>()
        info["name"] = name
        info["piece length"] = length
        info["pieces"] = hashes.toByteArray()
        if (single) {
            info["length"] = total
        } else {
            info["files"] = entries.map { mapOf("length" to it.length, "path" to it.parts) }
        }
        if (isPrivate) info["private"] = 1
        val root = HashMap<String, Any>()
        root["info"] = info
        root["created by"] = "Rhizome $RHIZOME_VERSION"
        if (trackers.isNotEmpty()) {
            root["announce"] = trackers[0]
            root["announce-list"] = trackers.map { listOf(it) }
        }
        val encoded = Bencode.encode(root)
        // Whatever this writes has to be something this client, and so any careful client, accepts.
        Metainfo.parse(encoded)
        return encoded
    }
}
