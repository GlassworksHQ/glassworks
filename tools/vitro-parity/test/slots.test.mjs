import { test } from 'node:test';
import assert from 'node:assert/strict';
import { topLevelViewCards } from '../lib/slots.mjs';

// A made-up DOM in HA's shapes: each element its light children, and a shadow tree where HA's elements have one.
const el = (localName, children = [], shadow = null) => ({
  localName, children, shadowRoot: shadow ? { children: shadow } : null, id: null,
});
const named = (e, id) => Object.assign(e, { id });
const card = (id, inner, shadow = []) => named(el('hui-card', [el(inner, [], shadow)]), id);
const stack = (id, type, rows) => named(el('hui-card', [el(type, [], [el('div', rows)])]), id);
const ids = (cards) => cards.map((c) => c.id);

test("a masonry view's slots are its top-level cards: a stack is one slot, its rows aren't", () => {
  // The shape d-masonry-home showed: masonry columns in the view's shadow tree, a vertical stack whose tile rows are
  // hui-cards in the stack's own shadow tree, and a horizontal stack of buttons.
  const view = el('hui-masonry-view', [], [
    el('div', [
      el('div', [card('entities', 'hui-entities-card'), stack('vstack', 'hui-vertical-stack-card', [card('tile-1', 'hui-tile-card'), card('tile-2', 'hui-tile-card'), card('tile-3', 'hui-tile-card')])]),
      el('div', [card('thermostat', 'hui-thermostat-card'), stack('hstack', 'hui-horizontal-stack-card', [card('button-1', 'hui-button-card'), card('button-2', 'hui-button-card')])]),
    ]),
  ]);
  const doc = el('#document', [el('html', [el('body', [el('home-assistant', [], [el('hui-root', [], [el('hui-view', [view])])])])])]);
  assert.deepEqual(ids(topLevelViewCards(doc)), ['entities', 'vstack', 'thermostat', 'hstack']);
});

test("a card's own subtree is never searched, light or shadow, however deep", () => {
  // A grid card's rows in its light DOM, and a stack nested in a stack.
  const grid = named(el('hui-card', [el('hui-grid-card', [card('grid-row', 'hui-tile-card')])]), 'grid');
  const nested = stack('outer', 'hui-vertical-stack-card', [stack('inner', 'hui-horizontal-stack-card', [card('deep', 'hui-button-card')])]);
  const doc = el('#document', [el('hui-sidebar-view', [], [el('div', [grid, nested])])]);
  assert.deepEqual(ids(topLevelViewCards(doc)), ['grid', 'outer']);
});

test("a layout-card used as a card is one card, and its grid isn't a view", () => {
  const layoutCard = named(el('hui-card', [el('layout-card', [], [el('grid-layout', [], [card('in-layout', 'hui-tile-card')])])]), 'layout');
  const doc = el('#document', [el('hui-masonry-view', [], [el('div', [layoutCard, card('after', 'hui-light-card')])])]);
  assert.deepEqual(ids(topLevelViewCards(doc)), ['layout', 'after']);
});

test("a layout-card view's cards are slots, each once, in document order", () => {
  // layout-card's own view element, its cards in its light DOM, and the same card reached twice is listed once.
  const a = card('a', 'hui-tile-card');
  const b = card('b', 'hui-tile-card');
  const doc = el('#document', [el('masonry-layout', [a, b], [el('div', [])])]);
  assert.deepEqual(ids(topLevelViewCards(doc)), ['a', 'b']);
});

test('the walk runs as source in the page: it uses nothing from its module', () => {
  // capture.mjs inlines the function's source into page.evaluate; a reference to module scope would throw there.
  const again = new Function(`return (${topLevelViewCards});`)();
  const doc = el('#document', [el('hui-masonry-view', [], [card('only', 'hui-tile-card')])]);
  assert.deepEqual(ids(again(doc)), ['only']);
});
