'use client';
// The Mirror's APK from its release (release.ts), where the screens would otherwise ask for a file: looking,
// downloading, checked against the release's SHA-256, or why not. No release is an error, never a file picker.
import { fmtSize } from '@glassworks/kiln-core';
import { useEffect } from 'react';
import c from './choice/choice.module.css';
import { releasePage } from './release';
import { MIRROR_OPEN, type MirrorSession, type MirrorState } from './session';

/** The pill on the Mirror's screens while the page is locked to the public. */
export function TestOnly() {
  return MIRROR_OPEN ? null : <div className="row"><span className="pill soon">Test only</span></div>;
}

export function ReleaseApk({ m, ms }: { m: MirrorSession; ms: MirrorState }) {
  useEffect(() => { void m.loadRelease(); }, [m]);
  const r = ms.release;
  if (!r) return null;
  const again = <button type="button" className="link" onClick={() => void m.loadRelease()}>Try again</button>;
  return (
    <div className="well">
      <div className="grow">
        {r.state === 'waiting' || r.state === 'looking' ? <>
          <b>Glassworks for the Mirror</b>
          <span className="small" role="status">Looking for its newest release…</span>
        </> : r.state === 'downloading' ? <>
          <span className={c.fileHead}><code>{r.name}</code><span className="small">{r.size ? fmtSize(r.size) : ''}</span></span>
          <div className={c.progress} role="status">
            <span className="small">Downloading Glassworks {r.version} · {fmtSize(r.got)}{r.size ? ` of ${fmtSize(r.size)}` : ''}</span>
            <div className="bar" role="progressbar" aria-label={`Downloading ${r.name}`} aria-valuemin={0} aria-valuemax={100}
              aria-valuenow={r.size ? Math.floor((r.got / r.size) * 100) : undefined}><i style={{ width: `${r.size ? Math.max(2, Math.floor((r.got / r.size) * 100)) : 2}%` }} /></div>
          </div>
        </> : r.state === 'ready' ? <>
          <span className={c.fileHead}><b>Glassworks {r.version}</b><span className="small">{fmtSize(r.size)}</span></span>
          <span className="small"><a className="link" href={releasePage(r.tag)} target="_blank" rel="noopener"><code>{r.name}</code></a></span>
          {!ms.apk?.error && <span className="status ok">✓ Checks out: SHA-256 as the release publishes it.</span>}
        </> : r.state === 'none' ? <>
          <b>There is no release of Glassworks for the Mirror yet.</b>
          <span className="status err" role="alert">Kiln installs the newest <code>generic-android-legacy</code> release from GitHub, and it found none. Nothing was written to the Mirror.</span>
          <span className="small">{again}, or <a className="link" href="https://github.com/GlassworksHQ/glassworks/releases" target="_blank" rel="noopener">see the releases</a>.</span>
        </> : <>
          <b>Kiln couldn&apos;t get Glassworks from its release.</b>
          <span className="status err" role="alert">{r.why}. Nothing was written to the Mirror.</span>
          <span className="small">{again}.</span>
        </>}
      </div>
    </div>
  );
}
