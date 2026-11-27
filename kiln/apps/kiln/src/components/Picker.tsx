'use client';
// Screen 1: pick your panel, with the frames drawn as on glassworks.dev/frames. Each card's data is
// the frame's own (frames/<id>/design/frame.json). What the picked frame needs comes first (its installer.json `picker`),
// then the frames Kiln installs, then the ones still to come (issue 672). Continue stays pinned to the bottom of the
// screen, with, for a returning person, finishing the picked frame's set-up or undoing its install. A running install
// shows here too, with the way back to it.
import { ships } from '@glassworks/frame-card';
import * as RadioGroup from '@radix-ui/react-radio-group';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useEffect, useState, useSyncExternalStore } from 'react';
import { INSTALLERS } from '@glassworks/frames/catalog';
import { FRAMES, installsAs, kilnRoute } from '../frames/catalog';
import { registry } from '../wizard/registry';
import { ROUTES, canonical, fromHash, status } from '../wizard/routes';
import { STEPS } from '../wizard/state';
import { FrameCard } from './FrameCard';
import { LastRun } from './LastRun';
import p from './Picker.module.css';
import { Shell, useBrowserChip } from './Shell';

// a tested device installs through its base's route (kilnRoute)
const INSTALLABLE = FRAMES.filter((f) => ships(f.status) && kilnRoute(f));
const SOON = FRAMES.filter((f) => !INSTALLABLE.includes(f));
/** The first installer page's old links (/#/wifi, …) belong to the frame it was for (installer.json `legacy.hashLinks`). */
const OLD_LINKS_FRAME = Object.values(INSTALLERS).find((i) => i.legacy?.hashLinks)?.route;

const NONE: readonly never[] = [];
/** `href` is a route of the frame at `base`, its query string included (`<base>?apk=local`). */
const isUnder = (href: string, base: string) => href === base || href.startsWith(`${base}/`) || href.startsWith(`${base}?`);

function useRunning(): { href: string; name: string } | undefined {
  const entry = useSyncExternalStore(registry.subscribe, registry.get, () => undefined);
  const others = useSyncExternalStore(registry.subscribe, registry.activities, () => NONE);
  const [, tick] = useState(0);
  useEffect(() => {
    const offs = [...(entry ? [entry.session.store.subscribe(() => tick((n) => n + 1))] : []), ...others.map((a) => a.subscribe(() => tick((n) => n + 1)))];
    return () => { for (const off of offs) off(); };
  }, [entry, others]);
  const s = entry?.session.store.state;
  if (entry && s && (status(s) === 'running' || s.screen === 'setup')) return { href: entry.base + ROUTES[canonical(s)], name: entry.name };
  for (const a of others) {
    const href = a.running();
    if (href) return { href, name: a.name };
  }
  return undefined;
}

export function Picker() {
  const chip = useBrowserChip();
  const router = useRouter();
  const running = useRunning();
  const [picked, setPicked] = useState(INSTALLABLE[0]?.id ?? '');
  const design = FRAMES.find((f) => f.id === picked);
  const frame = (design && kilnRoute(design)) ?? '';
  const data = design ? INSTALLERS[installsAs(design)] : undefined;
  // undo has routes of its own where the way back restores a backup, or an in-place flow's restore point (restore-blocks:
  // someone back on another day, or in a new tab, finds it here); elsewhere it is on the frame's own screens
  const undo = data?.wayBack?.how === 'restore-backup'
    || Object.values(data?.flows ?? {}).some((x) => (x as { wayBack?: { how?: string } }).wayBack?.how === 'restore-blocks');
  useEffect(() => {
    const old = fromHash(location.hash);
    if (old && OLD_LINKS_FRAME) router.replace(OLD_LINKS_FRAME + ROUTES[old]);
  }, [router]);
  const steps = STEPS.install.map((name, i) => ({ name, mark: i === 0 ? 'now' as const : 'todo' as const }));
  return (
    <Shell steps={steps} level={0} chip={chip}>
      <section className="picker" aria-labelledby="pick">
        <div className="picker-head">
          <h2 id="pick">Pick your panel</h2>
          <p className="lede">Choose the device you want to set up. Kiln guides you step by step and shows what you&apos;ll need before anything starts.</p>
        </div>
        {running && (
          <div className="running" role="status">
            <span>Kiln is still working on your {running.name}.</span>
            <Link className="link" href={running.href}>Return to install</Link>
          </div>
        )}
        <LastRun />
        {data?.picker && design && (
          <div className={`glass ${p.needs}`} role="group" aria-labelledby="needs">
            <h3 id="needs">What you&apos;ll need</h3>
            <ul>
              <li>Chrome or Edge on this computer</li>
              {data.picker.needs.map((x) => <li key={x}>{x}</li>)}
              {/* the install's time, from the frame's words: the same words its first screen says */}
              {data.words?.time?.total && <li>{data.words.time.total.charAt(0).toUpperCase() + data.words.time.total.slice(1)}</li>}
            </ul>
            <p className="small">{data.picker.wayBack}</p>
          </div>
        )}
        <h3 className={p.group} id="ready">Ready to install</h3>
        <RadioGroup.Root className="frames" value={picked} onValueChange={setPicked} aria-labelledby="ready">
          {INSTALLABLE.map((f) => <FrameCard key={f.id} f={f} />)}
        </RadioGroup.Root>
        {SOON.length > 0 && <>
          <h3 className={p.group} id="soon">Coming soon</h3>
          <RadioGroup.Root className="frames" aria-labelledby="soon" disabled>
            {SOON.map((f) => <FrameCard key={f.id} f={f} />)}
          </RadioGroup.Root>
        </>}
        <div className={`foot glass ${p.sticky}`}>
          <span className="links">
            {design && <span>{`Already installed on ${design.name}?`}</span>}
            {design && <Link className="link" href={`${frame}${ROUTES['set-up']}`}>Finish setting up</Link>}
            {design && undo && <><span aria-hidden="true">·</span><Link className="link" href={`${frame}${ROUTES.undo}`}>Undo an install</Link></>}
          </span>
          <button type="button" className="btn ember" disabled={!frame} onClick={() => router.push(running && isUnder(running.href, frame) ? running.href : frame)}>Continue</button>
        </div>
      </section>
    </Shell>
  );
}
