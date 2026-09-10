# Development

Use PowerShell 7, Microsoft JDK 17.0.20.1+1, Android platform 36 revision 2,
build-tools 36.0.0 and Python 3.13.15. Gradle's checked-in wrapper owns its version.
Set JAVA_HOME and ANDROID_HOME (or an untracked local.properties SDK path), then run:

```powershell
./gradlew.bat :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
python -m unittest discover -s tools -p 'test_*.py'
```

The workstation convenience scripts use HARDLINE_RELAY_DEV, defaulting to
`$env:USERPROFILE/HardlineRelayDev`, with `tools/jdk-17.0.20.1+1` and `AndroidSdk`
inside it. They do not download the restricted ATAK SDK. The workflow under
.github/workflows/validate.yml records the public CI toolchain and download hashes.

## Daily loop

The radio observatory is implemented by RadioMonitorService (one serial worker for
capture/probes), RadioEvidence/RadioSurvey (pure evidence and scheduling rules),
ObservationStore (bounded metadata-only database), and persistent ObservatoryUi
pages. PacketFeed owns recycled packet rows, NodeInspector owns live test controls,
and SatelliteMap owns a restricted WebView running packaged Leaflet. RadioCharts
uses fixed time buckets and scale. MainActivity retains profile operations. Version
0.6 adds packaged Leaflet 1.9.4; Android/firmware compatibility pins stay unchanged.

```powershell
.\tools\check.ps1
.\tools\devices.ps1 list
.\tools\devices.ps1 install
.\tools\devices.ps1 test
```

Install/test defaults to exactly two authorized physical devices. More, fewer,
offline, or unauthorized devices fail before installation. To select one:
`./tools/devices.ps1 install --serial SERIAL --count 1`.
Explicitly select both phones with two `--serial` arguments if other devices
are connected. Emulator use additionally requires `--allow-emulator`.

Live tools use one terminal per phone and do not save logs:
`./tools/devices.ps1 logs --serial SERIAL --count 1` and
`./tools/devices.ps1 mirror --serial SERIAL --count 1`. Stop with Ctrl+C.
Logs show HardlineRelay and crash entries only. Never collect Meshtastic packet
payloads, channel URLs, QR images, or keys in routine diagnostics.

`./tools/start-emulator.ps1` starts Hardline_Relay_API34 after the hypervisor
is enabled and Windows has restarted. Then use `./tools/check.ps1 -Connected`.
The emulator tests Android UI/lifecycle; it does not emulate Bluetooth LoRa radios.
Startup now waits for Android's package service, not just ADB connectivity.
It uses software graphics, 3 GB RAM and four virtual CPU cores on this workstation.
Hardware graphics caused the ATAK SDK host to crash with "No config chosen";
SwiftShader renders it successfully. `-Graphics host` is an optional alternative
for other workloads, not the verified ATAK baseline.
Only one Hardline emulator uses port 5554. An already-running instance is reused.

## Offline profile and radio acceptance

### Radio observatory acceptance

The ordinary Android suite includes synthetic packet-list tests (expansion height,
new-arrival buffering, stable scroll offsets), persistent-page checks and isolated
live inspector controls. These tests do not transmit. Core tests verify packet/status
association and that relayed/MQTT/stale observations do not qualify an unlocated
origin as a direct candidate.

For UI acceptance on the selected phone:

- Scroll and expand Activity cards through multiple one-second refreshes; check that
  To/From, status, size and hops stay glanceable. No EVENT/STATUS-only cards appear.
- Pan/zoom Map, open a marker, return, and verify the viewport stays put. Check imagery
  and labels load; marker actions open the same live inspector as Mesh. Test overlapping
  markers and the unlocated candidate panel using synthetic data when needed.
- Verify an offline map-unavailable state, tile-error/reload behavior, and that capture
  and node tests do not depend on imagery. GPS coordinates must never be transmitted.
- From a node inspector, explicitly start a bounded test and observe its progress and
  Stop behavior in place. Do not send public channel chat. Pause RF checks if an abrupt
  signal anomaly suggests the outdoor radio needs a placement check.

1. Explicitly select the single authorized device with `--serial SERIAL --count 1`
   for installation and ordinary instrumentation. The public-mesh test is skipped
   unless its opt-in instrumentation argument is present.
