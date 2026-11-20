package dev.aetherx86.rhizome

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A strict JSON reader, used to check that what the command line prints really is JSON. */
internal class JsonReader(private val text: String) {
    private var at = 0

    fun read(): Any? {
        val value = value()
        space()
        require(at == text.length) { "trailing text at $at" }
        return value
    }

    private fun space() {
        while (at < text.length && text[at] in " \t\r\n") at++
    }

    private fun expect(c: Char) {
        require(at < text.length && text[at] == c) { "expected $c at $at in $text" }
        at++
    }

    private fun value(): Any? {
        space()
        require(at < text.length) { "unexpected end" }
        return when (text[at]) {
            '{' -> {
                at++
                val out = LinkedHashMap<String, Any?>()
                space()
                if (text[at] == '}') {
                    at++
                    return out
                }
                while (true) {
                    space()
                    val key = string()
                    space()
                    expect(':')
                    out[key] = value()
                    space()
                    if (text[at] == ',') at++ else break
                }
                expect('}')
                out
            }
            '[' -> {
                at++
                val out = ArrayList<Any?>()
                space()
                if (text[at] == ']') {
                    at++
                    return out
                }
                while (true) {
                    out.add(value())
                    space()
                    if (text[at] == ',') at++ else break
                }
                expect(']')
                out
            }
            '"' -> string()
            't' -> literal("true", true)
            'f' -> literal("false", false)
            'n' -> literal("null", null)
            else -> number()
        }
    }

    private fun literal(word: String, value: Any?): Any? {
        require(text.startsWith(word, at)) { "bad literal at $at" }
        at += word.length
        return value
    }

    private fun number(): Number {
        val match = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?").matchAt(text, at)
            ?: throw IllegalArgumentException("bad number at $at")
        at += match.value.length
        return if (match.groupValues[2].isEmpty() && match.groupValues[3].isEmpty()) match.value.toLong() else match.value.toDouble()
    }

    private fun string(): String {
        expect('"')
        val out = StringBuilder()
        while (true) {
            require(at < text.length) { "unterminated string" }
            val c = text[at++]
            when {
                c == '"' -> return out.toString()
                c == '\\' -> {
                    when (val e = text[at++]) {
                        '"', '\\', '/' -> out.append(e)
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000C')
                        'u' -> {
                            out.append(text.substring(at, at + 4).toInt(16).toChar())
                            at += 4
                        }
                        else -> throw IllegalArgumentException("bad escape $e")
                    }
                }
                c.code < 0x20 -> throw IllegalArgumentException("raw control character in a string")
                else -> out.append(c)
            }
        }
    }
}

@Suppress("UNCHECKED_CAST")
private fun Any?.obj(): Map<String, Any?> = this as Map<String, Any?>

class CliTest {
    private val temp = ArrayList<Path>()
    private val closers = ArrayList<AutoCloseable>()

    @AfterTest
    fun cleanup() {
        for (c in closers.reversed()) runCatching { c.close() }
        for (t in temp) t.toFile().deleteRecursively()
    }

    private fun dir(): Path = Files.createTempDirectory("rhizome-cli").also { temp.add(it) }

    private class Result(val code: Int, val out: String, val err: String) {
        fun json(): Map<String, Any?> = JsonReader(out.trim()).read().obj()
        fun lines(): List<Map<String, Any?>> = out.lines().filter { it.isNotBlank() }.map { JsonReader(it).read().obj() }
    }

    private fun run(vararg args: String): Result {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code = Cli(PrintStream(out, true, Charsets.UTF_8), PrintStream(err, true, Charsets.UTF_8)).run(arrayOf(*args))
        return Result(code, out.toString(Charsets.UTF_8), err.toString(Charsets.UTF_8))
    }

    private fun bytes(size: Int, seed: Long) = ByteArray(size).also { Random(seed).nextBytes(it) }

    /** A folder of three files, one empty, one in a subfolder. */
    private fun album(): Path {
        val root = dir().resolve("album")
        Files.createDirectories(root.resolve("sub"))
        Files.write(root.resolve("a.txt"), bytes(100_000, 1))
        Files.write(root.resolve("empty.txt"), ByteArray(0))
        Files.write(root.resolve("sub").resolve("b.bin"), bytes(70_000, 2))
        return root
    }

    @Test
    fun versionIsReported() {
        assertEquals("rhizome $RHIZOME_VERSION", run("version").out.trim())
        assertEquals(mapOf("version" to RHIZOME_VERSION), run("--version", "--json").json())
        assertTrue(RhizomeClient().use { String(it.peerId, 0, 8) } == "-RZ0200-")
    }

