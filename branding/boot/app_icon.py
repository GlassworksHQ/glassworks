"""The generic app's icons (frames/generic-android), from the brand's own SVGs in site/public/brand/.

Writes into frames/generic-android/android/src/main/res/:
  mipmap-anydpi-v26/ic_launcher.xml   the adaptive icon (Android 8+): the layers below
  drawable-v26/ic_launcher_*.xml      its layers: app-icon.svg's night and glows, mark-on-dark.svg, mark-mono.svg
  drawable/ic_notification.xml        the service notification's small icon: mark-mono.svg in white, every API
  mipmap-*dpi/ic_launcher.png         app-icon.svg itself, for Android 6-7 (only with --png: needs rsvg-convert)

The brand's app icon is the mark on night, "always on dark" (glassworks.dev/brand). Nothing here is drawn: the
paths, colours and the mark's place in the icon are read from the SVGs, and only mapped onto Android's grids. The
adaptive icon's 72 of 108 dp are the part a launcher shows, so the 512 px icon maps onto those.
Run it after the brand's mark changes; test/test_app_icon.py fails while the vectors are stale. Standard library only.

  python3 branding/boot/app_icon.py          # the vectors
  python3 branding/boot/app_icon.py --png    # and the PNGs
"""
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
BRAND = os.path.join(REPO, "site/public/brand")
RES = os.path.join(REPO, "frames/generic-android/android/src/main/res")
SVG = "{http://www.w3.org/2000/svg}"

ADAPTIVE, SHOWN = 108.0, 72.0
# Android's launcher icon sizes, in px: 48 dp at each density.
PNGS = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}

HEADER = ('<?xml version="1.0" encoding="utf-8"?>\n'
          "<!-- Made by branding/boot/app_icon.py from site/public/brand/{src}; don't edit. -->\n")


def load(name):
    return ET.parse(os.path.join(BRAND, name)).getroot()


def argb(colour, opacity=1.0):
    """#rrggbb and an opacity as Android's #AARRGGBB."""
    a = round(float(opacity) * 255)
    return "#%02X%s" % (a, colour.lstrip("#").upper())


def stops(gradient):
    return [(float(s.get("offset")), s.get("stop-color"), s.get("stop-opacity", "1")) for s in gradient.iter(SVG + "stop")]


def gradient_xml(kind, attrs, items, indent):
    pad = " " * indent
    head = " ".join(f'android:{k}="{v}"' for k, v in [("type", kind)] + attrs)
    rows = "".join(f'{pad}        <item android:offset="{fmt(o)}" android:color="{argb(c, op)}" />\n' for o, c, op in items)
    return f"{pad}    <gradient {head}>\n{rows}{pad}    </gradient>\n"


def fmt(v):
    s = f"{v:.3f}".rstrip("0").rstrip(".")
    return "0" if s == "-0" else s


def mapped(d, scale, dx, dy):
    """An SVG path of absolute M/L/Z commands (the mark's), each point scaled by `scale` and moved by (dx, dy)."""
    out = []
    for cmd, args in re.findall(r"([MLZ])([^MLZ]*)", d):
        nums = [float(n) for n in re.findall(r"-?\d+(?:\.\d+)?", args)]
        if cmd != "Z" and (len(nums) != 2):
            raise SystemExit(f"app_icon: the mark's path has a {cmd} with {nums}; this maps only M x y / L x y / Z")
        out.append(cmd if cmd == "Z" else f"{cmd}{fmt(nums[0] * scale + dx)},{fmt(nums[1] * scale + dy)}")
    return "".join(out)


def points(d):
    nums = [float(n) for n in re.findall(r"-?\d+(?:\.\d+)?", d)]
    return list(zip(nums[0::2], nums[1::2]))


def mark_paths(svg, scale, dx, dy, ink=None):
    """The mark's paths as VectorDrawable paths. ink: one colour for every part (mark-mono's currentColor)."""
    lit = {g.get("id"): g for g in svg.iter(SVG + "linearGradient")}
    out = []
    for p in svg.iter(SVG + "path"):
        d = mapped(p.get("d"), scale, dx, dy)
        fill, stroke = p.get("fill", "none"), p.get("stroke")
        attrs = [f'android:pathData="{d}"']
        body = ""
        if stroke:
            colour = ink or stroke
            attrs += [f'android:strokeColor="{argb(colour, p.get("stroke-opacity", "1"))}"',
                      f'android:strokeWidth="{fmt(float(p.get("stroke-width", "1")) * scale)}"',
                      f'android:strokeLineJoin="{p.get("stroke-linejoin", "miter")}"']
        if fill.startswith("url(") and not ink:
            g = lit[fill[5:-1]]
            body = gradient_attr(g, [(x * scale + dx, y * scale + dy) for x, y in points(p.get("d"))])
        elif fill != "none":
            attrs.append(f'android:fillColor="{argb(ink or fill)}"')
        if body:
            out.append("    <path\n        " + "\n        ".join(attrs) + ">\n" + body + "    </path>\n")
        else:
            out.append("    <path\n        " + "\n        ".join(attrs) + " />\n")
    return "".join(out)


