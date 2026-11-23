'use client';
// "Tell us how it went": a device report from Kiln, without a GitHub account. What Kiln read comes filled in and can
// be changed; the person adds what worked and what didn't. Before anything is sent the sheet shows the issue exactly as
// GitHub will have it (issueOf, the route's own builder), and says it's public. Send opens it through /api/report and
// links it; GitHub's own form, filled in the same, is always one click away, and the way on when Kiln can't send.
// Nothing is sent or kept unless the person presses Send.
import * as Dialog from '@radix-ui/react-dialog';
import { useEffect, useId, useState, type ReactNode } from 'react';
import { clean, FLOWS, githubForm, GRADES, issueOf, OS_KINDS, prefilled, problems, STEPS, type Problems, type ReportFields, type ReportPrefill } from './report';
import { reportDemo } from './demo';
import './report.css';

export interface Sent { url: string; number: number }

/** Why Send didn't work, in Kiln's words; `problems`: the route found these in the fields. */
export class SendError extends Error {
  constructor(message: string, readonly problems?: Problems) { super(message); }
}

/** How the sheet reaches the route. The page's is real; demos and tests bring their own. */
export interface ReportTransport {
  /** Whether Kiln can send from here, and the time token for this form. */
  open(): Promise<{ token: string } | 'off'>;
  send(fields: ReportFields, token: string, honeypot: string): Promise<Sent>;
}

const WHY: Record<string, string> = {
  'rate-limited': 'Kiln has had a few reports from your network this hour. Try again later, or send it on GitHub.',
  'too-fast': 'That was quick. Wait a few seconds, then send it again.',
  expired: 'This form was open a long time. Send it again in a few seconds.',
  github: 'GitHub couldn\'t accept the report just now.',
  'not-configured': 'Kiln can\'t send reports from here.',
};

/** How long a request to the route may take, its answer read included, before the sheet gives up on it. */
export const REQUEST_MS = 20_000;

/** `init` with a deadline, and what's read of the answer within it; past it, the request is aborted and this throws. */
async function timed<T>(url: string, init: RequestInit, read: (r: Response) => Promise<T>, ms: number): Promise<T> {
  const abort = new AbortController();
  const timer = setTimeout(() => abort.abort(), ms);
  try { return await read(await fetch(url, { ...init, signal: abort.signal })); } finally { clearTimeout(timer); }
}

/** The page's: Kiln's own route, each request bounded by `ms`. */
export function routeTransportFor(ms = REQUEST_MS): ReportTransport {
  return {
    async open() {
      try {
        const j = await timed('/api/report', { cache: 'no-store' }, async (res) => (res.ok ? ((await res.json()) as { token?: unknown }) : {}), ms);
        return typeof j.token === 'string' ? { token: j.token } : 'off';
      } catch { return 'off'; }
    },
    async send(fields, token, honeypot) {
      let got: { status: number; ok: boolean; j: { url?: unknown; number?: unknown; error?: unknown; problems?: Problems } };
      try {
        got = await timed('/api/report', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ fields, token, kiln_leave_empty: honeypot }) },
          async (res) => ({ status: res.status, ok: res.ok, j: await res.json().catch(() => ({})) }), ms);
      } catch { throw new SendError('Kiln couldn\'t reach its server.'); }
      const { j, status, ok } = got;
      if (ok && typeof j.url === 'string' && typeof j.number === 'number') return { url: j.url, number: j.number };
      if (status === 422 && j.problems) throw new SendError('A few fields need a quick look before sending.', j.problems);
      throw new SendError(WHY[typeof j.error === 'string' ? j.error : ''] ?? 'Kiln couldn\'t send the report right now.');
    },
  };
}

export const routeTransport = routeTransportFor();

type Stage = 'form' | 'preview' | 'sending' | 'sent' | 'error';

/** The issue as GitHub will show it: its title, then its markdown, exactly as sent. */
export const previewOf = (f: ReportFields) => issueOf(clean(f));

function Field({ label, error, children }: { label: string; error?: string; children: (id: string) => ReactNode }) {
  const id = useId();
  return (
    <label className="field" htmlFor={id}>
      <span>{label}</span>
      {children(id)}
      {error && <span className="status err" role="alert">{error}</span>}
    </label>
  );
}

