"""fastboot-usb.py against a fake of the stock U-Boot's fastboot (BSP drivers/sunxi_usb/usb_fastboot.c)."""
import contextlib
import importlib.util
import io
import os
import struct
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("fastboot_usb", os.path.join(HERE, "..", "fastboot-usb.py"))
fb = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fb)

FAKE_SERIAL = "S1FAKESERIAL0000"
LIMIT = 32 * 1024 * 1024


class StockUBoot:
    """Packet-level fake: exact byte count for download, no reply to boot, snum in `product`."""

    def __init__(self, drop_packet=None, boot_reply=None, hang_after_boot=False):
        self.replies, self.commands, self.data_writes = [], [], []
        self.want = None
        self.got = self.packets = 0
        self.drop_packet = drop_packet
        self.buffer = bytearray()
        self.is_present = True
        self.boot_reply = boot_reply
        self.hang_after_boot = hang_after_boot
        self.hung = False

    def write(self, data, timeout_ms):
        data = bytes(data)
        if self.want is not None:
            self.data_writes.append(len(data))
            for off in range(0, len(data), 512):
                n, self.packets = self.packets, self.packets + 1
                if n != self.drop_packet:
                    self.buffer += data[off:off + 512]
                    self.got += min(512, len(data) - off)
            if self.got == self.want:
                self.want = None
                self.replies.append(b"OKAY")
            return
        cmd = data.decode()
        self.commands.append(cmd)
        if cmd.startswith("getvar:"):
            name = cmd[7:]
            value = {"version": "0.5", "product": FAKE_SERIAL, "serialno": "20080411", "secure": "yes",
                     "max-download-size": f"0x{LIMIT:08x}"}.get(name, "not supported")
            self.replies.append(b"OKAY" + value.encode())
        elif cmd.startswith("download:"):
            size = int(cmd[9:], 16)
            if size > LIMIT:
                self.replies.append(b"FAILdownload: data > buffer")
            else:
                self.want, self.got, self.buffer = size, 0, bytearray()
                self.replies.append(b"DATA%08x" % size)
        elif cmd == "reboot":
            self.replies.append(b"OKAY")
            self.rebooted = True
        elif cmd == "boot":
            # __boot formats "FAILinvalid boot image" into a local buffer and never sends it.
            if self.boot_reply:
                self.replies.append(self.boot_reply)
            elif self.hang_after_boot:
                self.hung = True
            elif len(self.buffer) > 2048 and struct.unpack_from(">I", self.buffer, 2048)[0] == 0x27051956:
                self.is_present = False
        else:
            self.replies.append(b"FAIL")

    def read(self, size, timeout_ms):
        if not self.is_present:
            raise fb.Gone("gone")
        if not self.replies or self.hung:
            raise fb.Timeout("timeout")
        return self.replies.pop(0)

    def present(self):
        return self.is_present


def android_image(size, kernel=b"ARMd"):
    b = bytearray(size)
    b[:8] = b"ANDROID!"
    if kernel == b"ARMd":
        b[2048 + 0x38:2048 + 0x3C] = kernel
    else:
        struct.pack_into(">I", b, 2048, 0x27051956)
    return bytes(b)


def run_main(dev, *args):
    out = io.StringIO()
    fb.PyUsb = lambda wait_s: dev
    with contextlib.redirect_stdout(out), contextlib.redirect_stderr(out):
        code = fb.main(["fastboot-usb.py", *args])
    return code, out.getvalue()


