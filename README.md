# Hardline Relay App

Simple Meshtastic setup and mesh status for Hardline Relay.
Private-channel development milestone; not a production release.

Start with [development](docs/DEVELOPMENT.md) and [product scope](docs/PRODUCT.md).
Run `./tools/check.ps1` from PowerShell. Open the IDE with `./tools/open-studio.ps1`.

The app binds to Meshtastic 2.7.13 over AIDL, creates an AES-256 secondary channel,
requests radio read-back, displays/scans its secret QR, and sends standard
Meshtastic text tests. This flow is verified in both directions on two Heltec V3
radios running 2.7.15.567b8ea with two Moto G Play 2024 phones on Android 14.
Meshtastic notifications appeared with its UI backgrounded. Existing primary/RF
settings are preserved. No backend, CoT forwarding or ATAK integration is included.

Versioned status vectors live in [protocol](protocol/README.md). The companion
[ATAK plugin repository](https://github.com/HardlineLabs/hardline-relay-atak-plugin)
has a standalone status harness and a local SDK-host plugin scaffold.
