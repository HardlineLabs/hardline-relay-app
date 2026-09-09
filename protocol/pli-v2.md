# Compact PLI and delivery recovery (plugin 0.6)

Plugin 0.6 transmits HRP1 type 7 on Meshtastic PRIVATE_APP (256), using the same
private PSK channel and broadcast destination as 0.5. Receivers still decode legacy
type 1 positions and type 2 receipts. Upgrade every sending/receiving plugin to
0.6 for compact PLI: older plugins do not understand type 7. Chat/point formats
are unchanged. No raw CoT XML, public-room forwarding, backend or fragmentation.

All fields are big-endian. A complete position is independently decodable:

| Field | Bytes |
| --- | --- |
| HRP1 magic | 4 |
| Type (7) | 1 |
| Nonzero unsigned attempt token | 4 |
| GPS fix Unix seconds, unsigned | 4 |
| Absolute latitude x 1e7, signed | 4 |
| Absolute longitude x 1e7, signed | 4 |
| Interval index in [0, 10, 30, 60, 120, 300, 600] | 1 |
| Callsign UTF-8, no control characters | 1–40 |

Total: 23–62 payload bytes; 26 with a four-byte callsign (legacy: 39). Meshtastic
and LoRa overhead is additional. A smaller payload does not reduce total airtime
by the same percentage. Callsigns remain in each position so a missed identity
advertisement cannot make a location undecodable. Coordinates round to 1e-7 degrees;
GPS time has second resolution. Type 2 receipts remain 13 bytes with a 64-bit token
whose low 32 bits contain the compact attempt token.

## Recovery and bounded airtime

PLI, chat and points share one in-memory outbox: at most four pending data messages,
including only one PLI, plus 32 receipt entries. A new PLI replaces an unsent older
PLI. Each data item requests Meshtastic reliable handling (`wantAck=true`), enabling firmware retries. The pinned firmware clears broadcast want_ack before
its default priority assignment; private broadcasts still use DEFAULT priority.
The AIDL does not expose explicit priority; text receives firmware HIGH priority.
This does not claim priority parity or highest radio priority. Optional explicit
text transport is described below; no admin or routing-ACK impersonation is used.
The firmware can independently retry; outbox counts are API attempts, not RF TX counts.

Application submissions are capped at three per message, normally at 0, 90–105,
and 195–225 seconds. Expire after five minutes. One submission at most per five
seconds globally; data at most once per 15 seconds. Receipts are single-shot,
jittered, and expire after 30 seconds. A duplicate PLI can request another receipt
without refreshing the position or its age. PLI receipts remain additionally
coalesced per origin. Never acknowledge an acknowledgment. A first valid peer
receipt confirms PLI processing by that peer, not every member of the channel.
Point/chat receipts must match the intended recipient. Public relay evidence alone
never confirms the receiving plugin's processing.

Automatic PLI retains the selected interval, holds while the latest attempt waits,
and sends the newest GPS fix after confirmation/deadline; no catch-up burst. Manual
PLI also recovers automatically. Pause cancels queued PLI, but cannot retract
firmware-queued packets. Missing receipts mean unconfirmed, not proven loss.

Brief BLE/service loss pauses submissions, preserves selected radio/channel and
last-known contacts, and reconnects automatically. Resume requires the identical
radio/channel/RF snapshot and readable, non-switching Relay profile metadata.
Different radio/configuration, explicit channel change or plugin unload clears the
outbox; expired messages are never submitted on recovery. No reboot persistence
or retransmission cancellation API is available in the pinned integration.

## Freshness and diagnostics

New local PLI still requires a phone GPS fix newer than two minutes matching ATAK's
self position. Incoming fixes up to 15 minutes old may be retained as clearly stale
last-known locations. Fix age contributes to stale classification immediately;
receiving a delayed packet never makes it fresh. Older fixes cannot reverse a newer
position, duplicates cannot renew its age, and fixes over 30 seconds in the future
are rejected. Clock disagreement remains a reason to check phone time.

Connection details show attempted submissions, retry count, binary bytes attempted
(before optional text encoding),
confirmed/expired outbox messages, private API receptions, rejection reason and the
latest exposed radio status. These are session diagnostics, not complete radio
queue/RF counters. Exported Meshtastic broadcasts remain an untrusted app boundary;
private-channel membership is not individual sender authentication.

## Optional text transport

The explicit Text transport toggle wraps every subsequent outbound plugin frame
(including receipts) as ASCII `HLR1:` followed by standard padded Base64, on
TEXT_MESSAGE_APP (1), on the same selected private channel. The default is Off;
the phone remembers the user's selection. All 0.6 receivers accept either encoding
independently of their sending toggle. No ordinary text is interpreted as PLI and
no public-room message is emitted. Framed text is visible in Meshtastic history.

Text messages receive firmware HIGH priority. This is a compatibility fallback,
not reserved airtime or guaranteed delivery. Encoding increases a 26-byte PLI to
41 bytes. All frames remain single-packet: binary contents are capped at 165 bytes,
producing at most 225 text payload bytes (below the pinned Data size limit with
protobuf overhead). Oversized chats are rejected visibly without truncation or
fragmentation; 107 UTF-8 body bytes fit even with the maximum 24-byte callsign.
A pending oversized chat must finish/expire before enabling this mode. Changing
the toggle affects subsequent transmissions/retries, not already queued firmware
packets. Receipts use the local sender's chosen transport, not automatically the
transport of the incoming message.
