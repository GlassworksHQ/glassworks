#!/usr/bin/env python3
# vendor-files.py: which proprietary files a Mirror system image needs, and which of them adb's
# shell can read off a running Mirror (option C, #334).
#
# OFF-DEVICE AND READ-ONLY. It reads a raw (unsparsed) ext4 system image with e2fsprogs' debugfs,
# copies its ELF folders into a temporary folder to run llvm-readelf on them, and deletes that
# folder at the end. It never touches a device. Its output holds paths, sizes, SHA-256 and SELinux
# labels only, never file contents (hard rule 3).
#
# Inputs:
#   --raw      the system image, unsparsed (Glass Panes system.img, see image-patch.md)
#   --vendor   one path per line, as seen from /system, of the files the vendor tree supplies
#              (e.g. the names under prebuilt_HY22/target/product/msm8916_64/system/ in the
#              re-hosted LA.BR.1.2.7 tree, listed with the GitHub tree API: names only)
#   --rules    optional: the unit's SELinux policy as text (`magiskpolicy --print-rules`, mirror-root.md
#              section 3), to say which files the shell domain may read
#
# Output: a TSV on stdout, one row per file the image needs, sorted by part:
#   part  path  bytes  sha256  label  shell  why
#
# Needs: debugfs (brew install e2fsprogs), llvm-readelf (brew install llvm). Python 3.9+.
#
#   python3 -I tools/mirror/vendor-files.py --raw system.raw --vendor hy22.txt --rules sepolicy-rules.txt
import argparse
import collections
import hashlib
import os
import re
import shutil
import subprocess
import sys
import tempfile

DEBUGFS = shutil.which('debugfs') or '/opt/homebrew/opt/e2fsprogs/sbin/debugfs'
READELF = shutil.which('llvm-readelf') or '/opt/homebrew/opt/llvm/bin/llvm-readelf'
ELF_DIRS = ['/lib', '/lib64', '/vendor', '/bin', '/xbin']


def both(names, sub=''):
    return [f'/vendor/lib/{sub}{n}' for n in names] + [f'/vendor/lib64/{sub}{n}' for n in names]


# Where each part of the Mirror starts. The closure over DT_NEEDED adds what these load.
# Read from the image (DT_NEEDED and the .so names open code dlopens) on 2026-11-18; which daemons
# init actually starts on a Mirror is in the panel plan (init.svc.*), so the daemon roots are a
# first cut, not a measured set.
ROOTS = collections.OrderedDict([
    ('gpu-display', {
        'files': both(['libEGL_adreno.so', 'libGLESv1_CM_adreno.so', 'libGLESv2_adreno.so',
                       'eglsubAndroid.so', 'libq3dtools_adreno.so'], 'egl/')
        + both(['libadreno_utils.so', 'libC2D2.so', 'libmm-qdcm.so', 'libscale.so', 'libqc-opt.so',
                'libRSDriver_adreno.so'])
        + ['/bin/mm-pp-daemon'],
        'prefixes': ['/etc/firmware/a3'],
        'why': 'Adreno GLES (hwcomposer dlopens libEGL_adreno), libskia links libqc-opt, memalloc dlopens libadreno_utils',
    }),
    ('platform', {
        'files': ['/bin/qseecomd', '/bin/time_daemon', '/vendor/bin/thermal-engine', '/bin/rmt_storage',
                  '/bin/tftp_server', '/bin/pm-service', '/bin/irsc_util', '/bin/qmuxd',
                  '/bin/msm_irqbalance', '/bin/qcom-system-daemon',
                  '/vendor/lib64/hw/keystore.msm8916.so', '/vendor/lib64/hw/gatekeeper.msm8916.so']
        + both(['libQSEEComAPI.so']),
        'prefixes': [],
        'why': 'daemons init starts on a Qualcomm build, and the hardware keystore (keystore.qcom dlopens libQSEEComAPI)',
    }),
    ('wifi-bt', {
        'files': ['/bin/wcnss_service', '/bin/cnss-daemon', '/bin/hci_qcomm_init', '/bin/btnvtool',
                  '/lib/modules/pronto/pronto_wlan.ko']
        + both(['libidl.so', 'libqmi_cci.so', 'libqmiservices.so', 'libqmi.so', 'libqcci_legacy.so',
                'libqmi_client_qmux.so', 'libmdmdetect.so', 'libbtnv.so']),
        'prefixes': [],
        'why': 'wpa_supplicant and wcnss_service link the QMI libraries; pronto_wlan.ko is the Wi-Fi driver (built from source, Dual BSD/GPL)',
    }),
    ('audio', {
        'files': both(['libacdbloader.so', 'libhwdaphal.so', 'libqct_resampler.so', 'libsrsprocessing.so',
                       'libsurround_proc.so']),
        'prefixes': ['/etc/acdbdata'],
        'why': 'audio.primary dlopens libacdbloader (calibration); libaudioflinger links libsrsprocessing',
    }),
    ('video', {
        'files': both(['libHevcSwDecoder.so', 'libavenhancements.so', 'libOmxAacDec.so']),
        'prefixes': ['/etc/firmware/venus'],
        'why': 'Venus firmware is not in /firmware/image on the unit (mirror-root.md), so it comes from system',
    }),
    ('camera', {
        'files': ['/bin/mm-qcamera-daemon']
        + both(['libts_detected_face_hal.so', 'libts_face_beautify_hal.so', 'libthermalclient.so',
                'libqomx_jpegdec.so', 'libqomx_jpegenc.so']),
        'prefixes': ['/etc/firmware/cpp_'],
        'contains': ['mmcamera', 'chromatix'],
        'why': 'only for the camera pane: mm-camera daemon, its modules and tuning',
    }),
])


