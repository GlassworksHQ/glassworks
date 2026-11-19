// A frame's recovery drawing (design/recovery.json), as Kiln's recovery-mode player (kiln/apps/kiln/src/recovery) draws it.
export type Mm = [number, number];
export interface Rect { kind: string; at: Mm; size: Mm }
/** `[x, y]` on the back, or `[x, y, z]` in a side face the drawing shows (`x = w` or `y = h`), `z` mm down from the back. */
export type Screw = [x: number, y: number, z?: number];
/** A side face the drawing shows: `right` is `x = w`, `bottom` is `y = h`. */
export type Shown = 'right' | 'bottom';

export interface RecoveryData {
  frame: string;
  name: string;
  body: { w: number; h: number; thickness: number; radius?: number };
  cover?: { screws: Screw[]; features: Rect[] };
  /**
   * A stand fixed to a side face by the side screws in that face. It comes off once they're out.
   * `depth`: a bar on that face, mm out from it, if it has one. `tube`: a U of tube from that face (or the
   * bar's ends), `inset` mm in from the corners and `z` mm down from the back, out to `reach` mm from the
   * face, its corners bent to `bend` mm; `closed` adds the tube's top bar along the face, which its screws
   * go through.
   */
  stand?: { edge: Shown; depth?: number; tube?: { reach: number; inset: number; z: number; diameter: number; bend: number; closed?: boolean } };
  board?: (Rect | { kind: 'window'; at: Mm; r: number } | { kind: 'button'; at: Mm })[];
  button?: { at: Mm; what: string };
  port?: { edge: 'top' | 'bottom' | 'left' | 'right'; at: number; kind: string };
  /** A power socket under the back at `at`, its cord running out toward a side face. The `cord` move plugs it in. */
  cord?: { at: Mm; toward: Shown; kind: string };
  /** A power switch in a side face, `at` mm along it. The `power` move flips it on. */
  power?: { edge: Shown; at: number; kind: string };
  steps: ('screws' | 'cover' | 'cord' | 'hold' | 'plug' | 'power')[];
  ready?: { seconds: number; says: string };
  labels?: Partial<Record<string, string>>;
}

