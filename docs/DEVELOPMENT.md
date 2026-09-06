# Development

Run commands from this repository in PowerShell 7. The workstation toolchain is
owned by the umbrella workspace's operations/relay-workstation.md and
tools/bootstrap-relay.ps1. Use that bootstrap on a replacement machine.

## Daily loop

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

## Private-channel hardware acceptance

Baseline: Meshtastic Android 2.7.13 (29320069), Heltec V3 firmware 2.7.15.567b8ea.
Two Moto G Play 2024 / Android 14 phones have passed creation, optical QR import,
radio read-back and a standard test text in each direction, including Meshtastic
notifications while its UI is backgrounded. This is nearby integration, not a range
or reliability certification. USB requires Motorola's OEM driver on this workstation.

1. Pair one radio per phone in Meshtastic and install this APK on both.
2. In Relay, create a uniquely named private channel and wait for verification.
3. Show its secret QR. On the other phone choose Scan, approve camera permission,
   scan optically and confirm Add. Do not capture the QR in screenshots/logs.
4. Keep Meshtastic backgrounded; tap Send test once in Relay on each phone.
   Confirm the token in the other phone's Meshtastic notification/conversation.
5. Refresh/restart Relay and confirm the channel survives and RF settings match
   their original values. Do not clear unrelated channels for a test.

Automated tests use non-production keys/fake services; they do not transmit RF.
They cover QR image decode, malformed/import rejection, slot planning/conflicts,
preservation, firmware/disconnection guards, read-back timeout and text/hop mapping.
Android tests cover startup/recreation and scanner runtime/QR rendering dependencies.
Actual camera alignment and notification delivery remain hardware acceptance checks.

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
There is no publication workflow in this scaffold.
