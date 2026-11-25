import { test } from 'node:test';
import assert from 'node:assert/strict';
import { captureMode, capturedWithTouch } from '../lib/pointer.mjs';

test('a touch panel keeps its coarse pointer and one layout for the PNG, slots and texts', () => {
  const card = [{ x: 0, y: 0, w: 500, h: 300 }];
  // The Mirror (no touch) is captured as always.
  assert.equal(captureMode({ touch: false, docHeight: 2000, viewportHeight: 1280, slots: card }), 'mouse');
  // A page that fits the window: a plain screenshot, nothing resized.
  assert.equal(captureMode({ touch: true, docHeight: 480, viewportHeight: 480, slots: card }), 'touch');
  // Taller than the window: the window grows to the page before anything is measured or shot.
  assert.equal(captureMode({ touch: true, docHeight: 584, viewportHeight: 480, slots: card }), 'grow');
  // A pinned card's place depends on the window: taken the usual way, with a fine pointer.
  assert.equal(captureMode({ touch: true, docHeight: 584, viewportHeight: 480, slots: [...card, { x: 0, y: 380, w: 200, h: 100, fixed: true }] }), 'pinned');
  // Pinned but fitting: nothing grows, so the pointer stays coarse.
  assert.equal(captureMode({ touch: true, docHeight: 480, viewportHeight: 480, slots: [{ x: 0, y: 380, w: 200, h: 100, fixed: true }] }), 'touch');
  assert.deepEqual(['mouse', 'touch', 'grow', 'pinned'].map(capturedWithTouch), [false, true, true, false]);
});
