// The headless Chromium the harness's tools use (capture.mjs, island-check.mjs, island-probe.mjs): CHROME=<path> if
// set, else Playwright's own Chromium, else the installed Google Chrome. One choice, so every tool runs wherever the
// capture does.
//
// Touches: launches a browser with a throwaway profile; nothing else.
import { chromium } from 'playwright-core';

export async function launch() {
  if (process.env.CHROME) return chromium.launch({ headless: true, executablePath: process.env.CHROME });
  try {
    return await chromium.launch({ headless: true });
  } catch {
    return chromium.launch({ headless: true, channel: 'chrome' });
  }
}
