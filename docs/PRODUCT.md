# Product scope

Relay is a small Android wrapper for Meshtastic, usable without ATAK.
The intended workflow is channel name, AES-256 encryption choice, compatible
public-mesh relay choice, then a shareable provisioning QR code.
Meshtastic owns the Bluetooth radio connection; each phone connects to this PC by USB.

This setup baseline reads Meshtastic 2.7.13 radio status through IMeshService.
Its published API/model/proto dependencies are pinned. Configuration writes are
available in the upstream interface but have not been integrated or radio-tested:
getChannelSet, setChannel, getConfig, setConfig, beginEditSettings, commitEditSettings.
Use the pinned reference sources listed in THIRD_PARTY.md, not current upstream main.

## Next development work

Implement configuration as preview, apply, read-back verification, and useful
failure recovery. Preserve unrelated channel/radio settings. Human UI tests and
USB automated tests should run against the same behavior through transport seams.
Before writing settings, record the actual board model, firmware, and legal operating
region. Do not select region from PC timezone or flash an unidentified board.

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
tests, analytics, Git, or clipboard history. Use Android Keystore-backed wrapping
for stored channel keys and disable backups of secrets. These storage/QR features
are requirements for the next increment, not claimed as implemented here.

The core's AES-256-GCM helper verifies JVM crypto capability and tamper rejection.
It is not the Meshtastic channel cipher and is not wired to radio traffic.
Meshtastic channel encryption and application authentication are separate concerns;
do not claim sender authenticity from channel encryption alone.

No backend, cloud account, enrollment service, telemetry, or custom key server is
needed for this development setup. No radio settings are automatically applied.
