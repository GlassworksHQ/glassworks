'use client';
// Set-up on a device frame, after Glassworks went on (setUp.ts): the wizard's own steps (setup/steps.tsx), over the
// device's adb. Both of the page's paths show it: the image's first start and the app alone.
import type { DeviceFrame } from '@glassworks/kiln-core';
import { FRAMES } from '../frames/catalog';
import type { AdbSetUp } from '../setup/adbSetUp';
import { HomeAssistant, Radio, RadioNext, Wifi } from '../setup/steps';
import { Waiting } from '../wizard/screens';
import { setUpSub, type RailStep } from '../wizard/state';
import type { SetUpState } from './setUp';

/** Whether the frame has a radio, from its frame.json (`panes.radio`): set-up's Radio step is only for one that does. */
export const hasRadio = (f: Pick<DeviceFrame, 'id'>) => (FRAMES.find((x) => x.id === f.id)?.panes?.radio?.support ?? 'no') !== 'no';

/** The rail's Set up sub-steps: no App step (the install set the app up), and Radio only on a frame with one. */
export const setUpRail = (s: SetUpState, f: Pick<DeviceFrame, 'id'>): RailStep['sub'] => setUpSub(s.setup, false, hasRadio(f));

export function SetUpScreen({ setUp, s }: { setUp: AdbSetUp; s: SetUpState }) {
  const setup = s.setup;
  const busy = !!s.busy;
  switch (setup.phase) {
    case 'wifi': return <Wifi busy={busy} steps={setUp} setup={setup} />;
    case 'ha': return <HomeAssistant key="ha" busy={busy} steps={setUp} setup={setup} />;
    case 'radio': return <Radio key={setup.radio.mode} busy={busy} steps={setUp} setup={setup} />;
    case 'radio-next': return <RadioNext busy={busy} steps={setUp} setup={setup} />;
    // `waiting` (and `settings`, which a frame whose install set the app up never reaches): the app is starting
    default: return <Waiting setup={{ phase: 'waiting', note: setup.phase === 'waiting' ? setup.note : '' }} />;
  }
}
