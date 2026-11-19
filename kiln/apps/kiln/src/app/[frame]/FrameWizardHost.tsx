'use client';
import dynamic from 'next/dynamic';

// WebUSB, IndexedDB and the adb key exist only in the browser, so the wizard never renders on the server.
export const FrameWizardHost = dynamic(() => import('../../wizard/LoadFrame').then((m) => m.LoadFrame), { ssr: false });
