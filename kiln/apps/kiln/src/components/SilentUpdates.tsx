// Done's Silent updates block (issue 422, the maintainer, 2026-11-18): whether the app's updates install with nobody at
// the device, as Kiln read it back. The name, a word in the grants list's colours (y on, n neutral, w attention),
// and one line on what it means. One look on every frame; each frame has its own words.
import type { RootUpdates } from '@glassworks/aqara-s1-plus-installer';
import type { MirrorUpdates } from '@glassworks/lululemon-mirror-installer';
import './SilentUpdates.css';

export interface SilentWords { word: string; tone: 'y' | 'n' | 'w'; line: string }

export function SilentUpdates({ words }: { words: SilentWords | null }) {
  if (!words) return null;
  return (
    <div className="su" role="group" aria-label="Silent updates">
      <div className="spread"><span>Silent updates</span><i className={words.tone}>{words.word}</i></div>
      <span className="small">{words.line}</span>
    </div>
  );
}

/** The S1: the boot image's root update service. */
export function s1Silent(u: RootUpdates): SilentWords {
  switch (u.state) {
    case 'on':
      return { word: 'On', tone: 'y', line: 'Updates from Home Assistant install with nobody at the panel. The root update service in its boot image installs them.' };
    case 'missing':
      return { word: 'A tap each', tone: 'n', line: 'This panel\'s boot image has no root update service, so each update waits for a tap on the panel. Updates still come from Home Assistant. Install again with Kiln to add it.' };
    case 'unknown':
      return { word: 'Not confirmed', tone: 'n', line: 'Kiln couldn\'t read the root update service back. Glassworks\' Device page on the panel says which it is.' };
  }
}

/** The Mirror: Glassworks as a privileged system app in the image, which may install its own updates. No touchscreen, so no tap. */
export function mirrorSilent(u: MirrorUpdates): SilentWords {
  switch (u.state) {
    case 'on':
      return { word: 'On', tone: 'y', line: 'Updates from Home Assistant install with nobody at the Mirror. Glassworks is a system app in the Mirror\'s image, and Android lets it install its own updates.' };
    case 'normal-app':
      return { word: 'A click each', tone: 'n', line: 'Glassworks went in as a normal app, so Android asks before each update, and the Mirror has no touchscreen to answer it. A USB mouse can. Kiln\'s install over fastboot makes it a system app whose updates install by themselves.' };
    case 'not-granted':
      return { word: 'Not granted', tone: 'w', line: 'Glassworks is a system app in the Mirror\'s image, but Android didn\'t let it install apps, so each update asks first. Write the image again with this build of Glassworks.' };
    case 'unknown':
      return { word: 'Not confirmed', tone: 'n', line: 'Kiln couldn\'t read Glassworks back from Android. Glassworks\' Device page on the Mirror says which it is.' };
  }
}