class FastbootUsbTest(unittest.TestCase):
    def test_getvar_never_prints_the_serial(self):
        dev = StockUBoot()
        code, out = run_main(dev, "getvar")
        self.assertEqual(code, 0)
        self.assertNotIn(FAKE_SERIAL, out)
        self.assertNotIn("20080411", out)
        self.assertIn("product: <redacted>", out)
        self.assertIn("max-download-size: 0x02000000", out)
        self.assertIn("version-bootloader: not supported", out)

    def test_refuses_every_command_but_getvar_download_boot(self):
        dev = StockUBoot()
        f = fb.Fastboot(dev, log=lambda s: None)
        for cmd in ("flash:boot", "erase:boot", "oem unlock", "oem run:mmc dev 1", "flashing unlock",
                    "flashing:unlock", "reboot-bootloader", "reboot-fastboot", "reboot-recovery", "continue", "boot:x", "bootx",
                    "download:2000000", "download:zzzzzzzz", "getvar:", "getvar:a b", "set_active:a"):
            with self.assertRaises(fb.Refused, msg=cmd):
                f.send(cmd)
        self.assertEqual(dev.commands, [])

    def test_download_sends_the_exact_count_in_chunks_without_a_zero_length_write(self):
        dev = StockUBoot()
        f = fb.Fastboot(dev, log=lambda s: None)
        data = android_image(LIMIT)
        f.download(data, LIMIT)
        self.assertEqual(dev.commands, ["download:02000000"])
        self.assertEqual(sum(dev.data_writes), LIMIT)
        self.assertNotIn(0, dev.data_writes)
        self.assertLessEqual(max(dev.data_writes), fb.CHUNK)
        self.assertEqual(bytes(dev.buffer), data)

    def test_download_over_the_limit_sends_nothing(self):
        dev = StockUBoot()
        f = fb.Fastboot(dev, log=lambda s: None)
        with self.assertRaises(fb.FastbootError):
            f.download(b"\0" * (LIMIT + 1), LIMIT)
        self.assertEqual(dev.commands, [])

    def test_a_dropped_packet_ends_in_a_clear_error_not_a_hang(self):
        dev = StockUBoot(drop_packet=100)
        f = fb.Fastboot(dev, log=lambda s: None)
        with self.assertRaisesRegex(fb.FastbootError, "toggle"):
            f.download(android_image(4 * 1024 * 1024), LIMIT)

    def test_boot_of_a_raw_kernel_image_comes_back_to_fastboot(self):
        with tempfile.NamedTemporaryFile(suffix=".img") as img:
            img.write(android_image(LIMIT))
            img.flush()
            dev = StockUBoot()
            code, out = run_main(dev, "boot", img.name)
        self.assertEqual(code, 2)
        self.assertIn("do_go fail", out)
        self.assertEqual(dev.commands, ["getvar:max-download-size", "download:02000000", "boot", "getvar:version"])

    def test_boot_of_a_uimage_drops_usb(self):
        with tempfile.NamedTemporaryFile(suffix=".img") as img:
            img.write(android_image(8 * 1024 * 1024, kernel=b"uImage"))
            img.flush()
            code, out = run_main(StockUBoot(), "boot", img.name)
        self.assertEqual(code, 0)
        self.assertIn("USB dropped", out)

    def test_reboot_sends_exactly_reboot(self):
        dev = StockUBoot()
        code, out = run_main(dev, "reboot")
        self.assertEqual(code, 0)
        self.assertEqual(dev.commands, ["reboot"])

    def test_a_short_image_is_refused_silently_and_reported_as_not_booted(self):
        with tempfile.NamedTemporaryFile(suffix=".img") as img:
            img.write(b"ANDROID!" + b"\0" * 100)
            img.flush()
            dev = StockUBoot()
            code, out = run_main(dev, "boot", img.name)
        self.assertEqual(code, 2)
        self.assertIn("without a reply", out)
        self.assertEqual(dev.commands[-2:], ["boot", "getvar:version"])

    def test_boot_fail_reply_is_an_error(self):
        with tempfile.NamedTemporaryFile(suffix=".img") as img:
            img.write(android_image(8 * 1024 * 1024))
            img.flush()
            code, out = run_main(StockUBoot(boot_reply=b"FAIL:secure mode,fastboot limited used"), "boot", img.name)
        self.assertEqual(code, 1)
        self.assertIn("FAIL :secure mode", out)

    def test_no_answer_with_usb_still_up_is_not_reported_as_booted(self):
        with tempfile.NamedTemporaryFile(suffix=".img") as img:
            img.write(android_image(8 * 1024 * 1024))
            img.flush()
            code, out = run_main(StockUBoot(hang_after_boot=True), "boot", img.name)
        self.assertEqual(code, 1)
        self.assertNotIn("USB dropped", out)
        self.assertIn("USB is still up", out)


if __name__ == "__main__":
    unittest.main()
