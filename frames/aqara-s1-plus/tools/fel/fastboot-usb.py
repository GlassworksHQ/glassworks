#!/usr/bin/env python3
"""Talks to the S1's stock U-Boot fastboot (1f3a:1010) over pyusb: getvar, a RAM boot, a reboot.

What it touches: USB, and the stock U-Boot's RAM (its 32 MiB download buffer). It never writes eMMC.
It sends only `getvar:<name>`, `download:<size>`, `boot` and a plain `reboot` (to flash), and refuses
every other command (flash, erase, oem, flashing, unlock, reboot-fastboot, which writes `misc`,
continue, ...) before anything goes on the wire.
Only the root runs it against a panel. Research: frames/aqara-s1-plus/research/frame-loader.md.

    fastboot-usb.py getvar [NAME ...]    the default names when none are given
    fastboot-usb.py boot IMAGE           download IMAGE to RAM, then `boot`
    fastboot-usb.py reboot               leave fastboot: boot from flash, as a power cycle would

On macOS: `DYLD_LIBRARY_PATH=/opt/homebrew/lib python3 fastboot-usb.py ...` (Homebrew's libusb,
`pip install pyusb`), and approve "Allow accessory to connect?" for every bootloader start.

Why not the platform `fastboot` CLI (read from the BSP U-Boot 2018 source, drivers/sunxi_usb):
- The gadget comes up unconfigured, so a host has to send SET_CONFIGURATION first. That request
  resets the bulk data toggles on both sides. This tool sends it every run.
- macOS fastboot clears both bulk pipes when it opens the device (ClearPipeStallBothEnds). The stock
  U-Boot ignores CLEAR_FEATURE, so only the host's toggles reset. After an odd number of packets
  from an earlier session, the device drops the next packet as a duplicate. A lost command hangs,
  and a lost data packet leaves `download` 512 bytes short of its count, so OKAY never comes.
- `download` ends when the received count equals the size exactly. Extra bytes or a lost packet
  hang it. A zero-length packet after the data can arrive as an empty command, answered with FAIL.
- `product` returns the panel's serial (`snum` is copied into the product string), so this tool
  redacts `product` and `serialno`.
- `boot` sends no reply at all, not even its own FAIL. It boots only a legacy uImage at offset 2048.
  Anything else prints "Booting raw image..", "do_go fail" and returns to the command loop.

Exit status: 0 done (for `boot`: USB dropped, so the kernel is starting), 2 U-Boot came back from
`boot` without booting, 1 anything else (for `boot`: no answer and USB still up, so unknown).
"""
import os
import struct
import sys
import time

VID, PID = 0x1F3A, 0x1010
IFACE_CLASS = (0xFF, 0x42, 0x03)
DEFAULT_VARS = ("version", "max-download-size", "secure", "unlocked", "version-bootloader",
                "slot-count", "product", "serialno")
REDACTED = ("product", "serialno")
STOCK_PAGE = 2048        # CFG_FASTBOOT_MKBOOTIMAGE_PAGE_SIZE: where stock `boot` looks for a uImage
UIMAGE_MAGIC = 0x27051956
CHUNK = 1024 * 1024      # any size works: the stock gadget reads each 512-byte packet by PIO
REPLY_MS = 5_000
CHUNK_MS = 30_000
DATA_DONE_MS = 30_000
BOOT_REPLY_MS = 10_000


class FastbootError(Exception):
    pass


class Refused(FastbootError):
    pass


class Timeout(FastbootError):
    pass


class Gone(FastbootError):
    pass


def check_command(cmd):
    """Only getvar, download, boot and reboot. The rest could write the panel or change its state."""
    if cmd in ("boot", "reboot"):
        return
    if cmd.startswith("getvar:") and len(cmd) <= 64 and cmd[7:] and all(
            c.isalnum() or c in "-_:." for c in cmd[7:]):
        return
    if cmd.startswith("download:") and len(cmd) == 17 and all(c in "0123456789abcdef" for c in cmd[9:]):
        return
    raise Refused(f"refused: {cmd.split(':')[0]!r} is not getvar, download, boot or reboot")


def show(name, value):
    return "<redacted>" if name in REDACTED and value else value


