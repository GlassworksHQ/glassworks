'use client';
// The frame's release row (release/ReleaseRow.tsx) and its Test only pill.
import type { DeviceFrame } from '@glassworks/kiln-core';
import { useCallback } from 'react';
import { ReleaseRow } from '../release/ReleaseRow';
import { deviceOpen, type AppSession, type AppState } from './appSession';

/** The pill on the frame's screens while the page is locked to the public. */
export function TestOnly({ f }: { f: Pick<DeviceFrame, 'id'> }) {
  return deviceOpen(f) ? null : <div className="row"><span className="pill soon">Test only</span></div>;
}

export function ReleaseApk({ m, ms }: { m: AppSession; ms: AppState }) {
  const load = useCallback(() => { void m.loadRelease(); }, [m]);
  if (!ms.release) return null;
  return <ReleaseRow r={ms.release} line={m.frame.apk.line} what={`Glassworks for ${m.frame.the}`} device={m.frame.the} apkError={ms.apk?.error} load={load} />;
}
