# Rhizome

A BitTorrent client and Kademlia DHT node in Kotlin, built on structured concurrency: one coroutine
per peer, with a bounded channel between it and the swarm.

A torrent client takes input from strangers all day, from the `.torrent` file to every peer, tracker
and DHT node it talks to. So this one is written on the assumption that all of them are hostile.

```text
rhizome info ubuntu.torrent
rhizome download ubuntu.torrent --out ~/Downloads
rhizome download "magnet:?xt=urn:btih:..." --out ~/Downloads --exit-when-done
rhizome lookup c12fe1c06bba254a9dc9f519b335aa7c1367a88a
```

## What it speaks

| Feature | Specification |
| --- | --- |
| Torrent files, peer wire protocol, HTTP trackers | BEP 3 |
| Compact peer lists | BEP 23 |
| UDP trackers | BEP 15 |
| DHT node: `ping`, `find_node`, `get_peers`, `announce_peer` | BEP 5 |
| Extension protocol and metadata exchange, which make magnet links work | BEP 10, BEP 9 |

## How it works

- **Info hash.** The hash covers the exact raw bytes of the `info` dictionary, recorded during parsing.
  Re-encoding a parsed dictionary gives the wrong hash for any file that was not perfectly canonical.
- **Piece picking.** Partly downloaded pieces come first, then the rarest piece in the swarm. At the very
  end, endgame mode lets peers race for the last blocks, and the losers get a `cancel`.
- **Pipelining.** Up to 16 requests stay in flight per peer. Waiting for each 16 KiB block before asking
  for the next would cap throughput by latency rather than bandwidth.
- **Lookups.** `get_peers` walks closer and closer to the target by XOR distance, asking three nodes at a
  time, and stops when the eight closest it knows have all answered.

## Security

| Threat | Defense |
| --- | --- |
| A torrent names `../../.ssh/authorized_keys` | File names are checked on every platform's rules, rejecting traversal, separators, drive letters, reserved Windows names and control characters. |
| A folder in the download directory is a symbolic link | Writes refuse to follow links. |
| A torrent's tracker URL points at `http://169.254.169.254/` or a router's admin page | Private, loopback and link-local addresses are refused unless `--allow-private` is set, and redirects are never followed. |
| A peer sends corrupt data | Pieces stay in memory until their SHA-1 matches, so bad data never reaches the disk. Peers responsible for three bad pieces are dropped. |
| A peer pushes data nobody asked for | Only blocks that were actually requested from that peer are accepted. |
| A forged length asks for a huge allocation | Message, metadata and tracker response sizes are all capped before anything is allocated. |
| A peer sends fake metadata for a magnet link | Metadata has to match the info hash before anything is parsed or written. |
| A peer requests a block outside a piece, or data while choked | The request is refused, and an out-of-range one closes the connection. |
| A forged DHT reply | A reply has to come from the address that was asked and carry its transaction id. |
| Fake nodes try to flood the routing table | A node joins the table only after answering a ping, and a full bucket keeps its live contacts. |
| An `announce_peer` lists a victim's address | Tokens tie an announce to the address that requested one. |
| A hostile DHT node names private addresses | Those addresses are dropped, so lookups cannot be aimed inside your network. |

## Usage as a library

Published to GitHub Packages as `dev.aetherx86.rhizome:rhizome`.

```kotlin
val client = RhizomeClient()
val session = client.add(Metainfo.parse(File("a.torrent").readBytes()), Paths.get("downloads"))
session.connect(InetSocketAddress("192.0.2.10", 6881))
session.startDiscovery()
session.awaitComplete()
```

## How it is tested

- Whole swarms run over real sockets on loopback: single and multi-file torrents, files crossing piece
  boundaries, resuming, leechers trading with each other, and magnet links. Downloads are compared with
  the original byte for byte.
- Scripted hostile peers send corrupt pieces, unsolicited blocks, forged metadata and malformed messages.
- A real DHT network of 30 nodes on loopback checks that lookups reach the truly closest node and route
  around dead ones, and two clients find each other through it and transfer a file.
- The Bencode decoder and the message parser take random and truncated input.
- Security checks were deliberately removed, one at a time, to confirm that the tests notice. Of the
  28 removed, 27 made the suite fail. The last is the check for `..` in file names, which a second rule
  also covers.

## Versioning

A bug or security fix increments `0.0.X`. New functionality or new platform support increments
`0.X.0`. See [CHANGELOG.md](CHANGELOG.md).

## License

MIT. See [LICENSE](LICENSE).

## Limitations

- **Transport protocol**: Peer connections use standard TCP peer wire transport (BEP 3) and UDP trackers/DHT (BEP 5, 15); uTP (`BEP 29`) congestion control and WebTorrent (`WebRTC`) peers are not included.
- **NAT traversal**: Listening ports must be reachable or mapped via router port forwarding; automatic UPnP/NAT-PMP port mapping is not built in.
