#!/usr/bin/env python3
"""A stand-in for `adb` that answers like an Android 10 panel, for test_runner.py. FAKE_ANDROID6: like the
lululemon Mirror's Android 6 instead (checked against an API 23 emulator, 2026-11-18): logcat has no
`-v monotonic`, only the wall clock to the µs (`-v usec`); `ps` ignores `-A -o` and prints its own columns; there
is no `nproc`; SurfaceFlinger names the app's layer plain `SurfaceView`; `dumpsys meminfo` writes "kB"; and the
kiosk is the generic app, whose activity is dev.glassworks.MainActivity behind a launcher alias. FAKE_SF_WRAPPED:
SurfaceFlinger lists layers as Android 17 does (`RequestedLayerState{…}`, the view's hash); FAKE_SF_UNNAMED: in a way the
runner can't read; FAKE_SF_HISTORY: how many frames `--latency` keeps (127, the S1's); FAKE_SF_SLOW_LIST: seconds
`--list` takes. FAKE_VITRO=true: the kiosk's Vitro setting (Settings → Labs) starts on, as the S1 kiosk with Vitro
draws: Vitro's start line instead of GeckoView's pages, the native harness's lines (VitroClarity: CLOCK_MONOTONIC page
clock; no camera line, as a build whose cameras don't report to Clarity), the frames on the app's window layer; `--ez
vitro` switches it and restarts the app. FAKE_VITRO_CAMERAS: Vitro's cameras report (ClarityCameras), and its camera
lines say they played over `web_rtc` (HLS on the fallback copies), or fell back to `mjpeg`.
FAKE_REVERSE_FAILS: `adb reverse` fails as on the lululemon Mirror's Android 6 over network adb ("more than one
device/emulator"); FAKE_TEST_DASH_LOOPBACK_ONLY: a kiosk that takes a test dashboard on loopback only, as before it took
one on the local network. FAKE_NO_ENGINE_LINE: a kiosk that doesn't say its engine with the test hold. FAKE_VITRO_STUCK: one that saves the
setting but keeps drawing with GeckoView. FAKE_VITRO_NO_RESTORE: one that won't turn Vitro off again.

State lives in $FAKE_ADB_STATE (a directory): the nonce of the page the runner opened, the taps it
sent, and the time ranges in which the fake screen paints. logcat, SurfaceFlinger and the fake page
all use this machine's monotonic clock, as the real ones share the device's: Clarity's clock
(clock.py), so GW_PERF_TIME_SCALE compresses the fake's waits as it does the runner's.
"""
import json
import os
import re
import sys
import threading
import time

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import clock as C  # noqa: E402
import perflib as P  # noqa: E402

STATE = os.environ["FAKE_ADB_STATE"]
PKG = "sh.edm.s1kiosk"
PERIOD_NS = 16_666_666
ANDROID6 = bool(os.environ.get("FAKE_ANDROID6"))
ACTIVITY = "dev.glassworks.MainActivity" if ANDROID6 else ".MainActivity"
# The frames `--latency` keeps: the S1's 127, or FAKE_SF_HISTORY (the Pixel Tablet's Android 17 keeps about 59, #643).
SF_HISTORY = int(os.environ.get("FAKE_SF_HISTORY") or 127)
# FAKE_SF_WRAPPED's SurfaceView: the view's hash, and the BLAST layer's whole name, the only one --latency takes.
SF_HASH, SF_ACT = "f5fea55", f"{PKG}/{PKG}.MainActivity"
SF_BLAST = f"{SF_HASH} SurfaceView[{SF_ACT}](BLAST)#163"
# And the window's: its container (the window's hash in front, no buffers) and the ViewRootImpl's BLAST layer, which
# holds the window's frames, Vitro's (the Pixel Tablet's Android 17, 2026-11-26).
SF_WIN_HASH, SF_VRI = "ac7b2d9", f"VRI-{SF_ACT}#158"
# The fake Android 6's wall clock: its monotonic clock plus this (logcat -v usec prints it).
WALL_SHIFT = 1_795_000_000.0
PIDS = {PKG: 4242, PKG + ":gpu_disable_art_image_": 4300, PKG + ":sandboxed_tab_disable_art_image_24": 4301, "otbr-agent": 900}


def state(name, default=None):
    try:
        with open(os.path.join(STATE, name)) as f:
            return json.load(f)
    except (OSError, ValueError):
        return default


def save(name, value):
    """Atomically: written aside, then renamed over the old file. Many fake adb processes read and
    write these at once, and a reader that met a file mid-write (truncated, not yet written) read
    nothing: the fake screen then took the last wake for 1970 and slept (#251's CI flake)."""
    path = os.path.join(STATE, name)
    tmp = f"{path}.{os.getpid()}.{threading.get_ident()}.tmp"
    with open(tmp, "w") as f:
        json.dump(value, f)
    os.replace(tmp, path)


# When each reference view's first screen is drawn after its navigation, in ms: each its own, inside
# the frames paint() puts on screen (0.15 to 0.5 s).
DRAWN_AFTER_MS = {"light": 180, "typical-nocam": 190, "typical": 210, "heavy": 230}


def paint(start, end):
    save("paint.json", state("paint.json", []) + [[start, end]])


def now():
    return C.mono()


def stamp(at):
    """A logcat line's time: monotonic seconds, or on FAKE_ANDROID6 the wall clock as `-v usec` writes it."""
    if not ANDROID6:
        return f"{at:10.3f}"
    # FAKE_CLOCK_STEP_S: the wall clock steps by that much when the first view opens (network time).
    w = at + WALL_SHIFT + (float(os.environ["FAKE_CLOCK_STEP_S"]) if state("clock_stepped.json") else 0)
    return time.strftime("%m-%d %H:%M:%S", time.gmtime(w)) + f".{int(w % 1 * 1e6):06d}"


def clock_pairs(cmd):
    """perfrun's Device.wall_clock on Android 6: the monotonic clock read around each log line it writes."""
    nonce = re.search(r'ClarityClock \"?(\w+)', cmd).group(1)
    out = []
    for i in range(1, int(re.search(r"seq 1 (\d+)", cmd).group(1)) + 1):
        m1 = now()
        save("clock_marks.json", state("clock_marks.json", []) + [[now(), f"{nonce} {i}"]])
        m2 = now()
        out.append(f"CLOCKPAIR {i} now at {int(m1 * 1e9)} nsecs now at {int(m2 * 1e9)} nsecs")
    return "\n".join(out) + "\n" + time.strftime("%Y", time.gmtime(now() + WALL_SHIFT)) + "\n"


WALL_MINUS_MONO = []


def wall_minus_mono():
    """C.now() - now(), constant in a process: a wall-clock read between two monotonic ones, read
    again while a pause between them makes the pair too far apart."""
    while not WALL_MINUS_MONO:
        m1, w, m2 = now(), C.now(), now()
        if m2 - m1 < 0.005:
            WALL_MINUS_MONO.append(w - (m1 + m2) / 2)
    return WALL_MINUS_MONO[0]


def vitro():
    """The kiosk's Vitro setting, which the running app draws with (`--ez vitro` restarts it): FAKE_VITRO at first."""
    return state("settings.json", {}).get("vitro", os.environ.get("FAKE_VITRO", "false")) == "true"


def held():
    return (state("test_hold.json") or 0) > C.now()


def test_hold(cmd):
    """The kiosk's test hold (#240): the screen on until it ends or expires, through a restart of the
    app too, logged as the kiosk logs it. FAKE_NO_TEST_HOLD: a kiosk before #240, which ignores it.
    FAKE_HOLD_DIES: as #332's first version, whose hold died with the process."""
    if os.environ.get("FAKE_NO_TEST_HOLD"):
        return
    was = held()
    if "--ez test_hold true" in cmd:
        s = int(re.search(r"--ei test_hold_s (\d+)", cmd).group(1))
        save("test_hold.json", C.now() + s)
        save("awake_at.json", C.now())       # it wakes the screen
        save("asleep.json", False)
        said = f"test hold {'renewed' if was else 'on'} for {s} s"
        if not os.environ.get("FAKE_NO_ENGINE_LINE"):
            # MainActivity, right after the hold: the engine that draws and the Vitro setting (Clarity's engine check).
            # FAKE_VITRO_STUCK: the setting saves, but the app keeps drawing with GeckoView (a switch that fails).
            name = "Vitro 0.0.1 (experimental)" if vitro() and not os.environ.get("FAKE_VITRO_STUCK") else "GeckoView 159.0"
            save("hold_log.json", state("hold_log.json", []) + [[now(), said],
                                                                 [now(), f"test hold engine: {name}; vitro setting {'on' if vitro() else 'off'}"]])
            return
    elif was:
        save("test_hold.json", None)
        save("awake_at.json", C.now())       # the idle timeout counts from the end
        save("kiosk_awake_at.json", C.now())
        said = "test hold off (ended)"
        if state("test_dash.json"):          # the test dashboard ends with the hold
            save("test_dash.json", None)
            save("hold_log.json", state("hold_log.json", []) + [[now(), said], [now(), "test dashboard off (ended)"]])
            return
    else:
        return
    save("hold_log.json", state("hold_log.json", []) + [[now(), said]])


