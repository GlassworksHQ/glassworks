import fs from "node:fs";
import path from "node:path";
import { ImageResponse } from "next/og";
import { markPlates } from "./markGeometry";

/*
 * A page's link preview: the Glass Tile (the site's own card, src/app/opengraph-image.png, drawn by
 * scripts/brand/raster.mjs) with the page's name in place of the wordmark. Same night field, the
 * brand's sky and violet glows, the mark, Inter 200 for the name and the same small type around it.
 * Frame and pane pages make theirs from their data at build time, so a new one needs no step.
 * Approved by the maintainer, 2026-11-15 ("the Glass Tile with the page's name").
 */

export const OG_SIZE = { width: 1200, height: 630 };

/* the brand's colours, as in raster.mjs */
const NIGHT = "#0a0b0f";
const TILE = "#111217";
const FG = "#f4f4f5";
const MUTED = "#a1a1aa";
const FAINT = "#7d808b";

/*
 * Inter from @fontsource (a dev dependency; satori reads .woff, not .woff2). The images are made at
 * build time, where it's installed. If a request ever renders one without it, ImageResponse falls back
 * to its own font rather than failing.
 */
function inter(weight: 200 | 400 | 500) {
  const file = `@fontsource/inter/files/inter-latin-${weight}-normal.woff`;
  for (const dir of [path.join(process.cwd(), "node_modules"), path.join(process.cwd(), "../node_modules")]) {
    const p = path.join(dir, file);
    if (fs.existsSync(p)) return { name: "Inter", data: fs.readFileSync(p), weight, style: "normal" as const };
  }
  return undefined;
}

const markSvg = (size: number) => {
  const plates = markPlates("full")
    .map((p) =>
      p.lit
        ? `<path d="${p.d}" fill="url(#lit)"/>`
        : `<path d="${p.d}" fill="none" stroke="${FG}" stroke-opacity="${p.opacity}" stroke-width="${p.strokeWidth}" stroke-linejoin="round"/>`,
    )
    .join("");
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 120 120" width="${size}" height="${size}"><defs><linearGradient id="lit" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#38bdf8"/><stop offset="1" stop-color="#a78bfa"/></linearGradient></defs>${plates}</svg>`;
  return `data:image/svg+xml;base64,${Buffer.from(svg).toString("base64")}`;
};

/** The name's size: as large as the wordmark (118 px) when it fits, smaller for long names, which may wrap. */
const nameSize = (name: string) => (name.length <= 10 ? 118 : name.length <= 14 ? 100 : name.length <= 18 ? 86 : 76);

export type Tile = {
  /** top left, small caps: "Frame · Beta", "Pane" */
  label: string;
  /** the page's name, in place of the wordmark */
  name: string;
  /** bottom left: one line about it */
  line: string;
};

export function tileImage({ label, name, line }: Tile) {
  const fonts = [inter(200), inter(400), inter(500)].filter((f) => f !== undefined);
  const mark = 150;
  return new ImageResponse(
    (
      <div
        style={{
          width: "100%",
          height: "100%",
          display: "flex",
          alignItems: "center",
          justifyContent: "center",
          backgroundColor: NIGHT,
          backgroundImage:
            "radial-gradient(60% 70% at 0% 0%, rgba(56,189,248,.14), transparent 70%), radial-gradient(60% 70% at 100% 100%, rgba(167,139,250,.12), transparent 70%)",
          fontFamily: "Inter",
        }}
      >
        <div
          style={{
            width: 1000,
            height: 500,
            display: "flex",
            flexDirection: "column",
            justifyContent: "space-between",
            padding: "64px 66px",
            borderRadius: 34,
            border: "1px solid rgba(255,255,255,.09)",
            backgroundColor: TILE,
            backgroundImage:
              "radial-gradient(55% 75% at 0% 0%, rgba(56,189,248,.28), transparent 70%), radial-gradient(50% 70% at 100% 100%, rgba(167,139,250,.24), transparent 70%)",
            color: FG,
          }}
        >
          <div
            style={{
              display: "flex",
              justifyContent: "space-between",
              fontSize: 13,
              fontWeight: 500,
              letterSpacing: "0.16em",
              textTransform: "uppercase",
              color: FAINT,
            }}
          >
            <span>{label}</span>
            <span>Open source · MPL-2.0</span>
          </div>
          <div style={{ display: "flex", alignItems: "center", gap: 30, marginTop: 20 }}>
            {/* eslint-disable-next-line @next/next/no-img-element -- satori draws <img>, not next/image */}
            <img src={markSvg(mark)} width={mark} height={mark} alt="" />
            <div
              style={{
                display: "flex",
                flex: 1,
                fontSize: nameSize(name),
                fontWeight: 200,
                lineHeight: 1.04,
                letterSpacing: "-0.03em",
              }}
            >
              {name}
            </div>
          </div>
          <div style={{ display: "flex", justifyContent: "space-between", gap: 40, fontSize: 15, color: MUTED }}>
            <span style={{ display: "flex", flex: 1, overflow: "hidden", whiteSpace: "nowrap", textOverflow: "ellipsis" }}>
              {line}
            </span>
            <span>glassworks.dev</span>
          </div>
        </div>
      </div>
    ),
    { ...OG_SIZE, fonts: fonts.length ? fonts : undefined },
  );
}
