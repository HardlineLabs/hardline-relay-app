"""Explicit ADB targets only; no persistent log collection or radio writes."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]


def parse_devices(output):
    devices = []
    for line in output.splitlines():
        parts = line.split()
        if not parts or line.startswith(("List of devices", "*")):
            continue
        if len(parts) >= 2:
            devices.append({"serial": parts[0], "state": parts[1]})
    return devices


def select_devices(devices, serials=None, count=2, allow_emulator=False):
    if count < 1:
        raise ValueError("Device count must be positive.")
    candidates = [d for d in devices if allow_emulator or not d["serial"].startswith("emulator-")]
    if serials:
        if len(set(serials)) != len(serials):
            raise ValueError("Duplicate serials are not allowed.")
        candidates = [d for d in candidates if d["serial"] in serials]
        if {d["serial"] for d in candidates} != set(serials):
            raise ValueError("A requested device is absent or excluded as an emulator.")
    if len(candidates) != count:
        raise ValueError(f"Expected {count} selected devices; found {len(candidates)}. Use --serial and --count explicitly.")
    blocked = [d for d in candidates if d["state"] != "device"]
    if blocked:
        raise ValueError("Unlock the phones and authorize USB debugging: " + str(blocked))
    return [d["serial"] for d in candidates]


def adb_path():
    sdk = os.environ.get("ANDROID_HOME")
    return str(Path(sdk) / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb")) if sdk else "adb"


def adb(*arguments, timeout=120):
    result = subprocess.run([adb_path(), *arguments], capture_output=True, text=True, timeout=timeout)
    if result.returncode:
        raise RuntimeError(result.stderr.strip() or result.stdout.strip() or "ADB failed")
    return result.stdout


def require_apk(path):
    path = Path(path).resolve()
    if path.suffix.lower() != ".apk" or not path.is_file():
        raise ValueError(f"Missing APK: {path}. Build first.")
    return str(path)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["list", "install", "test", "logs", "mirror"])
    parser.add_argument("--serial", action="append")
    parser.add_argument("--count", type=int, default=2)
    parser.add_argument("--allow-emulator", action="store_true")
    parser.add_argument("--apk", default=str(ROOT / "app/build/outputs/apk/debug/app-debug.apk"))
    parser.add_argument("--test-apk", default=str(ROOT / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"))
    options = parser.parse_args(argv)
    devices = parse_devices(adb("devices", "-l"))
    if options.action == "list":
        print(json.dumps(devices, indent=2))
        return
    serials = select_devices(devices, options.serial, options.count, options.allow_emulator)
    package = json.loads((ROOT / "tools/device-target.json").read_text())["applicationId"]
    if options.action in ("logs", "mirror") and len(serials) != 1:
        raise ValueError("Live tools require --serial SERIAL --count 1. Open one terminal per phone.")
    if options.action in ("install", "test"):
        apk = require_apk(options.apk)
        test_apk = require_apk(options.test_apk) if options.action == "test" else None
        # Validate all targets and APK paths before changing any device.
        for serial in serials:
            print(f"{serial}: " + adb("-s", serial, "install", "-r", apk).strip())
            if test_apk:
                adb("-s", serial, "install", "-r", test_apk)
                result = adb("-s", serial, "shell", "am", "instrument", "-w",
                             f"{package}.test/androidx.test.runner.AndroidJUnitRunner", timeout=300)
                print(result)
                if "OK (" not in result or "FAILURES" in result or "INSTRUMENTATION_FAILED" in result:
                    raise RuntimeError(f"Instrumentation did not pass on {serial}")
    elif options.action == "logs":
        # Output stays in the terminal. No unattended files, log archive, or key material.
        subprocess.run([adb_path(), "-s", serials[0], "logcat", "-v", "threadtime",
                        "HardlineRelay:I", "AndroidRuntime:E", "*:S"], check=True)
    elif options.action == "mirror":
        env = os.environ.copy()
        env["ADB"] = adb_path()
        subprocess.run(["scrcpy", "--serial", serials[0], "--max-size", "1280", "--no-audio"], env=env, check=True)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        pass
    except (ValueError, RuntimeError, subprocess.SubprocessError, OSError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        sys.exit(1)
