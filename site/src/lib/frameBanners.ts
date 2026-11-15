import fs from "node:fs";
import path from "node:path";
import { THEMES } from "../../scripts/brand/draw.mjs";
import { frameBanner } from "../../scripts/brand/frame.mjs";
import { frames } from "./frames";

/*
 * Each frame's README banner, banner-frame-<id>-on-*.svg in public/brand/: `npm run brand` draws it from
 * the frame's frame.json (scripts/brand/frame.mjs). A frame without a tagline
 * has none. Checked here at build time: a frame.json changed without `npm run brand`
 * fails the build, naming the file, so a README never shows an old banner. A banner whose frame is
 * gone or has no tagline any more stays published, because a README may still link it.
 */

export const frameBanners = frames
  .filter((f) => f.tagline)
  .map((f) => {
    for (const theme of ["dark", "light"] as const) {
      const file = path.join(process.cwd(), "public/brand", `banner-frame-${f.id}-on-${theme}.svg`);
      if (!fs.existsSync(file) || fs.readFileSync(file, "utf8") !== frameBanner(THEMES[theme], f)) {
        throw new Error(`public/brand/${path.basename(file)} isn't what frames/${f.id}/design/frame.json draws: run \`npm run brand\` and commit public/brand/`);
      }
    }
    return { id: f.id, name: f.name };
  });
