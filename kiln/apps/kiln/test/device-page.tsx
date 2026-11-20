// The device page as a person sees a demo state: the install's own screens, or once the install handed the device over,
// the standard flow's (StandardWizard) with what the install left to say on its Done. As DevicePage renders them.
import { act, render } from '@testing-library/react';
import { AppFlow, ImageFlow, LiveAppOutcome, LiveImageOutcome, appSteps } from '../src/device/DevicePage';
import { appDemo, type AppDemo } from '../src/device/demo';
import { imageDemo, type ImageDemo } from '../src/device/imageDemo';
import { imageSteps } from '../src/device/ImageScreens';
import { KEEP_DEMOS, keepDemo, type KeepDemo } from '../src/device/keepDemo';
import { StandardWizard } from '../src/standard/StandardWizard';
import { handTo, mirrorEntry, mirrorFrame } from './mirror-frame';

/** Lets the standard flow's last reads (Done's Silent updates, its Manage page) land. */
const settle = () => act(() => new Promise((r) => setTimeout(r, 50)));

/** An image or keep demo, rendered as the page renders it. */
export async function showImage(d: ImageDemo | KeepDemo) {
  const h = handTo();
  const out = (KEEP_DEMOS as readonly string[]).includes(d)
    ? await keepDemo(mirrorEntry, d as KeepDemo, true, h.handOver)
    : await imageDemo(mirrorEntry, d as ImageDemo, true, {}, h.handOver);
  const ss = h.standard();
  const away = () => {};
  if (ss) render(<StandardWizard session={ss} base="/lululemon-mirror" before={imageSteps(mirrorFrame, out.image.state.link)} outcome={<LiveImageOutcome image={out.image} away={away} />} />);
  else render(<ImageFlow image={out.image} app={out.app} />);
  await settle();
  return { ...out, standard: ss };
}

/** An app-alone demo, rendered as the page renders it. */
export async function showApp(d: AppDemo) {
  const h = handTo();
  const app = await appDemo(mirrorEntry, d, true, h.handOver);
  const ss = h.standard();
  if (ss) render(<StandardWizard session={ss} base="/lululemon-mirror" before={appSteps(mirrorFrame)} outcome={<LiveAppOutcome app={app} />} />);
  else render(<AppFlow session={app} />);
  await settle();
  return { app, standard: ss };
}
