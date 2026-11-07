# Changelog

Versions follow this rule: a bug or security fix increments `0.0.X`, and new functionality
or new platform support increments `0.X.0`.

## 0.1.0

First release.

- Torrent files and magnet links, with metadata fetched from peers (BEP 3, 9 and 10).
- The peer wire protocol, with rarest-first picking, request pipelining and endgame.
- HTTP and UDP trackers (BEP 3, 15 and 23).
- A Kademlia DHT node (BEP 5).
- Hardening against hostile torrents, peers, trackers and DHT nodes.
