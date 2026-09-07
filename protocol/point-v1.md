# Relay point experiment v1

Relay App owns this contract; the ATAK plugin holds an identical snapshot and
implements it. The app's channel provisioning behavior does not change.

## Transport and wire format

Use the PLI experiment's PRIVATE_APP (256), selected AES-256 secondary channel,
MQTT exclusion and unchanged 7-hop settings. Both messages are channel broadcasts
with wantAck=false. Recipient addressing is application filtering, not recipient-only
privacy: other channel-key holders can read the payload. There is no fragmentation,
compression, attachment transfer, automatic resend, or TAK-server forwarding.

All integers are big-endian. HRP1 types 1/2 retain their PLI meanings.

Type 3 point: HRP1 (4 bytes), type (u8), nonzero random attempt token (64 bits),
nonzero point ID (64 bits), positive update revision (i64 milliseconds), intended
recipient node ID (u32), latitude/longitude degrees times 10^7 (i32 each), symbol
(u8), ARGB color (32 bits), UTF-8 name byte length (u8), then 1–24 name bytes.
Fixed portion: 47 bytes. Total: **48–71 bytes**; “Rally 1” is 54 bytes.
Coordinate encoding rounds to the nearest integer. Reject malformed UTF-8, blank
names, control characters, invalid coordinates/IDs/symbols, truncation and extra bytes.
Do not silently truncate names or substitute an unsupported symbol.

Point identity is sender node ID plus the first eight SHA-256 bytes of the original
ATAK marker UID (zero maps to one). This is a compact identity, not authentication;
a 64-bit collision remains theoretically possible. Each explicit send has a fresh
token and revision max(current Unix milliseconds, that local marker's previous
revision + 1). Receiver compares revisions per sender/point; wall-clock revision
is not a live-location freshness claim. A new sender installation/clock rollback
may require correcting its clock before updating an already newer point.

Symbol table (index in wire byte):

| Index | ATAK type |
|---|---|
| 0 | b-m-p-s-m |
| 1 | a-f-G |
| 2 | a-h-G |
| 3 | a-n-G |
| 4 | a-u-G |
| 5 | a-f-G-U-C |
| 6 | a-h-G-U-C |
| 7 | a-n-G-U-C |
| 8 | a-u-G-U-C |

Type 4 receipt: HRP1, type (u8), echoed attempt token (64 bits), original sender
node ID as recipient (u32). Exactly **17 bytes**. No receipts of receipts.
PLI type 2 receipts cannot confirm a point.

## Receiving and status

Only the addressed node imports a point and returns its receipt, after a successful
marker create/update. Sender accepts a receipt only for its latest attempt token,
from that attempt's intended recipient, addressed to its local radio.
Receipts indicate plugin processing, not human observation or cryptographic proof.

Repeated sender/point/revision with identical content does not create another marker;
it may be acknowledged again after five seconds, allowing a lost receipt to recover.
An older revision or conflicting content at the same revision is ignored. Import
failure is not committed as received. Keep at most 128 received point identities per
plugin lifetime, rejecting new identities when full rather than silently evicting.
Received markers/history are in-memory and survive BLE loss/channel reselection,
but are removed on plugin unload/ATAK restart. They are separate from PLI peers and
show sender update and local receive time; they never expire as an “offline peer.”

The plugin's last-point indicator is separate from PLI status:
SUBMITTING, AWAITING RECEIPT, RECEIVED, UNCONFIRMED, or SEND FAILED.
After 60 seconds without a receipt, delivery is unknown, not proven loss.
A matching late receipt may confirm within five minutes while the same session
remains active. Older attempt callbacks/receipts must not overwrite the last attempt.
Version 0.5 retains 32 recent attempt timestamps for a separate point RTT average,
latest, longest and sample count. Matching older receipts may contribute one sample
without replacing the latest point status. RTT is local monotonic send-to-application
receipt time; duplicate/wrong-recipient receipts do not add samples. Session reset
clears these timing records.
Disconnect/channel change makes an outstanding result UNCONFIRMED; it must never
turn an already confirmed result into failure. New plugin lifetime starts with no send.

## ATAK boundary

Accepted PLI populates contacts under a namespaced radio UID with callsign and Relay
label; stale PLI remains visibly stale. An ATAK IpConnector with a local send intent
routes the normal point Send/contact selection into the plugin. There is no global
CoT interceptor. Only one supported map marker is accepted per request. Files,
shapes, routes, attachments and custom icon payloads are not encoded. The compact
point carries coordinates/name/supported symbol/color, not altitude, remarks or
arbitrary ATAK metadata. Both endpoints require the matching Hardline plugin.

The PLI contract's trusted-lab/unprotected Meshtastic broadcast limitations apply.
Never log point coordinates, packet bodies, channel keys or secret QR content.