class Fastboot:
    """The fastboot protocol over a transport with write(data, timeout_ms) and read(size, timeout_ms)."""

    def __init__(self, transport, log=print):
        self.t = transport
        self.log = log

    def send(self, cmd, timeout_ms=REPLY_MS):
        check_command(cmd)
        self.t.write(cmd.encode("ascii"), timeout_ms)

    def reply(self, cmd, timeout_ms=REPLY_MS):
        while True:
            r = bytes(self.t.read(512, timeout_ms)).decode("ascii", "replace")
            kind, value = r[:4], r[4:]
            if kind in ("INFO", "TEXT"):
                self.log(f"  ({kind.lower()}) {value}")
                continue
            if kind in ("OKAY", "DATA"):
                return kind, value
            if kind == "FAIL":
                raise FastbootError(f"{cmd.split(':')[0]}: FAIL {value}".rstrip())
            raise FastbootError(f"{cmd.split(':')[0]}: unexpected reply {r!r}")

    def getvar(self, name):
        cmd = f"getvar:{name}"
        self.send(cmd)
        kind, value = self.reply(cmd)
        if kind != "OKAY":
            raise FastbootError(f"getvar:{name}: {kind} instead of OKAY")
        return value.strip()

    def download(self, data, limit):
        if not data:
            raise FastbootError("the image is empty")
        if len(data) > limit:
            raise FastbootError(f"the image is {len(data)} bytes, max-download-size is {limit}")
        cmd = f"download:{len(data):08x}"
        self.send(cmd)
        kind, value = self.reply(cmd)
        if kind != "DATA" or int(value or "0", 16) != len(data):
            raise FastbootError(f"download: got {kind}{value}, expected DATA{len(data):08x}")
        start = time.monotonic()
        for off in range(0, len(data), CHUNK):
            self.t.write(data[off:off + CHUNK], CHUNK_MS)
        try:
            self.reply("download", DATA_DONE_MS)
        except Timeout:
            raise FastbootError(
                "download: all bytes sent, but no OKAY. The stock U-Boot counts packets exactly, so "
                "one was dropped (a data-toggle mismatch after another USB session). Unplug and "
                "replug, or restart fastboot, and run this tool first.") from None
        secs = time.monotonic() - start
        self.log(f"download: OKAY, {len(data)} bytes in {secs:.1f} s ({len(data) / secs / 1e6:.1f} MB/s)")

    def boot(self):
        """True: USB dropped (the kernel is starting). False: U-Boot came back without booting."""
        self.send("boot")
        try:
            kind, value = self.reply("boot", BOOT_REPLY_MS)
            self.log(f"boot: {kind}{value} (the stock U-Boot normally sends nothing)")
        except Timeout:
            self.log(f"boot: no reply in {BOOT_REPLY_MS // 1000} s (expected: the stock U-Boot sends none)")
        except Gone:
            return True
        if not self.t.present():
            return True
        try:
            self.getvar("version")
        except Timeout:
            raise FastbootError(
                "boot: no reply, and U-Boot doesn't answer getvar, but USB is still up. Watch the "
                "screen and wait for adb; with no Android in 4 minutes, power-cycle (nothing was written)."
            ) from None
        except Gone:
            return True
        return False


def reboot(f):
    """The stock U-Boot answers OKAY, waits 1 s and restarts. A lost reply is fine: it restarts anyway."""
    f.send("reboot")
    try:
        f.reply("reboot", REPLY_MS)
    except (Timeout, Gone):
        pass