def test_dashboard(value):
    """The kiosk's test dashboard (ScreenPolicy.testDashboard): a loopback page shown instead of the saved dashboard
    while the hold lasts, through restarts, logged as the kiosk logs it. "" ends it. FAKE_NO_TEST_DASHBOARD: a
    kiosk without it, which ignores the extra."""
    if os.environ.get("FAKE_NO_TEST_DASHBOARD"):
        return
    save("test_dashes_set.json", state("test_dashes_set.json", []) + [value])
    if value and not test_dashboard_ok(value):
        said = "test dashboard refused: only http on 127.0.0.1, localhost or a local network address"
    elif value and not held():
        said = "test dashboard refused: no test hold"
    elif value:
        again = state("test_dash.json") == value
        save("test_dash.json", value)
        said = "test dashboard on: " + re.match(r"(\w+://[^/]+)", value).group(1) + (" (already)" if again else "")
    elif state("test_dash.json"):
        save("test_dash.json", None)
        said = "test dashboard off (cleared)"
    else:
        return
    save("hold_log.json", state("hold_log.json", []) + [[now(), said]])


def test_dashboard_ok(url):
    """As the kiosk's TestHoldFile.isTestDashboard: http(s) on loopback, or on an IP address of the local network (a
    test whose adb reverse doesn't work, run.sh --ha-transport lan), by the kiosk's own rule (perflib.local_address).
    FAKE_TEST_DASH_LOOPBACK_ONLY: loopback only, as before."""
    import urllib.parse
    u = urllib.parse.urlsplit(url)
    if u.scheme not in ("http", "https") or " " in url:
        return False
    if u.hostname in ("127.0.0.1", "localhost", "::1"):
        return True
    return not os.environ.get("FAKE_TEST_DASH_LOOPBACK_ONLY") and P.local_address(u.hostname or "")


