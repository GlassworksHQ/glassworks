// Records the round 2 edge glow (mockups.html?scene=lit) as frames, then a GIF and an MP4 with ffmpeg.
// Drives one headless Chrome page and steps the animation clock (window.renderAt), so every frame is
// exact. Writes only to the output directory. Needs playwright-core (NODE_PATH) and ffmpeg.
//
//   NODE_PATH=<dir>/node_modules node motion.cjs <out-dir> <name> "<query>" <w> <h> <scale> <from-ms> <to-ms> <speed> [fps] [--frames]
//
// speed 1 is real time; 0.25 plays it four times slower. --frames also keeps the PNG frames.
const { chromium } = require("playwright-core");
const { execFileSync } = require("child_process");
const fs = require("fs"), path = require("path"), os = require("os");

(async () => {
  const [out, name, query, w, h, scale, from, to, speed, fpsArg] = process.argv.slice(2);
  const keep = process.argv.includes("--frames");
  const fps = +(fpsArg && !fpsArg.startsWith("--") ? fpsArg : 12);
  const chrome = process.env.CHROME || "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome";
  const browser = await chromium.launch({ executablePath: chrome });
  const page = await browser.newPage({ viewport: { width: +w, height: +h }, deviceScaleFactor: +scale });
  await page.goto("file://" + path.join(__dirname, "mockups.html") + "?" + query);
  await page.evaluate(() => document.fonts.ready);
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), "lit-"));
  const step = (1000 / fps) * +speed;
  let i = 0;
  for (let t = +from; t <= +to; t += step, i++) {
    await page.evaluate(x => window.renderAt(x), t);
    await page.screenshot({ path: path.join(tmp, String(i).padStart(4, "0") + ".png") });
  }
  await browser.close();
  fs.mkdirSync(out, { recursive: true });
  const ff = (...a) => execFileSync("ffmpeg", ["-loglevel", "error", "-y", "-framerate", String(fps), "-i", path.join(tmp, "%04d.png"), ...a]);
  ff("-vf", "split[a][b];[a]palettegen=max_colors=200:stats_mode=diff[p];[b][p]paletteuse=dither=bayer:bayer_scale=3", path.join(out, name + ".gif"));
  ff("-pix_fmt", "yuv420p", "-vf", "scale=trunc(iw/2)*2:trunc(ih/2)*2", path.join(out, name + ".mp4"));
  if (keep) fs.renameSync(tmp, path.join(out, name + "-frames")); else fs.rmSync(tmp, { recursive: true });
  console.log(path.join(out, name + ".gif"), i, "frames");
})().catch(e => { console.error(e); process.exit(1); });