2. Open Relay, Start capture and grant Bluetooth/notification permissions. Verify
   current RF settings, connection state, cached metric age and an ongoing capture
   notification. No diagnostic TX events should appear until a survey is started.
3. On a compatible mesh, inspect incoming cards without opening message content.
   Check packet type, source, signal and unknown fields. Cached nodes must remain
   distinguishable from new RF observations. Inspect Mesh, Activity, all filters,
   buffered arrivals, node details and HARDLINE ATAK navigation.
4. Run Check my connection or Test this node. Inspect the timeline for only
   directed REPLY_APP/native telemetry submissions. Distinguish routing ACKs,
   destination ACKs, passive RF receptions, errors and unanswered checks. Confirm
   that the survey finishes and honors Stop. Never use public-channel text tests.
5. Background/reopen Relay while capture is running; verify collection continues.
   Stop capture, restart it, and verify the gap and persisted metadata. Verify that
   service/process restart does not resume a survey or reuse current node evidence.
6. Use the optional phone-GPS map origin only with location permission. It must
   never call Meshtastic position-sharing APIs. Inspect map scale, position ages
   and list fallback. Verify metadata export contains no packet body or keys.
7. Repeat the existing profile storage/QR/recreation tests. Activation must stop
   diagnostics. Do not modify or clear installed public-mesh keys as a test shortcut.

Opt-in hardware acceptance (after installing both debug and instrumentation APKs):

```powershell
adb -s SERIAL shell am instrument -w -e class com.hardlinelabs.relay.PublicMeshAcceptanceTest -e publicMesh true com.hardlinelabs.relay.test/androidx.test.runner.AndroidJUnitRunner
# Adding -e survey true explicitly enables one bounded radio survey.
```

The test reports aggregate counts only, never node identities, positions, payloads
or channel secrets. Instrumentation exits can kill the target process; reopen Relay
and explicitly Start capture afterward. Deterministic tests cover timing, deadline,
unknown hops, late statuses, no-broadcast/no-chat probes, and metadata retention.

### Profile acceptance

Baseline: two Moto G Play 2024 / Android 14 phones, Meshtastic Android 2.7.13
(29320069), Heltec V3 firmware 2.7.15.567b8ea, US LONG_FAST / seven hops.
Nearby acceptance is distinct from range or reliability certification.

1. Pair one radio per phone in Meshtastic. Install Relay on both. Disable Wi-Fi and
   mobile data for the operational test, retaining Bluetooth and real GPS.
2. Create a protected separate-frequency profile. Scan its QR optically on the
   other phone. Require Saved/Locked and no new installed radio channel.
3. Restart Relay; require the saved profile to survive. Try a wrong passphrase;
   require rejection with no radio write. Activate with the correct passphrase.
4. Observe sending/restart/reconnect/verification on both radios. Require Active
   only after matching read-back; inspect unchanged primary, other keys and hops.
5. Create/scan a public-mesh-compatible profile and activate both. Require slot 20,
   private keys and working PLI/points. Switch back through the plugin dropdown;
   require Relay activation and the previous profile's frequency on both.
6. Interrupt connectivity during a change. Require unconfirmed/paused, then
   explicitly activate again to recover. Do not clear unrelated radio channels.
7. Run the companion plugin's physical PLI/point acceptance. Optional Relay text
   tests require the matching token in the other phone's Meshtastic conversation;
   local submission alone is not receipt.

JVM tests cover package bounds/tampering, wrong passphrases, supported RF slots,
channel preservation, read-back failure and text/hop mapping. Android tests cover
startup/recreation, QR encode/decode and encrypted Keystore-backed storage/provider
behavior on physical phones. Camera alignment and real radio reboot/recovery are
hardware checks. Never save actual channel QR images, keys or packet logs.

## Channel removal and test navigation

Channel-removal acceptance: create a disposable private profile, install it, then
use Remove and verify the reboot/read-back completes, the saved profile remains,
and primary/unrelated radio channels remain. Reconnect again to confirm persistence.
Never use a teammate's only active key as a disposable test. JVM tests cover primary
replacement, inherited-key/sole-primary rejection, concurrency and lost read-back.
For node tests, navigate through main pages while a check runs, tap the progress
banner to return to its inspector, and verify a second test cannot start. Ordinary
instrumentation uses synthetic state and never starts RF surveys.

## Node discovery acceptance (0.10)

