import type { Metadata } from 'next';
import { TabletRoute } from './TabletRoute';

export const metadata: Metadata = { title: 'Android tablet' };

/** Glassworks from the tablet's own browser, no computer (src/android/tablet/TabletPage.tsx). */
export default function Page() {
  return <TabletRoute />;
}
