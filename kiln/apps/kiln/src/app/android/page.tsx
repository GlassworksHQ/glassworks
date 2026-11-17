import type { Metadata } from 'next';
import { AndroidPage } from './AndroidPage';

export const metadata: Metadata = { title: 'Android device' };

/** Test only, behind ?apk=local, until Glassworks for Android has a release (src/android/AndroidWizard.tsx). */
export default function Page() {
  return <AndroidPage />;
}