JVM tests cover continuous scheduling, ten-minute spacing, busy-channel pauses,
Stop, starting-cache classification, RF/MQTT filtering and the native broadcast API
call. Ordinary Android tests use synthetic coordinates to check separate histories,
new/known colors and labels, empty-session cache exclusion, mode exclusion and
discovery history/cooldown storage across reopening. They never start discovery RF.

On the explicitly selected attached lab phone, install app and instrumentation APKs:

```powershell
./tools/devices.ps1 install --serial SERIAL --count 1
./tools/devices.ps1 test --serial SERIAL --count 1
adb -s SERIAL shell am instrument -w -e class com.hardlinelabs.relay.NodeDiscoveryAcceptanceTest -e nodeDiscovery true com.hardlinelabs.relay.test/androidx.test.runner.AndroidJUnitRunner
```

The opt-in test submits one native request, listens for three minutes, verifies
fresh-only records and no overlap with addressed surveys, then tests Stop/restart
cooldown and compares radio/configuration/channel snapshots without printing them.
An existing cooldown can add up to ten minutes. A persistently busy channel or
disconnection can prevent acceptance; never override its guard to obtain a result.
Aggregate reception counts are evidence only of that placement and observation
window. Reopen Relay and explicitly restart passive capture after instrumentation,
which can terminate the target process. No automatic survey should restart.

For manual UI acceptance, open Map and select Node discovery. Verify Start/Stop,
the running header's return-to-map action, separate dated history, teal new versus
blue known RF origins, and readable labels/unlocated fallback. Background/reopen
while running, then stop capture and require an interrupted record with no auto-resume.
An empty result is valid when nothing is heard; do not inject synthetic nodes into
the production database to make hardware acceptance appear successful.

An indoor check on 2026-09-09 submitted one request and heard eight RF origins in
three minutes: three absent from the starting cache, four with reported coordinates.
Stop, immediate-restart cooldown, survey exclusion and unchanged radio settings
passed. These are session observations, not proof of discovery causation or outdoor
range. Build/lint, 49 JVM tests, seven device-script tests and the ordinary Android
suite passed on the single attached phone. Synthetic map checks include marker
visibility after the native discovery controls resize the WebView.

## Build outputs

- app/build/outputs/apk/debug/app-debug.apk
- app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
- core/build/reports/tests/test/index.html
- app/build/reports/lint-results-debug.html

Outputs are disposable and ignored. Build directories are regenerated; never
check in screenshots, logs, APKs, local.properties, debug keys, or session notes.
Use `./gradlew.bat clean` when removing stale build outputs.
Python tests: `python -m unittest discover -s tools -p 'test_*.py'`.

## Fixed compatibility

The Gradle files and wrapper own compiler/dependency versions. Do not accept
Android Studio upgrade suggestions or run dependency-update bots. Gradle wrapper
checks its distribution SHA-256; dependency lockfiles and
gradle/verification-metadata.xml pin resolved dependencies and artifact hashes.

Intentional upgrades require a reviewed compatibility change, both protocol
snapshots compared, both repositories' checks, and two-phone integration.
Do not run --write-locks or --write-verification-metadata in routine builds.
Initial verification hashes are trust-on-first-use from the configured HTTPS
repositories, not an independent upstream signature audit.

## IDE

`./tools/open-studio.ps1` configures this repository and opens Android Studio.
Use its embedded JBR for the IDE and the pinned Microsoft JDK 17 for Gradle.
Product source lives here. Agent onboarding and workstation notes live in the
umbrella workspace, with links into these human-maintained documents.

## Git

Work from current origin/develop on feature/<name>. Validate and push coherent
commits, then merge through develop. main is reserved for an authorized release.
CI validates main, develop and feature branches. Source publication does not publish
a production-signed APK or redistribute the ATAK SDK.

## Survey history and per-radio acceptance (0.8)

Core checks cover Stop followed immediately by another queued target, physical-send
spacing, dynamic discoveries, busy-channel pauses and whole-mesh queue exhaustion.
Android tests cover per-radio storage isolation/reopening and historical coordinates,
fresh-survey cache exclusion and an estimated-range circle that includes hopped RF.
Use synthetic data for location screenshots/tests; do not save real location images.

