'use client';
import dynamic from 'next/dynamic';

// WebUSB, the adb key and the query string exist only in the browser, so the page never renders on the server.
export const AndroidPage = dynamic(() => import('../../android/AndroidWizard').then((m) => m.AndroidWizard), { ssr: false });
