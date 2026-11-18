// Done's screen-lock block (issue 522): a PIN, pattern or password means someone unlocks the tablet by hand each time
// its screen turns off. Shown only when Kiln read a lock; when it can't tell, Done says nothing.
import type { ScreenLock } from '@glassworks/generic-android-installer';

/** Google's page on changing the screen lock, None and Swipe included. */
export const SCREEN_LOCK_HELP = 'https://support.google.com/android/answer/9079129';

export const SCREEN_LOCK_LINE = 'This tablet has a PIN, pattern or password. When the screen turns off, someone has to unlock it. Glassworks keeps the screen on until you pick a time in its Screen settings. Before you do, turn the lock off in Android\'s Settings → Security → Screen lock → None or Swipe.';

export function ScreenLockBlock({ lock }: { lock?: ScreenLock }) {
  if (lock !== 'secure') return null;
  return (
    <div className="su" role="group" aria-label="Screen lock">
      <div className="spread"><span>Screen lock</span><i className="w">On</i></div>
      <span className="small">{SCREEN_LOCK_LINE} <a className="link" href={SCREEN_LOCK_HELP} target="_blank" rel="noreferrer">How to change it</a></span>
    </div>
  );
}
