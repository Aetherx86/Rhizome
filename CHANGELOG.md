# Changelog

Versions follow this rule: a bug or security fix increments `0.0.X`, and new functionality
or new platform support increments `0.X.0`.

## [0.2.0] - 2026-11-20

### Added

- Peer exchange (BEP 11). Connected peers tell each other about the rest of the swarm once a
  minute, as `ut_pex` added and dropped lists with flags. Received lists go through the same
  address policy as tracker responses, only the first 50 entries of a message are used, messages
  faster than half the interval are ignored, and a malformed list closes the connection. The
  extension handshake now carries the listening port (`p`) and client name (`v`).
- When two peers dial each other at once, both keep the connection opened by the peer with the
  higher peer id, so exactly one survives.
- DHT routing table upkeep (BEP 5). A full bucket keeps newcomers in a replacement cache instead
  of dropping them. A contact is removed after two unanswered queries in a row, or after one if
  it is questionable and a replacement is waiting. When a newcomer finds its bucket full, the
  quietest questionable contact is pinged first. `DhtNode.maintain()` refreshes buckets untouched
  for 15 minutes with a lookup for a random id in their range and pings the contacts that are
  still questionable. `DhtNode.startMaintenance()` runs it every minute, and `download` turns it on.
- `rhizome create` makes a torrent from a file or folder, hashing as a stream. It picks a piece
  size automatically, refuses symbolic links and unsafe names, supports `--tracker` and
  `--private`, and always writes the same bytes for the same input.
- `rhizome verify` checks data on disk against a torrent without writing anything, and reports
  missing pieces and incomplete files. It exits with 1 unless every piece checks out.
- `--json` on every command. `download --json` prints one JSON object per line.
- `rhizome version`, and `lookup --allow-private` for DHT networks on private addresses.
- `TorrentSession.status()`, `TorrentSession.pexPeersLearned`, `PieceStore` read-only mode,
  and an `isPrivate` option on `Metainfo.create`.

### Changed

- Private torrents (BEP 27) no longer announce to or look up peers on the DHT, and do not use
  peer exchange.
- Unknown command line options are now an error instead of being ignored.
- The peer id prefix is `-RZ0200-`.

### Fixed

- A base32 magnet hash containing a character that grows when uppercased, such as `ﬃ`, crashed
  the parser with an out-of-bounds write. It is now rejected.
- Hex info hashes accepted digits from other scripts, such as fullwidth letters. Only ASCII is
  accepted now.
- A multi-file torrent whose file lengths add up past 2^63 raised an arithmetic error instead of
  being rejected as invalid.
- The Bencode encoder wrote dictionary keys outside ISO-8859-1 as `?`, which could produce
  duplicate keys. Such keys are now refused.

## 0.1.0

First release.

- Torrent files and magnet links, with metadata fetched from peers (BEP 3, 9 and 10).
- The peer wire protocol, with rarest-first picking, request pipelining and endgame.
- HTTP and UDP trackers (BEP 3, 15 and 23).
- A Kademlia DHT node (BEP 5).
- Hardening against hostile torrents, peers, trackers and DHT nodes.
