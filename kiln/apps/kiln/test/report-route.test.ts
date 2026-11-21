// /api/report (src/report/server.ts, report.ts): a device report checked, rebuilt from whitelisted fields, scrubbed of
// what's private, escaped for GitHub's markdown, and opened as an issue by the app's bot or a token. 503 with neither.
import { generateKeyPairSync, createVerify } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { clean, escapeMarkdown, FLOWS, FORM_URL_MAX, githubForm, githubUrl, issueOf, LABEL, MAX_BYTES, OS_KINDS, problems, scrub, type ReportFields } from '../src/report/report';
import { appJwt, credentials, forgetToken, handleGet, handlePost, HONEYPOT, MIN_FILL_MS, RateLimit, timeToken, tokenProblem, type Deps, type ReportEnv } from '../src/report/server';

const { privateKey, publicKey } = generateKeyPairSync('rsa', { modulusLength: 2048 });
const PEM = privateKey.export({ type: 'pkcs1', format: 'pem' }).toString();
const APP: ReportEnv = { REPORT_APP_ID: '123', REPORT_APP_PRIVATE_KEY: PEM, REPORT_APP_INSTALLATION_ID: '456' };
const TOKEN: ReportEnv = { REPORT_GITHUB_TOKEN: 'github_pat_test' };

const FIELDS: ReportFields = {
  model: 'Lenovo Tab M10, TB-X605F', android: '10 (API 29)', osKind: 'stock', customOs: '', abi: 'arm64-v8a,armeabi-v7a',
  flow: 'usb', step: 'done', worked: 'The dashboard and Home Assistant adding it.', didnt: 'Nothing so far.', clarity: 'c', appVersion: '0.2.0',
};

const T0 = 1_800_000_000_000;

interface Call { url: string; init: RequestInit }
function github(o: { issue?: number; status?: number; tokenStatus?: number } = {}) {
  const calls: Call[] = [];
  const f = vi.fn(async (url: string | URL | Request, init: RequestInit = {}) => {
    calls.push({ url: String(url), init });
    if (String(url).includes('/access_tokens')) {
      return new Response(JSON.stringify({ token: 'ghs_installation', expires_at: new Date(T0 + 3_600_000).toISOString() }), { status: o.tokenStatus ?? 201 });
    }
    const n = o.issue ?? 77;
    return new Response(JSON.stringify({ html_url: `https://github.com/GlassworksHQ/glassworks/issues/${n}`, number: n }), { status: o.status ?? 201 });
  });
  return { calls, fetch: f as unknown as typeof fetch };
}

function deps(env: ReportEnv, gh = github(), now = T0 + 60_000, limit = new RateLimit()): Deps & { gh: ReturnType<typeof github>; logs: string[] } {
  const logs: string[] = [];
  return { env, fetch: gh.fetch, now: () => now, limit, gh, logs, log: (l) => logs.push(l) };
}

function post(body: unknown, headers: Record<string, string> = {}) {
  const text = typeof body === 'string' ? body : JSON.stringify(body);
  return new Request('https://kiln.example/api/report', { method: 'POST', headers: { 'content-type': 'application/json', 'x-forwarded-for': '192.0.2.10, 198.51.100.1', ...headers }, body: text });
}

const valid = (env: ReportEnv, extra: Record<string, unknown> = {}) => ({ fields: FIELDS, token: timeToken(credentials(env)!, T0), ...extra });
const sentIssue = (d: ReturnType<typeof deps>) => JSON.parse(String(d.gh.calls.find((c) => c.url.endsWith('/issues'))!.init.body)) as { title: string; body: string; labels: string[] };

beforeEach(() => forgetToken());

