// Which cards of a view without a Sections grid (masonry, sidebar, layout-card's views) are its card slots.
//
// Touches: nothing. capture.mjs runs this function inside HA's page (its source goes into page.evaluate), so it must
// stay self-contained: no imports and nothing from this module's scope.

/**
 * The top-level cards of every view in [root] (a document, or anything with `children` and `shadowRoot` like one), in
 * document order: each element first, then its shadow tree, then its light children.
 *
 * A card is a `hui-card` that sits in a view and in no other card. A stack's rows (a vertical-stack's, a
 * horizontal-stack's, a grid's) are `hui-card`s too, inside the stack's own `hui-card`: they belong to the stack's
 * slot, as Vitro's side counts them, and are never slots of their own. A layout-card used as a card is likewise one
 * card, and its grid isn't a view.
 */
export function topLevelViewCards(root) {
  const VIEWS = ['hui-masonry-view', 'hui-sidebar-view', 'masonry-layout', 'grid-layout', 'horizontal-layout', 'vertical-layout'];
  const kids = (n) => (n && n.children ? [...n.children] : []);
  // The views outside any card.
  const views = [];
  const findViews = (n) => {
    for (const e of kids(n)) {
      if (e.localName === 'hui-card') continue;
      if (VIEWS.includes(e.localName)) views.push(e);
      if (e.shadowRoot) findViews(e.shadowRoot);
      findViews(e);
    }
  };
  findViews(root);
  // Each view's cards: a card's own subtree is never searched, and a view nested in this one lists its own.
  const cards = [];
  const seen = new Set();
  const collect = (n) => {
    for (const e of kids(n)) {
      if (e.localName === 'hui-card') {
        if (!seen.has(e)) { seen.add(e); cards.push(e); }
        continue;
      }
      if (e.shadowRoot && !VIEWS.includes(e.localName)) collect(e.shadowRoot);
      collect(e);
    }
  };
  for (const v of views) {
    if (v.shadowRoot) collect(v.shadowRoot);
    collect(v);
  }
  return cards;
}
