'use client';
// Glassworks' APK from its release (apk.ts), where a page would otherwise ask for a file: looking, downloading,
// checked against the release's SHA-256, or why not. No release is an error, never a file picker.
import { fmtSize } from '@glassworks/kiln-core';
import { useEffect } from 'react';
import { releasePage, type ReleaseApk } from './apk';
import c from './release.module.css';

export interface ReleaseRowProps {
  r: ReleaseApk;
  /** the release line, as the no-release error names it */
  line: string;
  /** what the page installs, as the row names it: "Glassworks for the Mirror" */
  what: string;
  /** where nothing was written: "the Mirror", "the tablet" */
  device: string;
  /** the downloaded APK failed the page's own checks: the row shows no tick */
  apkError?: string;
  /** starts the download; the row calls it while `r` is waiting, and Try again calls it again */
  load(): void;
}

export function ReleaseRow({ r, line, what, device, apkError, load }: ReleaseRowProps) {
  const waiting = r.state === 'waiting';
  useEffect(() => { if (waiting) load(); }, [load, waiting]);
  const again = <button type="button" className="link" onClick={load}>Try again</button>;
  return (
    <div className="well">
      <div className="grow">
        {r.state === 'waiting' || r.state === 'looking' ? <>
          <b>{what}</b>
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
          {!apkError && <span className="status ok">✓ Checks out: SHA-256 as the release publishes it.</span>}
        </> : r.state === 'none' ? <>
          <b>There is no release of {what} yet.</b>
          <span className="status err" role="alert">Kiln installs the newest <code>{line}</code> release from GitHub, and it found none. Nothing was written to {device}.</span>
          <span className="small">{again}, or <a className="link" href="https://github.com/GlassworksHQ/glassworks/releases" target="_blank" rel="noopener">see the releases</a>.</span>
        </> : <>
          <b>Kiln couldn&apos;t get Glassworks from its release.</b>
          <span className="status err" role="alert">{r.why}. Nothing was written to {device}.</span>
          <span className="small">{again}.</span>
        </>}
      </div>
    </div>
  );
}