On each selected phone, verify the Radio connection color and short/long names with
capture stopped and running. Start a bounded node test, Stop, immediately select a
different cached node and require a queued/running state followed by a submission.
In Map enable Active survey, explicitly start, inspect fresh RF discoveries and the
unlocated list, then Stop. Select the older dated survey; restart the app and confirm
its retained nodes/positions. Run a new survey and require the map to start empty.
Tests of a very large real network are explicit, attended work; ordinary tests never
start the all-node queue. Cached coordinates can be stale and are labelled as such.

Switch the connected radio and require Activity, Mesh and survey history to change
together, including while a packet is expanded. Return to the first radio and require
its own history. Profiles remain shared. Remove profile must leave its installed
channel intact; Remove from radio must retain its saved profile. Use a disposable
private profile and restore the working private channel after radio acceptance.

Shutdown is covered with an addressed fake API and the two-step Android confirmation.
Physical shutdown requires someone to power the radio back on; do not shut down an
unattended outdoor radio just to test the button. Battery broadcast enabling currently
opens the pinned Meshtastic app with device-telemetry instructions because local
module-config read-back is absent from its external API. No unseen module config is
overwritten and enabled status is never inferred from a battery reading.

Opt-in regressions, after installing app and instrumentation APKs, on one explicitly
selected lab phone at a time:

```powershell
adb -s SERIAL shell am instrument -w -e class com.hardlinelabs.relay.PublicMeshAcceptanceTest#immediateNextNodeAndActiveSurveyHistory -e surveyRegression true com.hardlinelabs.relay.test/androidx.test.runner.AndroidJUnitRunner
adb -s SERIAL shell am instrument -w -e class com.hardlinelabs.relay.ChannelAcceptanceTest -e channelRegression true com.hardlinelabs.relay.test/androidx.test.runner.AndroidJUnitRunner
```

The first submits only a few addressed checks and stops; the second uses a disposable
private profile, verifies independent deletion, and restores the prior verified
active configuration. It requires that prior activation evidence to match the live
radio before writing. An unverified failure keeps the profile and pause state for
inspection. Neither test prints configuration protobufs, keys or coordinates.


## Congestion history and compatibility (0.9)

On Radio, start capture, choose a history window and scrub actual channel-utilization
readings. The selected timestamp must stay selected as new telemetry arrives;
Latest reading restores following. Gaps stay unknown. Change radios and require
independent history, then restart and inspect retained samples. Storage tests cover
zero/invalid values, duplicate telemetry, reopening, seven-day/10,080-row bounds
and the eight-radio eviction rule. No extra telemetry broadcasts are requested.

In HARDLINE ATAK, run Compatibility for ATAK and require readable settings plus
explicit unknowns, including remote configuration and unavailable module settings.
The scan is read-only. CORE_PORTNUMS_ONLY blocks private application reception;
Text transport on every peer is an explicit fallback, not a promise of delivery.

For bounded two-phone transport comparison, install both APKs, activate the same
private profile/key on each radio, and start these commands concurrently with the
same future Unix-millisecond START and verified SLOT. Use role 0 on the first phone
and role 1 on the second. Eight samples per phone are staggered over six minutes.

```powershell
adb -s SERIAL shell am instrument -w -e class com.hardlinelabs.relay.PrivateTransportAcceptanceTest#compareTextAndPrivateDelivery -e privateTransport true -e expectedSlot SLOT -e role ROLE -e start START com.hardlinelabs.relay.test/androidx.test.runner.AndroidJUnitRunner
```

The test reports text, private without reliability, private with reliability and
smaller reliable payload reception separately, plus end-to-end API latency. It
checks radio/configuration continuity and does not create RF congestion. Ordinary
instrumentation skips RF tests. Existing unlocked lab profiles can be selected
with the same class's `activateNamedLabProfile` method and `-e configureProfile NAME`;
this explicitly writes/activates that saved profile. `NAME=list` only lists names,
lock state and frequency slots, never keys. Keep both phones on a verified matching
profile after acceptance.


The 0.9 nearby lab check received all 16 bounded RF samples on a matching private
profile, with receiver means of 1,188 and 2,427 ms and a maximum of 2,957 ms. This
is only two samples per mode/direction on a quiet channel, not congestion testing.
The live Radio chart retained a scrubbed 4.1% reading as its sample count grew from
three to seven. The read-only scan reported the live pins, ALL/CLIENT configuration,
private profile and actual telemetry while retaining explicit unverified items.
The companion plugin owns the application-level recovery/host acceptance results.
