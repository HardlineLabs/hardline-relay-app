# Relay PLI experiment v1

The Relay App repository owns this contract; the ATAK plugin keeps an exact
snapshot. The plugin implements it; Relay continues to own channel provisioning.
This is a private lab protocol, not interoperable with other ATAK mesh plugins.

## Wire format

Meshtastic PRIVATE_APP (256), selected secondary channel with a 32-byte PSK,
MQTT uplink/downlink disabled. Preserve configured 7 hops; never write RF settings.
All integers are big-endian. No compression, fragmentation, or raw CoT XML.

PLI: ASCII HRP1 (4 bytes), type 1 (u8), random nonzero packet token (64 bits),
GPS fix Unix milliseconds (i64), latitude/longitude degrees times 10^7 (i32 each),
announced send interval (i32: 0/manual, 10, or 30 seconds), UTF-8 callsign byte
length (u16: 1–40), then callsign. Total 36–75 bytes. Control characters,
invalid UTF-8, invalid coordinates, unknown types, and extra bytes are rejected.

Receipt: HRP1, type 2 (u8), echoed 64-bit token; exactly 13 bytes.
Both types are channel broadcasts with radio wantAck=false. Only a sender holding
that pending token accepts a receipt, attributed to the receiving radio node ID.
A receiver sends one application receipt after updating the marker. Receipts do
not solicit receipts. This keeps the return path on the same PSK channel instead
of relying on a direct-message encryption path. It is intended for the two-phone
lab; multi-peer receipt traffic needs an airtime policy before wider deployment.

## Freshness and bounds

The plugin requires a phone GPS fix at most 120 seconds old, matching ATAK's self
position within 100 metres. It transmits ATAK's coordinates with the GPS fix time.
No manual/fake positions or fallback network positions are silently substituted.
Receivers reject fixes over 120 seconds old or over 30 seconds in the future;
keep both phone clocks automatic. Older fixes cannot replace newer peer positions.

Peer age uses local monotonic time since an accepted PLI, independently of sender
clock and receipt traffic. Stale threshold is max(60 seconds, 3 × announced interval).
Display sender fix time and local receipt time separately. A stale marker remains
last-known; recent reception is not a guarantee the peer is still online.
Duplicates do not refresh age or cause additional receipts. Manual sender mode
must remain visible. Missing receipt after 60 seconds is unconfirmed, not proven loss.
Late receipts are accepted while the token remains in the five-minute history.

Automatic transmission defaults Off and stops on unload, disconnect, or detected
radio/channel changes. Select a channel explicitly each session. No catch-up bursts,
automatic retries, or forwarding of normal TAK-server traffic. At most 32 peers,
32 outgoing tokens, 32 receipts per token, 256 duplicate IDs, and four queued
transmissions. Duplicate/token retention is five minutes; peer state is in-memory.

## Trust boundary

Channel encryption is not per-person authentication. The pinned Meshtastic API
uses unprotected exported Android broadcasts; another local app can spoof input.
These receipts are operational evidence on trusted lab phones, not cryptographic
proof of origin or human observation. Do not deploy in hostile environments until
IPC authenticity and authenticated/replay-resistant application messages are designed.
No channel keys, QR payloads, coordinates, or packet bodies belong in routine logs.
The plugin holds channel configuration transiently for change detection, never
stores it, and never writes radio settings or exports configuration services.

