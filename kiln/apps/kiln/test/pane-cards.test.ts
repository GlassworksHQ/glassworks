// Every engine frame's simulated Panes step names panes the catalog has, so each card gets its pane's icon: Kiln draws
// the icon from the pane catalog by the card's id (setup/steps.tsx), never from anything the device sends.
import { paneById } from '@glassworks/frame-card';
import { ENGINE } from '@glassworks/frames/catalog';
import { KILN } from '@glassworks/frames/kiln';
import { describe, expect, it } from 'vitest';

describe.each(ENGINE)('the Panes step on %s', (id) => {
  it('every card is a pane in the catalog, with its icon', async () => {
    const { sim } = await KILN[id]!();
    const cards = (sim as { panes?: { id: string }[] }).panes ?? [];
    expect(cards.length).toBeGreaterThan(0);
    for (const c of cards) expect(paneById(c.id)?.icon, c.id).toBeTruthy();
  });
});