def debugfs(raw, *cmds):
    if len(cmds) == 1:
        return subprocess.run([DEBUGFS, '-R', cmds[0], raw], capture_output=True, check=True).stdout
    with tempfile.NamedTemporaryFile('w', suffix='.cmds', delete=False) as f:
        f.write('\n'.join(cmds) + '\n')
    try:
        return subprocess.run([DEBUGFS, '-f', f.name, raw], capture_output=True, check=True).stdout
    finally:
        os.unlink(f.name)


def walk(raw, d='/'):
    """(path, kind, mode, size) for everything under d, through `debugfs ls -p`."""
    for line in debugfs(raw, f'ls -p {d}').decode().splitlines():
        p = line.strip().split('/')[1:]
        if len(p) < 6 or p[4] in ('.', '..'):
            continue
        mode = int(p[1], 8)
        path = d.rstrip('/') + '/' + p[4]
        kind = {0o040000: 'd', 0o120000: 'l'}.get(mode & 0o170000, 'f')
        yield path, kind, mode & 0o7777, int(p[5] or 0)
        if kind == 'd':
            yield from walk(raw, path)


def labels(raw, paths):
    out, cur = {}, None
    for line in debugfs(raw, *[f'ea_list {p}' for p in paths]).decode(errors='replace').splitlines():
        m = re.match(r'debugfs: ea_list (\S+)', line)
        if m:
            cur = m.group(1)
            continue
        m = re.search(r'security\.selinux \(\d+\) = "u:object_r:([^:]+):s0', line)
        if m and cur:
            out[cur] = m.group(1)
    return out


def shell_readable_types(rules_path):
    """Types the shell domain may open and read as files, from a magiskpolicy rule dump."""
    attrs = collections.defaultdict(set)   # type -> its attributes
    allows = []
    for line in open(rules_path):
        m = re.match(r'type (\S+) \{([^}]*)\}', line)
        if m:
            attrs[m.group(1)] = set(m.group(2).split())
            continue
        m = re.match(r'allow (\S+) (\S+) file \{([^}]*)\}', line)
        if m and {'read', 'open'} <= set(m.group(3).split()):
            allows.append((m.group(1), m.group(2)))
    subjects = {'shell'} | attrs['shell']
    return lambda t: any(s in subjects and (o == t or o in attrs[t]) for s, o in allows)


def main():
    a = argparse.ArgumentParser(description=__doc__)
    a.add_argument('--raw', required=True)
    a.add_argument('--vendor', required=True)
    a.add_argument('--rules')
    args = a.parse_args()

    vendor = {l.strip() for l in open(args.vendor) if l.strip()}
    tree = [(p, k, m, s) for p, k, m, s in walk(args.raw)]
    files = {p: (m, s) for p, k, m, s in tree if k == 'f'}
    vendor &= set(files)

    tmp = tempfile.mkdtemp(prefix='vendor-files-')
    try:
        for d in ELF_DIRS:
            debugfs(args.raw, f'rdump {d} {tmp}')
        elf = {}
        for p in files:
            local = tmp + p
            if not any(p.startswith(d + '/') for d in ELF_DIRS) or not os.path.isfile(local):
                continue
            with open(local, 'rb') as f:
                if f.read(4) != b'\x7fELF':
                    continue
            out = subprocess.run([READELF, '--needed-libs', '-h', local], capture_output=True, text=True).stdout
            need = re.findall(r'^\s+(\S+\.so)\s*$', out.split('NeededLibraries')[-1], re.M) if 'NeededLibraries' in out else []
            elf[p] = (64 if 'ELF64' in out else 32, need)

        def resolve(name, cls):
            for d in (['/lib64', '/vendor/lib64'] if cls == 64 else ['/lib', '/vendor/lib']):
                if d + '/' + name in elf:
                    return d + '/' + name
            return None

        def closure(roots):
            seen, stack = set(), [r for r in roots if r in files]
            while stack:
                x = stack.pop()
                if x in seen:
                    continue
                seen.add(x)
                cls, need = elf.get(x, (0, []))
                for n in need:
                    r = resolve(n, cls)
                    if r and r in vendor:
                        stack.append(r)
            return seen

        rows, taken = [], set()
        for part, r in ROOTS.items():
            roots = list(r['files']) + [p for p in vendor if any(p.startswith(x) for x in r['prefixes'])]
            roots += [p for p in vendor if any(c in p for c in r.get('contains', []))]
            for p in sorted(closure(roots) - taken):
                taken.add(p)
                rows.append((part, p, r['why'] if p in r['files'] else 'loaded by the part above'))

        lab = labels(args.raw, [p for _, p, _ in rows])
        readable = shell_readable_types(args.rules) if args.rules else None
        print('part\tpath\tbytes\tsha256\tlabel\tshell\twhy')
        for part, p, why in rows:
            data = open(tmp + p, 'rb').read() if os.path.isfile(tmp + p) else debugfs(args.raw, f'cat {p}')
            mode, size = files[p]
            label = lab.get(p, '?')
            world = bool(mode & 0o004)
            shell = '?' if readable is None else ('yes' if world and readable(label) else 'no')
            src = 'vendor' if p in vendor else 'built'
            print(f'{part}\t/system{p}\t{size}\t{hashlib.sha256(data).hexdigest()}\t{label}\t{shell}\t{src}: {why}')
        print(f'# {len(rows)} files, {sum(files[p][1] for _, p, _ in rows)} bytes; '
              f'the vendor list matched {len(vendor)} files in the image', file=sys.stderr)
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


if __name__ == '__main__':
    main()
