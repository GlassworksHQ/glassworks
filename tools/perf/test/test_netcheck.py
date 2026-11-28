"""netcheck.py (#649): the test computer's own network, read before a run. Touches no device and no network:
the CLI runs against fake_adb.py and fake_ping.py."""
import json
import os
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))
import netcheck as N  # noqa: E402

MACOS_PING = """PING 192.0.2.1 (192.0.2.1): 56 data bytes
64 bytes from 192.0.2.1: icmp_seq=0 ttl=64 time=3.144 ms
64 bytes from 192.0.2.1: icmp_seq=1 ttl=64 time=69.764 ms
Request timeout for icmp_seq 2
64 bytes from 192.0.2.1: icmp_seq=3 ttl=64 time=2.500 ms

--- 192.0.2.1 ping statistics ---
4 packets transmitted, 3 packets received, 25.0% packet loss
round-trip min/avg/max/stddev = 2.500/25.136/69.764/31.554 ms
"""
LINUX_PING = """PING 192.0.2.1 (192.0.2.1) 56(84) bytes of data.
64 bytes from 192.0.2.1: icmp_seq=1 ttl=64 time=0.412 ms
64 bytes from 192.0.2.1: icmp_seq=2 ttl=64 time<1 ms
"""


class Parsing(unittest.TestCase):
    def test_ping_replies(self):
        self.assertEqual(N.parse_ping(MACOS_PING), [3.144, 69.764, 2.5])         # not the summary line's numbers
        self.assertEqual(N.parse_ping(LINUX_PING), [0.412, 1.0])
        self.assertEqual(N.ping_stats([3.144, 69.764, 2.5], 4),
                         {"sent": 4, "received": 3, "min_ms": 2.5, "median_ms": 3.1, "p95_ms": 69.8, "max_ms": 69.8})
        self.assertIsNone(N.ping_stats([], 40))

    def test_default_route(self):
        mac = "   route to: default\ndestination: default\n       mask: default\n    gateway: 192.0.2.1\n  interface: en0\n"
        self.assertEqual(N.parse_default_route(mac), ("192.0.2.1", "en0"))
        self.assertEqual(N.parse_default_route("default via 192.0.2.1 dev eth0 proto dhcp metric 100\n"), ("192.0.2.1", "eth0"))
        self.assertEqual(N.parse_default_route(""), (None, None))

    def test_hardware_ports(self):
        ports = ("\nHardware Port: Ethernet Adapter (en4)\nDevice: en4\nEthernet Address: 00:00:5e:00:53:01\n\n"
                 "Hardware Port: Wi-Fi\nDevice: en0\nEthernet Address: 00:00:5e:00:53:02\n")
        self.assertEqual(N.parse_hardware_ports(ports, "en0"), "wifi")
        self.assertEqual(N.parse_hardware_ports(ports, "en4"), "wired")
        self.assertIsNone(N.parse_hardware_ports(ports, "en9"))

    def test_how_adb_reaches_the_panel(self):
        listing = ("List of devices attached\n"
                   "FAKE0001               device usb:1-1 product:p model:m device:d transport_id:1\n"
                   "192.0.2.10:5555        device product:p model:m device:d transport_id:2\n"
                   "adb-FAKE0002-abc._adb-tls-connect._tcp device product:p model:m device:d transport_id:3\n"
                   "emulator-5554          device product:sdk model:sdk device:emu transport_id:4\n")
        self.assertIs(N.parse_adb_devices(listing, "FAKE0001"), False)
        self.assertIs(N.parse_adb_devices(listing, "192.0.2.10:5555"), True)
        self.assertIs(N.parse_adb_devices(listing, "adb-FAKE0002-abc._adb-tls-connect._tcp"), True)
        self.assertIs(N.parse_adb_devices(listing, "emulator-5554"), False)       # stays on this computer
        # adb's libusb backend lists a USB device without usb: (CodeRabbit on #650): still USB.
        self.assertIs(N.parse_adb_devices("List of devices attached\nFAKE0003 device product:p model:m transport_id:5\n", None), False)
        self.assertIs(N.parse_adb_devices("List of devices attached\nadb-FAKE4-y._adb._tcp. device transport_id:6\n", None), True)
        self.assertIsNone(N.parse_adb_devices(listing, None))                      # four devices, none named
        self.assertIsNone(N.parse_adb_devices(listing, "OTHER"))
        one = "List of devices attached\nFAKE0001 device usb:1-1 transport_id:1\n"
        self.assertIs(N.parse_adb_devices(one, None), False)

    def test_panel_address(self):
        ip = ("1: lo    inet 127.0.0.1/8 scope host lo\\       valid_lft forever preferred_lft forever\n"
              "47: wlan0    inet 192.0.2.10/24 brd 192.0.2.255 scope global wlan0\\       valid_lft forever\n")
        self.assertEqual(N.parse_panel_address(ip), "192.0.2.10")
        self.assertIsNone(N.parse_panel_address("1: lo    inet 127.0.0.1/8 scope host lo\n"))

    def test_quiet(self):
        self.assertIs(N.quiet([2.3, 3.8, 9.7] * 10), True)           # the Pixel Tablet's own Wi-Fi to its gateway
        self.assertIs(N.quiet([20.0] * 40), True)                    # exactly the bar is quiet
        self.assertIs(N.quiet([20.04] * 40), False)                  # over it, though the report rounds it to 20.0
        self.assertIs(N.quiet([3.0] * 39 + [84.0]), False)           # a Mac on Wi-Fi: one stall is enough
        self.assertIsNone(N.quiet([]))


