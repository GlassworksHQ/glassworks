// How a page of a touch panel is captured so HA's page keeps the coarse pointer it has on the panel, and the PNG, the
// slots and the texts of a reference all describe one layout.
//
// Touches: nothing (a pure decision over numbers capture.mjs measured).

/**
 * The capture of one page, from its frame's input, the document's and the window's heights, and its slots:
 *
 * - `mouse`: a panel without touch. Playwright's full-page screenshot, as always.
 * - `touch`: a touch panel whose page fits the window. A plain screenshot keeps the touch emulation.
 * - `grow`: a touch panel whose page is taller than the window. Playwright's full-page screenshot would turn touch
 *   emulation off (it draws beyond the window), so the window grows to the page first; the page settles again and is
 *   measured and shot in that one layout, and the window goes back afterwards.
 * - `pinned`: the same, but a card is pinned (`position: fixed`), so its place depends on the window: growing it
 *   would move the card away from where the panel shows it. Such a page is taken the usual way, with a fine pointer,
 *   and its reference says so (`touch` absent), so Vitro draws it with a fine pointer too.
 */
export function captureMode({ touch, docHeight, viewportHeight, slots }) {
  if (!touch) return 'mouse';
  if (docHeight <= viewportHeight) return 'touch';
  if ((slots || []).some((s) => s.fixed)) return 'pinned';
  return 'grow';
}

/** Whether a page captured in [mode] had HA's coarse pointer (the reference's `viewport.touch`). */
export const capturedWithTouch = (mode) => mode === 'touch' || mode === 'grow';
