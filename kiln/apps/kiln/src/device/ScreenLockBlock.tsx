// Done's screen-lock block (issue 522): a PIN, pattern or password means someone unlocks the device by hand each time
// its screen turns off. Shown only when Kiln read a lock; when it can't tell, Done says nothing. The words are the
// frame's (kiln-core UsbFrame `screenLock`).
import type { UsbInstalled } from '@glassworks/kiln-core';

export function ScreenLockBlock({ lock, words }: { lock?: UsbInstalled['screenLock']; words: { line: string; help: string } }) {
  if (lock !== 'secure') return null;
  return (
    <div className="su" role="group" aria-label="Screen lock">
      <div className="spread"><span>Screen lock</span><i className="w">On</i></div>
      <span className="small">{words.line} <a className="link" href={words.help} target="_blank" rel="noreferrer">How to change it</a></span>
    </div>
  );
}
