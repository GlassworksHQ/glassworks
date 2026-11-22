// stock-probe.ts: guided 6-step probe for a stock lululemon Studio Mirror (MSM8916).
//
// Touches:
//   - Steps 1-2: READ-ONLY on the device (ADB key check + fastboot oem device-info & getvar).
//   - Step 3: writes a local sparse userdata image only (no device writes).
//   - Step 4: WRITES ONLY userdata (wipes stock app/onboarding data; stock system, boot,
//     recovery, modem, and persist are NEVER touched). Prompts [y/N] before every step.
//   - Steps 5-6: READ-ONLY over ADB on untouched stock system; writes a redacted Markdown
//     progress report (report.md) after every step so you can share progress as you go,
//     while raw dumps stay locally under <out>/raw/ (outside git, never committed).
//
//   npx -y tsx@4.23.15 tools/mirror/stock-probe.ts --dry-run
//   npx -y tsx@4.23.15 tools/mirror/stock-probe.ts --read-only
//   npx -y tsx@4.23.15 tools/mirror/stock-probe.ts
//   npx -y tsx@4.23.15 tools/mirror/stock-probe.ts --extract-only
import { StockProbeUsageError, stockProbeCli } from './src/stock-probe-cli';

stockProbeCli(process.argv.slice(2)).then((code) => {
  process.exitCode = code;
}, (e: unknown) => {
  console.error(`\nstock-probe: ${(e as Error).message}`);
  process.exitCode = e instanceof StockProbeUsageError ? 2 : 1;
});
