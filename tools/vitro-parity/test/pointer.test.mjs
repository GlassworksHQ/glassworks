import { test } from 'node:test';
import assert from 'node:assert/strict';
import { MAX_GROWS, afterGrow, captureMode, capturedWithTouch } from '../lib/pointer.mjs';

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
  assert.deepEqual(['mouse', 'touch', 'grow', 'pinned', 'dialog', 'dialog-touch'].map(capturedWithTouch), [false, true, true, false, false, true]);
  // An open More Info dialog is shot in the window as it is, however tall the page behind it (#885).
  assert.equal(captureMode({ touch: true, docHeight: 584, viewportHeight: 480, slots: card, dialog: true }), 'dialog-touch');
  assert.equal(captureMode({ touch: false, docHeight: 2000, viewportHeight: 1280, slots: card, dialog: true }), 'dialog');
});

test('a grown window is checked again: a page that grows with it is grown again, then fails the case', () => {
  // Grown to the page, and the page fits: shot as it is.
  assert.equal(afterGrow({ docHeight: 584, viewportHeight: 584, grows: 1 }), 'fits');
  // A page whose height follows the window's (calc(100vh + 104px)) is taller again: grow once more.
  assert.equal(afterGrow({ docHeight: 688, viewportHeight: 584, grows: 1 }), 'grow');
  assert.equal(afterGrow({ docHeight: 792, viewportHeight: 688, grows: MAX_GROWS - 1 }), 'grow');
  // Still taller after the last try: the case fails, rather than a screenshot the window cuts short.
  assert.throws(() => afterGrow({ docHeight: 896, viewportHeight: 792, grows: MAX_GROWS }), /still taller than the window/);
});