    @Test
    fun createThenInfoDescribeTheSameTorrent() {
        val album = album()
        val torrent = dir().resolve("album.torrent")
        val created = run("create", album.toString(), "--out", torrent.toString(), "--piece-length", "16K", "--tracker", "udp://tracker.example:6969", "--json")
        assertEquals(0, created.code, created.err)
        val summary = created.json()
        assertEquals("album", summary["name"])
        assertEquals(170_000L, summary["total_length"])
        assertEquals(16_384L, summary["piece_length"])
        assertEquals(11L, summary["piece_count"])
        assertEquals(3L, summary["files"])

        val info = run("info", torrent.toString(), "--json").json()
        assertEquals(summary["info_hash"], info["info_hash"])
        assertEquals(listOf("udp://tracker.example:6969"), info["trackers"])
        val files = (info["files"] as List<*>).map { it.obj() }
        assertEquals(listOf(listOf("album", "a.txt"), listOf("album", "empty.txt"), listOf("album", "sub", "b.bin")), files.map { it["path"] })
        assertEquals(listOf(0L, 100_000L, 100_000L), files.map { it["offset"] })

        // The same folder always gives the same torrent.
        val again = dir().resolve("again.torrent")
        assertEquals(0, run("create", album.toString(), "--out", again.toString(), "--piece-length", "16K", "--tracker", "udp://tracker.example:6969").code)
        assertContentEquals(Files.readAllBytes(torrent), Files.readAllBytes(again))

        val text = run("info", torrent.toString())
        assertTrue("info hash:    ${summary["info_hash"]}" in text.out, text.out)
    }

    @Test
    fun aSingleFileTorrentPicksItsOwnPieceSize() {
        val file = dir().resolve("big.bin")
        Files.write(file, bytes(3_000_000, 3))
        val torrent = dir().resolve("big.torrent")
        val result = run("create", file.toString(), "--out", torrent.toString(), "--private", "--json").json()
        assertEquals(16_384L, result["piece_length"])
        assertEquals(true, result["private"])
        val meta = Metainfo.parse(Files.readAllBytes(torrent))
        assertEquals(listOf("big.bin"), meta.files.single().path)
        assertTrue(meta.isPrivate)

        assertEquals(16 * 1024, TorrentCreator.choosePieceLength(1))
        assertEquals(512 * 1024, TorrentCreator.choosePieceLength(1L shl 30))
        assertEquals(16 shl 20, TorrentCreator.choosePieceLength(1L shl 50))
    }

    @Test
    fun createRefusesBadInput() {
        val album = album()
        val out = dir().resolve("x.torrent").toString()
        assertEquals(2, run("create", album.toString(), "--out", out, "--piece-length", "1000").code, "not a power of two")
        assertEquals(2, run("create", album.toString(), "--out", out, "--piece-length", "8K").code, "too small")
        assertEquals(2, run("create", album.toString()).code, "no --out")
        assertEquals(2, run("create", album.toString(), "--out", out, "--bogus").code, "unknown option")

        val empty = dir().resolve("nothing")
        Files.createDirectories(empty)
        assertEquals(2, run("create", empty.toString(), "--out", out).code, "no data")

        Files.createSymbolicLink(album.resolve("link"), dir())
        val linked = run("create", album.toString(), "--out", out, "--json")
        assertEquals(1, linked.code)
        assertTrue("symbolic link" in linked.json()["error"].toString(), linked.out)
        assertTrue(Files.notExists(java.nio.file.Paths.get(out)), "a failed create must not leave a torrent behind")
    }

    @Test
    fun verifyFindsTheDamagedPieceAndFile() {
        val album = album()
        val torrent = dir().resolve("album.torrent")
        assertEquals(0, run("create", album.toString(), "--out", torrent.toString(), "--piece-length", "16K").code)

        val clean = run("verify", torrent.toString(), "--dir", album.parent.toString(), "--json")
        assertEquals(0, clean.code, clean.out + clean.err)
        assertEquals(true, clean.json()["complete"])
        assertEquals(11L, clean.json()["pieces_verified"])

        // Byte 169,000 of the torrent is in piece 10, inside sub/b.bin.
        val b = album.resolve("sub").resolve("b.bin")
        val damaged = Files.readAllBytes(b).also { it[69_000] = (it[69_000] + 1).toByte() }
        Files.write(b, damaged)
        val result = run("verify", torrent.toString(), "--dir", album.parent.toString(), "--json")
        assertEquals(1, result.code)
        val report = result.json()
        assertEquals(false, report["complete"])
        assertEquals(listOf(10L), report["missing_pieces"])
        val files = (report["files"] as List<*>).map { it.obj() }
        assertEquals(listOf(true, true, false), files.map { it["complete"] })

        val text = run("verify", torrent.toString(), "--dir", album.parent.toString())
        assertTrue("10 of 11 verified" in text.out && "incomplete    album/sub/b.bin" in text.out, text.out)
    }

