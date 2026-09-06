# Product scope

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
never evicts an unrelated key. Removing a saved profile does not erase installed
radio keys; manage those in Meshtastic.

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
