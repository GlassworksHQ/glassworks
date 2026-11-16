// The setup page the panel serves at /setup (#34): a small build of Kiln's setup steps, written into
// core's assets with fixed names. CI rebuilds it and fails when the committed files differ
// (`npm run build:lan -w @glassworks/kiln-app`, then `git diff --exit-code core/src/main/assets/setup`).
import { fileURLToPath } from 'node:url';
import { defineConfig } from 'vite';

const here = fileURLToPath(new URL('.', import.meta.url));
const out = fileURLToPath(new URL('../../../../core/src/main/assets/setup', import.meta.url));

export default defineConfig({
  root: here,
  base: '/setup/',
  logLevel: 'warn',
  build: {
    outDir: out,
    emptyOutDir: true,
    target: 'es2022',
    modulePreload: false,
    assetsInlineLimit: 0,
    reportCompressedSize: false,
    rolldownOptions: {
      // React's and Radix's licence headers stay in the file the APK ships (THIRD-PARTY-NOTICES.md lists them too)
      output: { entryFileNames: 'setup.js', chunkFileNames: 'setup-[name].js', assetFileNames: 'setup.[ext]', comments: { legal: true } },
      // Radix marks its modules "use client" for Next; a plain bundle has no server side to keep apart
      onwarn(w, warn) { if (w.code !== 'MODULE_LEVEL_DIRECTIVE') warn(w); },
    },
  },
});
