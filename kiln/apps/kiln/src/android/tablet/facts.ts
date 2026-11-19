// What this browser says about the device it runs on, for identifyBrowser. Browser only.
import type { BrowserFacts } from '@glassworks/generic-android-installer';

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

export async function readFacts(): Promise<BrowserFacts> {
  const data = (navigator as Navigator & { userAgentData?: HighEntropy }).userAgentData;
  const h = await data?.getHighEntropyValues(['platform', 'model', 'platformVersion', 'bitness']).catch(() => undefined);
  const hints = h && { platform: String(h.platform ?? ''), model: String(h.model ?? ''), platformVersion: String(h.platformVersion ?? ''), bitness: String(h.bitness ?? '') };
  const g = gpu();
  return {
    userAgent: navigator.userAgent,
    platform: navigator.platform,
    maxTouchPoints: navigator.maxTouchPoints,
    screen: { width: screen.width, height: screen.height, dpr: devicePixelRatio },
    ...(g ? { gpu: g } : {}),
    ...(hints ? { hints } : {}),
  };
}
