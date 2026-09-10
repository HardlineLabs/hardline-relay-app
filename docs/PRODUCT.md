# Product scope

## Radio observatory (0.8)

Five pages share the connected Meshtastic radio:

- **Radio:** connection assessment, ten-minute activity chart, timestamped cached
  battery/channel-utilization/TX-airtime/uptime, and an explicit connection survey.
- **Mesh:** node name/ID search with stable ordering and explicit evidence ranking.
  Every node opens a live inspector: Test/Stop, check slots, progress, and results
  stay above separately scrolling reception history. The same inspector opens from
  map markers and packet details. Show on map focuses and highlights the node's
  reported position; nodes without coordinates show No reported location.
  Closing the inspector preserves the prior view.
- **Map:** interactive USGS satellite/aerial imagery with optional topographic labels,
  pinch zoom, pan, scale, Fit nodes, and local phone GPS. Detailed imagery is primarily
  U.S. coverage; resolution elsewhere is limited. Tiles require internet; offline
  the map is unavailable while node inspection and radio checks remain usable.
  Marker colors distinguish destination acknowledgments within ten minutes, observed
  RF traffic within ten minutes, and older/cached evidence. Dashed markers identify
  advertised coarse coordinate precision. Reported positions are not verified GPS;
  their source, precision and age are available in the inspector. Overlapping markers
  offer a node chooser. No location or route is inferred from signal strength.
  The collapsible Significant relays panel scrolls independently and lists unlocated
  candidates with a recent destination acknowledgment, or at least two direct LoRa
  receptions within ten minutes with SNR >= -7.5 dB and RSSI >= -115 dBm in this
  capture/context. These screening thresholds are not a forwarding test or coverage
  guarantee. A strong relayed packet does not qualify its origin. Optional phone GPS
  supplies the local origin while Relay is open and is never transmitted. Without
  a local fix, the map fits reported remote nodes and labels the unknown origin.
  Map providers receive viewport tile requests, not node metadata.
- **Activity:** an independently scrolling, recycled TX/RX packet list. Cards show
  direction, time, To/From node, status, exposed payload size, and observed hops.
  Unsupported encryption and rebroadcast flags are omitted. Tap a card to expand
  metadata and status history inline; tap again to collapse. Associated status-only
  events update TX cards, while collection events and unmatched statuses remain in
  the metadata export. New arrivals wait behind a count button while scrolling or
  reading an expanded card. All/TX/RX filters, traffic insights, comparison markers,
  clear history, and explicit JSONL export remain under compact controls.
- **HARDLINE ATAK:** the saved/protected profile and verified activation workflow
  described below, plus installed radio channel removal.

Every main page shows the current node test or mesh survey in a compact clickable
banner. Tap it to return to that node's existing inspector. Other tests remain
disabled while one is running. Finished results remain linked for 30 seconds;
navigation never starts or cancels a test. The Radio survey explanation describes
its up-to-four targets and two checks per target.

Live updates change existing views rather than rebuilding pages. The chart uses
fixed wall-clock 20-second buckets and a fixed scale capped at ten packets per bar;
scrolling and quiet periods do not rescale it. Text counters retain the full counts.

Start capture explicitly. An ongoing notification provides Stop capture. Collection
continues in the background while that service lives; it does not restart itself
after process death or reboot. Restarting creates a new capture boundary. The private database retains up to 5,000 metadata events or seven days per radio, whichever is smaller. Up to eight radios are retained, evicting the least recently used radio when necessary.
Clear history removes observations, not channel profiles. Payload bodies, channel
keys, passphrases and QR data are never retained in this history or its export.
Exports include node IDs, times, RF context and derived distances. Node coordinates are retained locally with per-radio node and survey history, never in activity records or metadata exports. Up to 1,000 nodes and 20 surveys are retained per radio. Older mixed activity has no trustworthy radio identity and is discarded on migration. Capture does not resume automatically after restart.

### Active checks and interpretation

