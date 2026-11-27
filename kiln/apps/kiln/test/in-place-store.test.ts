// idbInPlaceStore keeps each record as `<serial>-<digest>`; the way back reads the key back from that id (#944).
import { describe, expect, it } from 'vitest';
import { inPlaceKey } from '../src/device/inPlace';

const SHA = 'ab'.repeat(32);

describe('inPlaceKey', () => {
  it('a bundle\'s restore point: the serial and the bundle digest', () => {
    expect(inPlaceKey({ id: `SER1-${SHA}`, serial: 'SER1' })).toEqual({ serial: 'SER1', digest: SHA });
  });

  it('a restore point the person imported keeps its restore- prefix, so getRestore finds it again after a reload', () => {
    expect(inPlaceKey({ id: `SER1-restore-${SHA}`, serial: 'SER1' })).toEqual({ serial: 'SER1', digest: `restore-${SHA}` });
  });

  it('a serial with a dash in it stays whole', () => {
    expect(inPlaceKey({ id: `AB-12-restore-${SHA}`, serial: 'AB-12' })).toEqual({ serial: 'AB-12', digest: `restore-${SHA}` });
    expect(inPlaceKey({ id: `AB-12-${SHA}`, serial: 'AB-12' })).toEqual({ serial: 'AB-12', digest: SHA });
  });

  it('a record with no serial: read from the id, restore- prefix included', () => {
    expect(inPlaceKey({ id: `SER1-restore-${SHA}` })).toEqual({ serial: 'SER1', digest: `restore-${SHA}` });
    expect(inPlaceKey({ id: `SER1-${SHA}` })).toEqual({ serial: 'SER1', digest: SHA });
  });
});
