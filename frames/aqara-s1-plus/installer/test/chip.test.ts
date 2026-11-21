// The S1's FEL checks: another chip is refused in the S1's words, as the shared fel package's WrongChip says it.
import { FelDevice } from '@glassworks/fel';
import { FakeBrom, makeSpl } from '@glassworks/fel/fakes';
import { describe, expect, it } from 'vitest';
import { s1Plus } from '../src/recipe';

const S1_WORDS = 'this is not an Aqara S1 Plus (SoC 0x1823, expected an A133, 0x1855)';

describe('the S1 refuses another chip', () => {
  const other = () => { const b = new FakeBrom(); b.socId = 0x1823; return new FelDevice(b); };

  it('in its chip check', async () => {
    await expect(s1Plus.fel.checkChip(other())).rejects.toThrow(S1_WORDS);
  });

  it('and when it boots the loader', async () => {
    await expect(s1Plus.fel.boot(other(), [makeSpl(0x9000), new Uint8Array(16)], () => {})).rejects.toThrow(S1_WORDS);
  });

  it('and passes the A133', async () => {
    await expect(s1Plus.fel.checkChip(new FelDevice(new FakeBrom()))).resolves.toBe('SoC 0x1855 (A133), secure boot off');
  });
});
