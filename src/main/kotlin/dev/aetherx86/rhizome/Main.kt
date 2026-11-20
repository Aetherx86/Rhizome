package dev.aetherx86.rhizome

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.PrintStream
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.BitSet
import kotlin.system.exitProcess

const val RHIZOME_VERSION = "0.2.0"

private val USAGE = """
    usage: rhizome <command> [options]

      info <file.torrent>             show what a torrent contains
      create <file or folder>         make a torrent from data on disk
          --out FILE                  where to write the torrent (required)
          --piece-length N            piece size in bytes, or with a K or M suffix (default: automatic)
          --tracker URL               tracker to list in the torrent (repeatable)
          --private                   mark the torrent private (BEP 27): no DHT and no peer exchange
      verify <file.torrent>           check the data on disk against the torrent's piece hashes
          --dir DIR                   folder holding the data (default: current folder)
      download <file.torrent|magnet>  download, and keep seeding until stopped
          --out DIR                   where to put the files (default: current folder)
          --port N                    port to listen on (default: any free port)
          --peer HOST:PORT            connect to this peer (repeatable)
          --exit-when-done            stop as soon as the download completes
          --no-dht                    do not use the DHT
          --bootstrap HOST:PORT       DHT node to join through (repeatable)
          --allow-private             allow trackers and peers on private addresses
      lookup <hex info hash>          find peers for a torrent on the DHT
          --bootstrap HOST:PORT       DHT node to join through (repeatable)
          --allow-private             allow DHT nodes on private addresses
      version                         print the version

    Every command accepts --json, which prints machine-readable JSON instead of text.
    download prints one JSON object per line as it goes.
""".trimIndent()

private val DEFAULT_BOOTSTRAP = listOf(
    "router.bittorrent.com:6881",
    "dht.transmissionbt.com:6881",
    "router.utorrent.com:6881",
)

private val VALUE_OPTIONS = mapOf(
    "info" to emptySet(),
    "create" to setOf("--out", "--piece-length", "--tracker"),
    "verify" to setOf("--dir"),
    "download" to setOf("--out", "--port", "--peer", "--bootstrap"),
    "lookup" to setOf("--bootstrap"),
    "version" to emptySet<String>(),
)

private val FLAG_OPTIONS = mapOf(
    "info" to setOf("--json"),
    "create" to setOf("--json", "--private"),
    "verify" to setOf("--json"),
    "download" to setOf("--json", "--exit-when-done", "--no-dht", "--allow-private"),
    "lookup" to setOf("--json", "--allow-private"),
    "version" to setOf("--json"),
)

private class Options(command: String, args: List<String>) {
    val positional = ArrayList<String>()
    val values = HashMap<String, MutableList<String>>()
    val flags = HashSet<String>()

    init {
        val takesValue = VALUE_OPTIONS.getValue(command)
        val knownFlags = FLAG_OPTIONS.getValue(command)
        var i = 0
        while (i < args.size) {
            val arg = args[i]
            when {
                arg in takesValue -> {
                    require(i + 1 < args.size) { "$arg needs a value" }
                    values.getOrPut(arg) { ArrayList() }.add(args[i + 1])
                    i++
                }
                arg in knownFlags -> flags.add(arg)
                arg.startsWith("--") -> throw IllegalArgumentException("unknown option for $command: $arg")
                else -> positional.add(arg)
            }
            i++
        }
    }

    val json: Boolean get() = "--json" in flags

    fun single(name: String): String? {
        val all = values[name] ?: return null
        require(all.size == 1) { "$name can only be given once" }
        return all[0]
    }
}

private fun hostPort(text: String): InetSocketAddress {
    val host = text.substringBeforeLast(':', "").removeSurrounding("[", "]")
    val port = text.substringAfterLast(':').toIntOrNull()
    require(host.isNotEmpty() && port != null && port in 1..65535) { "expected HOST:PORT, got $text" }
    return InetSocketAddress(host, port)
}

private fun formatAddress(address: InetSocketAddress): String {
    val host = address.address?.hostAddress ?: address.hostString
    return if (address.address is Inet6Address) "[$host]:${address.port}" else "$host:${address.port}"
}

/** Reads a size such as `262144`, `256K` or `4M`. */
internal fun parseSize(text: String): Int {
    val multiplier = when (text.lastOrNull()?.uppercaseChar()) {
        'K' -> 1024L
        'M' -> 1024L * 1024
        else -> 1L
    }
    val digits = if (multiplier == 1L) text else text.dropLast(1)
    val value = digits.toLongOrNull()?.times(multiplier)
    require(value != null && value in 1..Int.MAX_VALUE) { "not a valid size: $text" }
    return value.toInt()
}

/** Whether every piece holding part of each file is verified. Empty files only need to exist. */
private fun fileCompletion(meta: Metainfo, verified: BitSet, root: Path): List<Boolean> = meta.files.map { file ->
    if (file.length == 0L) {
        var path = root
        for (part in file.path) path = path.resolve(part)
        Files.exists(path)
    } else {
        val first = (file.offset / meta.pieceLength).toInt()
        val last = ((file.offset + file.length - 1) / meta.pieceLength).toInt()
        verified.nextClearBit(first) > last
    }
}