def shell(cmd):
    td = re.search(r"--es test_dashboard '([^']*)'", cmd) if cmd.startswith("am start") else None
    # FAKE_REF_URL_FAILS: a per-state --pane-ram intent (the test dashboard with pane extras) isn't taken.
    if td and (os.environ.get("FAKE_RESTORE_FAILS") and not td.group(1)
               or os.environ.get("FAKE_REF_URL_FAILS") and td.group(1) and re.search(r"--ez (?!test_hold )", cmd)):
        # The kiosk didn't take it (am exits 0 all the same): nothing changes.
        save("restore_refused.json", state("restore_refused.json", 0) + 1)
        return f"Starting: Intent {{ cmp={PKG}/{ACTIVITY} }}\nError: Activity not started, unable to resolve Intent\n"
    if cmd.startswith("am start") and ("--ez test_hold " in cmd or td):
        if "--ez test_hold " in cmd:
            test_hold(cmd)
        if td:
            test_dashboard(td.group(1))
        if not re.search(r"--ez (?!test_hold )", cmd):
            return "Starting: Intent\nStatus: ok\nComplete\n" if " -W " in cmd else "Starting: Intent\n"
    if os.environ.get("FAKE_SCREEN_SLEEP_S") and ("KEYCODE_WAKEUP" in cmd
                                                   or cmd.startswith(("input swipe", "input tap"))):
        save("awake_at.json", C.now())       # what keeps the fake screen on (a wake, a touch)
        if "KEYCODE_WAKEUP" not in cmd or state("asleep.json"):
            # The kiosk's own timer counts touches, and a screen that comes back on; the wake key
            # alone doesn't reset it (the S1's night run, 2026-11-16).
            save("kiosk_awake_at.json", C.now())
        save("asleep.json", False)
        if "KEYCODE_WAKEUP" in cmd:
            save("nudges.json", state("nudges.json", 0) + 1)
            return ""
    props = {"ro.product.manufacturer": "Example", "ro.product.model": "Test Panel", "ro.build.version.release": "10",
             "ro.build.version.sdk": "29", "ro.product.cpu.abi": "armeabi-v7a", "ro.product.cpu.abilist": "armeabi-v7a,armeabi",
             "ro.board.platform": "testsoc", "ro.hardware": "testhw"}
    if cmd.startswith("getprop "):
        # FAKE_REAL_DEVICE: answers as a real device does, which has no such property.
        if cmd.split()[1] == C.FAKE_PROP and not os.environ.get("FAKE_REAL_DEVICE"):
            return "1"
        return props.get(cmd.split()[1], "")
    m = re.search(r"echo (\w+);? .*toybox nc( -u)? .*?(\d+\.\d+\.\d+\.\d+) (\d+)$", cmd)
    if m:
        # lanprobe.py's checks (#202): the panel reaches this computer over the LAN as FAKE_LAN says
        # (udp: both; tcp: TCP only; unset: neither), and then really sends the token there.
        token, udp, addr, port = m.group(1), bool(m.group(2)), m.group(3), int(m.group(4))
        save("lan_checks.json", state("lan_checks.json", []) + ["udp" if udp else "tcp"])
        lan = os.environ.get("FAKE_LAN", "")
        if (udp and lan == "udp") or (not udp and lan in ("udp", "tcp")):
            import socket
            if udp:
                with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
                    s.sendto((token + "\n").encode(), (addr, port))
            else:
                with socket.create_connection((addr, port), timeout=2) as s:
                    s.sendall((token + "\n").encode())
        return ""
    if cmd.startswith("am start"):
        u = re.search(r"--es url '([^']+)'", cmd)
        if u:
            # Like the kiosk: `url` saves the dashboard (a URL or "auto") and loads it.
            save("saved_url.json", u.group(1))
            save("urls_set.json", state("urls_set.json", []) + [u.group(1)])
            signed_in = os.environ.get("FAKE_SIGNED_IN")
            if signed_in and not u.group(1).startswith(signed_in):
                # #496: the sign-in is for another address now; the kiosk asks to sign in again.
                save("signin_moved.json", True)
        ez = [e for e in re.findall(r"--ez (\w+) (true|false)", cmd) if e[0] != "test_hold"]
        if ez:
            # Like the kiosk: each `--ez` saves a setting (voice_enabled, sendspin_enabled, restart).
            # FAKE_VOICE_LOST: as the S1 app 0.4.3, which saves voice_enabled asynchronously and exits
            # to restart at once, so the write is lost.
            save("intents.json", state("intents.json", []) + [dict(ez)])
            if ("restart", "true") in ez and os.environ.get("FAKE_HOLD_DIES"):
                save("test_hold.json", None)
            keep = {k: v for k, v in ez if k != "restart" and not (k == "voice_enabled" and os.environ.get("FAKE_VOICE_LOST"))
                    and not (k == "vitro" and v == "false" and os.environ.get("FAKE_VITRO_NO_RESTORE"))}
            save("settings.json", dict(state("settings.json", {}), **keep))
            if any(k == "vitro" for k, _ in ez):
                # MainActivity restarts the process for it (logged when a logcat follows): the new one draws with it.
                save("cold.json", True)
        m = re.search(r"--es open '([^']+)'", cmd)
        check = state("sfcheck_paint.json")
        if check and check[1] > now() and not (m and "/sfcheck.html" in m.group(1)):
            save("sfcheck_paint.json", [check[0], now()])     # the next intent replaces the check's page: it stops
        # Like the S1 kiosk (#103's pins): `open` is dropped until GeckoView's session is up.
        if m and os.environ.get("FAKE_LOCKDOWN") and "127.0.0.1" in m.group(1):
            # The legacy engine's lockdown (#198): only the dashboard's origin loads.
            save("lockdown.json", True)
        elif m and state("session_up.json", True) and "/sfcheck.html" in m.group(1):
            # The SurfaceFlinger check's page draws every frame (#643), as a real one does: for 20 s, or until the next
            # intent replaces it (above), however long a busy test machine takes to read it.
            save("sfcheck_paint.json", [now(), now() + 20])
        elif m and state("session_up.json", True):
            nonce = re.search(r"nonce=(\w+)", m.group(1))
            if nonce:
                save("refnonce.json" if "/clarity-" in m.group(1) else "nonce.json", [nonce.group(1), m.group(1)])
            else:
                save("opened_still.json", state("opened_still.json", []) + [m.group(1)])   # an idle sample's page
        if " -W " in cmd:
            # A launch (the cold start); an intent with a setting goes to the running kiosk instead.
            if not os.environ.get("FAKE_NO_DASHBOARD") and not u and not ez:
                save("cold.json", True)
            return f"Starting: Intent\nStatus: ok\nActivity: {PKG}/{ACTIVITY}\nTotalTime: 1700\nWaitTime: 1710\nComplete\n"
        return "Starting: Intent\n"
    if cmd.startswith("logcat -d") and ANDROID6 and "-v monotonic" in cmd:
        return "Invalid parameter monotonic to -v\nUsage: logcat [options] [filterspecs]\n"
    if cmd.startswith("logcat -d") and "-s ClarityClock" in cmd:
        return "".join(f"{stamp(at)}  5151  5151 I ClarityClock: {msg}\n" for at, msg in state("clock_marks.json", []))
    if "/proc/timer_list" in cmd and "ClarityClock" in cmd:
        return clock_pairs(cmd) if ANDROID6 else ""
    if cmd == "dumpsys activity recents":
        if os.environ.get("FAKE_NO_TASK"):
            return "ACTIVITY MANAGER RECENT TASKS (dumpsys activity recents)\n"
        real = f"{PKG}/{ACTIVITY}"
        return ("ACTIVITY MANAGER RECENT TASKS (dumpsys activity recents)\n  Recent tasks:\n  * Recent #0: TaskRecord{91b2af #4 A=x}\n"
                f"    realActivity={real}\n  * Recent #1: TaskRecord{{2a #3 A=y}}\n    realActivity=com.android.launcher3/.Launcher\n")
    if cmd.startswith("logcat -d"):
        # The kiosk's start lines for the panes it runs now (perflib.pane_state reads them).
        s = state("settings.json", {})
        voice = s.get("voice_enabled", "true") == "true"
        t = now()
        out = [f"{stamp(t - 5)}  31236  31237 I Glassworks.ESPHome: ESPHome device 'aqara-s1-plus-000000' mac 00:00:00:00:00:00, voice={'true' if voice else 'false'}"]
        if s.get("sendspin_enabled", "true") == "true":
            out.append(f"{stamp(t - 4.9)}  31236  31237 I Glassworks.Sendspin: Sendspin player starting (client id test)")
        for pane in ("wall-base", "time-zone", "diagnostics"):   # #67: the kiosk says what each switch is
            on = s.get(pane.replace("-", "_") + "_enabled", "true") == "true"
            out.append(f"{stamp(t - 4.8)}  31236  31237 I Glassworks.ESPHome: Pane {pane} {'on' if on else 'off'}")
        if voice and s.get("mic_muted", "false") != "true":
            out.append(f"{stamp(t - 3)}  31236  31237 I Glassworks.Voice: Microphone open: source=6 buffer=20480")
        if os.environ.get("FAKE_IDLE_SCREEN_OFF") and " -t 1" not in cmd:
            # One before the sample's window (an earlier night, not this sample's) and one in it.
            out.insert(0, f"{stamp(t - 600)}   1919   1920 I PowerManagerService: Going to sleep due to timeout (uid 1000)...")
            out.append(f"{stamp(t - 0.5)}   1919   1920 I PowerManagerService: Going to sleep due to device_admin (uid 1000)...")
        out += [f"{stamp(at)}  31236  31236 I Glassworks.Screen: {said}" for at, said in state("hold_log.json", [])]
        if " -t 1" in cmd:
            out = [f"{stamp(t - 2)}      1      1 I old: the last line"]
        return "\n".join(out) + "\n"
    if cmd.startswith(("input swipe", "input tap")):
        # Like a page: a gesture nobody asked for (no current *-ready left to answer) lands on whatever
        # is on screen. Record it, and don't count it as the page's.
        kind = "swipe" if cmd.startswith("input swipe") else "tap"
        asked = state("asked.json", {})
        if asked.get(kind, 0) < 1:
            save("unasked.json", state("unasked.json", []) + [cmd])
            return ""
        asked[kind] -= 1
        save("asked.json", asked)
        save(f"{kind}s.json", state(f"{kind}s.json", 0) + 1)
        return ""
    if cmd == "ip -4 -o addr show":
        return ("1: lo    inet 127.0.0.1/8 scope host lo\\       valid_lft forever preferred_lft forever\n"
                "3: wlan0    inet 192.0.2.10/24 brd 192.0.2.255 scope global wlan0\\       valid_lft forever preferred_lft forever\n")
    if cmd.startswith("dumpsys SurfaceFlinger --list") and ANDROID6:
        return f"DimLayer\ncom.android.systemui.ImageWallpaper\nSurfaceView\n{PKG}/{ACTIVITY}\nStatusBar\nNavigationBar\n"
    if cmd.startswith("dumpsys SurfaceFlinger --list") and os.environ.get("FAKE_SF_SLOW_LIST"):
        time.sleep(float(os.environ["FAKE_SF_SLOW_LIST"]))    # a listing that takes this long (real seconds, #643)
    if cmd.startswith("dumpsys SurfaceFlinger --list") and os.environ.get("FAKE_SF_WRAPPED"):
        # Android 17 (as an API 37 emulator listed it, 2026-11-19): each layer in RequestedLayerState{…}, the
        # SurfaceView's name after the view's hash; the frames on its (BLAST) child, none on its parent.
        return (f"RequestedLayerState{{Display 0 name=\"Built-in Screen\"#41}}\n"
                f"RequestedLayerState{{Background for {SF_HASH} SurfaceView[{SF_ACT}]#164 parentId=162 relativeParentId=158 z=-2147483648}}\n"
                f"RequestedLayerState{{{SF_BLAST} parentId=162}}\n"
                f"RequestedLayerState{{ActivityRecord{{104391098 u0 {SF_ACT} t10}}#147 parentId=146}}\n"
                f"RequestedLayerState{{{SF_WIN_HASH} {SF_ACT}#155 parentId=147}}\n"
                f"RequestedLayerState{{{SF_VRI} parentId=155}}\n"
                f"RequestedLayerState{{{SF_HASH} SurfaceView[{SF_ACT}]#162 parentId=161 relativeParentId=158 z=-2}}\n")
    if cmd.startswith("dumpsys SurfaceFlinger --list") and os.environ.get("FAKE_SF_UNNAMED"):
        # A naming this runner can't read: the app's window is listed, its SurfaceView isn't.
        return f"Display Root#0\nSomeNewLayer<{PKG}/{PKG}.MainActivity>#7\n{PKG}/{PKG}.MainActivity#3\n"
    if cmd.startswith("dumpsys SurfaceFlinger --list") and vitro():
        # Vitro draws into the app's own window; the SurfaceView is its GeckoView fallback's (the check page's).
        return (f"Display Root#0\nSurfaceView - {PKG}/{PKG}.MainActivity#0\n{PKG}/{PKG}.MainActivity#0\n"
                f"Background for -SurfaceView - {PKG}/{PKG}.MainActivity#0\n")
    if cmd.startswith("dumpsys SurfaceFlinger --list"):
        return f"Display Root#0\nSurfaceView - {PKG}/{PKG}.MainActivity#0\nBackground for -SurfaceView - {PKG}/{PKG}.MainActivity#0\n"
    if cmd.startswith("dumpsys SurfaceFlinger --latency") and os.environ.get("FAKE_SF_WRAPPED"):
        name = re.search(r"--latency '(.*)'", cmd).group(1)
        if name in (f"{SF_HASH} SurfaceView[{SF_ACT}]#162", f"{SF_WIN_HASH} {SF_ACT}#155"):
            return f"{PERIOD_NS}\n0\t0\t0\n"          # a parent: no buffers of its own
        # The SurfaceView's BLAST layer holds GeckoView's frames (the web engine's, and with Vitro on, its fallback's:
        # sfcheck's page), the VRI layer Vitro's own (Greptile on #949: with Vitro on, the check read nothing).
        if name not in ((SF_VRI, SF_BLAST) if vitro() else (SF_BLAST,)):
            return f"{PERIOD_NS}\n"                   # any other form of the name: the period alone
    if cmd.startswith("dumpsys SurfaceFlinger --latency"):
        t, frames = int(now() * 1e9), []
        ranges = state("paint.json", []) + ([state("sfcheck_paint.json")] if state("sfcheck_paint.json") else [])
        if vitro():
            # The window has Vitro's frames, the SurfaceView the check page's (GeckoView's fallback): never both.
            name = re.search(r"--latency '(.*)'", cmd).group(1)
            surface = re.sub(r"^[0-9a-f]+ (?=SurfaceView)", "", name).startswith("SurfaceView")  # Android 17's hash
            ranges = ([state("sfcheck_paint.json")] if state("sfcheck_paint.json") else []) if surface \
                else state("paint.json", [])
        for a, b in ranges:
            x = int(a * 1e9)
            while x <= min(int(b * 1e9), t):
                frames.append(x)
                x += PERIOD_NS
        frames = sorted(set(frames))                          # the check's range and the pages' interleave in time
        rows = [f"{f - 1000}\t{f}\t{f - 2000}" for f in frames[-SF_HISTORY:]]
        return "\n".join([str(PERIOD_NS)] + rows + ["0\t0\t0"]) + "\n"
    if cmd.startswith("dumpsys package"):
        return "    versionCode=403 minSdk=26 targetSdk=34\n    versionName=0.4.3\n"
    if cmd.startswith("pm path"):
        return "package:/data/app/sh.edm.s1kiosk-1/base.apk\n"
    if cmd.startswith("stat -c %s"):
        return "123456789\n"
    if cmd == "ps -A -o PID,NAME" and ANDROID6:
        rows = "".join(f"u0_a55    {p:<5} 1203  1234   5678  SyS_epoll_ 0000000000 S {n}\n" for n, p in PIDS.items())
        return "USER      PID   PPID  VSIZE  RSS   WCHAN              PC  NAME\nroot      1     0     4264   1304  SyS_epoll_ 00004acfc4 S /init\n" + rows
    if cmd == "ps -A -o PID,NAME":
        return "  PID NAME\n" + "".join(f"{p} {n}\n" for n, p in PIDS.items()) + "  1 init\n"
    if cmd == "dumpsys meminfo":
        # A pane turned on with its extra adds to the app's PSS: Assist 30 MB, Sendspin 10 MB, the wall
        # base 3 MB, the time zone 1 MB, diagnostics 2 MB.
        on = state("settings.json", {})
        # Assist costs its 30 MB while its microphone runs (voice on, not muted), as on the S1.
        assist = on.get("voice_enabled") == "true" and on.get("mic_muted", "false") != "true"
        main = 190_000 + (30_000 if assist else 0) + (10_000 if on.get("sendspin_enabled") == "true" else 0) + sum(
            kb for k, kb in (("wall_base_enabled", 3_000), ("time_zone_enabled", 1_000), ("diagnostics_enabled", 2_000)) if on.get(k) == "true")
        rows = {PKG + ":gpu_disable_art_image_": 200_000, PKG: main, PKG + ":sandboxed_tab_disable_art_image_24": 180_000, "otbr-agent": 2_500}
        body = "".join(f"    {v if ANDROID6 else format(v, ',')}{' kB' if ANDROID6 else 'K'}: {n} (pid {PIDS[n]}{' / activities' if n == PKG else ''})\n"
                       for n, v in rows.items())
        return "Total PSS by process:\n" + body + "\nTotal PSS by OOM adjustment:\n"
    if cmd.startswith("dumpsys meminfo "):
        return " App Summary\n                       Pss(KB)\n           Java Heap:     9032\n         Native Heap:    24792\n" \
               "            Graphics:    29916\n               TOTAL:   190000       TOTAL SWAP PSS:       30\n"
    if re.match(r"cat /proc/\d+/status", cmd):
        return "Name:\tx\nVmHWM:\t  300000 kB\n"
    if cmd == "cat /proc/meminfo":
        return "MemTotal:        2023196 kB\nMemFree:          285844 kB\nMemAvailable:     899720 kB\n"
    if cmd.startswith("cat /proc/stat"):
        j = int(now() * 100)
        out = f"cpu  {j} 0 {j} {j * 6} 0 0 0 0 0 0\n"
        for pid in re.findall(r"echo PID (\d+)", cmd):
            out += f"PID {pid}\n{pid} (Gecko Main) S 1 1 0 0 -1 1 2 3 4 5 {j // 10} {j // 20} 1 1 10 -10\n"
        return out
    if cmd == "nproc 2>/dev/null || grep -c '^cpu[0-9]' /proc/stat":
        if os.environ.get("FAKE_IDLE_FAILS"):
            # The device step reads it first; the idle sample's read then fails: a run that stops
            # partway (run.sh's exit trap).
            n = state("nproc_reads.json", 0) + 1
            save("nproc_reads.json", n)
            if n > 1:
                return "garbled\n"
        return "4\n"
    if "thermal_zone" in cmd:
        if "case" in cmd:
            return "45000\n"
        return "cpu_thermal_zone=45000 gpu_thermal_zone=44000\n" if "=" in cmd else "cpu_thermal_zone 45000\ngpu_thermal_zone 44000\n"
    for name, khz in (("scaling_cur_freq", 1200000), ("cpuinfo_max_freq", 1608000)):   # #244: the S1 held at 1.2 GHz
        if f"cpufreq/{name}" in cmd:
            # As mksh lists the glob (as text: cpu10 before cpu2), here with 4 cores.
            return "".join(f"/sys/devices/system/cpu/cpu{i}/cpufreq/{name} {khz}\n" for i in (0, 1, 2, 3))
    if cmd == "cat /proc/loadavg":
        return "0.50 0.60 0.70 1/500 1234\n"
    if cmd == "dumpsys window displays":
        size = "1080x1920" if ANDROID6 else "1440x720"
        return f"WINDOW MANAGER DISPLAY CONTENTS (dumpsys window displays)\n  Display: mDisplayId=0\n    init={size} 240dpi cur={size} app={size}\n"
    if cmd.startswith("wm size"):
        return "Physical size: 1440x720\n"
    if cmd.startswith("wm density"):
        return "Physical density: 240\n"
    if cmd == "dmesg":
        # The kernel's log, read-only: FAKE_LMK=kernel puts the low-memory killer's line for the page's process in it,
        # stamped (seconds since boot, the monotonic clock) when the page went; the name cut to its last 15 characters.
        at = state("lmk_at.json")
        if at is not None and os.environ.get("FAKE_HA_URL"):
            # A slow read (up to 20 s on a panel), while the page the app loaded again asks for a command, as a fresh
            # harness does (no `after`): whatever the fake Home Assistant hands it now, it would run.
            import urllib.request
            try:
                with urllib.request.urlopen(os.environ["FAKE_HA_URL"].rstrip("/") + "/clarity/next?after=", timeout=1) as r:
                    cmd_ = json.loads(r.read()) if r.status == 200 else None
            except (OSError, ValueError):
                cmd_ = None
            mark(f"dmesg: the page back got {cmd_['view'] if cmd_ else 'no command'}")
        out = "[    0.000000] Booting Linux on physical CPU 0x0\n"
        if at is not None and os.environ.get("FAKE_LMK") == "kernel":
            name = child_proc()[-15:]
            out += (f"[{at - float(os.environ.get('FAKE_LMK_AGO_S', '0.1')):12.6f}] lowmemorykiller: Killing '{name}' (4301), "
                    "adj 0,\n   to free 172032kB on behalf of 'kswapd0' (77) because\n")
        return out
    if cmd.startswith("am force-stop"):
        if os.environ.get("FAKE_HOLD_DIES"):
            save("test_hold.json", None)
        save("stopped.json", True)
        save("session_up.json", False)
        if os.environ.get("FAKE_HOME_RELAUNCH"):
            save("cold.json", True)
        return ""
    return ""


