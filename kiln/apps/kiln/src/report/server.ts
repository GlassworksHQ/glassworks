// The server half of /api/report (route.ts): checks a report, rebuilds its issue from the whitelisted fields
// (./report.ts) and opens it on GitHub as Kiln's bot. Node only: it signs with node:crypto.
//
// Auth, the first that's set:
// - a GitHub App (REPORT_APP_ID, REPORT_APP_PRIVATE_KEY, REPORT_APP_INSTALLATION_ID): a JWT for the app, then an
//   installation token for this repo alone with Issues: write, kept until shortly before it expires. Issues show as the
//   app's bot.
// - REPORT_GITHUB_TOKEN: a fine-grained token with Issues: write on this repo only.
// Neither: 503, and the page offers GitHub's own form instead.
//
// Abuse: a honeypot field; a signed time token from when the form opened (no sooner than MIN_FILL_MS, no older than
// MAX_AGE_MS), which holds on serverless because it needs no state; and a per-address limit, which is per instance (in
// memory: a cold start or another instance starts its own count), on top of a per-instance cap. Addresses are kept only
// as a hash, in memory, for an hour. Nothing logs a report, an address or a token: only GitHub's status on a failure.
import { createHash, createHmac, createSign, timingSafeEqual } from 'node:crypto';
import { clean, fieldsOf, issueOf, LABEL, MAX_BYTES, problems, REPO } from './report';

export interface ReportEnv {
  REPORT_APP_ID?: string;
  REPORT_APP_PRIVATE_KEY?: string;
  REPORT_APP_INSTALLATION_ID?: string;
  REPORT_GITHUB_TOKEN?: string;
}

export type Credentials =
  | { kind: 'app'; appId: string; key: string; installationId: string }
  | { kind: 'token'; token: string };

/** The honeypot's name: a field people never see, which only a bot fills in. Not one browsers autofill. */
export const HONEYPOT = 'kiln_leave_empty';
export const MIN_FILL_MS = 4_000;
export const MAX_AGE_MS = 2 * 60 * 60_000;
/** Reports from one address in an hour, and from every address on one instance. */
export const PER_ADDRESS = 3;
export const PER_INSTANCE = 30;
const HOUR = 60 * 60_000;

const API = 'https://api.github.com';
const HEADERS = { accept: 'application/vnd.github+json', 'x-github-api-version': '2022-11-28', 'user-agent': 'glassworks-kiln-report' };

/** The credentials in `env`, the app first; undefined when neither is complete. */
export function credentials(env: ReportEnv): Credentials | undefined {
  const { REPORT_APP_ID: appId, REPORT_APP_PRIVATE_KEY: key, REPORT_APP_INSTALLATION_ID: installationId, REPORT_GITHUB_TOKEN: token } = env;
  // Vercel's dashboard keeps a pasted PEM's newlines; a one-line value with \n in it works too
  if (appId?.trim() && key?.trim() && installationId?.trim()) return { kind: 'app', appId: appId.trim(), key: key.replace(/\\n/g, '\n').trim(), installationId: installationId.trim() };
  if (token?.trim()) return { kind: 'token', token: token.trim() };
  return undefined;
}

const b64url = (b: Buffer | string) => Buffer.from(b).toString('base64url');

/** The app's JWT (RS256), valid from a minute ago (clock drift) for nine minutes; GitHub allows ten. */
export function appJwt(appId: string, key: string, nowMs: number): string {
  const now = Math.floor(nowMs / 1000);
  const head = b64url(JSON.stringify({ alg: 'RS256', typ: 'JWT' }));
  const claims = b64url(JSON.stringify({ iat: now - 60, exp: now + 540, iss: appId }));
  const sig = createSign('RSA-SHA256').update(`${head}.${claims}`).sign(key);
  return `${head}.${claims}.${b64url(sig)}`;
}

/** An installation token, kept per instance until five minutes before GitHub says it expires. */
let kept: { token: string; until: number; for: string } | undefined;
export const forgetToken = () => { kept = undefined; };