/** The command line. Output goes to [out] and errors to [err], so that it can run in-process. */
class Cli(private val out: PrintStream = System.out, private val err: PrintStream = System.err) {
    private fun emit(value: Map<String, Any?>) {
        out.println(Json.encode(value))
        out.flush()
    }

    private fun showInfo(options: Options) {
        val path = options.positional.singleOrNull() ?: throw IllegalArgumentException("which torrent file?")
        val meta = Metainfo.parse(File(path).readBytes())
        if (options.json) {
            emit(
                linkedMapOf(
                    "name" to meta.name,
                    "info_hash" to meta.infoHash.toHex(),
                    "total_length" to meta.totalLength,
                    "piece_length" to meta.pieceLength,
                    "piece_count" to meta.pieceCount,
                    "private" to meta.isPrivate,
                    "trackers" to meta.trackers,
                    "files" to meta.files.map { linkedMapOf("path" to it.path, "length" to it.length, "offset" to it.offset) },
                ),
            )
            return
        }
        out.println("name:         ${meta.name}")
        out.println("info hash:    ${meta.infoHash.toHex()}")
        out.println("size:         ${meta.totalLength} bytes in ${meta.pieceCount} pieces of ${meta.pieceLength}")
        out.println("private:      ${meta.isPrivate}")
        for (url in meta.trackers) out.println("tracker:      $url")
        for (file in meta.files) out.println("file:         ${file.path.joinToString("/")} (${file.length} bytes)")
    }

    private fun create(options: Options) {
        val source = options.positional.singleOrNull() ?: throw IllegalArgumentException("which file or folder?")
        val target = options.single("--out") ?: throw IllegalArgumentException("create needs --out FILE")
        val pieceLength = options.single("--piece-length")?.let(::parseSize)
        val bytes = TorrentCreator.create(
            Paths.get(source),
            pieceLength,
            options.values["--tracker"].orEmpty(),
            "--private" in options.flags,
        )
        Files.write(Paths.get(target), bytes)
        val meta = Metainfo.parse(bytes)
        if (options.json) {
            emit(
                linkedMapOf(
                    "torrent" to target,
                    "name" to meta.name,
                    "info_hash" to meta.infoHash.toHex(),
                    "total_length" to meta.totalLength,
                    "piece_length" to meta.pieceLength,
                    "piece_count" to meta.pieceCount,
                    "files" to meta.files.size,
                    "private" to meta.isPrivate,
                ),
            )
        } else {
            out.println("wrote $target")
            out.println("info hash:    ${meta.infoHash.toHex()}")
            out.println("size:         ${meta.totalLength} bytes in ${meta.pieceCount} pieces of ${meta.pieceLength}")
            out.println("magnet:       magnet:?xt=urn:btih:${meta.infoHash.toHex()}")
        }
    }

    /** Returns 0 if every piece checks out, and 1 otherwise. */
    private fun verify(options: Options): Int {
        val path = options.positional.singleOrNull() ?: throw IllegalArgumentException("which torrent file?")
        val meta = Metainfo.parse(File(path).readBytes())
        val root = Paths.get(options.single("--dir") ?: ".")
        if (!Files.isDirectory(root)) throw java.io.IOException("no such folder: $root")
        val verified = PieceStore(meta, root, readOnly = true).use { it.verifyExisting() }
        val files = fileCompletion(meta, verified, root.toRealPath())
        val complete = verified.cardinality() == meta.pieceCount
        if (options.json) {
            emit(
                linkedMapOf(
                    "name" to meta.name,
                    "info_hash" to meta.infoHash.toHex(),
                    "complete" to complete,
                    "pieces_verified" to verified.cardinality(),
                    "piece_count" to meta.pieceCount,
                    "missing_pieces" to (0 until meta.pieceCount).filter { !verified[it] },
                    "files" to meta.files.zip(files).map { (file, done) ->
                        linkedMapOf("path" to file.path, "length" to file.length, "complete" to done)
                    },
                ),
            )
        } else {
            out.println("pieces:       ${verified.cardinality()} of ${meta.pieceCount} verified")
            for ((file, done) in meta.files.zip(files)) {
                out.println("${if (done) "ok        " else "incomplete"}    ${file.path.joinToString("/")}")
            }
            out.println(if (complete) "complete" else "incomplete")
        }
        return if (complete) 0 else 1
    }