LOCK = threading.Lock()


# As on the S1 (2026-11-12): a page's console line reaches logcat from the content process, tagged
# "Isolated Web Content", and only its first 1023 bytes arrive.
PAGE_TAG, PAGE_LINE_BYTES = "Isolated Web Content", 1023
# The pages' protocol, read from the pages themselves, so the fake can't fall behind them.
with open(os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "page", "tiers.json")) as _f:
    _T = json.load(_f)
    PROTOCOL, CLARITY = _T["PROTOCOL"], _T["CLARITY"]
    DIALOGS = _T["DIALOG"]["entities"]
PAGE_LINE_MAX = 900     # stats.js LINE_MAX: longer lines are split, as the page does


def page_lines(key, text):
    """The page's consoleLines (stats.js), in Python: split on UTF-8 bytes, never inside a character.
    test_runner.FakeMatchesThePanel checks it against the page's own (run in node)."""
    if len(text.encode()) <= PAGE_LINE_MAX:
        return ["GWPERF " + text]
    sizes = [len(c.encode()) for c in text]
    parts, i = [], 0
    while i < len(text):
        j, size = i, 0
        while j < len(text) and size + sizes[j] <= PAGE_LINE_MAX:
            size += sizes[j]
            j += 1
        while j < len(text) and j > i + 1 and (text[j - 1].isspace() or text[j].isspace()):
            j -= 1
        parts.append(text[i:j])
        i = j
    return [f"GWPERF+ {key} {k}/{len(parts)} {p}" for k, p in enumerate(parts)]


SEQ = [0]


def shape_of(obj):
    """A page line's type, as test/page-lines.json keys them."""
    t = obj.get("t")
    staged = t in ("measure", "step", "warmup") and obj.get("stage") in ("updates", "scroll")
    return f"{t}/{obj['stage']}" if staged else t


def page_log(obj, split=True, record=True, at=None):
    if at is None and "epoch_ms" in obj:
        # This line ties the page's clock to logcat's (perflib.page_to_mono_ms), so it is stamped when
        # its Date.now() was read: stamped when printed, a fake 100 ms late on a busy machine moved
        # every change of the updates step out of its window.
        at = obj["epoch_ms"] / 1000 - wall_minus_mono()
    SEQ[0] += 1
    text = json.dumps(obj, separators=(",", ":"))
    if record:
        # The reference page's lines' keys, so test_runner can hold the fake to the panel's shapes
        # (test/page-lines.json).
        with open(os.path.join(STATE, "page-lines.jsonl"), "a") as f:
            f.write(json.dumps({"shape": shape_of(obj), "keys": sorted(obj)}) + "\n")
    lines = page_lines(f"{obj.get('nonce')}:{SEQ[0]}", text) if split else ["GWPERF " + text]
    # Losses, as a busy logcat can lose lines: the last part of a split updates step, or the first
    # scroll step whole.
    if os.environ.get("FAKE_DROP_LAST_PART") and len(lines) > 1 and obj.get("stage") == "updates":
        lines = lines[:-1]
    if os.environ.get("FAKE_DROP_SCROLL_STEP") and obj.get("t") == "step" and obj.get("stage") == "scroll" \
            and obj.get("n") == 0:
        lines = []
    # A page's window, marked in commands.log around its measure and step lines (#643): the tests count the
    # SurfaceFlinger reads inside it.
    if obj.get("t") == "measure":
        mark(f"window open {obj.get('stage')} {obj.get('n')}")
    for l in lines:
        line(PAGE_TAG, l, at=at)
    if obj.get("t") == "step":
        mark(f"window closed {obj.get('stage')} {obj.get('n')}")


