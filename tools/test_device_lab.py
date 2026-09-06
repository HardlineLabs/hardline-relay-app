import unittest
from device_lab import parse_devices, select_devices


class DeviceSelectionTest(unittest.TestCase):
    def test_zero_devices_cannot_report_success(self):
        with self.assertRaises(ValueError):
            select_devices([], count=0)

    def test_two_authorized_phones(self):
        devices = parse_devices("List of devices attached\na device product:x\nb device product:y\n")
        self.assertEqual(["a", "b"], select_devices(devices))

    def test_unauthorized_is_not_silently_skipped(self):
        with self.assertRaisesRegex(ValueError, "authorize USB"):
            select_devices([{"serial": "a", "state": "device"}, {"serial": "b", "state": "unauthorized"}])

    def test_missing_or_extra_devices_require_explicit_selection(self):
        for devices in ([], [{"serial": str(i), "state": "device"} for i in range(3)]):
            with self.assertRaises(ValueError):
                select_devices(devices)

    def test_emulator_is_excluded_by_default(self):
        devices = [{"serial": "emulator-5554", "state": "device"}]
        with self.assertRaises(ValueError):
            select_devices(devices, count=1)
        self.assertEqual(["emulator-5554"], select_devices(devices, count=1, allow_emulator=True))

    def test_explicit_selection_does_not_touch_extra_phone(self):
        devices = [{"serial": s, "state": "device"} for s in ("a", "b", "personal")]
        self.assertEqual(["a", "b"], select_devices(devices, ["a", "b"]))

    def test_offline_or_duplicate_target_fails(self):
        with self.assertRaises(ValueError):
            select_devices([{"serial": "a", "state": "offline"}], count=1)
        with self.assertRaises(ValueError):
            select_devices([{"serial": "a", "state": "device"}], ["a", "a"])


if __name__ == "__main__":
    unittest.main()
