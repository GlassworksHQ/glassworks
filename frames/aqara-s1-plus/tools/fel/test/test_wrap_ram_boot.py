"""wrap-ram-boot.py on a synthetic boot image: the layout the stock U-Boot's `boot` and `do_bootm` need."""
import contextlib
import importlib.util
import io
import os
import struct
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))


def load(name, file):
    spec = importlib.util.spec_from_file_location(name, os.path.join(HERE, "..", file))
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    return m


wrap = load("wrap_ram_boot", "wrap-ram-boot.py")
fbu = load("fastboot_usb_for_wrap", "fastboot-usb.py")

CMDLINE = "console=ttyS0,115200 root=/dev/mmcblk0p4 init=/init snum=FAKESERIAL0000 mac_addr=02:00:00:00:00:01"


def kernel(image_size=0x200000, flags=0xA, size=0x100000):
    k = bytearray(size)
    struct.pack_into("<IIQQQ", k, 0, 0x14358000, 0, 0x80000, image_size, flags)
    k[0x38:0x3C] = b"ARMd"
    return bytes(k)


def boot_img(k=None, ramdisk=b"\x1f\x8b" + b"r" * 4094):
    k = kernel() if k is None else k
    hdr = bytearray(2048)
    hdr[:8] = b"ANDROID!"
    struct.pack_into("<8I", hdr, 8, len(k), 0x40080000, len(ramdisk), 0x43000000, 0, 0, 0x40000100, 2048)
    pad = lambda b: b + bytes(-len(b) % 2048)
    return bytes(hdr) + pad(k) + pad(ramdisk)


class WrapRamBootTest(unittest.TestCase):
    def test_layout_matches_the_stock_boot_and_bootm(self):
        boot = boot_img()
        img = wrap.build(boot, CMDLINE + "\n")
        wrap.check(img, boot)
        self.assertNotEqual(img[:8], b"ANDROID!")
        magic, _, _, _, ih_load, ep, _ = struct.unpack_from(">7I", img, 0x800)
        self.assertEqual(magic, 0x27051956)
        self.assertEqual(ih_load, 0)
        self.assertEqual(struct.unpack_from("<I", img, 0x800 + 0x10)[0], 0, "Android view: ramdisk_size 0")
        self.assertEqual(tuple(img[0x81C:0x820]), (5, 2, 14, 0))
        self.assertEqual(0x41000000 + 0x840 + ep, 0x41080000)
        self.assertEqual(img[0x80000:0x80000 + 0x100000], kernel())
        line = img[0x40:img.index(b"\0", 0x40)].decode()
        self.assertTrue(line.startswith(CMDLINE))
        self.assertIn(" androidboot.gw139=ram initrd=0x41300000,0x1000", line)
        self.assertEqual(img[0x300000:0x301000], b"\x1f\x8b" + b"r" * 4094)
        self.assertEqual(fbu.boot_image_note(img), "legacy uImage at offset 2048: stock `boot` runs bootm on it")

    def test_canary_differs_from_the_plain_image_only_in_the_first_instruction(self):
        boot = boot_img()
        plain, canary = wrap.build(boot, CMDLINE), wrap.build(boot, CMDLINE, canary=True)
        words = range(0, len(plain) - 3, 4)
        diff = [i for i in words if plain[i:i + 4] != canary[i:i + 4] and not 0x800 <= i < 0x840]
        self.assertEqual(diff, [0x80000])
        self.assertEqual(struct.unpack_from("<I", canary, 0x80000)[0], 0x14000000)
        self.assertIn("canary", wrap.check(canary, boot, canary=True))
        self.assertNotIn("canary", wrap.check(plain, boot))
        with self.assertRaisesRegex(wrap.WrapError, "differ"):
            wrap.check(canary, boot)

    def test_stop_os_is_the_canary_with_ih_os_openbsd(self):
        boot = boot_img()
        canary, stop = wrap.build(boot, CMDLINE, canary=True), wrap.build(boot, CMDLINE, stop_os=True)
        words = range(0, len(canary) - 3, 4)
        diff = [i for i in words if canary[i:i + 4] != stop[i:i + 4]]
        self.assertEqual(diff, [0x804, 0x81C], "only the header CRC and the os/arch/type/comp word")
        self.assertEqual(tuple(stop[0x81C:0x820]), (1, 2, 14, 0))
        summary = wrap.check(stop, boot, canary=True)
        self.assertIn("canary", summary)
        self.assertIn("stop: ih_os OpenBSD", summary)
        self.assertNotIn("stop", wrap.check(canary, boot, canary=True))
        no_spin = bytearray(stop)
        no_spin[0x80000:0x80004] = kernel()[:4]
        hdr = bytearray(wrap.uimage_header(bytes(no_spin[0x840:]), 0x80000 - 0x840, wrap.IH_OS_OPENBSD))
        no_spin[0x800:0x840] = hdr
        with self.assertRaisesRegex(wrap.WrapError, "without the canary"):
            wrap.check(bytes(no_spin))

    def test_refuses_what_would_not_boot(self):
        cases = {
            "initrd already set": (boot_img(), CMDLINE + " initrd=0x1,0x2"),
            "no placement anywhere": (boot_img(kernel(flags=0x2)), CMDLINE),
            "over 32 MiB": (boot_img(ramdisk=b"\x1f\x8b" + bytes(0x2000000)), CMDLINE),
            "command line too long": (boot_img(), "x" * 1960),
            "not an Android image": (b"\0" * 8192, CMDLINE),
        }
        for name, (boot, line) in cases.items():
            with self.assertRaises(wrap.WrapError, msg=name):
                wrap.build(boot, line)

    def test_check_catches_damage(self):
        img = bytearray(wrap.build(boot_img(), CMDLINE))
        bad_data = bytearray(img)
        bad_data[0x300010] ^= 1
        with self.assertRaisesRegex(wrap.WrapError, "CRC"):
            wrap.check(bytes(bad_data))
        bad_load = bytearray(img)
        bad_load[0x813] = 1
        with self.assertRaisesRegex(wrap.WrapError, "ih_load"):
            wrap.check(bytes(bad_load))
        empty = bytes(img).replace(b"initrd=0x41300000,0x1000", b"initrd=0x41300000,0x0000")
        self.assertNotEqual(empty, bytes(img))
        with self.assertRaisesRegex(wrap.WrapError, "empty ramdisk"):
            wrap.check(empty)

    def test_never_prints_the_command_line(self):
        with tempfile.TemporaryDirectory() as d:
            paths = [os.path.join(d, n) for n in ("boot.img", "cmdline.txt", "out.img")]
            open(paths[0], "wb").write(boot_img())
            open(paths[1], "w").write(CMDLINE + "\n")
            out = io.StringIO()
            with contextlib.redirect_stdout(out), contextlib.redirect_stderr(out):
                self.assertEqual(wrap.main(["wrap-ram-boot.py", *paths]), 0)
                self.assertEqual(wrap.main(["wrap-ram-boot.py", "--check", paths[2]]), 0)
        self.assertNotIn("FAKESERIAL", out.getvalue())
        self.assertNotIn("mac_addr", out.getvalue())


if __name__ == "__main__":
    unittest.main()
