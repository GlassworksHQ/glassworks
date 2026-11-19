'use client';
import dynamic from 'next/dynamic';

// WebUSB, IndexedDB and the adb key exist only in the browser, so the wizard never renders on the server.
export const S1Wizard = dynamic(
  () => Promise.all([import('../../wizard/FrameWizard'), import('./frame')]).then(([{ FrameWizard }, { frame, demoFrame, BASE }]) => {
    const S1 = () => <FrameWizard frame={frame} demo={demoFrame} base={BASE} />;
    return S1;
  }),
  { ssr: false },
);
