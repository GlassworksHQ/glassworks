// Development and preview only: the report sheet at one stage, on any screen with "Tell us how it goes", without
// Kiln's server or GitHub. `&report=form` opens it as the person would; `preview`, `sent` and `error` fill in the
// person's part first; `off` is a deployment with no credentials (the preview offers GitHub's form instead).
// For example /android?demo=done-untested&report=preview.
import type { ReportFields } from './report';
import type { ReportTransport } from './ReportDialog';

const demoOn = () => process.env.NODE_ENV === 'development' || process.env.NEXT_PUBLIC_KILN_DEMO === '1';

export const REPORT_DEMOS = ['form', 'preview', 'sent', 'error', 'off'] as const;
export type ReportDemo = (typeof REPORT_DEMOS)[number];

/** The person's part, as a demo fills it in. */
export const DEMO_FILL: Partial<ReportFields> = {
  osKind: 'stock',
  worked: 'The dashboard, Home Assistant adding it, and the screen turning off at night.',
  didnt: 'Nothing so far.',
};

/** A stand-in for the route: always on (or off), and a sent report is a made-up issue. */
export const demoTransport = (off: boolean): ReportTransport => ({
  open: async () => (off ? 'off' : { token: 'demo' }),
  send: () => new Promise((r) => setTimeout(() => r({ url: 'https://github.com/GlassworksHQ/glassworks/issues', number: 1234 }), 700)),
});

/** The stage the address asks for, in a build with demos; undefined otherwise. */
export function reportDemo(search = typeof location === 'undefined' ? '' : location.search, on = demoOn()):
  { stage: 'form' | 'preview' | 'sent' | 'error'; transport: ReportTransport; fill?: Partial<ReportFields> } | undefined {
  if (!on) return undefined;
  const name = new URLSearchParams(search).get('report') as ReportDemo | null;
  if (!name || !(REPORT_DEMOS as readonly string[]).includes(name)) return undefined;
  const transport = demoTransport(name === 'off');
  if (name === 'form') return { stage: 'form', transport };
  return { stage: name === 'off' ? 'preview' : name, transport, fill: DEMO_FILL };
}
