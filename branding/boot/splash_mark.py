"""The Glassworks stack as an Android VectorDrawable, for the Android 12+ launch splash.

Writes frames/aqara-s1-plus/android/src/main/res/drawable/glassworks_mark.xml. Standard library only.
Run it after the stack's design changes; test/test_splash_mark.py fails while the file is stale.

The drawing is StackView's (core/.../ui/StackView.kt) in the pose the connecting screen's first frame
has on a device without a boot frame: the boot's float (LoadingOverlay.BOOT_LIFTS) at its glow
(BOOT_GLOW), the top plate lit, the two below in outline. So the system splash fades into that frame.
StackView's blur-free parts carry over one to one; VectorDrawable has no blur, and StackView has none.

Android sizes a splash icon without a background at 288 dp and shows the circle of 192 dp in its
middle. 120 stack units are 200 dp here, the stack's size on a 1280x800 dp tablet (LoadingOverlay:
150 css px of a 960x480 page), and the plates stay inside that circle (FIT).
"""
import math
import os
import sys

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
OUT = os.path.join(REPO, "frames/aqara-s1-plus/android/src/main/res/drawable/glassworks_mark.xml")

SIDE, R, CX, TOP0, GAP = 60.0, 9.0, 60.0, 11.0, 19.0
C = math.cos(math.pi / 6)
W = (0.7, 0.45, 0.2)
LIFTS = (7.16, 4.46, 3.86)
GLOW = 0.90
OUTLINE, RIM, FROST, FROST_DY = 5.0, 2.2, 11.0, 2.5

DP, UNITS_DP = 288.0, 200.0 / 120.0
VIEW = DP / UNITS_DP
FIT = 96.0 / UNITS_DP * 0.95          # 95 % of the 192 dp circle's radius, in units

FG, BODY = "F4F4F5", "0B0C11"
SKY, INDIGO, VIOLET = "38BDF8", "818CF8", "A78BFA"
K = 0.5522847498


def rounded_square():
    """The plate before the isometric transform: segments of ("L", p) or ("C", c1, c2, p) from (R, 0)."""
    s, r, k = SIDE, R, K * R
    return (R, 0.0), [
        ("L", (s - r, 0.0)), ("C", (s - r + k, 0.0), (s, r - k), (s, r)),
        ("L", (s, s - r)), ("C", (s, s - r + k), (s - r + k, s), (s - r, s)),
        ("L", (r, s)), ("C", (r - k, s), (0.0, s - r + k), (0.0, s - r)),
        ("L", (0.0, r)), ("C", (0.0, r - k), (r - k, 0.0), (r, 0.0)),
    ]


def plate(i, dy=0.0):
    """Plate i (0 = top) in stack units: StackView.platePath, an affine map, so its Béziers stay exact."""
    top = TOP0 + i * GAP - LIFTS[i] + dy

    def f(p):
        x, y = p
        return (CX + C * (x - y), top + 0.5 * (x + y))
    start, segs = rounded_square()
    return f(start), [(s[0],) + tuple(f(p) for p in s[1:]) for s in segs]


def points(path, steps=64):
    """Points along the path, for bounds and the circle check."""
    (x0, y0), segs = path
    out, cur = [(x0, y0)], (x0, y0)
    for s in segs:
        if s[0] == "L":
            cur = s[1]
            out.append(cur)
            continue
        p0, (c1, c2, p3) = cur, s[1:]
        for n in range(1, steps + 1):
            t = n / steps
            a, b, c, d = (1 - t) ** 3, 3 * (1 - t) ** 2 * t, 3 * (1 - t) * t * t, t ** 3
            out.append((a * p0[0] + b * c1[0] + c * c2[0] + d * p3[0], a * p0[1] + b * c1[1] + c * c2[1] + d * p3[1]))
        cur = p3
    return out


def bounds(path):
    ps = points(path)
    xs, ys = [p[0] for p in ps], [p[1] for p in ps]
    return min(xs), min(ys), max(xs), max(ys)


def offset():
    """Moves the plates' extent (strokes included) to the middle of the viewport."""
    b = [bounds(plate(i)) for i in range(3)]
    l, t = min(x[0] for x in b) - OUTLINE / 2, min(x[1] for x in b) - OUTLINE / 2
    r, bt = max(x[2] for x in b) + OUTLINE / 2, max(x[3] for x in b) + OUTLINE / 2
    return VIEW / 2 - (l + r) / 2, VIEW / 2 - (t + bt) / 2


def reach():
    """The farthest any plate's outline gets from the viewport's centre, in units."""
    ox, oy = offset()
    return max(math.hypot(x + ox - VIEW / 2, y + oy - VIEW / 2) for i in range(3) for x, y in points(plate(i))) + OUTLINE / 2


