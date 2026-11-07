package dev.aetherx86.rhizome

import java.net.URLDecoder
import java.security.MessageDigest

class InvalidTorrent(message: String) : Exception(message)

fun sha1(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): ByteArray {
    val digest = MessageDigest.getInstance("SHA-1")
    digest.update(data, offset, length)
    return digest.digest()
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

fun hexToBytes(text: String): ByteArray? {
    if (text.length % 2 != 0) return null
    return ByteArray(text.length / 2) { i ->
        val high = Character.digit(text[i * 2], 16)
        val low = Character.digit(text[i * 2 + 1], 16)
        if (high < 0 || low < 0) return null
        ((high shl 4) or low).toByte()
    }
}

/** A file in a torrent, with its position in the torrent's single continuous byte stream. */
class TorrentFile(val path: List<String>, val length: Long, val offset: Long)

/**
 * A parsed `.torrent` file.
 *
 * Everything here comes from an untrusted file. The dangerous part is file names: a torrent
 * naming `../../.ssh/authorized_keys` must never be able to write outside the download folder.
 */
class Metainfo private constructor(
    val infoHash: ByteArray,
    val name: String,
    val pieceLength: Int,
    private val pieceHashes: ByteArray,
    val files: List<TorrentFile>,
    val totalLength: Long,
    val trackers: List<String>,
    val isPrivate: Boolean,
    /** The exact bytes of the `info` dictionary, which is also what peers exchange for magnets. */
    val rawInfo: ByteArray,
) {
    val pieceCount: Int get() = pieceHashes.size / 20

    fun pieceHash(index: Int): ByteArray = pieceHashes.copyOfRange(index * 20, index * 20 + 20)

    fun pieceSize(index: Int): Int {
        require(index in 0 until pieceCount) { "piece $index does not exist" }
        return if (index == pieceCount - 1) (totalLength - pieceLength.toLong() * index).toInt() else pieceLength
    }

    fun withTrackers(extra: List<String>): Metainfo = Metainfo(
        infoHash, name, pieceLength, pieceHashes, files, totalLength,
        (trackers + extra).distinct(), isPrivate, rawInfo,
    )

    companion object {
        /** Refuses absurd piece sizes, which would otherwise mean huge in-memory buffers. */
        const val MAX_PIECE_LENGTH = 64 shl 20

        private val WINDOWS_RESERVED = Regex(
            "^(CON|PRN|AUX|NUL|COM[0-9]|LPT[0-9])(\\..*)?$",
            RegexOption.IGNORE_CASE,
        )

        /**
         * Accepts only a plain file or folder name. Rules apply on every platform, so that a
         * torrent that is safe on Linux is also safe on Windows, whichever one reads it.
         */
        fun isSafeName(name: String): Boolean {
            if (name.isEmpty() || name == "." || name == "..") return false
            if (name.any { it == '/' || it == '\\' || it == ':' || it.code < 0x20 || it == '\u007F' }) return false
            if (name.endsWith('.') || name.endsWith(' ')) return false
            return !WINDOWS_RESERVED.matches(name)
        }

        fun parse(source: ByteArray): Metainfo {
            val root = try {
                Bencode.decode(source)
            } catch (e: BencodeError) {
                throw InvalidTorrent("not a valid torrent file: ${e.message}")
            }
            if (root !is BDict) throw InvalidTorrent("a torrent file must be a dictionary")
            val info = root.dict("info") ?: throw InvalidTorrent("missing the info dictionary")

            val trackers = ArrayList<String>()
            root.text("announce")?.let { trackers.add(it) }
            root.list("announce-list")?.items?.forEach { tier ->
                (tier as? BList)?.items?.forEach { url -> (url as? BBytes)?.let { trackers.add(it.text()) } }
            }

            // The hash covers the raw bytes of the info dictionary, never a re-encoding.
            val raw = source.copyOfRange(info.start, info.end)
            return fromInfo(raw, trackers.distinct())
        }

        /** Builds metadata from a raw info dictionary, as received from peers for a magnet link. */
        fun fromInfo(rawInfo: ByteArray, trackers: List<String> = emptyList()): Metainfo {
            val info = try {
                Bencode.decode(rawInfo)
            } catch (e: BencodeError) {
                throw InvalidTorrent("invalid info dictionary: ${e.message}")
            }
            if (info !is BDict) throw InvalidTorrent("info must be a dictionary")

            val name = info.text("name") ?: throw InvalidTorrent("missing a name")
            if (!isSafeName(name)) throw InvalidTorrent("unsafe name: $name")

            val pieceLength = info.int("piece length") ?: throw InvalidTorrent("missing the piece length")
            if (pieceLength !in 1..MAX_PIECE_LENGTH.toLong()) {
                throw InvalidTorrent("piece length $pieceLength is out of range")
            }
            val hashes = info.bytes("pieces") ?: throw InvalidTorrent(
                if (info["meta version"] != null) {
                    "this is a v2-only torrent, which is not supported"
                } else {
                    "missing the piece hashes"
                },
            )
            if (hashes.isEmpty() || hashes.size % 20 != 0) {
                throw InvalidTorrent("piece hashes must be a non-empty multiple of 20 bytes")
            }

            val files = ArrayList<TorrentFile>()
            var total = 0L
            val single = info.int("length")
            val multiple = info.list("files")
            if ((single == null) == (multiple == null)) {
                throw InvalidTorrent("a torrent needs exactly one of length and files")
            }
            if (single != null) {
                if (single <= 0) throw InvalidTorrent("file length $single is invalid")
                files.add(TorrentFile(listOf(name), single, 0))
                total = single
            } else {
                val seen = HashSet<List<String>>()
                for (item in requireNotNull(multiple).items) {
                    val entry = item as? BDict ?: throw InvalidTorrent("a file entry must be a dictionary")
                    val length = entry.int("length") ?: throw InvalidTorrent("a file entry has no length")
                    if (length < 0) throw InvalidTorrent("file length $length is invalid")
                    val parts = entry.list("path")?.items?.map {
                        (it as? BBytes)?.text() ?: throw InvalidTorrent("a path element must be a string")
                    } ?: throw InvalidTorrent("a file entry has no path")
                    if (parts.isEmpty()) throw InvalidTorrent("a file entry has an empty path")
                    for (part in parts) {
                        if (!isSafeName(part)) throw InvalidTorrent("unsafe file path: ${parts.joinToString("/")}")
                    }
                    if (!seen.add(parts)) throw InvalidTorrent("duplicate file: ${parts.joinToString("/")}")
                    files.add(TorrentFile(listOf(name) + parts, length, total))
                    total = Math.addExact(total, length)
                }
                if (files.isEmpty()) throw InvalidTorrent("the torrent lists no files")
                // A file and a folder cannot share one name, and on disk one would clobber the other.
                for (file in files) {
                    for (depth in 2 until file.path.size) {
                        if (file.path.subList(1, depth) in seen) {
                            throw InvalidTorrent("a file is also used as a folder: ${file.path.joinToString("/")}")
                        }
                    }
                }
            }
            if (total <= 0) throw InvalidTorrent("the torrent holds no data")

            val pieces = hashes.size / 20
            val expected = (total + pieceLength - 1) / pieceLength
            if (expected != pieces.toLong()) {
                throw InvalidTorrent("$pieces piece hashes do not match $total bytes at $pieceLength per piece")
            }
            return Metainfo(
                sha1(rawInfo), name, pieceLength.toInt(), hashes, files, total, trackers,
                info.int("private") == 1L, rawInfo,
            )
        }

        /** Creates a torrent from in-memory files, given as path to contents. */
        fun create(
            name: String,
            files: List<Pair<List<String>, ByteArray>>,
            pieceLength: Int,
            trackers: List<String> = emptyList(),
            singleFile: Boolean = files.size == 1 && files[0].first.size == 1 && files[0].first[0] == name,
        ): ByteArray {
            val all = java.io.ByteArrayOutputStream()
            for ((_, data) in files) all.writeBytes(data)
            val joined = all.toByteArray()
            val hashes = java.io.ByteArrayOutputStream()
            var position = 0
            while (position < joined.size) {
                val length = minOf(pieceLength, joined.size - position)
                hashes.writeBytes(sha1(joined, position, length))
                position += length
            }
            val info = HashMap<String, Any>()
            info["name"] = name
            info["piece length"] = pieceLength
            info["pieces"] = hashes.toByteArray()
            if (singleFile) {
                info["length"] = joined.size
            } else {
                info["files"] = files.map { (path, data) -> mapOf("length" to data.size, "path" to path) }
            }
            val root = HashMap<String, Any>()
            root["info"] = info
            if (trackers.isNotEmpty()) {
                root["announce"] = trackers[0]
                root["announce-list"] = trackers.map { listOf(it) }
            }
            return Bencode.encode(root)
        }
    }
}

/** A parsed magnet link. */
class Magnet(val infoHash: ByteArray, val name: String?, val trackers: List<String>, val peers: List<String>) {
    companion object {
        private const val BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

        private fun decodeBase32(text: String): ByteArray? {
            if (text.length != 32) return null
            val out = ByteArray(20)
            var buffer = 0
            var bits = 0
            var index = 0
            for (c in text.uppercase()) {
                val value = BASE32.indexOf(c)
                if (value < 0) return null
                buffer = (buffer shl 5) or value
                bits += 5
                if (bits >= 8) {
                    bits -= 8
                    out[index++] = ((buffer ushr bits) and 0xFF).toByte()
                }
            }
            return out
        }

        fun parse(link: String): Magnet {
            if (!link.startsWith("magnet:?")) throw InvalidTorrent("not a magnet link")
            var hash: ByteArray? = null
            var name: String? = null
            val trackers = ArrayList<String>()
            val peers = ArrayList<String>()
            for (part in link.removePrefix("magnet:?").split('&')) {
                val key = part.substringBefore('=')
                val value = try {
                    URLDecoder.decode(part.substringAfter('=', ""), Charsets.UTF_8)
                } catch (_: IllegalArgumentException) {
                    throw InvalidTorrent("a magnet link has bad percent-encoding")
                }
                when (key) {
                    "xt" -> if (value.startsWith("urn:btih:")) {
                        val text = value.removePrefix("urn:btih:")
                        hash = if (text.length == 40) hexToBytes(text) else decodeBase32(text)
                        if (hash == null) throw InvalidTorrent("a magnet link has a bad info hash")
                    }
                    "dn" -> name = value
                    "tr" -> trackers.add(value)
                    "x.pe" -> peers.add(value)
                }
            }
            return Magnet(
                hash ?: throw InvalidTorrent("a magnet link has no BitTorrent info hash"),
                name,
                trackers,
                peers,
            )
        }
    }
}
