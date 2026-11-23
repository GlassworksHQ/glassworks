// A download that resumes (#347, #621): a dropped connection carries on with Range where it stopped, so nothing is
// read twice, and so does one that goes silent: no answer or no bytes for STALL_MS counts as dropped. A 429 (Refract's
// rate limit) waits as long as its Retry-After asks, up to a minute, then tries again. When the proxy can't serve the
// file (a network error that keeps coming back, a 5xx, a wrong size), it fails with a ProxyError, which Kiln shows as
// its own bug, with Try again and Report this: there is no download-and-select (#579).
//
// Every large file Kiln downloads comes through Refract (kiln/workers/refract, refract.glassworks.dev): our Cloudflare
// Worker, which streams allowlisted GitHub release files with CORS and Range, and stores nothing. Kiln checks each
// file's SHA-256 itself.
import { ProxyError } from './device';

/** How long a download may go without a byte before it fails (Assets) or resumes (resumableBody): the stall watchdog's default. */
export const STALL_MS = 30_000;

/** Where Refract answers. A build points Kiln elsewhere with NEXT_PUBLIC_KILN_PROXY (a local `wrangler dev`). */
export const REFRACT = 'https://refract.glassworks.dev';

/** A Glassworks release file on Refract: `<tag>/<name>` under its `glassworks` prefix (releases.json). */
export const releaseFileUrl = (base: string, tag: string, name: string) =>
  `${base.replace(/\/+$/, '')}/glassworks/${encodeURIComponent(tag)}/${encodeURIComponent(name)}`;

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
  /** No answer, or no bytes, for this long counts as a dropped connection; default kiln-core's STALL_MS. */
  stallMs?: number;
}

/**
 * The file at `url`, `size` bytes, as a stream that resumes with Range. Shaped like a Blob for an image loader. With
 * `size` undefined (a file nothing pins, such as a release APK), the first answer's Content-Length is the size, and
 * `onLength` hears it.
 */
export function resumableBody<S extends number | undefined>(url: string, size: S, o: ResumeOptions & { onLength?: (n: number) => void } = {}): { size: S; stream(): ReadableStream<Uint8Array> } {
  const f = o.fetch ?? fetch.bind(globalThis);
  const sleep = o.sleep ?? ((ms: number) => new Promise<void>((r) => setTimeout(r, ms)));
  const retries = o.retries ?? 5;
  const stallMs = o.stallMs ?? STALL_MS;
  /**
   * `p`, or a ProxyError once it has been silent for stallMs: the proxy (or the network) stopped sending. `onSilent`
   * lets go of what was waiting (the attempt's request), so a retry never leaves an earlier one open.
   */
  const unlessSilent = <T>(p: Promise<T>, onSilent?: () => void): Promise<T> => {
    let timer: ReturnType<typeof setTimeout> | undefined;
    const silent = new Promise<never>((_, reject) => {
      timer = setTimeout(() => { onSilent?.(); reject(new ProxyError(`the download proxy sent nothing for ${Math.ceil(stallMs / 1000)} s`)); }, stallMs);
    });
    return Promise.race([p, silent]).finally(() => clearTimeout(timer));
  };
  return {
    size,
    stream() {
      let got = 0, tries = 0;
      let total: number | undefined = size;
      let reader: ReadableStreamDefaultReader<Uint8Array> | undefined;
      /** cancelled by its reader: a read it cut short isn't a dropped connection, and nothing is fetched again */
      let cancelled = false;
      const open = async () => {
        let r: Response;
        const attempt = new AbortController();
        try {
          r = await unlessSilent(f(url, { headers: got ? { Range: `bytes=${got}-` } : {}, cache: 'no-store', signal: attempt.signal }), () => attempt.abort());
        } catch (e) {
          if (e instanceof ProxyError) throw e;
          throw new ProxyError(`the download proxy can't be reached (${(e as Error).message})`);
        }
        if (r.status === 429 || (r.status === 503 && r.headers.has('retry-after'))) {
          const ms = retryAfterMs(r.headers.get('retry-after'));
          if (ms !== undefined && ms > MAX_RETRY_AFTER_MS) throw new Refused(`the download proxy is busy for ${Math.ceil(ms / 1000)} s (${r.status})`);
          throw new Busy(`the download proxy is busy (${r.status})`, ms ?? 0);
        }
        // an error answer's body is read under the same watchdog: one that stalls is a silent attempt, let go and retried
        const why = () => unlessSilent(reason(r), () => attempt.abort());
        if (r.status >= 500) throw new Refused(`the download proxy answered ${r.status}${await why()}`);
        if (got === 0) {
          if (r.status !== 200) throw new Refused(`the download proxy answered ${r.status}${await why()}`);
          const len = Number(r.headers.get('content-length') ?? NaN);
          if (Number.isFinite(len) && total !== undefined && len !== total) throw new Refused(`the download proxy offers ${len} bytes, not the file's ${total}`);
          if (total === undefined && Number.isSafeInteger(len) && len >= 0) { total = len; o.onLength?.(len); }
        } else {
          const m = /^bytes (\d+)-\d+\/(\d+)$/.exec(r.headers.get('content-range') ?? '');
          if (r.status !== 206 || !m || Number(m[1]) !== got || (total !== undefined && Number(m[2]) !== total)) throw new Refused(`the download proxy can't resume at byte ${got} (${r.status})`);
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
              const { done, value } = await unlessSilent(reader!.read());
              if (done) {
                if (total === undefined || got === total) { c.close(); return; }
                throw new ProxyError(`the connection closed at byte ${got} of ${total}`);
              }
              got += value.length;
              if (total !== undefined && got > total) throw new Refused(`the download proxy sent more than the file's ${total} bytes`);
              c.enqueue(value);
              return;
            } catch (e) {
              void reader?.cancel().catch(() => {}); // a silent one is let go: the next try opens a new one
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

/**
 * The whole file at `url`, through resumableBody: resumed where a connection dropped, a 429 waited out. `size` when
 * something pins it; else the first answer's Content-Length. `onProgress` hears each chunk.
 */
export async function downloadWhole(url: string, o: ResumeOptions & { size?: number; onProgress?: (got: number, size?: number) => void } = {}): Promise<Uint8Array> {
  let size = o.size;
  const reader = resumableBody(url, o.size, { ...o, onLength: (n) => { size = n; } }).stream().getReader();
  const parts: Uint8Array[] = [];
  let got = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    parts.push(value);
    got += value.length;
    o.onProgress?.(got, size);
  }
  const out = new Uint8Array(got);
  let at = 0;
  for (const p of parts) { out.set(p, at); at += p.length; }
  return out;
}
