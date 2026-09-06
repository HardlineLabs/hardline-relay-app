# Product scope

Relay is a small Android wrapper for Meshtastic, usable without ATAK.
The intended workflow is channel name, AES-256 encryption choice, compatible
public-mesh relay choice, then a shareable provisioning QR code.
Meshtastic owns the Bluetooth radio connection; each phone connects to this PC by USB.

The current milestone binds to Meshtastic 2.7.13 IMeshService and requires radio
firmware 2.7.15.567b8ea. It creates private secondary channels, imports a Relay QR,
and sends standard TEXT_MESSAGE_APP channel broadcasts. The primary channel,
region, modem preset and hop count are never written. It preserves other channels;
an identical import is idempotent, while conflicting names or a full radio fail.

Create/import requires explicit confirmation. New keys use SecureRandom (32 bytes).
Names accept 1–11 ASCII letters, digits, underscores or hyphens; admin is reserved.
QRs contain one ChannelSet entry with add=true and no LoRa configuration. Imports
reject public/short keys, MQTT flags, extra options, foreign URLs and bulk replacement.
Scan inside Relay: general Meshtastic QR imports are not promised to behave identically.

After setChannel, Relay explicitly requests getRemoteChannel for the local radio,
then waits up to 20 seconds for Meshtastic's radio-response-backed cache to match.
A timeout is unverified, not a rollback or an automatic retry. Refresh and inspect
Meshtastic before retrying. Normal refresh reads Meshtastic's cache; it is not a new
radio interrogation. Do not edit channels concurrently in another app.

The test button submits one uniquely labelled text on the selected secondary
channel using the configured hop limit. Submission is not delivery. Confirm the
same token on the other phone. Meshtastic/Android own notification permissions,
muting and foreground/background behavior; Relay does not manufacture a receipt.

## Next development work

Extend failure recovery, test power-cycle/Bluetooth-loss cases, and design the
remaining simple configuration UI. Encryption-off/public-forwarding controls,
channel deletion and persistent delivery history are not implemented in Relay.
Human and USB tests must exercise the same behavior. Record board/firmware/region
before writes; never infer region from the PC timezone or flash an unidentified board.

Compatible public nodes may forward ciphertext without the channel key.
They still need compatible RF settings and forwarding policies. A phone cannot
guarantee whether a third-party node retransmits a packet; do not imply that a
single switch can enforce that. MQTT is not part of this mode and stays disabled
in intended Relay profiles. Validate the exact mapping on the chosen firmware.

## Security foundation

AES-256 is the default intended private-channel choice; generate a fresh random
32-byte channel key, never the well-known public/default key. Encryption off needs
a clear user choice. A QR code contains the complete channel secret by design.
Reveal/share it only on demand; never include it in logs, screenshots for routine
tests, analytics, Git, or clipboard history. QR display/scanning use FLAG_SECURE;
camera frames are not saved. Relay keeps keys only in transient memory, not its
files, preferences, clipboard, saved-instance state or backend. Meshtastic and the
radio remain the persistent owners. Backups are disabled. If Relay later stores
keys, Android Keystore-backed wrapping and explicit backup exclusions are required.

The core's AES-256-GCM helper verifies JVM crypto capability and tamper rejection.
It is not the Meshtastic channel cipher and is not wired to radio traffic.
Meshtastic channel encryption and application authentication are separate concerns;
do not claim sender authenticity from channel encryption alone.

No backend, cloud account, enrollment service, telemetry, or custom key server is
needed for this milestone. Radio writes occur only after channel confirmation.
