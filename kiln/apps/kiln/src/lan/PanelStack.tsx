import { markPlates } from '@glassworks/brand';

/**
 * How one plate of the panel's stack looks, as on the panel's own setup screen (StackView.Look): not set
 * yet (dashed), set (outlined), lit (sky to violet, the top plate when setup is done), or a problem (rose).
 */
export type PlateLook = 'ghost' | 'outline' | 'lit' | 'bad';

/** The plates' outline strength, top first (StackView's weights). */
const WEIGHT = [0.7, 0.45, 0.2];
/** The mark's three plates, top first. */
const PLATES = [...markPlates('full')].reverse();

/**
 * The Glassworks stack the panel's setup screen shows beside its steps (#63): the mark's plates, which set
 * one per step. Kiln's own page shows its furnace (../components/Stack.tsx) instead.
 */
export function PanelStack({ looks, width }: { looks: readonly [PlateLook, PlateLook, PlateLook]; width: number }) {
  return (
    <svg className="stack" viewBox="4 6 112 108" width={width} aria-hidden="true">
      {[2, 1, 0].map((i) => (
        <path key={i} d={PLATES[i]!.d} className={`ps-${looks[i]}`} strokeOpacity={looks[i] === 'outline' ? WEIGHT[i] : undefined} />
      ))}
    </svg>
  );
}
