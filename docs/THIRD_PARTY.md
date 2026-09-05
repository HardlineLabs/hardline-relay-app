# Third-party resources

Meshtastic Android 2.7.13 is pinned at commit
7a68802bc2b8cdb9c76a77f2093aac130fc8ec05. Its API/model/proto artifacts
are GPL-licensed; preserve upstream notices and evaluate corresponding-source
obligations before distributing linked binaries. Current repositories are private.

Reference: https://github.com/meshtastic/Meshtastic-Android/tree/v2.7.13
Protobuf submodule: https://github.com/meshtastic/protobufs/tree/44298d374fd83cfbc36fdb76c6f966e980cadd93
Existing ATAK plugin: https://github.com/meshtastic/ATAK-Plugin/tree/1.1.42
Pinned reference commit: 52cc9e1c140c50b622b132a47a1a4a9a755548d0

Upstream checkouts and reference APKs are kept outside product source under
the local HardlineRelayDev directory. Do not change or publish them as Hardline code.
The official plugin's extra encryption feature is reference material only.

The Gradle wrapper was sourced from the pinned plugin checkout; its scripts carry
Gradle's Apache-2.0 notice. Dependency artifacts retain their packaged notices.
The standalone ATAK harness does not link against an ATAK SDK.

ATAK SDK and plugin signing resources: https://tak.gov/products/atak-civ
Official public source: https://github.com/TAK-Product-Center/atak-civ
Do not use that repository's 5.5 SDK to claim 5.6 compatibility.
