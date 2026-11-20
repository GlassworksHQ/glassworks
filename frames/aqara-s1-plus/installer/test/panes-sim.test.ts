// The S1's simulated Panes step (?demo=panes) shows what Glassworks on the S1 really sends: the cards in the app's
// recorded setup-channel transcript (pinned by the app's SetupChannelStepsTest), in its order, with its names and lines.
import { describe, expect, it } from 'vitest';
import transcript from '../../../../kiln/packages/setup-channel/test/aqara-s1-plus-transcript.json';
import { kilnSim } from '../src/sim';

type Card = { id: string; title: string; detail: string; on: boolean };

describe('the S1 demo\'s Panes step', () => {
  it('has the cards the app sends, in its order', () => {
    const recorded = (transcript[0]!.reply as { state: { panes?: Card[] } }).state.panes ?? [];
    expect(recorded.length).toBeGreaterThan(0);
    const shape = (c: Card) => ({ id: c.id, title: c.title, detail: c.detail });
    expect(kilnSim.panes.map(shape)).toEqual(recorded.map(shape));
  });
});