def line(tag, msg, pid=4242, at=None):
    """One logcat line, stamped now or `at`."""
    if tag == PAGE_TAG:
        msg = msg.encode()[:PAGE_LINE_BYTES].decode(errors="ignore")
    with LOCK:
        print(f"{stamp(now() if at is None else at)}  {pid}  {pid + 1} I {tag}: {msg}", flush=True)


def noise(every):
    """Unrelated device logging, as on a real panel: logcat follows the whole device."""
    while True:
        line("ha_driven", "enterConnectingState: connect fail, errno:111", pid=2527)
        C.sleep(every)


def page(nonce):
    def gw(obj):
        page_log(dict(obj, run=1, nonce=nonce), split=not os.environ.get("FAKE_NO_SPLIT"), record=False)

    gw({"t": "hello", "ua": "Mozilla/5.0 (Android 10; Mobile; rv:159.0) Gecko/159.0 Firefox/159.0", "load": {"lcp_ms": 420}})
    gw({"t": "js", "runs": 30, "median_ms": 9.5, "p95_ms": 12.0, "checksum": 7, "expected": 7})
    if os.environ.get("FAKE_SYNTH_SCREEN_OFF"):
        # The kiosk's idle timeout turns the screen off while the test page runs.
        line("PowerManagerService", "Going to sleep due to device_admin (uid 1000)...", pid=1919)
    for stage, n in (("css-transform", 1), ("css-transform", 16), ("scroll", 240)):
        gw({"t": "warmup", "stage": stage, "n": n})
        C.sleep(0.05)
        a = now()
        gw({"t": "measure", "stage": stage, "n": n})
        C.sleep(0.6)
        paint(a, now())
        gw({"t": "step", "stage": stage, "n": n, "window_ms": 600, "scrolled_px": 600 if stage == "scroll" else None,
            "raf": {"n": 36, "median_ms": 16.67, "p95_ms": 16.67, "jank_pct": 0, "frozen_pct": 0}})
        C.sleep(0.3)
    gw({"t": "done"})


