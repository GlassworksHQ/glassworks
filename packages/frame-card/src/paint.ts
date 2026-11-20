// The paint of the drawn device: its front glass, a mirror's silvering and a dock's contacts. It depicts hardware, not
// the brand, so it is the one place in this package with literal colours (test/no-hex.test.ts). A
// frame's side colours come from its frame.json.
export const PAINT = {
  /* glassworks.dev DeviceModel.module.css */
  glass: '#07080b',
  mirrorA: '#3d4048',
  mirrorB: '#1a1c21',
  mirrorC: '#31343b',
  /* a dock's metal contacts */
  pin: '#8d9097',
} as const;
