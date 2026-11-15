import { registerHooks } from "node:module";
import { defineConfig, globalIgnores } from "eslint/config";

// typescript-eslint needs TypeScript's JavaScript API, which TypeScript 7 (the repo root's) doesn't
// ship. The site depends on typescript@6, which npm nests in site/node_modules because the root has
// 7, but eslint-config-next is hoisted to the root and would find 7 there, so its `typescript` is
// pointed at the site's. Not the @typescript/typescript6 alias: its own dependency hoists to the root
// and takes the root's `tsc` bin, which turned every workspace's tsc into 6. registerHooks needs
// Node 22.15 (the root's engines).
const typescript6 = import.meta.resolve("typescript");
registerHooks({
  resolve: (specifier, context, next) =>
    specifier === "typescript" ? { url: typescript6, format: "commonjs", shortCircuit: true } : next(specifier, context),
});
const { default: nextVitals } = await import("eslint-config-next/core-web-vitals");
const { default: nextTs } = await import("eslint-config-next/typescript");

const eslintConfig = defineConfig([
  ...nextVitals,
  ...nextTs,
  // Override default ignores of eslint-config-next.
  globalIgnores([
    // Default ignores of eslint-config-next:
    ".next/**",
    "out/**",
    "build/**",
    "next-env.d.ts",
    // the libraries' API reference: Dokka's own pages and scripts, unpacked by scripts/api-docs.mjs
    "public/docs/libraries/**",
    ".api-docs/**",
  ]),
]);

export default eslintConfig;