    private suspend fun download(options: Options) {
        val target = options.positional.singleOrNull() ?: throw IllegalArgumentException("what should be downloaded?")
        val directory = Paths.get(options.single("--out") ?: ".")
        val allowPrivate = "--allow-private" in options.flags
        val peers = options.values["--peer"].orEmpty().map(::hostPort)
        val port = options.single("--port")?.let { text ->
            text.toIntOrNull()?.takeIf { it in 0..65535 } ?: throw IllegalArgumentException("not a valid port: $text")
        } ?: 0
        val dht = if ("--no-dht" in options.flags) null else DhtNode(allowPrivateAddresses = allowPrivate)
        val config = ClientConfig(port = port, allowPrivateAddresses = allowPrivate)
        try {
            RhizomeClient(config, dht).use { client ->
                if (options.json) emit(linkedMapOf("event" to "listening", "port" to client.port))
                else out.println("listening on port ${client.port}")
                val session = if (target.startsWith("magnet:")) {
                    client.addMagnet(Magnet.parse(target), directory)
                } else {
                    client.add(Metainfo.parse(File(target).readBytes()), directory)
                }
                if (dht != null) {
                    val bootstrap = options.values["--bootstrap"] ?: DEFAULT_BOOTSTRAP
                    client.scope.launch {
                        dht.bootstrap(bootstrap.mapNotNull { runCatching { hostPort(it) }.getOrNull() })
                    }
                    dht.startMaintenance()
                }
                session.startDiscovery()
                peers.forEach { session.connect(it) }

                while (!session.isComplete) {
                    delay(1000)
                    val status = session.status()
                    if (options.json) {
                        emit(linkedMapOf<String, Any?>("event" to "progress") + status.toMap())
                    } else {
                        out.println(
                            "%5.1f%%  %d peers  down %d  up %d  rejected %d".format(
                                status.progress * 100,
                                status.peers,
                                status.downloaded,
                                status.uploaded,
                                status.hashFailures,
                            ),
                        )
                    }
                }
                if (options.json) emit(linkedMapOf<String, Any?>("event" to "complete") + session.status().toMap())
                else out.println("complete: every piece is verified and on disk")
                if ("--exit-when-done" !in options.flags) {
                    if (options.json) emit(linkedMapOf("event" to "seeding"))
                    else out.println("seeding, press Ctrl-C to stop")
                    while (true) {
                        delay(60_000)
                        if (options.json) emit(linkedMapOf<String, Any?>("event" to "progress") + session.status().toMap())
                    }
                }
            }
        } finally {
            dht?.close()
        }
    }

    private suspend fun lookup(options: Options) {
        val hash = options.positional.singleOrNull()?.let(::hexToBytes)
        require(hash != null && hash.size == 20) { "expected a 40 character hex info hash" }
        val allowPrivate = "--allow-private" in options.flags
        DhtNode(allowPrivateAddresses = allowPrivate).use { node ->
            val bootstrap = options.values["--bootstrap"] ?: DEFAULT_BOOTSTRAP
            val joined = withTimeoutOrNull(15_000) {
                node.bootstrap(bootstrap.mapNotNull { runCatching { hostPort(it) }.getOrNull() })
            }
            val tableSize = node.table.size
            val result = node.getPeers(hash)
            if (options.json) {
                emit(
                    linkedMapOf(
                        "info_hash" to hash.toHex(),
                        "bootstrap_timed_out" to (joined == null),
                        "routing_table" to tableSize,
                        "closest_nodes" to result.closest.size,
                        "queries_sent" to node.queriesSent,
                        "peers" to result.peers.map(::formatAddress),
                    ),
                )
                return
            }
            if (joined == null) out.println("warning: bootstrap timed out")
            out.println("routing table: $tableSize nodes")
            out.println("closest answering nodes: ${result.closest.size}")
            out.println("peers found: ${result.peers.size}")
            for (peer in result.peers) out.println("  ${formatAddress(peer)}")
        }
    }

    private fun fail(json: Boolean, message: String?) {
        if (json) emit(linkedMapOf("error" to message)) else err.println("error: $message")
    }

    /** Runs one command and returns the exit code: 0 for success, 1 for failure, 2 for bad usage. */
    fun run(args: Array<String>): Int {
        if (args.isEmpty() || args[0] in setOf("help", "--help", "-h")) {
            out.println(USAGE)
            return 0
        }
        val command = if (args[0] == "--version") "version" else args[0]
        if (command !in VALUE_OPTIONS) {
            err.println(USAGE)
            return 2
        }
        val json = "--json" in args
        return try {
            val options = Options(command, args.drop(1))
            runBlocking {
                when (command) {
                    "info" -> showInfo(options).let { 0 }
                    "create" -> create(options).let { 0 }
                    "verify" -> verify(options)
                    "download" -> download(options).let { 0 }
                    "lookup" -> lookup(options).let { 0 }
                    else -> {
                        if (options.json) emit(linkedMapOf("version" to RHIZOME_VERSION)) else out.println("rhizome $RHIZOME_VERSION")
                        0
                    }
                }
            }
        } catch (e: InvalidTorrent) {
            fail(json, e.message)
            1
        } catch (e: IllegalArgumentException) {
            fail(json, e.message)
            2
        } catch (e: java.io.IOException) {
            fail(json, e.message ?: e.toString())
            1
        }
    }
}

fun main(args: Array<String>) {
    exitProcess(Cli().run(args))
}
