import type { ReactNode } from 'react';
import { AndroidPage } from '../AndroidPage';

/**
 * /android and its step routes (route group, so /android/tablet stays apart): the page lives in the layout, so it stays
 * mounted while the address moves between steps, as a frame's does under [frame]. Each page only names its route.
 */
export default function Layout({ children }: { children: ReactNode }) {
  return (
    <>
      <AndroidPage />
      {children}
    </>
  );
}