async function installationToken(c: Extract<Credentials, { kind: 'app' }>, get: typeof fetch, now: number): Promise<string> {
  if (kept && kept.for === `${c.appId}/${c.installationId}` && kept.until > now) return kept.token;
  const res = await get(`${API}/app/installations/${encodeURIComponent(c.installationId)}/access_tokens`, {
    method: 'POST',
    headers: { ...HEADERS, authorization: `Bearer ${appJwt(c.appId, c.key, now)}`, 'content-type': 'application/json' },
    // this repo alone, issues alone, whatever else the installation may have
    body: JSON.stringify({ repositories: [REPO.split('/')[1]], permissions: { issues: 'write' } }),
  });
  if (!res.ok) throw new GitHubError('installation token', res.status);
  const j = (await res.json()) as { token?: unknown; expires_at?: unknown };
  if (typeof j.token !== 'string') throw new GitHubError('installation token', 502);
  const expires = typeof j.expires_at === 'string' ? Date.parse(j.expires_at) : NaN;
  kept = { token: j.token, until: (Number.isFinite(expires) ? expires : now + HOUR) - 5 * 60_000, for: `${c.appId}/${c.installationId}` };
  return j.token;
}

export class GitHubError extends Error {
  constructor(readonly what: string, readonly status: number) { super(`${what}: GitHub answered ${status}`); }
}

/** The key the time tokens are signed with: from the credentials, so there is no other secret to set. */
const tokenKey = (c: Credentials) => createHash('sha256').update(`kiln-report-time\0${c.kind === 'app' ? c.key : c.token}`).digest();

/** A time token for a form opened at `now`: the time, and its HMAC. */
export function timeToken(c: Credentials, now: number): string {
  const t = String(now);
  return `${t}.${createHmac('sha256', tokenKey(c)).update(t).digest('base64url')}`;
}

/** Why a time token isn't good now, or undefined when it is. */
export function tokenProblem(c: Credentials, token: unknown, now: number): 'missing' | 'bad' | 'too-fast' | 'expired' | undefined {
  if (typeof token !== 'string' || !token) return 'missing';
  const [t, mac, extra] = token.split('.');
  if (!t || !mac || extra !== undefined || !/^\d{1,16}$/.test(t)) return 'bad';
  const want = createHmac('sha256', tokenKey(c)).update(t).digest();
  const got = Buffer.from(mac, 'base64url');
  if (got.length !== want.length || !timingSafeEqual(got, want)) return 'bad';
  const age = now - Number(t);
  if (age < MIN_FILL_MS) return 'too-fast';
  if (age > MAX_AGE_MS) return 'expired';
  return undefined;
}

/** Reports in the last hour, per address (by hash) and for the instance. In memory: see the header. */
export class RateLimit {
  private by = new Map<string, number[]>();
  private all: number[] = [];
  constructor(private perAddress = PER_ADDRESS, private perInstance = PER_INSTANCE) {}

  /** Takes a slot for `address` at `now`, or says how many seconds until one is free. */
  take(address: string, now: number): { ok: true } | { ok: false; retryAfter: number } {
    const key = createHash('sha256').update(address).digest('base64url');
    const recent = (xs: number[]) => xs.filter((t) => now - t < HOUR);
    this.all = recent(this.all);
    const mine = recent(this.by.get(key) ?? []);
    const full = mine.length >= this.perAddress ? mine : this.all.length >= this.perInstance ? this.all : undefined;
    if (full) return { ok: false, retryAfter: Math.max(1, Math.ceil((full[0]! + HOUR - now) / 1000)) };
    mine.push(now);
    this.all.push(now);
    this.by.set(key, mine);
    this.sweep(now);
    return { ok: true };
  }

  /** Forgets every address with nothing in the last hour. Each report runs it, and route.ts every few minutes. */
  sweep(now: number): void {
    for (const [k, v] of this.by) if (!v.some((t) => now - t < HOUR)) this.by.delete(k);
    this.all = this.all.filter((t) => now - t < HOUR);
  }

  /** How many addresses it holds now (tests). */
  get size(): number { return this.by.size; }
}