export function ReportSheet({ prefill, transport = routeTransport, start = 'form', fill }: { prefill: ReportPrefill; transport?: ReportTransport; start?: Stage; fill?: Partial<ReportFields> }) {
  const [f, setF] = useState<ReportFields>(() => ({ ...prefilled(prefill), ...fill }));
  const [stage, setStage] = useState<Stage>(start);
  const [shown, setShown] = useState<Problems>({});
  const [access, setAccess] = useState<{ token: string } | 'off' | undefined>();
  const [honeypot, setHoneypot] = useState('');
  const [sent, setSent] = useState<Sent>();
  const [why, setWhy] = useState('');
  useEffect(() => { let live = true; void transport.open().then((a) => { if (live) setAccess(a); }); return () => { live = false; }; }, [transport]);
  useEffect(() => { if (start === 'sent' || start === 'error') void go(start); }, []); // eslint-disable-line react-hooks/exhaustive-deps
  const set = (k: keyof ReportFields) => (e: { target: { value: string } }) => setF((x) => ({ ...x, [k]: e.target.value }));
  const off = access === 'off';
  const form = githubForm(f);
  const github = form.url;
  /** Said wherever GitHub's form is offered, when it gets less than was written. */
  const cut = form.cut && <p className="small" role="note">GitHub&apos;s form can&apos;t take all of your answers from Kiln, so it opens with the start of them. Paste the rest there before you submit it.</p>;

  function preview() {
    const wrong = problems(clean(f));
    setShown(wrong);
    if (!Object.keys(wrong).length) setStage('preview');
  }

  async function go(demo?: 'sent' | 'error') {
    const a = access ?? await transport.open();
    if (a === 'off') { setAccess('off'); setStage('preview'); return; }
    setStage('sending');
    try {
      const r = demo === 'error' ? Promise.reject(new SendError(WHY.github!)) : transport.send(clean(f), a.token, honeypot);
      setSent(await r);
      setStage('sent');
    } catch (e) {
      const err = e instanceof SendError ? e : new SendError('Kiln couldn\'t send it.');
      if (err.problems) { setShown(err.problems); setStage('form'); return; }
      if (/a long time/.test(err.message)) void transport.open().then(setAccess);
      setWhy(err.message);
      setStage('error');
    }
  }

  if (stage === 'sent' && sent) {
    return (
      <>
        <Dialog.Title asChild><h3>Thanks, it&apos;s on GitHub</h3></Dialog.Title>
        <Dialog.Description>Your report is issue #{sent.number}. Anyone can read it there, and we may ask a question under it.</Dialog.Description>
        <div className="report-foot">
          <a className="btn ember" href={sent.url} target="_blank" rel="noreferrer">See it on GitHub</a>
          <Dialog.Close className="link">Close</Dialog.Close>
        </div>
      </>
    );
  }
  if (stage === 'error') {
    return (
      <>
        <Dialog.Title asChild><h3>Kiln couldn&apos;t send it</h3></Dialog.Title>
        <Dialog.Description>{why} GitHub&apos;s form opens with your report filled in. It needs a GitHub account.</Dialog.Description>
        {cut}
        <div className="report-foot">
          <a className="btn ember" href={github} target="_blank" rel="noreferrer">Open it on GitHub instead</a>
          <button type="button" className="link" onClick={() => setStage('preview')}>Back</button>
        </div>
      </>
    );
  }
  if (stage === 'preview' || stage === 'sending') {
    const { title, body } = previewOf(f);
    return (
      <>
        <Dialog.Title asChild><h3>This goes on GitHub, in public</h3></Dialog.Title>
        <Dialog.Description>Anyone can read it there. Kiln sends exactly this and keeps no copy.</Dialog.Description>
        <div className="report-preview" aria-label="Your report as it will appear">
          <b className="report-title">{title}</b>
          <pre>{body}</pre>
        </div>
        {off && <p className="small" role="status">Kiln can&apos;t send reports from here right now, so GitHub&apos;s form opens with this filled in. It needs a GitHub account.</p>}
        {cut}
        <div className="report-foot">
          {off
            ? <a className="btn ember" href={github} target="_blank" rel="noreferrer">Open it on GitHub</a>
            : <button type="button" className="btn ember" disabled={stage === 'sending' || !access} onClick={() => void go()}>{stage === 'sending' ? 'Sending…' : 'Send report'}</button>}
          <button type="button" className="link" disabled={stage === 'sending'} onClick={() => setStage('form')}>Edit report</button>
          {!off && <a className="link" href={github} target="_blank" rel="noreferrer">Open it on GitHub instead</a>}
        </div>
      </>
    );
  }
  return (
    <>
      <Dialog.Title asChild><h3>Tell us how it went</h3></Dialog.Title>
      <Dialog.Description>Your report helps the next person with this device, even if everything worked. It&apos;s public, so leave out anything private.</Dialog.Description>
      <form className="report-form" onSubmit={(e) => { e.preventDefault(); preview(); }} noValidate>
        <fieldset>
          <legend>What Kiln read</legend>
          <div className="fields">
            <Field label="Device model" error={shown.model}>{(id) => <input id={id} className="input" value={f.model} onChange={set('model')} maxLength={100} autoComplete="off" />}</Field>
            <Field label="Android version" error={shown.android}>{(id) => <input id={id} className="input" value={f.android} onChange={set('android')} maxLength={40} autoComplete="off" />}</Field>
            <Field label="Processor architecture (ABI)" error={shown.abi}>{(id) => <input id={id} className="input" value={f.abi} onChange={set('abi')} maxLength={80} autoComplete="off" />}</Field>
            <Field label="Glassworks version" error={shown.appVersion}>{(id) => <input id={id} className="input" value={f.appVersion} onChange={set('appVersion')} maxLength={40} placeholder="0.2.0" autoComplete="off" />}</Field>
            <Field label="How you installed it" error={shown.flow}>{(id) => (
              <select id={id} className="select" value={f.flow} onChange={set('flow')}>
                <option value="">Choose one</option>
                {Object.entries(FLOWS).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
              </select>
            )}</Field>
            <Field label="How far Kiln got" error={shown.step}>{(id) => (
              <select id={id} className="select" value={f.step} onChange={set('step')}>
                <option value="">Skip</option>
                {Object.entries(STEPS).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
              </select>
            )}</Field>
          </div>
        </fieldset>
        <div className="fields">
          <Field label="Stock or custom Android?" error={shown.osKind}>{(id) => (
            <select id={id} className="select" value={f.osKind} onChange={set('osKind')}>
              <option value="">Pick one</option>
              {Object.entries(OS_KINDS).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
            </select>
          )}</Field>
          {f.osKind === 'custom'
            ? <Field label="Which custom OS?" error={shown.customOs}>{(id) => <input id={id} className="input" value={f.customOs} onChange={set('customOs')} maxLength={100} placeholder="LineageOS 21, unofficial" autoComplete="off" />}</Field>
            : <span />}
        </div>
        <Field label="What worked" error={shown.worked}>{(id) => <textarea id={id} className="input" data-first rows={3} value={f.worked} onChange={set('worked')} maxLength={4000} placeholder="The dashboard, Home Assistant adding it, voice, the screen turning off…" />}</Field>
        <Field label="What didn't work" error={shown.didnt}>{(id) => <textarea id={id} className="input" rows={3} value={f.didnt} onChange={set('didnt')} maxLength={4000} placeholder="Nothing so far, if it all worked" />}</Field>
        <Field label="Clarity grade, if you ran it" error={shown.clarity}>{(id) => (
          <select id={id} className="select" value={f.clarity} onChange={set('clarity')}>
            <option value="">I didn&apos;t run it</option>
            {GRADES.map((g) => <option key={g} value={g}>{g}</option>)}
          </select>
        )}</Field>
        {/* for bots only: hidden from people and screen readers, and never autofilled */}
        <div className="report-trap" aria-hidden="true">
          <label>Leave this empty<input name="kiln_leave_empty" tabIndex={-1} autoComplete="off" value={honeypot} onChange={(e) => setHoneypot(e.target.value)} /></label>
        </div>
        {cut}
        <div className="report-foot">
          <button type="submit" className="btn ember">Preview</button>
          <a className="link" href={github} target="_blank" rel="noreferrer">Open it on GitHub instead</a>
          <Dialog.Close className="link">Not now</Dialog.Close>
        </div>
      </form>
    </>
  );
}

/**
 * "Tell us how it went" as a link-styled button, and the sheet it opens. `prefill`: what Kiln read. In a demo build,
 * `&report=form|preview|sent|error|off` opens it at that stage (./demo.ts).
 */
export function ReportButton({ prefill, children = 'Tell us how it goes', transport }: { prefill: ReportPrefill; children?: ReactNode; transport?: ReportTransport }) {
  const [demo] = useState(() => reportDemo());
  const [open, setOpen] = useState(!!demo);
  return (
    <Dialog.Root open={open} onOpenChange={setOpen}>
      <Dialog.Trigger className="link">{children}</Dialog.Trigger>
      <Dialog.Portal>
        <Dialog.Overlay className="overlay" />
        {/* the person's part first: what Kiln read is already there */}
        <Dialog.Content className="sheet report" onOpenAutoFocus={(e) => {
          const first = (e.currentTarget as HTMLElement | null)?.querySelector<HTMLElement>('[data-first]');
          if (first) { e.preventDefault(); first.focus(); }
        }}>
          {open && <ReportSheet prefill={prefill} transport={transport ?? demo?.transport ?? routeTransport} start={demo?.stage} fill={demo?.fill} />}
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}