def refdash(nonce, url):
    """A reference dashboard: it loads (first paint 150 ms, load event 120 ms), stays still, goes live
    (state updates), one window of frames, the taps, then swipes where it asks, done."""
    tier = re.search(r"/clarity-ref/([\w-]+)", url).group(1)
    # Protocol 6: a camera page's HLS copy (tiers.json FALLBACK) plays over HLS; the rest over WebRTC.
    page, path = (tier[:-4], "hls") if tier.endswith("-hls") else (tier, "web_rtc")
    taps = int(re.search(r"taps=(\d+)", url).group(1))
    m = re.search(r"swipes=(\d+)", url)
    swipes = int(m.group(1)) if m else int(os.environ.get("FAKE_SWIPES", "2"))
    save("opened.json", state("opened.json", []) + [tier + ("-warmup" if "warmup=1" in url else "")])
    foreign = os.environ.get("FAKE_FOREIGN_PAGE")
    foreign = foreign if (foreign == "end") == (tier == "heavy") else None
    dpr = float(os.environ.get("FAKE_DPR", "1.5"))

    def gw(obj, at=None):
        # FAKE_STAGE_DELAY (protocol 8): a slow view that keeps going: this long before each line
        # after it is usable, so the whole view outlasts the usable deadline but no stage stops.
        if obj.get("t") not in ("refdash", "drawn") and os.environ.get("FAKE_STAGE_DELAY") and tier == os.environ.get("FAKE_STAGE_VIEW", tier):
            C.sleep(float(os.environ["FAKE_STAGE_DELAY"]))
        page_log(dict(obj, nonce=nonce, run=1), split=not os.environ.get("FAKE_NO_SPLIT"), at=at)

    # FAKE_DIALOG_HELD=<view> (#410): HA still held the view before's more-info dialogs at this view's
    # check, before its navigation; the page isn't measured on them and says so (harness.js openView).
    if os.environ.get("FAKE_DIALOG_HELD") == tier:
        C.sleep(0.5)
        gw({"t": "error", "message": "Error: HA wasn't done with the view before's more-info dialogs after 10 s "
                                     "(climate.clarity_0, sensor.clarity_10 not shown yet); this view isn't measured on them"})
        return
    # FAKE_SLOW_VIEW: the view takes this long to be usable (the runner keeps the screen awake).
    C.sleep(float(os.environ.get("FAKE_SLOW_VIEW", "0")))
    if os.environ.get("FAKE_NEVER_USABLE") == tier:
        return                 # never usable: not a line
    if os.environ.get("FAKE_SCREEN_OFF") == tier:
        # As smoke10: the kiosk's idle timeout turns the screen off while the view opens.
        line("PowerManagerService", "Going to sleep due to device_admin (uid 1000)...", pid=1919)
    if foreign == "before":
        line("GeckoSession", "handleMessage GeckoView:PageStart uri=http://192.0.2.10:8123/lovelace?external_auth=1")
    # Protocol 4: the frontend stays loaded; the view opens by navigation, so no page load is logged.
    clk, tie = page_clock()
    origin = clk() - 5000
    nav = clk() - origin
    a = now()
    paint(a + 0.15, a + 0.5)
    save("asked.json", {})
    stale = state("last_nonce.json")
    if os.environ.get("FAKE_STALE_READY") and stale:
        # The last page's requests, replayed late into this one (its console lines can arrive after
        # the next page opened). The runner must answer only the current page's nonce.
        for t in ("swipe-ready", "input-ready"):
            line(PAGE_TAG, "GWPERF " + json.dumps({"t": t, "nonce": stale, "run": 1, "n": 0, "x": 720, "y": 360,
                                                          "y1": 612, "y2": 108, "ms": 300, "taps": 2}))
    save("last_nonce.json", nonce)
    if foreign == "after":
        line("GeckoSession", "handleMessage GeckoView:PageStart uri=http://192.0.2.10:8123/lovelace?external_auth=1")
    C.sleep(0.5)
    # Clarity v2: the draw on its own line first, then refdash once free. Each view draws at its own
    # time after the navigation, so a draw stored on the wrong view shows (#264's delta verifier).
    drawn_after = DRAWN_AFTER_MS.get(page, 200)
    gw(dict({"t": "drawn", "tier": tier, "protocol": PROTOCOL, "clarity": CLARITY, "nav_ms": round(nav, 1),
             "usable": {"rendered_tick_ms": round(nav + drawn_after, 1), "first_screen_cards": 14, "head_cards": 10}},
            **tie(origin)))
    if os.environ.get("FAKE_NEVER_FREE") == tier:
        return                 # drawn, but the main thread never free: no refdash
    C.sleep(0.2)
    gw(dict({"t": "refdash", "tier": tier, "protocol": PROTOCOL, "clarity": CLARITY, "frontend": None if vitro() else "20261028.5",
        "nodes": 1458, "cards": 50, "cards_rendered": 47, "css": [960, 480], "dpr": dpr, "scroll_height": 2635,
        "fcp_ms": None, "lcp_ms": None, "load_ms": None,
        # Protocol 4: navigation at `nav`; the view's first screen rendered DRAWN_AFTER_MS later, the
        # main thread free 20 ms after that.
        "nav_ms": round(nav, 1), "video_caps": {"mse_h264": True, "native_hls": "", "rvfc": True},
        "dialogs_before": None,                    # #410: HA was done with the view before's dialogs at once
        "usable": {"rendered_tick_ms": round(nav + drawn_after, 1), "first_screen_cards": 14, "head_cards": 10,
                   "interactive_ms": round(nav + drawn_after + 20, 1), "blocking_ms": 0.0, "long_frames": 0, "longest_frame_ms": 16.7}},
        **tie(origin)))
    C.sleep(1.0)
    if os.environ.get("FAKE_HANG") == tier:
        return                 # as smoke10's heavy: usable, then nothing more (no done)
    mark(f"page warmup {tier}")
    gw({"t": "warmup", "stage": "updates", "n": 1})
    if os.environ.get("FAKE_HANG_LIVE") == tier:
        return                 # as smoke 7's heavy (#202): live, then too slow to say anything more
    if os.environ.get("FAKE_PAGE_GONE") == tier:
        return page_gone()     # as on the Mirror (2026-11-23): the page's process killed mid-view
    C.sleep(0.3)
    # The runner's window opens at the measure line's stamp, so the line carries the time the frames
    # start from: stamped when printed, a fake 100 ms late on a busy machine had no frame in the window.
    m0, t0 = now(), clk() - origin
    paint(m0, m0 + 0.5)      # each change on screen within a frame or two
    gw({"t": "measure", "stage": "updates", "n": 1, "moving": 12, "offscreen": 0, "targets": 10, "update_every_ms": 200,
        "animations": 0, "animations_expected": 0}, at=m0)
    C.sleep(0.5)
    # As long as the real page's (smoke7's were cut at 1023 bytes; this one is about 1.8 KB, so it is
    # split): a change every 10 ms over the 500 ms window, and 31 rAF times.
    # [sent, arrived, transition, kind, tick]: the fake HA's schedule, the page reads each 0.4 ms later.
    # FAKE_BUSY_MAIN: from 200 ms on, nothing reaches the page in the window (a busy main thread).
    busy = os.environ.get("FAKE_BUSY_MAIN")
    changes = [[round(10.0 * k, 1), None if busy and 10 * k >= 200 else round(10.0 * k + 0.4, 1), 180 if k % 9 == 4 else 0,
                2 if k % 9 == 4 else 0, None if busy and 10 * k >= 200 else round(10.0 * k + 0.6, 1)] for k in range(1, 49)]
    gw({"t": "step", "stage": "updates", "n": 1, "window_ms": 500, "t0": round(t0, 1), "pacing_ms": 500, "from": "sent",
        "clock_rtt_ms": float(os.environ.get("FAKE_CLOCK_RTT", "3.0")), "stream_at_ms": -1000.0, "stream_asked_ms": -1001.5,
        "raf": {"n": 30, "median_ms": 16.67, "p95_ms": 16.67, "jank_pct": 0, "frozen_pct": 0, "fps": 60},
        "raf_times": [round(i * 16.7, 1) for i in range(31)], "changes": changes})
    if taps:
        before = state("taps.json", 0)
        save("asked.json", {"tap": taps})
        gw({"t": "input-ready", "x": 720, "y": 360, "taps": taps})
        end = now() + 10
        while state("taps.json", 0) - before < taps and now() < end:
            C.sleep(0.1)
        gw({"t": "input", "stage": tier, "method": "vitro-frame" if vitro() else "event-timing", "taps": state("taps.json", 0) - before,
            "values": [16, 48], "inp_ms": 48})
    # Vitro logs no camera line unless its cameras report to Clarity (FAKE_VITRO_CAMERAS).
    n = 0 if vitro() and not os.environ.get("FAKE_VITRO_CAMERAS") else {"typical": 1, "heavy": 4}.get(page, 0)
    if n and vitro():
        # VitroClarity's line (ClarityCameras.window): each camera's pictures, one listener call each, over the window.
        fb = os.environ["FAKE_VITRO_CAMERAS"] == "mjpeg"
        played = {"found": True, "first_frame_ms": 640.0, "first_frame_since_start_ms": 520, "frames": 75, "shown_pct": 100.0,
                  "max_gap_ms": 66.7, "stall_total_ms": 0.0, "stalls_at": [], "freezes": 0 if path == "web_rtc" else None,
                  "gaps_source": "vitro-frames (freeze rule)" if path == "web_rtc" else "vitro-frames (gaps over 500 ms)",
                  "source": "vitro-frames", "span_ms": 5000.0, "sessions": 1, "others": [], "transport": path, "errors": []}
        # Fell back: WebRTC never showed a picture, and the choice played MJPEG instead.
        fell = {"found": True, "first_frame_ms": None, "shown_pct": None, "max_gap_ms": None, "stall_total_ms": None,
                "others": [{"path": "mjpeg", "first_frame_ms": 410.0, "connected": True}], "transport": "mjpeg",
                "errors": ["web_rtc: no answer to the offer"]}
        gw({"t": "camera", "stage": tier, "path": path, "window_ms": 5000, "t0_ms": 9000.0, "nav_ms": 5000.0, "fps": 15,
            "expected": n, "found": n, "fetches": None,
            "streams": [dict({"path": path, "entity": f"camera.clarity_cam_{i}", "w": None, "h": None,
                              "stall_presented": None, "callbacks": None, "decoded": None, "dropped": None, "waiting": None},
                             **(fell if fb and path == "web_rtc" else played)) for i in range(n)]})
    elif n:
        # #174: the view's live cameras, from going live to the first swipe. FAKE_NO_VIDEO: none plays.
        dead = os.environ.get("FAKE_NO_VIDEO") == tier
        # As the S1 logs them (2026-11-13): over HLS, a hardware decoder per stream from the media
        # process, and on heavy the Allwinner decoder's flushes and drains when a stream runs out of
        # data. Over WebRTC, nothing: Gecko decodes it in software (#202, step 0).
        for _ in range(0 if dead or path == "web_rtc" else n):
            line("MediaCodec", "[OMX.allwinner.video.decoder.avc] setting surface generation to 17119233", pid=16718)
        if tier == "heavy-hls" and not dead:
            line("omx_vdec_aw", "<__anFlush:1043>: decoder flush", pid=1774)
            line("omx_vdec_aw", "<__anFlush:1043>: decoder flush", pid=1774)
            line("omx_vdec", "<doSubmit:2004>: found eos flag in input data", pid=1774)
        webrtc = {"inbound": True, "frames_received": 75, "frames_decoded": 75, "frames_dropped": 0, "freezes": 0, "freeze_s": 0,
                  "decoder": None, "power_efficient": None, "codec": "video/H264",
                  "candidate": {"local": "relay", "relay": "tcp", "protocol": "udp"}}
        gw({"t": "camera", "stage": tier, "path": path, "window_ms": 5000, "t0_ms": 9000.0, "nav_ms": 5000.0, "fps": 15,
            "expected": n, "found": n,
            "streams": [{"found": True, "w": 0 if dead else (1280 if i == 0 else 640), "h": 0 if dead else (720 if i == 0 else 360),
                         "first_frame_ms": None if dead else 640.0, "shown_pct": None if dead else 100.0,
                         "max_gap_ms": None if dead else 70.0, "stall_total_ms": None if dead else 0.0,
                         "stall_presented": None if dead else 0,
                         "frames": 0 if dead else 75, "decoded": 0 if dead else 75, "dropped": 0, "waiting": 0,
                         "source": "quality+rvfc", "path": path,
                         **({"webrtc": None if dead else webrtc, "others": [{"path": "hls", "first_frame_ms": 420.0, "connected": False}]}
                            if path == "web_rtc" else {"others": []})} for i in range(n)],
            # The segments' fetch times (stats.js hlsFetches). Heavy's whole line is longer than a
            # logcat line (1023 bytes), so it goes out in parts, as the page's does.
            "fetches": {"segment": {"n": 5 * n, "queued_ms": {"median": 1.2, "p95": 3.4, "max": 4.1},
                                    "wait_ms": {"median": 6.5, "p95": 12.25, "max": 14.0},
                                    "total_ms": {"median": 18.75, "p95": 40.5, "max": 52.0},
                                    "kb": 180 * n, "arrival_gap_max_ms": 1012.5},
                        "playlist": {"n": 5 * n, "queued_ms": {"median": 0.5, "p95": 1.0, "max": 1.0},
                                     "wait_ms": {"median": 2.0, "p95": 3.0, "max": 3.0},
                                     "total_ms": {"median": 3.0, "p95": 5.0, "max": 5.0}},
                        "streams": n}})
    for i in range(swipes):
        before = state("swipes.json", 0)
        save("asked.json", {"swipe": 1})
        ready = {"t": "swipe-ready", "n": i, "x": 720, "y1": 612, "y2": 108, "ms": 300}
        if os.environ.get("FAKE_BAD_SWIPE"):
            ready = json.loads(os.environ["FAKE_BAD_SWIPE"])
        gw(ready)
        end = now() + float(os.environ.get("FAKE_SWIPE_WAIT", "10"))
        while state("swipes.json", 0) == before and now() < end:
            C.sleep(0.05)
        moved = state("swipes.json", 0) > before
        if not moved:
            gw({"t": "step", "stage": "scroll", "n": i, "window_ms": 0, "scrolled_px": 0, "raf": {"n": 0}})
            continue
        s0 = now()
        paint(s0, s0 + 0.5)   # the swipe and its fling
        gw({"t": "measure", "stage": "scroll", "n": i})
        C.sleep(0.5)
        gw({"t": "step", "stage": "scroll", "n": i, "window_ms": 500, "scrolled_px": 700, "hit_edge": False, "changes_during": 3, "first_scroll_ms": 40,
            "raf": {"n": 30, "median_ms": 16.67, "p95_ms": 16.67, "jank_pct": 0, "frozen_pct": 0, "fps": 60},
            "raf_intervals": [16.7] * 30})
        C.sleep(0.2)
    # Protocol 3 (#211): HA's more-info dialogs, after the swipes (the runner sends dialogs=0 to the
    # HLS copies and a warm-up). Each opens in 10 ms, its 300 ms sheet runs at 60 fps and it settles
    # at 320 ms; the sensor's graph at 40 ms. FAKE_DIALOG_NEVER_OPENS=<view>: none opens there (HA
    # never shows them); FAKE_DIALOG_SLOW_GRAPH=<ms>: the sensor's history graph is drawn that late.
    m = re.search(r"dialogs=(\d+)", url)
    asked = DIALOGS[:int(m.group(1)) if m else len(DIALOGS)]
    for i, d in enumerate(asked):
        never = os.environ.get("FAKE_DIALOG_NEVER_OPENS") == tier
        graph = float(os.environ.get("FAKE_DIALOG_SLOW_GRAPH", "40")) if d["graph"] else None
        settled = None if never else max(320.0, graph or 0)
        anim = [] if never else [16.7] * 18
        gw({"t": "dialog", "stage": tier, "n": i, "of": len(DIALOGS), "entity": d["entity"], "kind": d["kind"],
            "graph": d["graph"], "mode": None if never else "bottom-sheet", "show_duration_ms": None if never else 300,
            "reduced_motion": False, "opened": not never, "open_ms": None if never else 10.0,
            "shown_ms": None if never else 320.0, "graph_ms": None if never else graph, "settled_ms": settled,
            "anim": {"n": 0} if never else {"n": 18, "median_ms": 16.7, "p95_ms": 16.67, "p95_raw_ms": 16.7, "max_ms": 16.7,
                                             "fps": 59.88, "jank_pct": 0, "frozen_pct": 0},
            "anim_intervals": anim, "dropped": 0, "longest_frame_ms": None if never else 16.7,
            "close_ms": None if never else 310.0, "close_longest_ms": None if never else 16.7,
            "error": "it never opened within 20 s" if never else None, "close_error": None})
        C.sleep(0.1)
    save("asked.json", {})
    mark(f"page done {tier}")
    if os.environ.get("FAKE_HA_URL"):
        # As the harness does at the end of a view: stop the stream, which also ends the command.
        import urllib.request
        try:
            urllib.request.urlopen(os.environ["FAKE_HA_URL"].rstrip("/") + f"/clarity/still?nonce={nonce}", timeout=10).read()
        except OSError:
            pass
    # #410: after its dialogs, HA done with them (none pending, open or in the history) at once; none
    # asked for, no settle. FAKE_SETTLE_VIA=<view>: HA still held two there, so the view was left for
    # the start view, where they came and were closed.
    settle = None
    if asked:
        settle = {"ms": 0, "clean": True, "closed": 0, "pending": [], "open": False, "history": False}
    if asked and os.environ.get("FAKE_SETTLE_VIA") == tier:
        first = {"ms": 30000, "clean": False, "closed": 0, "pending": [d["entity"] for d in asked[1:]], "open": False, "history": True}
        gw(dict({"t": "settle", "stage": tier}, **first))
        settle = {"ms": 31200, "clean": True, "closed": 1, "pending": [], "open": False, "history": False,
                  "via": "start", "first": first, "at": "start"}
    gw({"t": "done", "stage": tier, "settle": settle})
    if foreign == "end":
        C.sleep(0.3)
        line("GeckoSession", "handleMessage GeckoView:PageStart uri=http://192.0.2.10:8123/lovelace?external_auth=1")

