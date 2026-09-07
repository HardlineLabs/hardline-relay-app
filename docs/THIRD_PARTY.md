# Third-party resources

QR rendering/decoding: ZXing core 3.5.4, https://github.com/zxing/zxing.
In-app camera scanning: ZXing Android Embedded 4.3.0,
https://github.com/journeyapps/zxing-android-embedded/tree/v4.3.0.
Its AndroidX runtime dependency is explicitly pinned to core 1.13.1.
These libraries are Apache-2.0 licensed; preserve their packaged notices.
All new resolved artifacts are included in Gradle locks and SHA-256 verification.

Meshtastic Android 2.7.13 is pinned at commit
7a68802bc2b8cdb9c76a77f2093aac130fc8ec05. Its API/model/proto artifacts
are GPL-licensed; preserve upstream notices and evaluate corresponding-source
obligations before distributing linked binaries. This repository publishes Hardline source, not upstream SDK assets or binaries.

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

## Online map

Leaflet 1.9.4 is bundled under app/src/main/assets/map (BSD-2-Clause).
The original license is packaged as LICENSE.leaflet. Distribution files come from
https://unpkg.com/leaflet@1.9.4/dist/leaflet.js and leaflet.css, with SHA-256:

- leaflet.js: db49d009c841f5ca34a888c96511ae936fd9f5533e90d8b2c4d57596f4e5641a
- leaflet.css: a7837102824184820dfa198d1ebcd109ff6d0ff9a2672a074b9a1b4d147d04c6

Source: https://github.com/Leaflet/Leaflet/tree/v1.9.4. No remote scripts,
external geocoder, map account, analytics, or JavaScript-to-Java bridge is loaded.
Unused default Leaflet bitmap controls are not bundled; markers use local DOM/CSS.

Online imagery and labeled imagery are USGS The National Map services:
https://basemap.nationalmap.gov/arcgis/rest/services/USGSImageryOnly/MapServer
and USGSImageryTopo/MapServer on the same host. Attribution is visible on the map:
USGS, USDA FSA/NAIP, NASA/Landsat and Alaska SPOT. Detailed imagery is primarily U.S.;
source resolution varies. Native tiles end at zoom 16; deeper zoom enlarges those
pixels, without claiming additional resolution. See
https://www.usgs.gov/faqs/what-are-base-map-services-or-urls-used-national-map.
No bulk tile download, offline region export, or tile redistribution is implemented.