def gradient_attr(g, pts):
    """An SVG linearGradient in objectBoundingBox units (SVG's default) over a path's box, in the path's own space.

    SVG stretches the gradient with the box, so on a box that isn't square its stripes aren't at right angles to
    (x1, y1)-(x2, y2); VectorDrawable's always are. The gradient's position t is linear in x and y, so the same
    stripes are a VectorDrawable gradient from where t = 0 along t's gradient n, to start + n / |n|², where t = 1.
    """
    xs, ys = [p[0] for p in pts], [p[1] for p in pts]
    x0, y0, w, h = min(xs), min(ys), max(xs) - min(xs), max(ys) - min(ys)
    u1, v1, u2, v2 = (float(g.get(k, d)) for k, d in (("x1", "0"), ("y1", "0"), ("x2", "1"), ("y2", "0")))
    du, dv = u2 - u1, v2 - v1
    ll = du * du + dv * dv
    nx, ny = du / (w * ll), dv / (h * ll)
    nn = nx * nx + ny * ny
    sx, sy = x0 + u1 * w, y0 + v1 * h
    attrs = [("startX", fmt(sx)), ("startY", fmt(sy)), ("endX", fmt(sx + nx / nn)), ("endY", fmt(sy + ny / nn))]
    return ('        <aapt:attr name="android:fillColor">\n' + gradient_xml("linear", attrs, stops(g), 8)
            + "        </aapt:attr>\n")


def vector(size_dp, viewport, body, aapt=False):
    ns = '\n    xmlns:aapt="http://schemas.android.com/aapt"' if aapt else ""
    return (f'<vector xmlns:android="http://schemas.android.com/apk/res/android"{ns}\n'
            f'    android:width="{fmt(size_dp)}dp"\n    android:height="{fmt(size_dp)}dp"\n'
            f'    android:viewportWidth="{fmt(viewport)}"\n    android:viewportHeight="{fmt(viewport)}">\n{body}</vector>\n')


def icon_mark_place(icon):
    """The mark's translate and scale inside app-icon.svg's 512 px, as the brand set them."""
    g = next(e for e in icon.iter(SVG + "g") if e.get("transform"))
    t = re.match(r"translate\(([\d.]+) ([\d.]+)\) scale\(([\d.]+)\)", g.get("transform"))
    if not t:
        raise SystemExit(f"app_icon: app-icon.svg's mark has transform {g.get('transform')!r}")
    return float(t.group(1)), float(t.group(2)), float(t.group(3))


def adaptive_map(icon):
    """(scale, dx, dy) taking the mark's 120 units to the adaptive icon's 108 dp, placed as in app-icon.svg."""
    side = float(icon.get("viewBox").split()[2])
    k = SHOWN / side
    tx, ty, s = icon_mark_place(icon)
    inset = (ADAPTIVE - SHOWN) / 2
    return s * k, inset + tx * k, inset + ty * k


def background(icon):
    side = float(icon.get("viewBox").split()[2])
    k, inset = SHOWN / side, (ADAPTIVE - SHOWN) / 2
    rects = [r for r in icon.iter(SVG + "rect") if r.get("fill", "none") != "none"]
    radial = {g.get("id"): g for g in icon.iter(SVG + "radialGradient")}
    full = f"M0,0H{fmt(ADAPTIVE)}V{fmt(ADAPTIVE)}H0Z"
    out = []
    for r in rects:
        fill = r.get("fill")
        if not fill.startswith("url("):
            out.append(f'    <path\n        android:pathData="{full}"\n        android:fillColor="{argb(fill)}" />\n')
            continue
        g = radial[fill[5:-1]]
        attrs = [("centerX", fmt(inset + float(g.get("cx")) * side * k)), ("centerY", fmt(inset + float(g.get("cy")) * side * k)),
                 ("gradientRadius", fmt(float(g.get("r")) * side * k))]
        out.append(f'    <path android:pathData="{full}">\n        <aapt:attr name="android:fillColor">\n'
                   + gradient_xml("radial", attrs, stops(g), 8) + "        </aapt:attr>\n    </path>\n")
    return vector(ADAPTIVE, ADAPTIVE, "".join(out), aapt=True)


ADAPTIVE_XML = """<!-- The launcher icon on Android 8+: the brand's app icon (site/public/brand/app-icon.svg), the mark on night. The
     monochrome layer is Android 13's themed icon. Android 6-7 show the same icon as PNGs (mipmap-*dpi). -->
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />
</adaptive-icon>
"""


def outputs():
    """{path under res/: text} for every vector this writes."""
    icon, dark, mono = load("app-icon.svg"), load("mark-on-dark.svg"), load("mark-mono.svg")
    s, dx, dy = adaptive_map(icon)
    mark_side = float(mono.get("viewBox").split()[2])
    return {
        "mipmap-anydpi-v26/ic_launcher.xml": HEADER.format(src="app-icon.svg") + ADAPTIVE_XML,
        "drawable-v26/ic_launcher_background.xml": HEADER.format(src="app-icon.svg") + background(icon),
        "drawable-v26/ic_launcher_foreground.xml": HEADER.format(src="mark-on-dark.svg")
            + vector(ADAPTIVE, ADAPTIVE, mark_paths(dark, s, dx, dy), aapt=True),
        "drawable-v26/ic_launcher_monochrome.xml": HEADER.format(src="mark-mono.svg")
            + vector(ADAPTIVE, ADAPTIVE, mark_paths(mono, s, dx, dy, ink="#ffffff")),
        # Plain paths and colours, no gradients: Android 6 loads it as it is (a status bar icon draws only its alpha).
        "drawable/ic_notification.xml": HEADER.format(src="mark-mono.svg")
            + vector(24, mark_side, mark_paths(mono, 1.0, 0.0, 0.0, ink="#ffffff")),
    }


def write_pngs():
    src = os.path.join(BRAND, "app-icon.svg")
    for density, px in PNGS.items():
        out = os.path.join(RES, f"mipmap-{density}", "ic_launcher.png")
        os.makedirs(os.path.dirname(out), exist_ok=True)
        subprocess.run(["rsvg-convert", "-w", str(px), "-h", str(px), "-o", out, src], check=True)


def main(argv):
    for rel, text in outputs().items():
        path = os.path.join(RES, rel)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8") as f:
            f.write(text)
    if "--png" in argv:
        write_pngs()


if __name__ == "__main__":
    main(sys.argv[1:])