describe('the route', () => {
  it('opens the issue with the app\'s installation token, scoped to this repo\'s issues', async () => {
    const d = deps(APP);
    const res = await handlePost(post(valid(APP)), d);
    expect(res.status).toBe(201);
    expect(await res.json()).toEqual({ url: 'https://github.com/GlassworksHQ/glassworks/issues/77', number: 77 });
    const [mint, issue] = d.gh.calls;
    expect(mint!.url).toBe('https://api.github.com/app/installations/456/access_tokens');
    expect(JSON.parse(String(mint!.init.body))).toEqual({ repositories: ['glassworks'], permissions: { issues: 'write' } });
    const jwt = (mint!.init.headers as Record<string, string>).authorization!.replace('Bearer ', '');
    const [h, c, sig] = jwt.split('.');
    expect(createVerify('RSA-SHA256').update(`${h}.${c}`).verify(publicKey, Buffer.from(sig!, 'base64url'))).toBe(true);
    expect(JSON.parse(Buffer.from(c!, 'base64url').toString())).toMatchObject({ iss: '123' });
    expect(issue!.url).toBe('https://api.github.com/repos/GlassworksHQ/glassworks/issues');
    expect((issue!.init.headers as Record<string, string>).authorization).toBe('Bearer ghs_installation');
    expect(sentIssue(d).labels).toEqual([LABEL]);
  });

  it('keeps the installation token for the next report', async () => {
    const d = deps(APP);
    await handlePost(post(valid(APP)), d);
    await handlePost(post(valid(APP)), d);
    expect(d.gh.calls.filter((c) => c.url.includes('access_tokens'))).toHaveLength(1);
  });

  it('takes a PEM pasted as one line with \\n in it', () => {
    const c = credentials({ ...APP, REPORT_APP_PRIVATE_KEY: PEM.replace(/\n/g, '\\n') });
    expect(c?.kind === 'app' && c.key).toBe(PEM.trim());
    expect(() => appJwt('1', (c as { key: string }).key, T0)).not.toThrow();
  });

  it('with a token instead, uses it as it is', async () => {
    const d = deps(TOKEN);
    expect((await handlePost(post(valid(TOKEN)), d)).status).toBe(201);
    expect(d.gh.calls).toHaveLength(1);
    expect((d.gh.calls[0]!.init.headers as Record<string, string>).authorization).toBe('Bearer github_pat_test');
  });

  it('prefers the app when both are set, and needs all three of its values', () => {
    expect(credentials({ ...APP, ...TOKEN })?.kind).toBe('app');
    expect(credentials({ REPORT_APP_ID: '1', REPORT_APP_PRIVATE_KEY: PEM, ...TOKEN })?.kind).toBe('token');
    expect(credentials({ REPORT_APP_ID: '1', REPORT_APP_PRIVATE_KEY: PEM })).toBeUndefined();
  });

  it('answers 503 with no credentials, to GET and POST, and asks GitHub nothing', async () => {
    const d = deps({});
    expect(handleGet(d).status).toBe(503);
    const res = await handlePost(post(valid(TOKEN)), d);
    expect(res.status).toBe(503);
    expect(await res.json()).toEqual({ error: 'not-configured' });
    expect(d.gh.calls).toHaveLength(0);
  });

  it('GET gives a time token the POST then takes, not before MIN_FILL_MS', async () => {
    const early = deps(TOKEN, github(), T0);
    const { token } = (await handleGet(early).json()) as { token: string };
    expect(handleGet(early).headers.get('cache-control')).toBe('no-store');
    const c = credentials(TOKEN)!;
    expect(tokenProblem(c, token, T0 + 1000)).toBe('too-fast');
    expect(tokenProblem(c, token, T0 + MIN_FILL_MS)).toBeUndefined();
    expect(tokenProblem(c, token, T0 + 3 * 3_600_000)).toBe('expired');
    expect(tokenProblem(c, `${T0}.forged`, T0 + 60_000)).toBe('bad');
    expect(tokenProblem(credentials(APP)!, token, T0 + 60_000)).toBe('bad'); // signed with another deployment's key
    expect(tokenProblem(c, undefined, T0)).toBe('missing');
    const res = await handlePost(post({ fields: FIELDS, token }), deps(TOKEN, github(), T0 + 500));
    expect(res.status).toBe(400);
    expect(await res.json()).toEqual({ error: 'too-fast' });
  });

  it('refuses a filled honeypot without asking GitHub', async () => {
    const d = deps(TOKEN);
    const res = await handlePost(post(valid(TOKEN, { [HONEYPOT]: 'https://spam.example' })), d);
    expect(res.status).toBe(400);
    expect(d.gh.calls).toHaveLength(0);
    expect((await handlePost(post(valid(TOKEN, { [HONEYPOT]: '' })), d)).status).toBe(201);
  });

  it('refuses a body past MAX_BYTES, whatever content-length says', async () => {
    const d = deps(TOKEN);
    const big = JSON.stringify(valid(TOKEN, { pad: 'x'.repeat(MAX_BYTES) }));
    expect((await handlePost(post(big), d)).status).toBe(413);
    expect((await handlePost(post(big, { 'content-length': '10' }), d)).status).toBe(413);
    expect((await handlePost(post(valid(TOKEN), { 'content-length': String(MAX_BYTES + 1) }), d)).status).toBe(413);
    expect(d.gh.calls).toHaveLength(0);
  });

  it('refuses what isn\'t JSON, and fields that aren\'t strings', async () => {
    const d = deps(TOKEN);
    expect((await handlePost(post('not json'), d)).status).toBe(400);
    expect((await handlePost(post(valid(TOKEN), { 'content-type': 'text/plain' }), d)).status).toBe(415);
    expect((await handlePost(post(valid(TOKEN, { fields: { ...FIELDS, model: 42 } })), d)).status).toBe(400);
    expect((await handlePost(post([1, 2]), d)).status).toBe(400);
    expect(d.gh.calls).toHaveLength(0);
  });

  it('answers 422 with the problems, by field', async () => {
    const d = deps(TOKEN);
    const res = await handlePost(post(valid(TOKEN, { fields: { ...FIELDS, model: '', flow: 'fax', clarity: 'Z', worked: 'x'.repeat(4001) } })), d);
    expect(res.status).toBe(422);
    const { problems: p } = (await res.json()) as { problems: Record<string, string> };
    expect(Object.keys(p).sort()).toEqual(['clarity', 'flow', 'model', 'worked']);
    expect(d.gh.calls).toHaveLength(0);
  });

  it('builds the title and body itself: a client\'s title, body or labels are ignored', async () => {
    const d = deps(TOKEN);
    await handlePost(post(valid(TOKEN, { title: 'Pwned', body: '@everyone', labels: ['security'], fields: { ...FIELDS, title: 'x', body: 'y' } })), d);
    const sent = sentIssue(d);
    expect(sent).toEqual({ ...issueOf(clean(FIELDS)), labels: [LABEL] });
    expect(sent.title).toBe('Tested: Lenovo Tab M10, TB-X605F');
    expect(sent.body).not.toMatch(/Pwned|everyone/);
  });

  it('limits each address to a few an hour, and says when to come back', async () => {
    const limit = new RateLimit(2, 100);
    const d = deps(TOKEN, github(), T0 + 60_000, limit);
    expect((await handlePost(post(valid(TOKEN)), d)).status).toBe(201);
    expect((await handlePost(post(valid(TOKEN)), d)).status).toBe(201);
    const third = await handlePost(post(valid(TOKEN)), d);
    expect(third.status).toBe(429);
    expect(Number(third.headers.get('retry-after'))).toBeGreaterThan(3000);
    // another address is its own count
    expect((await handlePost(post(valid(TOKEN), { 'x-forwarded-for': '198.51.100.7' }), d)).status).toBe(201);
    // an hour on, the first has room again
    expect((await handlePost(post(valid(TOKEN)), deps(TOKEN, github(), T0 + 60_000 + 3_600_001, limit))).status).toBe(201);
  });

  it('forgets an address an hour after its last report, with or without another report', () => {
    const l = new RateLimit();
    l.take('192.0.2.1', T0);
    l.take('192.0.2.2', T0 + 30 * 60_000);
    l.sweep(T0 + 60 * 60_000 + 1);
    expect(l.size).toBe(1); // the first is gone, the second still counts
    l.take('192.0.2.3', T0 + 2 * 60 * 60_000);
    expect(l.size).toBe(1); // a report sweeps too
  });

  it('caps the instance across addresses', () => {
    const l = new RateLimit(10, 2);
    expect(l.take('192.0.2.1', T0).ok).toBe(true);
    expect(l.take('192.0.2.2', T0).ok).toBe(true);
    expect(l.take('192.0.2.3', T0).ok).toBe(false);
  });

  it('a GitHub failure is 502, and logs only its status', async () => {
    const d = deps(TOKEN, github({ status: 403 }));
    const res = await handlePost(post(valid(TOKEN, { fields: { ...FIELDS, worked: 'my secret words' } })), d);
    expect(res.status).toBe(502);
    expect(await res.json()).toEqual({ error: 'github' });
    expect(d.logs).toEqual(['report: new issue: GitHub answered 403']);
    const app = deps(APP, github({ tokenStatus: 401 }));
    expect((await handlePost(post(valid(APP)), app)).status).toBe(502);
    expect(app.logs).toEqual(['report: installation token: GitHub answered 401']);
  });

  it('never passes on a URL that isn\'t an issue of this repo', async () => {
    const gh = github();
    const evil = vi.fn(async () => new Response(JSON.stringify({ html_url: 'https://evil.example/x', number: 1 }), { status: 201 }));
    const d = { ...deps(TOKEN, gh), fetch: evil as unknown as typeof fetch };
    expect((await handlePost(post(valid(TOKEN)), d)).status).toBe(502);
  });

  it('logs nothing on success', async () => {
    const spy = vi.spyOn(console, 'error').mockImplementation(() => {});
    const log = vi.spyOn(console, 'log').mockImplementation(() => {});
    const d = deps(TOKEN);
    await handlePost(post(valid(TOKEN)), { ...d, log: undefined });
    expect(spy).not.toHaveBeenCalled();
    expect(log).not.toHaveBeenCalled();
    spy.mockRestore(); log.mockRestore();
  });

  it('route.ts reads only the four variables, and runs on Node', () => {
    const src = readFileSync(new URL('../src/app/api/report/route.ts', import.meta.url), 'utf8');
    expect([...src.matchAll(/process\.env\.(\w+)/g)].map((m) => m[1]).sort()).toEqual(['REPORT_APP_ID', 'REPORT_APP_INSTALLATION_ID', 'REPORT_APP_PRIVATE_KEY', 'REPORT_GITHUB_TOKEN']);
    expect(src).toMatch(/runtime = 'nodejs'/);
    expect(src).not.toMatch(/console\./);
  });
});

