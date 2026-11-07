package dev.aetherx86.rhizome

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Paths
import kotlin.system.exitProcess

private val USAGE = """
    usage: rhizome <command> [options]

      info <file.torrent>             show what a torrent contains
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
""".trimIndent()

private val DEFAULT_BOOTSTRAP = listOf(
    "router.bittorrent.com:6881",
    "dht.transmissionbt.com:6881",
    "router.utorrent.com:6881",
)

private class Options(args: List<String>) {
    val positional = ArrayList<String>()
    val values = HashMap<String, MutableList<String>>()
    val flags = HashSet<String>()

    init {
        val takesValue = setOf("--out", "--port", "--peer", "--bootstrap")
        var i = 0
        while (i < args.size) {
            val arg = args[i]
            when {
                arg in takesValue -> {
                    require(i + 1 < args.size) { "$arg needs a value" }
                    values.getOrPut(arg) { ArrayList() }.add(args[i + 1])
                    i++
                }
                arg.startsWith("--") -> flags.add(arg)
                else -> positional.add(arg)
            }
            i++
        }
    }
}

private fun hostPort(text: String): InetSocketAddress {
    val host = text.substringBeforeLast(':', "")
    val port = text.substringAfterLast(':').toIntOrNull()
    require(host.isNotEmpty() && port != null && port in 1..65535) { "expected HOST:PORT, got $text" }
    return InetSocketAddress(host, port)
}

private fun showInfo(path: String) {
    val meta = Metainfo.parse(File(path).readBytes())
    println("name:         ${meta.name}")
    println("info hash:    ${meta.infoHash.toHex()}")
    println("size:         ${meta.totalLength} bytes in ${meta.pieceCount} pieces of ${meta.pieceLength}")
    println("private:      ${meta.isPrivate}")
    for (url in meta.trackers) println("tracker:      $url")
    for (file in meta.files) println("file:         ${file.path.joinToString("/")} (${file.length} bytes)")
}

private suspend fun download(options: Options) {
    val target = options.positional.firstOrNull() ?: throw IllegalArgumentException("what should be downloaded?")
    val out = Paths.get(options.values["--out"]?.first() ?: ".")
    val allowPrivate = "--allow-private" in options.flags
    val dht = if ("--no-dht" in options.flags) {
        null
    } else {
        DhtNode(allowPrivateAddresses = allowPrivate)
    }
    val config = ClientConfig(
        port = options.values["--port"]?.first()?.toInt() ?: 0,
        allowPrivateAddresses = allowPrivate,
    )
    RhizomeClient(config, dht).use { client ->
        println("listening on port ${client.port}")
        val session = if (target.startsWith("magnet:")) {
            client.addMagnet(Magnet.parse(target), out)
        } else {
            client.add(Metainfo.parse(File(target).readBytes()), out)
        }
        if (dht != null) {
            val bootstrap = options.values["--bootstrap"] ?: DEFAULT_BOOTSTRAP
            client.scope.launch {
                dht.bootstrap(bootstrap.mapNotNull { runCatching { hostPort(it) }.getOrNull() })
            }
        }
        session.startDiscovery()
        options.values["--peer"]?.forEach { session.connect(hostPort(it)) }

        while (!session.isComplete) {
            delay(1000)
            println(
                "%5.1f%%  %d peers  down %d  up %d  rejected %d".format(
                    session.progress() * 100,
                    session.peerCount,
                    session.downloaded,
                    session.uploaded,
                    session.hashFailures,
                ),
            )
        }
        println("complete: every piece is verified and on disk")
        if ("--exit-when-done" !in options.flags) {
            println("seeding, press Ctrl-C to stop")
            while (true) delay(60_000)
        }
    }
    dht?.close()
}

private suspend fun lookup(options: Options) {
    val hash = options.positional.firstOrNull()?.let(::hexToBytes)
    require(hash != null && hash.size == 20) { "expected a 40 character hex info hash" }
    DhtNode().use { node ->
        val bootstrap = options.values["--bootstrap"] ?: DEFAULT_BOOTSTRAP
        val joined = withTimeoutOrNull(15_000) {
            node.bootstrap(bootstrap.mapNotNull { runCatching { hostPort(it) }.getOrNull() })
        }
        if (joined == null) println("warning: bootstrap timed out")
        println("routing table: ${node.table.size} nodes")
        val result = node.getPeers(hash)
        println("closest answering nodes: ${result.closest.size}")
        println("peers found: ${result.peers.size}")
        for (peer in result.peers) println("  ${peer.address.hostAddress}:${peer.port}")
    }
}

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        println(USAGE)
        return
    }
    try {
        val options = Options(args.drop(1))
        runBlocking {
            when (args[0]) {
                "info" -> showInfo(options.positional.firstOrNull() ?: throw IllegalArgumentException("which file?"))
                "download" -> download(options)
                "lookup" -> lookup(options)
                else -> {
                    println(USAGE)
                    exitProcess(2)
                }
            }
        }
        exitProcess(0)
    } catch (e: InvalidTorrent) {
        System.err.println("error: ${e.message}")
        exitProcess(1)
    } catch (e: IllegalArgumentException) {
        System.err.println("error: ${e.message}")
        exitProcess(2)
    } catch (e: java.io.IOException) {
        System.err.println("error: ${e.message}")
        exitProcess(1)
    }
}
