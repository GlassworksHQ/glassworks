import { registerHooks } from "node:module";
import { defineConfig, globalIgnores } from "eslint/config";

// typescript-eslint needs TypeScript's JavaScript API, which TypeScript 7 (the repo root's) doesn't
// ship. The site has TypeScript 6's as `typescript` in site/node_modules (the npm alias
// @typescript/typescript6 recommends), but eslint-config-next is hoisted to the root and would
// find 7 there, so its `typescript` is pointed at the site's. Only ESLint sees this; tsc and
// next build keep the root's TypeScript 7.
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
  ]),
]);

export default eslintConfig;