Check my connection selects up to four known nodes, prioritized by recent evidence,
and attempts two directed checks per node. Test this node attempts four checks.
Checks alternate a one-byte REPLY_APP packet requesting a routing acknowledgment
and a native device-telemetry request. They never use a chat, position, configuration,
or broadcast-send operation. No diagnostic responder or additional app is needed on
public nodes. No scan or probe runs automatically just because capture is active.

One check is pending at a time. Submission spacing is at least 15 seconds, with
a 25-second observation window and a five-second gap after timeout. Fresh channel
utilization of 50% or more pauses further submissions. A connection or individual-node survey ends within six minutes and never exceeds 12 submissions. A new test is accepted immediately after Stop/completion and visibly waits for outstanding radio spacing before submitting. The whole-mesh survey below has its own bounded queue instead of the six-minute limit. Firmware may
retry packets independently; Stop prevents new submissions but cannot retract
already queued RF traffic. Radio/configuration changes, disconnects, and profile
activation stop a survey. Unresolved profile activation blocks diagnostic sends.
RF region, preset, frequency, keys and hop settings are preserved.

The pinned API loses native response request IDs and does not retain non-chat
destinations in the upstream message database. Its DELIVERED status can therefore
mean an implicit relay acknowledgment, not confirmation from the selected node.
Relay labels this **routing acknowledgment**. Only the upstream RECEIVED status is
treated as a destination acknowledgment. The confirming transport is not exposed
by that status. Separately, actual LoRa receptions show which origins were heard
during the survey. A native telemetry arrival is not claimed as an exactly correlated
response. A timeout is unconfirmed; a routing acknowledgment can later be followed
by an error. Expanded TX cards and exports preserve those transitions. No result guarantees future
delivery or proves that an unanswered node is offline.


### Active survey map and history

The Active survey toggle replaces the normal map with a selected survey. Start
creates an empty evidence set, preserving older surveys in a dated history picker.
Each known eligible node gets two addressed checks consecutively, then the queue
moves to the next node. Newly discovered eligible nodes join once. The queue stops
when exhausted or when Stop is pressed; at most 1,000 targets / 2,000 submissions
are allowed. The same utilization pause and per-packet timing apply. A large mesh
can take hours; capture/connection/configuration loss ends it without auto-resume.

Only origins actually received over LoRa during that survey populate its map,
including incidental and hopped traffic. MQTT and cached last-heard timestamps do
not qualify. Located received nodes stay green with their last-RF age. The unlocated
panel lists every heard origin without coordinates, without the normal map's strong
relay thresholds. Tap either to open its inspector. A survey stores the names,
reported positions/precision, last RF metadata and tested-node IDs for comparison;
packet bodies and keys are excluded. The header shows the latest survey age and the
picker identifies the displayed survey's date/time. At most 20 surveys per radio
survive app restarts; starting a new one selects it immediately.

Estimated range is an optional circle from the survey's fixed recorded origin to
the farthest located RF origin, including hops. It is observed mesh reach, not
coverage, direct radio range or a guarantee within the circle. Reported coordinates
can be old or coarse. Without origin/received-node coordinates the circle is
unavailable. An available radio position is preferred; optional phone GPS can supply
the origin when the phone represents the radio's placement.

### Node discovery (0.10)

Map has separate **Active survey** and **Node discovery** view toggles. Select
Node discovery, then Start discovery. Discovery starts with an empty evidence map;
only remote origins received over LoRa during that session enter its map or
unlocated list. Teal means absent from the starting Relay/Meshtastic cache; blue
means known at the start and heard again during discovery. Text labels distinguish
both. Cached-only nodes, MQTT, local packets and unverified transport do not qualify.
New means new to this session's starting cache, not proof that our request caused
the arrival. Names and reported coordinates can fill in after reception; discovery
does not require or request a position. The local origin is shown separately when
available. Last-RF ages remain visible as heard nodes age within the session.

Discovery uses the pinned API's native `requestUserInfo(-1)`: our radio's User
announcement on primary channel 0, with `NODEINFO_APP` and replies requested.
It preserves configured RF settings and hop limit. It never sends public chat,
phone GPS or configuration writes. The first request respects outstanding radio
spacing; subsequent requests are at least ten minutes apart. A fresh utilization
reading of 40% or more pauses requests while listening continues. Start/Stop and
capture/process restarts preserve the per-radio request cooldown. Failed submissions
stop discovery and retain the cooldown. Other apps and firmware can send separately.