describe('what a report may say', () => {
  it.each([
    ['an IPv4 address', 'HA at 192.168.1.20:8123 is slow', 'HA at (removed) is slow'],
    ['an IPv6 address', 'it got fe80::1c2b:3a4d and 2001:db8:0:0:0:0:0:1', 'it got (removed) and (removed)'],
    ['a MAC', 'wlan0 is AA:BB:CC:00:11:22 or aa-bb-cc-00-11-22', 'wlan0 is (removed) or (removed)'],
    ['a serial', 'serial R52M40ABCDE, adb says 2c00104cc245c891e10', 'serial (removed), adb says (removed)'],
    ['an IMEI', 'IMEI 356938035643809', 'IMEI (removed)'],
    ['an email', 'write me at someone@example.com', 'write me at (removed)'],
    ['a URL with a password', 'see https://me:hunter2@ha.example.org/x', 'see (removed)'],
    ['Home Assistant\'s own address', 'open http://homeassistant.local:8123/lovelace or abcd1234.ui.nabu.casa', 'open (removed) or (removed)'],
    ['any other site', 'my dashboard https://myhome.duckdns.org/ is up', 'my dashboard (removed) is up'],
    ['a token', 'token eyJhbGciOiJIUzI1NiJ9.eyJpc3MiOiJ4In0.abc_DEF-123', 'token (removed)'],
  ])('removes %s', (_, given, want) => {
    expect(scrub(given)).toBe(want);
  });

  it.each([
    'Samsung Galaxy Tab A, SM-T580', 'arm64-v8a,armeabi-v7a', 'Android 13 (API 33)', 'Exynos 7870 and MT8768', 'LineageOS 21, unofficial',
    'std::string', 'at 12:30:45', 'Glassworks 0.2.0', 'https://github.com/GlassworksHQ/glassworks/issues/12', 'https://www.glassworks.dev/frames',
  ])('keeps %s', (s) => {
    expect(scrub(s)).toBe(s);
  });

  it('escapes HTML, links, images, mentions and issue references', () => {
    const e = escapeMarkdown('<img src=x onerror=alert(1)> ![t](https://evil.example/p.png) [click](https://evil.example) @maintainer cc @org/team see #12 and other/repo#3 https://github.com/x **bold** `code`');
    expect(e).not.toMatch(/<img/);
    expect(e).toContain('&lt;img src=x onerror=alert(1)&gt;');
    expect(e).toContain('!\\[t\\](`https://evil.example/p.png)`'); // the URL in a code span: never fetched, never a link
    expect(e).toContain('`@maintainer`');
    expect(e).toContain('`@org/team`');
    expect(e).toContain('`#12`');
    expect(e).toContain('`other/repo#3`');
    expect(e).toContain('`https://github.com/x`');
    expect(e).toContain('\\*\\*bold\\*\\*');
    expect(e).toContain("'code'"); // the person's backticks: apostrophes, so the code spans are only ours
  });

  it('a line can\'t pass for one of the form\'s headings', () => {
    expect(escapeMarkdown('### Device model\n- item\n1. one')).toBe('\\### Device model\n\\- item\n\\1. one');
  });

  it('a backtick can\'t open a mention out of its code span: the person\'s backticks become apostrophes', () => {
    expect(escapeMarkdown('a `@x` b')).toBe('a \'`@x`\' b');
  });

  it.each([
    ['`@a`@maintainer', '\'`@a`\'`@maintainer`'],
    ['@a@b', '`@a` `@b`'], // side by side: a space keeps the two spans apart
    ['#12@x', '`#12` `@x`'],
    ['see:@x', 'see:`@x`'],
    ['a@b', 'a`@b`'],
  ])('every mention ends up in its own code span, whatever is next to it: %s', (given, want) => {
    const e = escapeMarkdown(given);
    expect(e).toBe(want);
    // outside the code spans, no @ is left
    expect(e.replace(/`[^`]*`/g, '')).not.toContain('@');
  });

  it('the whole issue: the form\'s headings, the template\'s option words, and where it came from', () => {
    const { title, body } = issueOf(clean(FIELDS));
    expect(title).toBe('Tested: Lenovo Tab M10, TB-X605F');
    expect(body).toContain('### Device model\n\nLenovo Tab M10, TB-X605F');
    expect(body).toContain(`### Stock or custom Android?\n\n${OS_KINDS.stock}`);
    expect(body).toContain(`### How did you install it?\n\n${FLOWS.usb}`);
    expect(body).toContain('### Which custom OS?\n\n_No response_');
    expect(body).toContain('### ABI\n\narm64\\-v8a,armeabi\\-v7a'.replace(/\\-/g, '-')); // a dash mid-line stays
    expect(body).toContain('### Clarity grade\n\nC');
    expect(body).toContain('Kiln had reached: Done: the install finished');
  });

  it('every field the template has, and only those, by id', () => {
    const yml = readFileSync(new URL('../../../../.github/ISSUE_TEMPLATE/device-report.yml', import.meta.url), 'utf8');
    const ids = [...yml.matchAll(/^\s+id: (\w+)/gm)].map((m) => m[1]);
    expect(ids).toEqual(['model', 'android', 'os_kind', 'custom_os', 'abi', 'flow', 'worked', 'didnt', 'clarity', 'app_version']);
    expect(yml).toContain(`labels: ["${LABEL}"]`);
    for (const v of [...Object.values(FLOWS), ...Object.values(OS_KINDS)]) expect(yml).toContain(`- ${v}`);
    const url = new URL(githubUrl(FIELDS));
    expect([...url.searchParams.keys()].filter((k) => k !== 'template' && k !== 'title').every((k) => ids.includes(k))).toBe(true);
  });

  it('GitHub\'s form, filled in: the template, and the same cleaned fields', () => {
    const url = new URL(githubUrl({ ...FIELDS, worked: 'fine at 192.168.1.20' }));
    expect(url.origin + url.pathname).toBe('https://github.com/GlassworksHQ/glassworks/issues/new');
    expect(url.searchParams.get('template')).toBe('device-report.yml');
    expect(url.searchParams.get('title')).toBe('Tested: Lenovo Tab M10, TB-X605F');
    expect(url.searchParams.get('os_kind')).toBe(OS_KINDS.stock);
    expect(url.searchParams.get('flow')).toBe(FLOWS.usb);
    expect(url.searchParams.get('worked')).toBe('fine at (removed)');
    expect(url.searchParams.get('clarity')).toBe('C');
  });

  it('a character dropped can\'t join two runs into a serial the route would then remove: cleaning settles first', () => {
    const f = clean({ ...FIELDS, model: 'ACME:AB12345678' });
    expect(f.model).toBe('(removed)');
    expect(clean(f)).toEqual(f);
    expect(issueOf(clean(f))).toEqual(issueOf(f));
  });

  it('the longest report the fields allow, in any script, fits the request limit', async () => {
    const wide = { ...FIELDS, worked: '漢'.repeat(4000), didnt: '字'.repeat(4000), model: 'Ä'.repeat(100), customOs: 'Ö'.repeat(100), osKind: 'custom' as const };
    expect(problems(clean(wide))).toEqual({});
    const body = JSON.stringify({ fields: clean(wide), token: timeToken(credentials(TOKEN)!, T0), [HONEYPOT]: '' });
    expect(new TextEncoder().encode(body).length).toBeLessThanOrEqual(MAX_BYTES);
    expect((await handlePost(post(body), deps(TOKEN))).status).toBe(201);
  });

  it.each([
    ['short ASCII answers', 'a'.repeat(1000), 'b'.repeat(1000), false],
    ['long ASCII answers', 'a'.repeat(5000), 'b'.repeat(5000), true],
    ['CJK answers at the field limit', '漢'.repeat(1500), '字'.repeat(1500), true],
    ['emoji, never split in half', '😀'.repeat(2000), 'ok', true],
    ['one long answer and one short', 'a'.repeat(9000), 'fine', true],
  ])('GitHub\'s form keeps its whole address under the limit, as encoded: %s', (_, worked, didnt, cut) => {
    const g = githubForm({ ...FIELDS, worked, didnt });
    expect(g.url.length).toBeLessThanOrEqual(FORM_URL_MAX);
    expect(g.cut).toBe(cut);
    const q = new URL(g.url).searchParams;
    const w = q.get('worked') ?? '', d = q.get('didnt') ?? '';
    expect(worked.startsWith(w) && didnt.startsWith(d)).toBe(true);
    if (!cut) expect([w, d]).toEqual([worked, didnt]);
    expect(w.length + d.length).toBeGreaterThan(0);
    expect(/[\uD800-\uDBFF]$/.test(w)).toBe(false);
    if (didnt === 'fine') expect(d).toBe('fine'); // the short one is kept whole, the long one gets the rest
  });

  it('cleaning twice changes nothing, so the preview is what the route sends', () => {
    const messy = { ...FIELDS, model: '  Teclast\u0000 P20HD™  ', worked: 'ok\r\n\r\n\r\n\r\nat 10.0.0.2', android: 'Android 13 (API 33)' };
    expect(clean(clean(messy))).toEqual(clean(messy));
    expect(clean(messy).model).toBe('Teclast P20HD');
    expect(clean(messy).worked).toBe('ok\n\nat (removed)');
  });

  it('problems: required fields, the template\'s choices, grades and versions', () => {
    expect(problems(clean(FIELDS))).toEqual({});
    const p = problems(clean({ ...FIELDS, osKind: '' as never, appVersion: 'latest', clarity: 'F', didnt: ' ' }));
    expect(Object.keys(p).sort()).toEqual(['appVersion', 'clarity', 'didnt', 'osKind']);
    expect(problems(clean({ ...FIELDS, clarity: 'a+' }))).toEqual({});
  });
});
