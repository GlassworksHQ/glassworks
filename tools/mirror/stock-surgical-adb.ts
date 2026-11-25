#!/usr/bin/env -S npx -y tsx@4.23.15
// Phase 2 guided CLI for reversible surgical ADB + optional full stock system/boot backup on an
// untouched stock 1.2x+ lululemon Studio Mirror.
// Run: npx --prefer-offline tsx@4.23.15 tools/mirror/stock-surgical-adb.ts --help
import { surgicalAdbCli, SurgicalAdbUsageError } from './src/surgical-adb-cli';

surgicalAdbCli(process.argv.slice(2)).then(
  (code) => { process.exitCode = code; },
  (e: unknown) => {
    const msg = e instanceof Error ? e.message : String(e);
    console.error(msg);
    process.exitCode = e instanceof SurgicalAdbUsageError ? 2 : 1;
  },
);