/** The address a request came from, as Vercel's proxy says it. Used only for the rate limit. */
export function addressOf(req: Request): string {
  const fwd = req.headers.get('x-forwarded-for')?.split(',')[0]?.trim();
  return fwd || req.headers.get('x-real-ip')?.trim() || 'unknown';
}

export interface Deps {
  env: ReportEnv;
  fetch: typeof fetch;
  now: () => number;
  limit: RateLimit;
  /** where a failure's one line goes: GitHub's status, nothing from the report */
  log?: (line: string) => void;
}

const json = (status: number, body: unknown, headers: Record<string, string> = {}) =>
  new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json', 'cache-control': 'no-store', ...headers } });

/** GET: whether reports can be sent from here, and a time token for the form just opened. */
export function handleGet(d: Deps): Response {
  const c = credentials(d.env);
  if (!c) return json(503, { error: 'not-configured' });
  return json(200, { token: timeToken(c, d.now()) });
}

/** POST { fields, token, [HONEYPOT] }: the report, checked, as a new issue. 201 { url, number }. */
export async function handlePost(req: Request, d: Deps): Promise<Response> {
  const c = credentials(d.env);
  if (!c) return json(503, { error: 'not-configured' });
  if (!(req.headers.get('content-type') ?? '').toLowerCase().startsWith('application/json')) return json(415, { error: 'not-json' });
  const declared = Number(req.headers.get('content-length') ?? '0');
  if (declared > MAX_BYTES) return json(413, { error: 'too-big' });
  const text = await readCapped(req, MAX_BYTES);
  if (text === undefined) return json(413, { error: 'too-big' });
  let raw: unknown;
  try { raw = JSON.parse(text); } catch { return json(400, { error: 'not-json' }); }
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return json(400, { error: 'not-json' });
  const r = raw as Record<string, unknown>;
  if (r[HONEYPOT] !== undefined && r[HONEYPOT] !== '') return json(400, { error: 'refused' });
  const now = d.now();
  const late = tokenProblem(c, r.token, now);
  if (late) return json(400, { error: late });
  const given = fieldsOf(r.fields);
  if (!given) return json(400, { error: 'invalid' });
  const fields = clean(given);
  const wrong = problems(fields);
  if (Object.keys(wrong).length) return json(422, { error: 'invalid', problems: wrong });
  const slot = d.limit.take(addressOf(req), now);
  if (!slot.ok) return json(429, { error: 'rate-limited' }, { 'retry-after': String(slot.retryAfter) });
  const { title, body } = issueOf(fields);
  try {
    const auth = c.kind === 'app' ? await installationToken(c, d.fetch, now) : c.token;
    const res = await d.fetch(`${API}/repos/${REPO}/issues`, {
      method: 'POST',
      headers: { ...HEADERS, authorization: `Bearer ${auth}`, 'content-type': 'application/json' },
      body: JSON.stringify({ title, body, labels: [LABEL] }),
    });
    if (!res.ok) {
      // a token GitHub no longer takes: the next report mints a new one
      if (res.status === 401 && c.kind === 'app') forgetToken();
      throw new GitHubError('new issue', res.status);
    }
    const j = (await res.json()) as { html_url?: unknown; number?: unknown };
    const url = typeof j.html_url === 'string' ? j.html_url : '';
    if (!new RegExp(`^https://github\\.com/${REPO}/issues/\\d+$`).test(url) || typeof j.number !== 'number') throw new GitHubError('new issue', 502);
    return json(201, { url, number: j.number });
  } catch (e) {
    (d.log ?? console.error)(`report: ${e instanceof GitHubError ? e.message : 'GitHub unreachable'}`);
    return json(502, { error: 'github' });
  }
}

/** The request's body as text, or undefined past `max` bytes (whatever its content-length said). */
async function readCapped(req: Request, max: number): Promise<string | undefined> {
  if (!req.body) return '';
  const reader = req.body.getReader();
  const parts: Uint8Array[] = [];
  let n = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    n += value.byteLength;
    if (n > max) { await reader.cancel(); return undefined; }
    parts.push(value);
  }
  const all = new Uint8Array(n);
  let at = 0;
  for (const p of parts) { all.set(p, at); at += p.byteLength; }
  return new TextDecoder().decode(all);
}