def page_clock():
    """The page's clock and the fields that tie it to logcat's. The web harness's: performance.now() after its
    timeOrigin, tied by Date.now() on the line (origin_epoch_ms, epoch_ms). Vitro's (VitroClarity): CLOCK_MONOTONIC,
    the fake's now(), after origin_mono_ms (`clock: monotonic`)."""
    if vitro():
        return (lambda: now() * 1000), (lambda origin: {"engine": "vitro", "clock": "monotonic", "origin_mono_ms": origin})
    return (lambda: C.now() * 1000), (lambda origin: {"origin_epoch_ms": origin, "epoch_ms": C.now() * 1000})


def child_proc():
    """The browser page's process name: FAKE_CHILD_PROC after the package's colon (`tab` by default; a long one such as
    `sandboxed_tab_disable_art_image_24` keeps no colon in the kernel's last 15 characters)."""
    return f"{PKG}:{os.environ.get('FAKE_CHILD_PROC', 'tab')}"


def page_gone():
    """The view's browser page gone mid-view, as MainActivity.onContentGone logs it and then loads the kiosk's page
    again (the test dashboard's start view, which says when it is usable and done, as after a restart).
    FAKE_PAGE_GONE_HOW: killed (the default), crashed, or none: the reload alone, with no line before it (a sign-in or
    a navigation, which still fails the run). FAKE_PAGE_GONE_LATE_S: seconds before the page comes back. FAKE_LMK: the low-memory killer's line for the page's process, in dmesg
    (kernel) or logcat (lmkd), just before."""
    how = os.environ.get("FAKE_PAGE_GONE_HOW", "killed")
    C.sleep(0.2)
    mark("page gone")
    if os.environ.get("FAKE_LMK"):
        save("lmk_at.json", now())
        if os.environ["FAKE_LMK"] == "lmkd":
            line("lowmemorykiller", f"Kill '{child_proc()}' (4301), uid 10083, oom_adj 0 to free 172032kB", pid=600)
    if how != "none":
        line("Glassworks", f"Content process {how}; restarting session")
    C.sleep(0.2)
    saved = (state("test_dash.json") if held() else None) or state("saved_url.json") or os.environ.get("FAKE_SAVED_URL")
    C.sleep(float(os.environ.get("FAKE_PAGE_GONE_LATE_S", "0")))   # the page comes back this late
    restart_page(saved, done=not os.environ.get("FAKE_PAGE_GONE_NO_DONE"))


def restart_page(saved, done=True):
    """The kiosk's page on the start view, as after a restart: its load, the harness's usable and done lines (no
    nonce: restart mode). done=False: its done line lost in logcat."""
    if not vitro():
        line("GeckoSession", f"handleMessage GeckoView:PageStart uri={saved}")
    clk, _ = page_clock()
    origin = clk() - 200
    C.sleep(0.5)
    page_log(restart_refdash(origin), record=False)
    line("GeckoSession", "handleMessage GeckoView:PageStop uri=null")
    C.sleep(0.2)
    if done:
        page_log({"t": "done", "stage": "restart", "run": 1, "nonce": ""}, record=False)


def restart_refdash(origin):
    _, tie = page_clock()
    return dict({"t": "refdash", "tier": "start", "protocol": PROTOCOL, "clarity": CLARITY,
                 "frontend": None if vitro() else "20261028.5", "nodes": 1082, "cards": 22,
            "cards_rendered": 20, "css": [960, 480],
            "dpr": float(os.environ.get("FAKE_DPR", "1.5")), "scroll_height": 1936, "fcp_ms": 120, "lcp_ms": 120,
            "load_ms": 100, "nav_ms": 0,
            "usable": {"rendered_tick_ms": 400.0, "first_screen_cards": 13, "head_cards": 10, "interactive_ms": 410.0,
                       "blocking_ms": 0.0, "long_frames": 0, "longest_frame_ms": 16.7},
            "run": 1, "nonce": ""}, **tie(origin))


