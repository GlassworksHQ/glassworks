// The system image straight from Refract (#347, kiln/workers/refract: our Cloudflare Worker that
// streams allowlisted release files with CORS for Kiln and Range, and stores nothing; maintainer,
// 2026-11-15) into the image loader, as one stream that resumes: kiln-core's resumableBody.

/** Where Refract answers, and the image's id in its allowlist. The tests point it at a fake. */
export const IMAGE_PROXY = {
  base: 'https://refract.glassworks.dev',
  systemImg: 'glass-panes/mirror-android-3/system.img',
} as const;

/** Kiln's route couldn't serve a download (kiln-core's, so the engine knows it), and the resuming download. */
export { MAX_RETRY_AFTER_MS, ProxyError, resumableBody, retryAfterMs, type ResumeOptions } from '@glassworks/kiln-core';
