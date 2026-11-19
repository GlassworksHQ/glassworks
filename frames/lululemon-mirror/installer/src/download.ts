// The system image straight from Refract (#347, kiln/workers/refract: our Cloudflare Worker that
// streams allowlisted release files with CORS for Kiln and Range, and stores nothing; maintainer,
// 2026-11-15) into the image loader, as one stream.
// A dropped connection resumes with Range where it stopped, so nothing is read twice. A 429 (Refract's
// rate limit) waits as long as its Retry-After asks, up to a minute, then tries again. When the proxy
// can't serve the file (a network error that keeps coming back, a 5xx, a wrong size), Kiln shows it
// as its own bug, with Try again and Report this: there is no download-and-select (#579).

/** Where Refract answers, and the image's id in its allowlist. The tests point it at a fake. */
export const IMAGE_PROXY = {
  base: 'https://refract.glassworks.dev',
  systemImg: 'glass-panes/mirror-android-2/system.img',
} as const;

/** The proxy couldn't give us the file: Kiln's bug, shown with Try again and Report this. */
export class ProxyError extends Error {}
/** An answer that retrying won't change (a refusal, a wrong size): stop at once. */
class Refused extends ProxyError {}
/** 429, or 503 with Retry-After: Refract's rate limit. Wait as long as it asks, then try again. */
class Busy extends ProxyError { constructor(m: string, readonly ms: number) { super(m); } }

/** The longest Retry-After Kiln waits out; a longer one stops the download, to try again later. */
export const MAX_RETRY_AFTER_MS = 60_000;

/** Retry-After as milliseconds: delta-seconds or an HTTP date (RFC 9110 10.2.3). */
export function retryAfterMs(v: string | null, now = Date.now()): number | undefined {
  if (!v) return undefined;
  if (/^\d+$/.test(v.trim())) return Number(v.trim()) * 1000;
  const t = Date.parse(v);
  return Number.isNaN(t) ? undefined : Math.max(0, t - now);
}

export interface ResumeOptions {
  fetch?: typeof fetch;
  /** Reconnects after a dropped connection before giving up; default 5. */
  retries?: number;
  sleep?: (ms: number) => Promise<void>;
}

/** The file at `url`, `size` bytes, as a stream that resumes with Range. Shaped like a Blob for loadSystem. */
export function resumableBody(url: string, size: number, o: ResumeOptions = {}): { size: number; stream(): ReadableStream<Uint8Array> } {
  const f = o.fetch ?? fetch.bind(globalThis);
  const sleep = o.sleep ?? ((ms: number) => new Promise<void>((r) => setTimeout(r, ms)));
  const retries = o.retries ?? 5;
  return {
    size,
    stream() {
      let got = 0, tries = 0;
      let reader: ReadableStreamDefaultReader<Uint8Array> | undefined;
      /** cancelled by its reader: a read it cut short isn't a dropped connection, and nothing is fetched again */
      let cancelled = false;
      const open = async () => {
        let r: Response;
        try {
          r = await f(url, { headers: got ? { Range: `bytes=${got}-` } : {}, cache: 'no-store' });
        } catch (e) {
          throw new ProxyError(`the download proxy can't be reached (${(e as Error).message})`);
        }
        if (r.status === 429 || (r.status === 503 && r.headers.has('retry-after'))) {
          const ms = retryAfterMs(r.headers.get('retry-after'));
          if (ms !== undefined && ms > MAX_RETRY_AFTER_MS) throw new Refused(`the download proxy is busy for ${Math.ceil(ms / 1000)} s (${r.status})`);
          throw new Busy(`the download proxy is busy (${r.status})`, ms ?? 0);
        }
        if (r.status >= 500) throw new Refused(`the download proxy answered ${r.status}${await reason(r)}`);
        if (got === 0) {
          if (r.status !== 200) throw new Refused(`the download proxy answered ${r.status}${await reason(r)}`);
          const len = Number(r.headers.get('content-length') ?? NaN);
          if (Number.isFinite(len) && len !== size) throw new Refused(`the download proxy offers ${len} bytes, not the image's ${size}`);
        } else {
          const m = /^bytes (\d+)-\d+\/(\d+)$/.exec(r.headers.get('content-range') ?? '');
          if (r.status !== 206 || !m || Number(m[1]) !== got || Number(m[2]) !== size) throw new Refused(`the download proxy can't resume at byte ${got} (${r.status})`);
        }
        if (!r.body) throw new Refused('the download proxy sent no body');
        reader = r.body.getReader();
      };
      return new ReadableStream<Uint8Array>({
        async pull(c) {
          for (;;) {
            if (cancelled) return;
            try {
              if (!reader) await open();
              const { done, value } = await reader!.read();
              if (done) {
                if (got === size) { c.close(); return; }
                throw new ProxyError(`the connection closed at byte ${got} of ${size}`);
              }
              got += value.length;
              if (got > size) throw new Refused(`the download proxy sent more than the image's ${size} bytes`);
              c.enqueue(value);
              return;
            } catch (e) {
              reader = undefined;
              if (cancelled) return;
              if (e instanceof Refused || ++tries > retries) { c.error(e instanceof ProxyError ? e : new ProxyError((e as Error).message)); return; }
              await sleep(e instanceof Busy && e.ms ? e.ms : 1000 * tries);
            }
          }
        },
        cancel() { cancelled = true; void reader?.cancel(); },
      });
    },
  };
}

async function reason(r: Response): Promise<string> {
  try { const t = (await r.text()).trim().split('\n')[0]?.slice(0, 200); return t ? `: ${t}` : ''; } catch { return ''; }
}