    @Test
    fun verifyNeverWritesAnything() {
        val album = album()
        val torrent = dir().resolve("album.torrent")
        assertEquals(0, run("create", album.toString(), "--out", torrent.toString()).code)
        val elsewhere = dir()
        val result = run("verify", torrent.toString(), "--dir", elsewhere.toString(), "--json")
        assertEquals(1, result.code)
        assertEquals(0L, result.json()["pieces_verified"])
        assertEquals(0, elsewhere.toFile().list()!!.size, "verify created files")
        assertEquals(1, run("verify", torrent.toString(), "--dir", elsewhere.resolve("missing").toString()).code)
    }

    @Test
    fun errorsAreReportedAsJsonWhenAskedFor() {
        val junk = dir().resolve("junk.torrent")
        Files.write(junk, "not a torrent".toByteArray())
        val result = run("info", junk.toString(), "--json")
        assertEquals(1, result.code)
        assertTrue("not a valid torrent" in result.json()["error"].toString())
        assertEquals(2, run("lookup", "xyz", "--json").code)
        assertEquals(2, run("frobnicate").code)
        val plain = run("info", junk.toString())
        assertTrue(plain.err.startsWith("error: "), plain.err)
    }

    @Test
    fun downloadPrintsJsonLinesAndFinishes() {
        val album = album()
        val torrentFile = dir().resolve("album.torrent")
        assertEquals(0, run("create", album.toString(), "--out", torrentFile.toString(), "--piece-length", "16K").code)
        val meta = Metainfo.parse(Files.readAllBytes(torrentFile))
        val seeder = RhizomeClient(ClientConfig(allowPrivateAddresses = true)).also { closers.add(it) }
        val seed = seeder.add(meta, album.parent)
        runBlocking { withTimeout(30_000) { seed.awaitComplete() } }

        val target = dir()
        val result = run(
            "download", torrentFile.toString(), "--out", target.toString(),
            "--peer", "127.0.0.1:${seeder.port}", "--no-dht", "--exit-when-done", "--json",
        )
        assertEquals(0, result.code, result.out + result.err)
        val events = result.lines()
        assertEquals("listening", events.first()["event"])
        val last = events.last()
        assertEquals("complete", last["event"])
        assertEquals("seeding", last["state"])
        assertEquals(1.0, last["progress"])
        assertEquals(11L, last["pieces_verified"])
        assertEquals(meta.infoHash.toHex(), last["info_hash"])
        for (path in listOf(listOf("a.txt"), listOf("empty.txt"), listOf("sub", "b.bin"))) {
            val original = path.fold(album) { p, part -> p.resolve(part) }
            val copy = path.fold(target.resolve("album")) { p, part -> p.resolve(part) }
            assertContentEquals(Files.readAllBytes(original), Files.readAllBytes(copy), path.joinToString("/"))
        }
    }

    @Test
    fun lookupFindsAnAnnouncedPeerOnALoopbackNetwork() {
        val network = (0 until 8).map {
            DhtNode(0, ByteArray(20).also { id -> Random(100L + it).nextBytes(id) }, true, queryTimeoutMillis = 500)
                .also { node -> closers.add(node) }
        }
        val hash = ByteArray(20).also { Random(9).nextBytes(it) }
        runBlocking {
            for (joining in network.drop(1)) joining.bootstrap(listOf(network[0].address))
            for (joining in network) joining.findNode(joining.id)
            assertTrue(network[3].announce(hash, 51413) > 0)
        }
        val result = run("lookup", hash.toHex(), "--bootstrap", "127.0.0.1:${network[0].port}", "--allow-private", "--json")
        assertEquals(0, result.code, result.out + result.err)
        val report = result.json()
        assertEquals(false, report["bootstrap_timed_out"])
        assertTrue((report["routing_table"] as Long) > 0)
        assertEquals(listOf("127.0.0.1:51413"), report["peers"])
    }
}