class PyUsb:
    """The one stock fastboot device on USB, configured afresh (which resets both data toggles)."""

    def __init__(self, wait_s):
        import usb.core
        import usb.util
        self.usb = usb
        deadline = time.monotonic() + wait_s
        while True:
            found = list(usb.core.find(find_all=True, idVendor=VID, idProduct=PID))
            if len(found) == 1:
                break
            if len(found) > 1:
                raise FastbootError("more than one 1f3a:1010 device: unplug all but the panel")
            if time.monotonic() > deadline:
                hint = (" On macOS, approve \"Allow accessory to connect?\" (System Settings > Privacy "
                        "& Security) for each bootloader start." if sys.platform == "darwin" else "")
                raise FastbootError(f"no 1f3a:1010 device after {wait_s} s.{hint}")
            time.sleep(0.5)
        self.dev = found[0]
        self.dev.set_configuration(1)
        intf = next((i for i in self.dev.get_active_configuration()
                     if (i.bInterfaceClass, i.bInterfaceSubClass, i.bInterfaceProtocol) == IFACE_CLASS), None)
        if intf is None:
            raise FastbootError("1f3a:1010 has no ff/42/03 fastboot interface")
        usb.util.claim_interface(self.dev, intf)
        d = usb.util.ENDPOINT_IN
        self.ep_in = usb.util.find_descriptor(intf, custom_match=lambda e: usb.util.endpoint_direction(e.bEndpointAddress) == d)
        self.ep_out = usb.util.find_descriptor(intf, custom_match=lambda e: usb.util.endpoint_direction(e.bEndpointAddress) != d)
        if self.ep_in is None or self.ep_out is None:
            raise FastbootError("the fastboot interface has no bulk IN/OUT pair")
        print(f"device: 1f3a:1010, bus {self.dev.bus} address {self.dev.address}, configured, "
              f"endpoints 0x{self.ep_out.bEndpointAddress:02x}/0x{self.ep_in.bEndpointAddress:02x}")

    def _io(self, fn, *args):
        try:
            return fn(*args)
        except self.usb.core.USBTimeoutError:
            raise Timeout("USB timeout") from None
        except self.usb.core.USBError as e:
            if e.errno == 19 or "No such device" in str(e):
                raise Gone(f"USB device gone ({e})") from None
            raise FastbootError(f"USB error: {e}") from None

    def write(self, data, timeout_ms):
        n = self._io(self.ep_out.write, data, timeout_ms)
        if n != len(data):
            raise FastbootError(f"USB write sent {n} of {len(data)} bytes")

    def read(self, size, timeout_ms):
        return self._io(self.ep_in.read, size, timeout_ms)

    def present(self):
        return self.usb.core.find(idVendor=VID, idProduct=PID) is not None


def boot_image_note(data):
    """What the stock `boot` will do with this image, from its bytes (it checks offset 2048 only)."""
    if len(data) <= STOCK_PAGE:
        return f"the image is {len(data)} bytes; expect stock `boot` to refuse it without a reply"
    if struct.unpack_from(">I", data, STOCK_PAGE)[0] == UIMAGE_MAGIC:
        return "legacy uImage at offset 2048: stock `boot` runs bootm on it"
    kind = "a raw arm64 Image" if data[STOCK_PAGE + 0x38:STOCK_PAGE + 0x3C] == b"ARMd" else "no uImage"
    return (f"{kind} at offset 2048, no uImage: expect stock `boot` to print 'do_go fail' and "
            "return to fastboot without booting")


def main(argv):
    if (len(argv) < 2 or argv[1] not in ("getvar", "boot", "reboot")
            or (argv[1] == "boot" and len(argv) != 3) or (argv[1] == "reboot" and len(argv) != 2)):
        sys.stderr.write(__doc__.split("\n\n")[2] + "\n")
        return 1
    wait_s = int(os.environ.get("FASTBOOT_USB_WAIT", "60"))
    try:
        data = None
        if argv[1] == "boot":
            with open(argv[2], "rb") as f:
                data = f.read()
            print(f"image: {len(data)} bytes; {boot_image_note(data)}")
        fb = Fastboot(PyUsb(wait_s))
        if argv[1] == "getvar":
            for name in argv[2:] or DEFAULT_VARS:
                print(f"{name}: {show(name, fb.getvar(name))}")
            return 0
        if argv[1] == "reboot":
            reboot(fb)
            print("reboot: sent. The panel boots from flash.")
            return 0
        limit = int(fb.getvar("max-download-size"), 16)
        fb.download(data, limit)
        if fb.boot():
            print("boot: USB dropped. The kernel is starting from RAM: wait for adb.")
            return 0
        print("boot: U-Boot answered again after `boot`: it did not boot this image. Nothing was written.")
        return 2
    except FastbootError as e:
        print(f"error: {e}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
