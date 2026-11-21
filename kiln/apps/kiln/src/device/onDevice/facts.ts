// What this browser says about the device it runs on, for the frame's UsbOnDevice `identify`. Browser only.
import type { BrowserFacts } from '@glassworks/kiln-core';

type HighEntropy = { getHighEntropyValues(hints: string[]): Promise<Record<string, unknown>> };

function gpu(): string | undefined {
  try {
    const gl = document.createElement('canvas').getContext('webgl');
    if (!gl) return undefined;
    const ext = gl.getExtension('WEBGL_debug_renderer_info');
    return String(gl.getParameter(ext ? ext.UNMASKED_RENDERER_WEBGL : gl.RENDERER));
  } catch {
    return undefined;
  }
}

/**
 * Whether this browser lets a WebAssembly memory reach 4 GiB: only a 64-bit one does (the frame's identify says why).
 * Allocates one 64 KiB page, which the page drops at once.
 */
function wasm4g(): boolean | undefined {
  if (typeof WebAssembly === 'undefined') return undefined;
  try {
    new WebAssembly.Memory({ initial: 1, maximum: 65536 });
    return true;
  } catch (e) {
    return e instanceof RangeError ? false : undefined;
  }
}

export async function readFacts(): Promise<BrowserFacts> {
  const data = (navigator as Navigator & { userAgentData?: HighEntropy }).userAgentData;
  const h = await data?.getHighEntropyValues(['platform', 'model', 'platformVersion', 'bitness']).catch(() => undefined);
  const hints = h && { platform: String(h.platform ?? ''), model: String(h.model ?? ''), platformVersion: String(h.platformVersion ?? ''), bitness: String(h.bitness ?? '') };
  const g = gpu();
  const w = wasm4g();
  return {
    userAgent: navigator.userAgent,
    platform: navigator.platform,
    maxTouchPoints: navigator.maxTouchPoints,
    screen: { width: screen.width, height: screen.height, dpr: devicePixelRatio },
    ...(g ? { gpu: g } : {}),
    ...(hints ? { hints } : {}),
    ...(w === undefined ? {} : { wasm4g: w }),
  };
}