Discovery continues until Stop, capture/connection loss, radio/configuration change,
or profile activation; it never resumes automatically after interruption. The two
survey modes and individual-node tests cannot transmit concurrently. Changing a
view toggle selects a map; use Stop discovery (also reachable from the running
header or Radio page) to end the session. Stop prevents further submissions but
cannot retract a request already handed to Meshtastic.

Discovery and active-survey histories have separate pickers and share the existing
20-session/1,000-heard-nodes-per-session retention bounds. Estimated range includes
hopped origins and uses reported coordinates, as in Active survey. Reaching the
node storage cap does not increase RF traffic or terminate listening.

The API exposes neither this native request's packet ID nor verified RF submission.
Activity therefore says Submitted to Meshtastic, not delivered. Firmware cooldowns,
congestion, radio roles, incompatible channels and range can prevent replies.
Discovery is an observation of the tuned mesh, not a frequency sweep or complete
census. Silence never establishes that a node is absent.

### Radio controls

The Radio tab is green when connected and red when disconnected. The page shows
short/long radio names, node ID, battery percentage/external-power state and voltage
with the reading age. Shutdown requires Shutdown radio followed by Confirm shutdown;
it addresses only the confirmed connected node. Submission is not proof of power-off,
and restarting the radio requires physical access.

Battery sharing uses Meshtastic device telemetry, not its external power-sensor
module. The pinned 2.7.13 AIDL cannot read local module config and suppresses local
admin replies from external broadcasts. Relay therefore shows this setting as
unavailable and provides a guided button to enable Device metrics in Meshtastic.
It never guesses the setting or overwrites unseen telemetry options.

### Observation coverage

This is an observer of the pinned Android API, not a raw RF sniffer. Decodable text,
position, telemetry, node-information and supported port events can be observed.
Firmware forwarding/retries, corrupt or undecryptable receptions, all unknown ports,
and other apps' outgoing submissions are not completely exposed. Traceroutes are
handled internally by Meshtastic and suppressed from its external receive broadcast.
Local malformed/duplicate counters are not exposed through this integration, so no
per-packet errors or aggregate totals are invented. Outgoing API statuses may be recorded
without an associated submission; these do not invent TX rows. Identical API notifications within two seconds
are coalesced; this is not a measurement of over-air duplicate traffic.

Cached nodes never count as fresh RF observations. MQTT observations are separated.
RSSI/SNR describe the final reception, not a whole relayed path. Missing/ambiguous
hop fields stay unknown. A relay byte is only a partial identifier, not a full route.
Distance uses reported coordinates and shows position age; it is not inferred from
signal strength. The radio's cached condition readings also show their age. The
Meshtastic exported broadcast boundary remains untrusted, as with the ATAK plugin.

## Private profiles

Hardline Relay manages saved private-channel profiles and radio activation.
Meshtastic owns the Bluetooth connection; the companion ATAK plugin owns PLI,
points and mesh status. These operational paths work without Wi-Fi or mobile data.

## Channels

Create a name (1–11 ASCII letters, digits, underscores or hyphens), choose
public-mesh compatibility, and optionally set a 12–128-character passphrase.
Every channel gets a fresh random 256-bit PSK; encryption is always enabled.
Creation saves a profile without installing it on the radio. Scan a teammate's
Hardline QR to save the same configuration, also without changing the radio.
Identical scans are idempotent; conflicting names cannot overwrite saved data.

The [profile contract](../protocol/channel-profile-v1.md) owns the QR format,
cryptography, RF mapping and app/plugin boundary. Public-mesh compatibility uses
US LONG_FAST slot 20 with a private key. Separate-frequency profiles select a
random supported slot excluding 20. They are not exclusive frequencies. Other
nodes need compatible RF settings and forwarding policies to carry ciphertext;
Relay cannot promise a third-party relay or coverage. MQTT stays disabled.

