# Hardline Relay App

Simple Meshtastic setup and mesh status for Hardline Relay.
Development scaffold; not an operational radio configuration tool yet.

Start with [development](docs/DEVELOPMENT.md) and [product scope](docs/PRODUCT.md).
Run `./tools/check.ps1` from PowerShell. Open the IDE with `./tools/open-studio.ps1`.

The app has a read-only Meshtastic 2.7.13 AIDL status connection. The JVM core has
fake Meshtastic/ATAK transport and AES-256-GCM tests. Configuration writes, secure
key storage, QR provisioning, CoT forwarding, and real-device validation are next.
No backend enrollment or key infrastructure is included.

Versioned status vectors live in [protocol](protocol/README.md). The companion
[ATAK plugin repository](https://github.com/HardlineLabs/hardline-relay-atak-plugin)
has a standalone status harness and a local SDK-host plugin scaffold.
