'use client';
import dynamic from 'next/dynamic';

// navigator, the screen and WebGL exist only in the browser, so the page never renders on the server.
export const TabletRoute = dynamic(() => import('../../../android/tablet/TabletPage').then((m) => m.TabletPage), { ssr: false });