Saved profiles and installed radio channels are different. Up to 64 profiles can
be saved; a radio has one primary and up to seven secondary slots. Activation
never evicts an unrelated key. Installed channels include LongFast/default and
unrelated channels. Remove from radio identifies the radio slot, pauses traffic, disables that channel and restarts the radio for a full read-back. Saved profiles remain available. Remove profile deletes only the app copy and works offline; installed radio keys remain. A timeout leaves plugin sending paused until a verified activation recovers it.
Removing primary promotes the first remaining explicitly keyed channel to slot 0,
with the replacement shown before confirmation. Install another channel first if
primary is the only channel; inherited secondary keys need explicit configuration
before primary removal. There is no remote revocation or secure-erasure claim.

## Activation

Tap Activate, or select a saved profile from the ATAK plugin. Protected profiles
require their passphrase. Relay pauses plugin sending, verifies the connected
radio, installs the secondary key, sets the shared RF slot and verifies read-back.
A frequency switch affects the whole radio, including every installed channel.
The plugin routes another saved profile through activation even when its key is
already installed, because that alone does not establish the correct frequency.

The supported radio is US / LONG_FAST with zero calibration offset and seven hops.
An explicit frequency override is cleared during activation so slot selection takes
effect. Region, preset, calibration, hops, primary and unrelated channels/settings
are preserved. Unsupported configurations fail with an explanation.

Progress distinguishes sending, possible restart, reconnecting, verification and
Active. Failed or timed-out read-back is unconfirmed, never a rollback guarantee.
An unresolved activation keeps plugin traffic paused; explicitly activate again to
verify it. No automatic configuration retries. Normal Refresh reads Meshtastic's
cache; do not edit the radio concurrently in another app. Automatic PLI stays Off
after a successful switch.

The optional text test submits one standard Meshtastic channel message. Submission
is not a delivery receipt; check the other phone's conversation/notification.
Android and Meshtastic own notification permissions and muting.

## Stored data and trust

Protected packages encrypt the key and RF configuration with a passphrase-derived
key. Scanning does not decrypt them. Passphrases are not saved, and the full local
profile collection is additionally wrapped with an Android Keystore key in
no-backup storage. QR/passphrase screens block normal screenshots; camera frames,
QRs and keys are not logged, copied to the clipboard or sent to a backend.

This protects an unused contingency from casual access to a captured phone.
Previously installed keys remain available to Meshtastic and the radio. It does
not revoke a captured device, erase old keys, resist all rooted-device inspection,
or authenticate individual senders. Share passphrases separately from the QR.
The read-only metadata provider exports labels and activation evidence, never keys.

The original core AES-GCM helper is a JVM crypto test, separate from Meshtastic's
radio cipher. The new profile encryption is for stored provisioning packages,
not an additional layer around radio packets.

## Channel congestion and ATAK compatibility (0.9)

Radio includes a channel-congestion chart with 10-minute, 1-hour, 6-hour, 24-hour
and 7-day windows, plus a scrubber for individual radio telemetry readings. The
0–100% scale is fixed; empty time is unknown, not zero or interpolated utilization.
Each point shows its timestamp and RF context. Capture records new local telemetry,
not repeated polls of a cached value or metrics predating a configuration change.
History is local per radio, bounded to seven days / 10,080 readings, within the
existing eight-radio limit. Clear history removes that radio's congestion readings.
Cached current readings remain separately labelled. No extra RF poll is needed.

HARDLINE ATAK's **Compatibility for ATAK** is a read-only scan of accessible settings:
software/firmware pins, transmission enabled, supported LoRa settings/hops, private
port filtering, device role, power saving, Bluetooth, cached congestion, ATAK GPS
permission and pending activation. CORE_PORTNUMS_ONLY is blocking because it can
reject private ATAK traffic while standard text works. Unknown fields and inaccessible
module/host/peer settings are explicitly unverified. No blocker found is not a
promise of delivery. The scan changes no settings and exposes no keys or positions.

The companion plugin's compact PLI and recovery behavior is specified once in
[the current wire contract](../protocol/pli-v2.md).