class Cli(unittest.TestCase):
    def run_it(self, *args, **extra):
        tmp = tempfile.mkdtemp()
        state = os.path.join(tmp, "state")
        os.makedirs(state)
        adb = os.path.join(tmp, "adb")
        with open(adb, "w") as f:
            f.write(f'#!/bin/sh\nexec "{sys.executable}" "{os.path.join(HERE, "fake_adb.py")}" "$@"\n')
        os.chmod(adb, 0o755)
        env = dict(os.environ, ADB=adb, FAKE_ADB_STATE=state, GW_PERF_PING=os.path.join(HERE, "fake_ping.py"),
                   GW_PERF_GATEWAY="192.0.2.1", **extra)
        env.pop("ANDROID_SERIAL", None)
        p = subprocess.run([sys.executable, os.path.join(os.path.dirname(HERE), "netcheck.py"), "--out", tmp, "--count", "10", *args],
                           env=env, capture_output=True, text=True, timeout=60)
        with open(os.path.join(tmp, "netcheck.json")) as f:
            text = f.read()
        return p, json.loads(text), text

    def test_a_quiet_wired_rig(self):
        p, out, text = self.run_it()
        self.assertEqual(p.returncode, 0, p.stderr)
        self.assertEqual(out["gateway"], {"sent": 10, "received": 10, "min_ms": 3.0, "median_ms": 3.0, "p95_ms": 3.0, "max_ms": 3.0})
        self.assertEqual(out["panel"]["received"], 10)
        self.assertIs(out["adb_over_network"], False)            # the fake S1 is on USB
        self.assertIs(out["quiet"], True)
        self.assertNotIn("warning", p.stderr)
        self.assertNotIn("192.0.2", text)                         # numbers only, never an address

    def test_the_fake_over_the_lan_is_recorded(self):
        """run.sh --ha-transport lan: the fake's traffic crossed this computer's network whatever adb goes over."""
        _, out, _ = self.run_it("--ha-over-lan")
        self.assertIs(out["ha_over_lan"], True)
        self.assertIs(out["adb_over_network"], False)
        _, out, _ = self.run_it()
        self.assertNotIn("ha_over_lan", out)                     # through adb reverse: the file as before

    def test_a_stalling_wifi_with_wireless_adb(self):
        p, out, _ = self.run_it(FAKE_PING_GATEWAY_MS="2.3,3.1,4.0,2.8,68.0,3.3,84.0,2.9,3.6,3.0", FAKE_ADB_WIFI="1")
        self.assertEqual(p.returncode, 0, p.stderr)
        self.assertEqual((out["gateway"]["p95_ms"], out["gateway"]["max_ms"]), (84.0, 84.0))
        self.assertIs(out["adb_over_network"], True)
        self.assertIs(out["quiet"], False)
        self.assertIn("warning: this computer's own link to its gateway goes over 20 ms", p.stderr)
        self.assertIn("sudo ifconfig awdl0 down", p.stderr)

    def test_the_bar_is_judged_before_rounding(self):
        """CodeRabbit on #650: replies of 20.04 ms are over 20 ms, though the file says 20.0."""
        p, out, _ = self.run_it(FAKE_PING_GATEWAY_MS="20.04")
        self.assertEqual((out["gateway"]["p95_ms"], out["gateway"]["max_ms"]), (20.0, 20.0))
        self.assertIs(out["quiet"], False)
        self.assertIn("warning", p.stderr)
        p, out, _ = self.run_it(FAKE_PING_GATEWAY_MS="20.0")
        self.assertIs(out["quiet"], True)
        self.assertNotIn("warning", p.stderr)

    def test_a_gateway_that_doesnt_answer(self):
        p, out, _ = self.run_it(FAKE_PING_SILENT="192.0.2.1")
        self.assertEqual(p.returncode, 0, p.stderr)
        self.assertIsNone(out["gateway"])
        self.assertIsNone(out["quiet"])
        self.assertIn("to its gateway no answer", p.stderr)


if __name__ == "__main__":
    unittest.main()
