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
rhizome create seed/photos --out photos.torrent
rhizome verify photos.torrent --dir seed --json
```

## What it speaks

| Feature | Specification |
| --- | --- |
| Torrent files, peer wire protocol, HTTP trackers | BEP 3 |
| Compact peer lists | BEP 23 |
| UDP trackers | BEP 15 |
| DHT node: `ping`, `find_node`, `get_peers`, `announce_peer` | BEP 5 |
| Extension protocol and metadata exchange, which make magnet links work | BEP 10, BEP 9 |
| Peer exchange | BEP 11 |
| Private torrents: no DHT and no peer exchange | BEP 27 |

## How it works

- **Info hash.** The hash covers the exact raw bytes of the `info` dictionary, recorded during parsing.
  Re-encoding a parsed dictionary gives the wrong hash for any file that was not perfectly canonical.
- **Piece picking.** Partly downloaded pieces come first, then the rarest piece in the swarm. At the very
  end, endgame mode lets peers race for the last blocks, and the losers get a `cancel`.
- **Pipelining.** Up to 16 requests stay in flight per peer. Waiting for each 16 KiB block before asking
  for the next would cap throughput by latency rather than bandwidth.
- **Lookups.** `get_peers` walks closer and closer to the target by XOR distance, asking three nodes at a
  time, and stops when the eight closest it knows have all answered.
- **Routing table upkeep.** A full bucket keeps newcomers in a replacement cache. A contact that misses
  two queries in a row is removed, or one if it has been quiet for 15 minutes and a replacement is waiting,
  and the freshest replacement takes its place. Buckets nobody touched for 15 minutes are refreshed with a
  lookup for a random id in their range.
- **Peer exchange.** Once a minute each peer hears which peers joined and left the swarm since the last
  message. A peer that dialed us is only shared if it told us its listening port.

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
| A peer exchange message lists private addresses, or hundreds of victims | Private addresses are refused unless `--allow-private` is set, only the first 50 entries of a message are used, and messages faster than the agreed rate are ignored. |
| A flood of fake DHT ids tries to push out real contacts | Newcomers wait in a replacement cache, and a contact is only replaced once it stops answering. |

## Creating, verifying and scripting (0.2.0)

`create` makes a torrent from a file or folder. Files are taken in name order and no creation date is
written, so the same input always gives the same info hash. Without `--piece-length`, the piece size is
the smallest power of two from 16 KiB that keeps the torrent near 2048 pieces, up to 16 MiB.

```text
$ rhizome create seed/photos --out photos.torrent
wrote photos.torrent
info hash:    e3846f7e1a3ab3fdd4a23048ee61d21281ab451b
size:         420000 bytes in 26 pieces of 16384
magnet:       magnet:?xt=urn:btih:e3846f7e1a3ab3fdd4a23048ee61d21281ab451b
```

Options: `--piece-length N` (bytes, or with a `K` or `M` suffix, a power of two from 16 KiB to 64 MiB),
`--tracker URL` (repeatable) and `--private`. Symbolic links and unsafe file names are refused.

`verify` hashes the data on disk and writes nothing. It exits with 0 when every piece checks out and 1
otherwise.

```text
$ rhizome verify photos.torrent --dir seed
pieces:       26 of 26 verified
ok            photos/a.jpg
ok            photos/raw/b.raw
complete
```

Every command takes `--json`. `download --json` prints one object per line: `listening`, then
`progress` each second, then `complete`.

```text
$ rhizome download photos.torrent --out dl --peer 127.0.0.1:51515 --no-dht --exit-when-done --json
{"event":"listening","port":57931}
{"event":"progress","info_hash":"e3846f7e1a3ab3fdd4a23048ee61d21281ab451b","name":"photos","state":"seeding","pieces_verified":26,"piece_count":26,"progress":1.0,"peers":1,"downloaded":420000,"uploaded":0,"hash_failures":0,"pex_peers_learned":0}
{"event":"complete","info_hash":"e3846f7e1a3ab3fdd4a23048ee61d21281ab451b","name":"photos","state":"seeding","pieces_verified":26,"piece_count":26,"progress":1.0,"peers":1,"downloaded":420000,"uploaded":0,"hash_failures":0,"pex_peers_learned":0}
$ rhizome verify photos.torrent --dir dl --json
{"name":"photos","info_hash":"e3846f7e1a3ab3fdd4a23048ee61d21281ab451b","complete":true,"pieces_verified":26,"piece_count":26,"missing_pieces":[],"files":[{"path":["photos","a.jpg"],"length":300000,"complete":true},{"path":["photos","raw","b.raw"],"length":120000,"complete":true}]}
```

The seeder in that example was a second `rhizome download photos.torrent --out seed --port 51515 --no-dht`,
which verifies the existing files and then seeds them. Errors with `--json` come out as
`{"error":"..."}`. Exit codes are 0 for success, 1 for a failure and 2 for bad usage. `lookup` takes
`--allow-private` for DHT nodes on private addresses, and `version` prints the version.

## Usage as a library

Published to GitHub Packages as `dev.aetherx86.rhizome:rhizome`.

```kotlin
val client = RhizomeClient()
val session = client.add(Metainfo.parse(File("a.torrent").readBytes()), Paths.get("downloads"))
session.connect(InetSocketAddress("192.0.2.10", 6881))
session.startDiscovery()
session.awaitComplete()
println(session.status().toMap())
```

A long-running DHT node should call `node.startMaintenance()` to keep its routing table fresh.

## How it is tested

- Whole swarms run over real sockets on loopback: single and multi-file torrents, files crossing piece
  boundaries, resuming, leechers trading with each other, and magnet links. Downloads are compared with
  the original byte for byte.
- Scripted hostile peers send corrupt pieces, unsolicited blocks, forged metadata and malformed messages.
- A real DHT network of 30 nodes on loopback checks that lookups reach the truly closest node and route
  around dead ones, and two clients find each other through it and transfer a file.
- Three clients on loopback check that peer exchange introduces a leecher to a seeder it was never told
  about, and that the duplicate connection this causes is resolved to exactly one.
- The command line runs in-process: `create`, `info`, `verify`, `download` against a loopback seeder,
  and `lookup` against a loopback DHT, with every `--json` line read back by a strict JSON parser.
- Mutated torrents and random magnet links must fail only with `InvalidTorrent`, and random Bencode
  values must round-trip to the same bytes.
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
