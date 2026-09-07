# Hardline Relay

An offline radio observatory for Meshtastic, with private-channel management and a
companion ATAK plugin.
A Hardline Labs hobby project built for fun and experimentation. **Work in progress**;
nearby lab testing does not make this a certified or mission-critical system.

Radio explains the connected device's condition and runs bounded, addressed mesh
checks. Mesh puts observed and cached nodes on an offline geographic plot. Activity
turns exposed packet metadata into a readable timeline, filters, and traffic insights.
Capture never starts a survey automatically, and diagnostics never send public chat.

HARDLINE ATAK contains the existing contingency-channel workflow:
create several channels, share a QR, and choose when to activate each.
Protected QR packages can be saved ahead of time and unlocked with a team passphrase.
Use private encryption on public-mesh-compatible RF settings, or choose a separate
frequency slot. Relay shows radio configuration, restart, reconnect and verification
progress instead of treating a settings submission as success.

All operational features work offline using Bluetooth, LoRa and phone GPS. No account,
backend, Wi-Fi or mobile data is required. Initial software installation is separate.

- [Product behavior and limits](docs/PRODUCT.md)
- [Build and hardware checks](docs/DEVELOPMENT.md)
- [ATAK plugin](https://github.com/HardlineLabs/hardline-relay-atak-plugin)
- [Wire contracts](protocol/README.md) and [third-party notices](docs/THIRD_PARTY.md)

Pinned baseline: Meshtastic Android 2.7.13, Heltec V3 firmware 2.7.15.567b8ea,
US / LONG_FAST / seven hops. This version does not select a region for you.
