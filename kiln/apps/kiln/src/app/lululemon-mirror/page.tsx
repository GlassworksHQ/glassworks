import type { Metadata } from 'next';
import { MirrorPage } from './MirrorPage';

export const metadata: Metadata = { title: 'lululemon Studio Mirror' };

/** Test only, behind ?apk=local: the Mirror is under development, with no release (src/mirror/MirrorWizard.tsx). */
export default function Page() {
  return <MirrorPage />;
}
