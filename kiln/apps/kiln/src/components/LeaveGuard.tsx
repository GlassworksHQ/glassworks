'use client';
// While a frame's session writes to the panel (the S1's, or another frame's kept beside it), leaving or reloading the page asks first, and the
// screen stays awake. It sits in the root layout and reads the registry, so it holds on every route:
// the session keeps writing when someone goes to the picker (wizard/registry.ts).
import { useEffect, useState, useSyncExternalStore } from 'react';
import { registry } from '../wizard/registry';
import { busyWriting } from '../wizard/state';

const NONE: readonly never[] = [];

export function useWriting(): boolean {
  const entry = useSyncExternalStore(registry.subscribe, registry.get, () => undefined);
  const others = useSyncExternalStore(registry.subscribe, registry.activities, () => NONE);
  const [writing, setWriting] = useState(false);
  useEffect(() => {
    const check = () => setWriting((!!entry && busyWriting(entry.session.store.state)) || others.some((a) => a.writing()));
    check();
    const offs = [...(entry ? [entry.session.store.subscribe(check)] : []), ...others.map((a) => a.subscribe(check))];
    return () => { for (const off of offs) off(); };
  }, [entry, others]);
  return writing;
}

export function LeaveGuard() {
  const writing = useWriting();
  useEffect(() => {
    if (!writing) return;
    const warn = (e: BeforeUnloadEvent) => { e.preventDefault(); };
    addEventListener('beforeunload', warn);
    let lock: WakeLockSentinel | undefined;
    let asking = false;
    let gone = false;
    // One request at a time: two quick returns to the tab would otherwise take two locks and drop one unreleased.
    const acquire = () => {
      if (asking || !navigator.wakeLock) return;
      asking = true;
      navigator.wakeLock.request('screen').then((l) => { if (gone) void l.release(); else lock = l; }, () => {}).finally(() => { asking = false; });
    };
    // The browser lets go of the lock when the tab is hidden; take it again on the way back.
    const back = () => { if (document.visibilityState === 'visible' && (!lock || lock.released)) acquire(); };
    acquire();
    document.addEventListener('visibilitychange', back);
    return () => {
      gone = true;
      removeEventListener('beforeunload', warn);
      document.removeEventListener('visibilitychange', back);
      void lock?.release();
    };
  }, [writing]);
  return null;
}