def num(v):
    s = f"{v:.3f}".rstrip("0").rstrip(".")
    return "0" if s in ("-0", "") else s


def path_data(path, ox, oy):
    def p(q):
        return f"{num(q[0] + ox)},{num(q[1] + oy)}"
    start, segs = path
    out = [f"M{p(start)}"]
    for s in segs:
        out.append(("L" if s[0] == "L" else "C") + " ".join(p(q) for q in s[1:]))
    return "".join(out) + "Z"


def argb(rgb, a):
    return f"#{int(max(0.0, min(1.0, a)) * 255):02X}{rgb}"


def linear(attr, x0, y0, x1, y1, stops, pad):
    items = "\n".join(f'{pad}        <item android:offset="{num(o)}" android:color="{c}" />' for o, c in stops)
    return (f'{pad}<aapt:attr name="{attr}">\n'
            f'{pad}    <gradient android:type="linear" android:startX="{num(x0)}" android:startY="{num(y0)}" '
            f'android:endX="{num(x1)}" android:endY="{num(y1)}">\n{items}\n{pad}    </gradient>\n{pad}</aapt:attr>')


def render():
    ox, oy = offset()
    level = GLOW
    lit = path_data(plate(0), ox, oy)
    l, t, r, b = bounds(plate(0))
    l, t, r, b = l + ox, t + oy, r + ox, b + oy
    w, h = r - l, b - t
    radius = 0.7 * math.hypot(w, h) / 2
    out = [
        '<?xml version="1.0" encoding="utf-8"?>',
        "<!-- The Glassworks stack for the Android 12+ launch splash (values-v31/themes.xml), as the",
        "     connecting screen's first frame draws it. Made by branding/boot/splash_mark.py; don't edit. -->",
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
        '    xmlns:aapt="http://schemas.android.com/aapt"',
        f'    android:width="{num(DP)}dp"',
        f'    android:height="{num(DP)}dp"',
        f'    android:viewportWidth="{num(VIEW)}"',
        f'    android:viewportHeight="{num(VIEW)}">',
    ]
    for i in (2, 1):
        out += [
            "    <path",
            f'        android:pathData="{path_data(plate(i), ox, oy)}"',
            f'        android:strokeColor="{argb(FG, W[i])}"',
            f'        android:strokeWidth="{num(OUTLINE)}"',
            '        android:strokeLineJoin="round"',
            '        android:strokeLineCap="round" />',
        ]
    out += ["    <group>", f'        <clip-path android:pathData="{lit}" />',
            f'        <path android:pathData="{lit}" android:fillColor="#FF{BODY}" />']
    for j in (1, 2):
        out += [
            "        <path",
            f'            android:pathData="{path_data(plate(j, FROST_DY), ox, oy)}"',
            f'            android:strokeColor="{argb(FG, W[j] * 0.35)}"',
            f'            android:strokeWidth="{num(FROST)}"',
            '            android:strokeLineJoin="round"',
            '            android:strokeLineCap="round" />',
        ]
    out += [
        f'        <path android:pathData="{lit}">',
        linear("android:fillColor", l, t, r, b,
               [(0, argb(SKY, 0.95 * level)), (0.55, argb(INDIGO, 0.85 * level)), (1, argb(VIOLET, 0.92 * level))], "            "),
        "        </path>",
        f'        <path android:pathData="{lit}">',
        '            <aapt:attr name="android:fillColor">',
        f'                <gradient android:type="radial" android:centerX="{num(l + 0.3 * w)}" '
        f'android:centerY="{num(t + 0.15 * h)}" android:gradientRadius="{num(radius)}">',
        f'                    <item android:offset="0" android:color="{argb("FFFFFF", 0.3 * level)}" />',
        '                    <item android:offset="1" android:color="#00000000" />',
        "                </gradient>",
        "            </aapt:attr>",
        "        </path>",
        "    </group>",
        "    <path",
        f'        android:pathData="{lit}"',
        f'        android:strokeWidth="{num(RIM)}"',
        '        android:strokeLineJoin="round"',
        '        android:strokeLineCap="round">',
        linear("android:strokeColor", l, t, l, b,
               [(0, argb("FFFFFF", 0.95 * level)), (0.5, argb("E0F2FE", 0.35 * level)), (1, argb("C4B5FD", 0.55 * level))], "        "),
        "    </path>",
        "</vector>",
    ]
    return "\n".join(out) + "\n"


def main():
    if reach() > FIT:
        sys.exit(f"the stack reaches {reach():.1f} units from the centre; the splash shows {FIT:.1f}")
    with open(OUT, "w", encoding="utf-8") as f:
        f.write(render())
    print(f"wrote {os.path.relpath(OUT, REPO)} (reach {reach():.1f} of {FIT:.1f} units)")


if __name__ == "__main__":
    main()