def dashboard():
    """A cold start as on the S1: Displayed, then 0.6 s later GeckoView's own about:blank (the session
    is up from here), then the kiosk's own dashboard."""
    line("ActivityManager", f"Start proc 5555:{PKG}/u0a83 for activity {{{PKG}/{PKG}.MainActivity}}", pid=1918)
    if not os.environ.get("FAKE_NO_CHILD_START"):
        # The browser page's process, which the low-memory killer names (FAKE_LMK); FAKE_NO_CHILD_START: started
        # before the runner read logcat, so no line for it.
        line("ActivityManager", f"Start proc 4301:{child_proc()}/u0i12 for service {{{PKG}/org.mozilla.gecko.process."
                                f"GeckoChildProcessServices$tab0}}", pid=1918)
    save("kiosk_awake_at.json", C.now())   # the new process's idle timer starts
    # The generic app comes back as the home app through its launcher alias, and is displayed under it.
    line("ActivityTaskManager", f"Displayed {PKG}/{'dev.glassworks.generic.Launcher' if ANDROID6 else '.MainActivity'}: +1s714ms", pid=1918)
    C.sleep(0.6)
    if vitro():
        # VitroEngine.open: Vitro draws the page; no GeckoView page loads (its fallback starts only for a web page).
        line("Glassworks.Vitro", "Starting Vitro 0.0.1 (experimental) (native Lovelace renderer; fallback=lazy)")
    else:
        line("GeckoSession", "handleMessage GeckoView:PageStart uri=about:blank")
        line("GeckoSession", "handleMessage GeckoView:PageStop uri=null")
    save("session_up.json", True)
    a = now()
    paint(a + 0.2, a + 1.2)  # the screen paints whether or not anyone reads logcat
    saved = (state("test_dash.json") if held() else None) or state("saved_url.json") or os.environ.get("FAKE_SAVED_URL")
    if state("signin_moved.json"):
        # #496: Home Assistant moved: the setup screen's Sign in step covers the kiosk, and HA's login page loads.
        line("Glassworks", "Home Assistant is at another address than the sign-in's: asking to sign in again, no token sent")
        line("GeckoSession", f"handleMessage GeckoView:PageStart uri={saved}")
        line("GeckoSession", "handleMessage GeckoView:PageStop uri=null")
        line("GeckoSession", "handleMessage GeckoView:PageStart uri=http://127.0.0.1/auth/authorize?response_type=code")
        line("GeckoSession", "handleMessage GeckoView:PageStop uri=null")
        return
    if saved and "/clarity-" in saved and not os.environ.get("FAKE_RESTART_PAGE_LOST"):
        # Protocol 4: the kiosk was pointed at a reference dashboard; it comes back to it, and the
        # harness (no nonce: restart mode) says when it was usable, then done.
        if not vitro():
            line("GeckoSession", f"handleMessage GeckoView:PageStart uri={saved}")
        clk, _ = page_clock()
        origin = clk() - 200
        if os.environ.get("FAKE_RESTART_SLOW"):
            # As on the S1: HA's load event (PageStop) comes long before the dashboard is usable.
            line("GeckoSession", "handleMessage GeckoView:PageStop uri=null")
            C.sleep(float(os.environ["FAKE_RESTART_SLOW"]))
        C.sleep(0.5)
        page_log(restart_refdash(origin), record=False)
        line("GeckoSession", "handleMessage GeckoView:PageStop uri=null")
        C.sleep(0.2)
        if not os.environ.get("FAKE_NO_RESTART_DONE"):     # F8: the done line lost (logcat dropped it)
            page_log({"t": "done", "stage": "restart", "run": 1, "nonce": ""}, record=False)
        if os.environ.get("FAKE_SETTLE_VIA") == "warmup":
            # #410: the warm-up's dialogs (no nonce, after the restart's done), the last one held by HA
            # past the page's wait on the start view and after the move too. The runner may have
            # opened the first view by then (the real warm-up takes 20 s a held dialog): the lines
            # are still the restart's.
            C.sleep(2.0)
            for i, d in enumerate(DIALOGS):
                page_log({"t": "dialog", "stage": "warmup", "n": i, "of": len(DIALOGS), "entity": d["entity"],
                          "opened": i < len(DIALOGS) - 1, "run": 1, "nonce": ""}, record=False)
            first = {"ms": 30000, "clean": False, "closed": 0, "pending": [DIALOGS[-1]["entity"]], "open": False, "history": True}
            page_log(dict({"t": "settle", "stage": "warmup", "run": 1, "nonce": ""}, **first), record=False)
            page_log(dict({"t": "settle", "stage": "warmup", "run": 1, "nonce": ""}, ms=60000, clean=False, closed=0,
                          pending=[DIALOGS[-1]["entity"]], open=False, history=True, via="start", first=first, at="start"),
                     record=False)
        return
    # Stamped on the page's own timeline (load event at 0.8 s, frames until 1.2 s), not when printed:
    # on the 0.2 clock, a fake 80 ms late on a busy CI machine put PageStop after the last frame, and
    # real use settled on page-stop, not quiet-gap.
    line("GeckoSession", "handleMessage GeckoView:PageStart uri=http://192.0.2.10:8123/lovelace?external_auth=1", at=a)
    C.sleep(0.8)
    line("GeckoSession", "handleMessage GeckoView:PageStop uri=null", at=a + 0.8)
    if os.environ.get("FAKE_OWN_RELOAD"):
        # A sign-in redirect: the kiosk loads its page a second time, this long after the first stopped.
        C.sleep(float(os.environ.get("FAKE_OWN_RELOAD_DELAY", "0.5")))
        line("GeckoSession", "handleMessage GeckoView:PageStart uri=http://192.0.2.10:8123/lovelace/0?external_auth=1")
        C.sleep(0.8)
        line("GeckoSession", "handleMessage GeckoView:PageStop uri=null")


def mark(text):
    """A line in commands.log, in order with the adb commands (the tests read it)."""
    with open(os.path.join(STATE, "commands.log"), "a") as f:
        f.write(text + "\n")


def take_commands(base):
    """As the harness does in the loaded frontend: wait for the runner's next view at the fake Home
    Assistant (/clarity/next), then play it (refdash, below)."""
    import urllib.parse
    import urllib.request
    last = ""
    while True:
        try:
            with urllib.request.urlopen(base.rstrip("/") + "/clarity/next?after=" + last, timeout=30) as r:
                if r.status != 200:
                    continue
                cmd = json.loads(r.read())
        except OSError:
            C.sleep(0.2)
            continue
        last = cmd["nonce"]
        if os.environ.get("FAKE_CLOCK_STEP_S"):
            save("clock_stepped.json", True)
        q = {k: v for k, v in cmd.items() if k != "view"}
        save("views.json", state("views.json", []) + [cmd])
        with open(os.path.join(STATE, "commands.log"), "a") as f:     # in order with the adb commands
            f.write(f"view {cmd['view']} {cmd['nonce']} taps={cmd.get('taps')}\n")
        save("refnonce.json", [cmd["nonce"], f"/clarity-ref/{cmd['view']}?{urllib.parse.urlencode(q)}"])


def screen_timeout(after_s):
    """As the S1 at night: the screen goes off after_s seconds after the last wake or touch.
    FAKE_KIOSK_IDLE_S: the kiosk's own idle timer too, which a wake key doesn't reset."""
    save("awake_at.json", state("awake_at.json") or C.now())
    kiosk_s = float(os.environ.get("FAKE_KIOSK_IDLE_S") or 0)
    while True:
        C.sleep(0.1)
        awake_at = state("awake_at.json")
        if awake_at is None:
            continue                     # unreadable: no evidence the screen has been idle
        if not state("asleep.json") and not held() and C.now() - awake_at > after_s:
            save("asleep.json", True)
            line("PowerManagerService", "Going to sleep due to timeout (uid 1000)...", pid=1919)
        kiosk_at = state("kiosk_awake_at.json")
        if kiosk_s and kiosk_at and not state("asleep.json") and not held() and C.now() - kiosk_at > kiosk_s:
            save("asleep.json", True)
            line("PowerManagerService", "Going to sleep due to device_admin (uid 1000)...", pid=1919)


def logcat(fmt):
    if ANDROID6 and fmt == "monotonic":
        print("Invalid parameter monotonic to -v", flush=True)
        return
    print(f"{stamp(1.0)}  1  1 I old: line from before", flush=True)
    if os.environ.get("FAKE_HA_URL"):
        threading.Thread(target=take_commands, args=(os.environ["FAKE_HA_URL"],), daemon=True).start()
    if os.environ.get("FAKE_LOGCAT_NOISE"):
        threading.Thread(target=noise, args=(float(os.environ["FAKE_LOGCAT_NOISE"]),), daemon=True).start()
    if os.environ.get("FAKE_SCREEN_SLEEP_S"):
        threading.Thread(target=screen_timeout, args=(float(os.environ["FAKE_SCREEN_SLEEP_S"]),), daemon=True).start()
    # A cap, so no fake logcat outlives its test for long; the runner ends it before that.
    end = now() + 600
    while now() < end:
        if state("lockdown.json"):
            save("lockdown.json", None)
            line("Glassworks.Lockdown", "Lockdown: refused page from 127.0.0.1", pid=4242)
        if state("nonce.json"):
            nonce = state("nonce.json")[0]
            save("nonce.json", None)
            page(nonce)
        if state("refnonce.json"):
            nonce, url = state("refnonce.json")
            save("refnonce.json", None)
            refdash(nonce, url)
        if state("stopped.json"):
            save("stopped.json", None)
            line("ActivityManager", f"Force stopping {PKG} appid=10083 user=0: from pid 777", pid=1918)
        if state("cold.json"):
            save("cold.json", None)
            dashboard()
        C.sleep(0.05)


def main():
    args = sys.argv[1:]
    if args[:1] == ["-s"]:
        args = args[2:]
    with open(os.path.join(STATE, "commands.log"), "a") as f:
        f.write(" ".join(args) + "\n")
    if args[:1] == ["get-state"]:
        print("device")
    elif args[:1] == ["reverse"]:
        if os.environ.get("FAKE_REVERSE_FAILS"):
            # The Mirror's Android 6 adbd over network adb (2026-11-28): its USB transport stays registered next to the
            # TCP one, and its reverse service can't pick one, with -s or -t too.
            print("adb: error: more than one device/emulator", file=sys.stderr)
            sys.exit(1)
    elif args[:1] == ["devices"]:
        # netcheck.py (#649): the S1 on USB, or FAKE_ADB_WIFI: a panel on wireless debugging (an mDNS name).
        serial = "adb-FAKE0001-x1._adb-tls-connect._tcp" if os.environ.get("FAKE_ADB_WIFI") else "FAKE0001"
        how = "" if os.environ.get("FAKE_ADB_WIFI") else " usb:1-1"
        print(f"List of devices attached\n{serial} device{how} product:fake model:Fake device:fake transport_id:1")
    elif args[:1] == ["logcat"]:
        logcat(args[args.index("-v") + 1] if "-v" in args else "brief")
    elif args[:1] == ["shell"]:
        sys.stdout.write(shell(" ".join(args[1:])))


if __name__ == "__main__":
    main()
