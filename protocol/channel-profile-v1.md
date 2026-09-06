# Offline channel profiles v1

Relay App owns this contract; the ATAK plugin keeps an exact snapshot.
Profiles never travel over LoRa. Creation, scanning, unlocking, local storage and
activation require no internet connection. Bluetooth to Meshtastic is needed for
radio configuration; phone GPS supplies the plugin's position fixes.

## QR package

A QR contains `hardline://channel/v1/` followed by unpadded base64url bytes:
mode u8 (0=open, 1=protected), ASCII name length u8 (1–11), name, then payload.
The name is visible metadata. Unknown modes, oversized packages and invalid fields
are rejected. Maximum QR input is 1400 characters; maximum decoded package is 900 bytes.

Open payload: big-endian frequency slot i32, followed by one encoded Meshtastic
ChannelSettings protobuf. Only name and a random 32-byte PSK are allowed; MQTT
uplink/downlink remain false. Region/preset for this version is US / LONG_FAST.
Slot 20 is public-mesh compatible; separate-frequency creation chooses uniformly
from supported slots 1–104 excluding 20. The creator chooses once, so scanning
teammates receive the same slot and key. An alternate slot is not exclusive spectrum.

Protected payload: random salt (16 bytes), random GCM nonce (12 bytes), ciphertext
and 16-byte authentication tag. PBKDF2-HMAC-SHA256 uses 600,000 iterations and a
256-bit derived key. AES-256-GCM encrypts the complete open payload. Associated
data is the ASCII URI prefix concatenated with the mode/name header. The passphrase
is not included. Creation accepts 12–128 characters; share the passphrase separately.
The profile ID is the hex SHA-256 of the complete decoded package.

Scanning saves a protected package without decrypting or installing it. Unlocking
happens only during explicit activation. Passphrases are not saved. All packages,
including unprotected ones, are additionally encrypted in Android no-backup storage
with a local Android Keystore key. At most 64 profiles are saved. Importing an
identical package is idempotent; conflicting names are rejected without overwriting.

## Activation and progress

Relay unlocks the profile, pauses plugin traffic, validates the connected radio,
installs/verifies the private secondary channel, and applies the frequency slot.
An explicit frequency override is cleared so it cannot defeat slot selection.
US region, LONG_FAST preset, zero calibration offset and the existing seven hops
are required; primary/other channels and unrelated settings are preserved.
No free secondary slot means no automatic eviction. Radio slots and saved profiles
are distinct: the radio supports one primary plus up to seven secondary channels.

Progress distinguishes sending settings, waiting for connectivity, reconnecting,
verifying and active. Radio responses update Meshtastic's configuration cache;
Relay requests read-back and checks the expected settings. A timeout is unconfirmed,
not rollback or success. An unresolved activation keeps plugin traffic paused until
activation succeeds. No catch-up sends or silent retries of configuration writes.
A frequency switch affects the entire radio, including all loaded channels.

## App/plugin boundary

Read-only provider `content://com.hardlinelabs.relay.profiles/profiles` exposes only
id, name, locked, activation, node, index, slot, fingerprint and switching columns.
Fingerprint is SHA-256 of the encoded installed ChannelSettings; no PSK, passphrase
or QR data is exported. Cursor extras also carry switching, including when no
profiles remain, so a removed package cannot silently release an unverified change. Mutation methods are unsupported. Labels are not secrets.

On launch, Relay grants the CIV host read-only, persistable URI access to this
metadata URI. The loaded plugin queries as the host and retains that grant. Its
separate APK manifest cannot add package queries to the ATAK host. If the provider
is unavailable, the plugin pauses sending and prompts opening Relay to reconnect;
it must not interpret a missing cursor as an empty saved-profile collection.
See Android's [automatic package visibility](https://developer.android.com/training/package-visibility/automatic)
and [URI grants](https://developer.android.com/reference/android/content/Context#grantUriPermission(java.lang.String,%20android.net.Uri,%20int)).
Plugin launches the explicit Relay MainActivity with a profileId to present its
activation screen. An intent alone does not write settings or bypass a passphrase.
A new successful activation is selected only after matching the live radio node,
channel fingerprint and complete supported RF profile. Automatic PLI remains Off.

Installed keys remain accessible to Meshtastic and the radio. The passphrase
protects unused saved contingencies, not an already-installed key or a previously
compromised copy. Removing a saved package does not erase radio keys. No remote
revocation, secure-erasure guarantee or protection against a rooted device is claimed.
Public-mesh compatibility permits compatible nodes to relay ciphertext, subject to
their forwarding policies; it does not guarantee coverage or third-party relaying.
