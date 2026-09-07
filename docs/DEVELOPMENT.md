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
ObservationStore (bounded metadata-only database), and ObservatoryUi/RadioCharts
(native Android views). MainActivity retains profile operations. No new runtime
dependencies or protocol changes are required. Version 0.5 keeps all compatibility
pins below.

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

1. Explicitly select the single authorized device with `--serial SERIAL --count 1`
   for installation and ordinary instrumentation. The public-mesh test is skipped
   unless its opt-in instrumentation argument is present.
2. Open Relay, Start capture and grant Bluetooth/notification permissions. Verify
   current RF settings, connection state, cached metric age and an ongoing capture
   notification. No diagnostic TX events should appear until a survey is started.
3. On a compatible mesh, inspect incoming cards without opening message content.
   Check packet type, source, signal and unknown fields. Cached nodes must remain
   distinguishable from new RF observations. Inspect Mesh, Activity, all filters,
   paused scrolling, node details and HARDLINE ATAK navigation.
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
