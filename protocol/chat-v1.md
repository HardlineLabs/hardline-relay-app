# Relay chat v1

The Relay App owns this contract; the plugin keeps an identical snapshot.
Both endpoints need Hardline Relay ATAK plugin 0.5 or later. This is not the
Meshtastic TEXT_MESSAGE_APP chat protocol or another ATAK plugin's format.

Explicit one-to-one ATAK conversations with a Relay contact use its PluginConnector.
There is no global CoT interceptor, public-room forwarding, group fan-out, attachment
transport, automatic resend or TAK-server bridge. Normal ATAK stores chat history;
the plugin never logs message bodies. PLI creates selectable Relay contacts;
an incoming addressed chat can also establish a reply contact without GPS.

Use PRIVATE_APP (256), selected private secondary channel, 32-byte PSK, MQTT off,
7 hops, channel broadcast and wantAck=false. The application recipient restricts
which plugin imports/acknowledges a message; channel members can decrypt it. The
PLI contract's trusted-phone and exported-broadcast limitations apply.

All numbers are big endian. Text: HRP1 (4 bytes), type 5 (u8), ATAK message UUID
(16 bytes: most/least significant i64), recipient radio number (u32, 1–0xfffffffe),
sender Unix milliseconds (i64, positive), callsign length (u8, 1–24), UTF-8 callsign,
remaining UTF-8 message (1–160 bytes, nonblank). Total 36–218 bytes. Callsigns reject
control characters; messages permit newline/tab only. Reject invalid UTF-8,
oversized content and unsupported recipients without truncation or fragmentation.

Receipt: HRP1, type 6, echoed UUID, original sender radio number (u32), exactly
25 bytes. Issue only after successful ATAK chat database insertion. Incoming history
uses a stable sender-node/UUID identity so reconnects do not duplicate messages.
Duplicates can receive another receipt no more than once per identity per five seconds;
at most 128 receipt timestamps are retained for five minutes. Receipts do not solicit replies.

Match receipts to UUID, intended sender node, local recipient and the active radio
session, within five minutes. Only then mark ATAK DELIVERED; never claim human READ.
After two minutes without a receipt, the plugin shows unconfirmed/delivery unknown.
At most 32 recent outgoing attempts are retained. Reset tracking on disconnect,
channel change and unload; ATAK retains its own conversation history.
