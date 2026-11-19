'use client';
// The Mirror's release row (release/ReleaseRow.tsx) and its Test only pill.
import { useCallback } from 'react';
import { ReleaseRow } from '../release/ReleaseRow';
import { MIRROR_APK, MIRROR_OPEN, type MirrorSession, type MirrorState } from './session';

/** The pill on the Mirror's screens while the page is locked to the public. */
export function TestOnly() {
  return MIRROR_OPEN ? null : <div className="row"><span className="pill soon">Test only</span></div>;
}

export function ReleaseApk({ m, ms }: { m: MirrorSession; ms: MirrorState }) {
  const load = useCallback(() => { void m.loadRelease(); }, [m]);
  if (!ms.release) return null;
  return <ReleaseRow r={ms.release} line={MIRROR_APK.line} what="Glassworks for the Mirror" device="the Mirror" apkError={ms.apk?.error} load={load} />;
}
