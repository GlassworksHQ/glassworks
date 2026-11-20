// Done's Silent updates block (issue 422, the maintainer, 2026-11-18): whether the app's updates install with nobody at
// the device, as Kiln read it back. The name, a word in the grants list's colours (y on, n neutral, w attention),
// and one line on what it means. One look on every frame; each frame has its own words (its installer.json `words.silent`).
import './SilentUpdates.css';

import type { SilentWords } from '@glassworks/kiln-core';

export type { SilentWords };

export function SilentUpdates({ words }: { words: SilentWords | null }) {
  if (!words) return null;
  return (
    <div className="su" role="group" aria-label="Silent updates">
      <div className="spread"><span>Silent updates</span><i className={words.tone}>{words.word}</i></div>
      <span className="small">{words.line}</span>
    </div>
  );
}
