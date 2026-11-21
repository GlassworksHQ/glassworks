"use client";

import Link from "next/link";
import { useEffect, useMemo, useRef, useState } from "react";
import { DeviceModel, geometry, type FrameDesign, type FrameModel, type View } from "@glassworks/frame-card";
import { KILN } from "@/lib/links";
import { KilnGlyph, MusicGlyph } from "../Glyphs";
import s from "./MorphStory.module.css";
import { blendModels, blendViews } from "./storyBlend";
import { BEDSIDE_DISPLAY, ROUND_DIAL, SQUARE_SWITCH, WIDE_DISPLAY } from "./storyModels";

/*
 * Concept D (6-Beat Goldilocks Edition). Every beat is a real 3D device, drawn by @glassworks/frame-card:
 * the frames' own models (S1, Mirror, Pixel Tablet) or made-up shapes (storyModels.ts). Between beats one
 * piece of glass travels; at each beat it lands exactly on that device's front and hands over to its model.
 *
 * One single physical piece of dark precision glass gliding across 6 clean, airy scroll beats
 * (730vh, the pacing at HOLDS: each beat holds still, then the glass travels on an eased morph).
 *
 * Each beat spotlights ONE hardware shape + ONE real Glassworks feature over the live
 * CSS-Grid + @container-reflowing Home Assistant Lovelace dashboard:
 *   Beat 0: Aqara S1 Plus Wall Switch (right) — Clean unobstructed HA dashboard
 *   Beat 1: Wide Kitchen Display (left) — Assist as Spark, the edge glow (EdgeGlow.kt + EdgeGlowOverlay.kt)
 *   Beat 2: Wall Switch Landscape (right) — REAL Top-Right Notification Card (NotificationOverlay.kt)
 *   Beat 3: lululemon Studio Mirror (left) — Presence Detection collapse to side pill + reflective glass
 *   Beat 4: Google Pixel Tablet (right) — REAL TimerDock + Sendspin + 3 light lines to what HA reads, its dock
 *           state among them. As it settles, the glass hands over to the tablet's own 3D model from
 *           @glassworks/frame-card, on its speaker dock; lift it off (hover) and the Docked line reads Off.
 *   Beat 5: Compact Square 4" Switch (center) — Solo HA Thermostat + Round/Bedside ghost silhouettes
 */

type ShapeId = "wall" | "tablet" | "mirror" | "kitchen" | "square" | "round" | "bedside";
type AssistPhase = "listening" | "thinking" | "speaking";
/** Spark's run, as the demo steps through it: it listens, thinks, then speaks the reply, and starts over */
const nextAssistPhase = (p: AssistPhase): AssistPhase => (p === "listening" ? "thinking" : p === "thinking" ? "speaking" : "listening");

type BeatStage = {
  w: number;
  xPct: number; // % of stage width
  yPx: number;
  glowX: number;
  glowY: number;
};

type Keyframe = BeatStage & {
  h: number;
  bodyR: number;
  insetTop: number; // %
  insetBottom: number; // %
  insetX: number; // %
  screenR: number;
  rx: number;
  ry: number;
  /* the glass's 3D pose, derived from DeviceModel's geometry() */
  persp?: number; // px
  lean?: number; // deg, a second rotateX after rotateY, as DeviceModel's front
  z?: number; // px, translateZ
  dx?: number; // px
  dy?: number; // px
};

const PERSPECTIVE = 1200;

/** A docked model's body lifted off its dock: a 2D move (px) and a scale less 1, about the body's centre */
type Lift = { x: number; y: number; s: number };

/*
 * Staging coordinates only (width on screen, stage placement, and ambient light position).
 * All real-device heights, corner radii, bezels, screen insets, and 3D angles come from
 * frames/<id>/design/frame.json (and storyModels.ts for future concept shapes) via modelPose().
 */
const BEAT_STAGES: BeatStage[] = [
  // 0: Wall Switch · Aqara S1 Plus (Right)
  { w: 500, xPct: 22, yPx: -4, glowX: 72, glowY: 48 },
  // 1: Wide Kitchen Display · Assist voice (Left)
  { w: 440, xPct: -22, yPx: -4, glowX: 28, glowY: 48 },
  // 2: Wall Switch Landscape · Notifications (Right)
  { w: 500, xPct: 22, yPx: -4, glowX: 72, glowY: 48 },
  // 3: Full-Length Mirror · lululemon Studio Mirror on its stand (Left)
  { w: 168, xPct: -23, yPx: -8, glowX: 27, glowY: 46 },
  // 4: Google Pixel Tablet on its dock · timers, Sendspin, Docked (Right, room for right light rays)
  { w: 468, xPct: 7, yPx: -4, glowX: 60, glowY: 48 },
  // 5: Compact Square · Square 4" Switch + Future Shapes (Center)
  { w: 244, xPct: 0, yPx: -82, glowX: 50, glowY: 38 },
];

/* the finale's shapes: the body's width on screen, px (its height and the rest come from its model) */
const FUTURE_W: Record<"square" | "round" | "bedside", number> = { square: 244, round: 238, bedside: 356 };

/* the phone layout's bodies: px wide, before they shrink to fit a narrow screen */
const MOBILE_W: Record<ShapeId, number> = {
  wall: 336,
  tablet: 330,
  mirror: 140,
  kitchen: 336,
  square: 220,
  round: 220,
  bedside: 316,
};


/* Beat 4's lines: what Home Assistant reads from the tablet. Docked is the app's binary_sensor (#444). */
const wireRays = (docked: boolean) =>
  [
    { title: "Home Assistant", detail: "ESPHome" },
    { title: "Music Assistant", detail: "Sendspin" },
    { title: "Docked", detail: docked ? "On · binary sensor" : "Off · binary sensor" },
  ] as const;

const clamp = (v: number, a = 0, b = 1) => Math.min(b, Math.max(a, v));
const smooth = (t: number) => t * t * (3 - 2 * t);
const lerp = (a: number, b: number, t: number) => a + (b - a) * t;

/*
 * The story's pacing. The section is 730vh tall (MorphStory.module.css), so it scrolls 630vh; each beat
 * holds still for a stretch, then the glass travels to the next (an eased morph). Measured in vh of that
 * scroll so a beat's time reads plainly; p, the section's progress, is that over 630.
 *   Beat 0  hold   0 –  49 | travel  49 –  92
 *   Beat 1  hold  92 – 147 | travel 147 – 193
 *   Beat 2  hold 193 – 254 | travel 254 – 300
 *   Beat 3  hold 300 – 375 | travel 375 – 425  (presence detected from the middle of its hold)
 *   Beat 4  hold 425 – 523 | travel 523 – 566  (the tablet lands on its dock early on, then stays docked)
 *   Beat 5  hold 566 – 630
 */
const SCROLL_VH = 630;
const HOLDS: [number, number][] = (
  [
    [0, 49],
    [92, 147],
    [193, 254],
    [300, 375],
    [425, 523],
    [566, 630],
  ] as const
).map(([a, b]) => [a / SCROLL_VH, b / SCROLL_VH]);

/** Beat 3's Mirror sees someone from the middle of its hold */
const MIRROR_PRESENCE_P = (HOLDS[3]![0] + HOLDS[3]![1]) / 2;
/** The Mirror is on screen (seg, from morphSegment): its presence follows the scroll, or the visitor's tap, here */
const onMirror = (seg: number) => seg >= 2.45 && seg <= 3.55;

/** Where the glass is: a whole number while a beat holds, between two while it travels */
function morphSegment(p: number): number {
  const x = clamp(p);
  for (let i = 0; i < HOLDS.length; i++) {
    const [a, b] = HOLDS[i]!;
    if (x <= b) return x >= a ? i : i - 1 + smooth((x - HOLDS[i - 1]![1]) / (a - HOLDS[i - 1]![1]));
  }
  return HOLDS.length - 1;
}

function windowO(val: number, center: number, inner: number, outer: number) {
  const d = Math.abs(val - center);
  if (d <= inner) return 1;
  if (d >= outer) return 0;
  return 1 - smooth((d - inner) / (outer - inner));
}

function samplePose(sVal: number, frames: Keyframe[]): Keyframe {
  const i = Math.min(frames.length - 2, Math.floor(clamp(sVal, 0, frames.length - 1)));
  const t = clamp(sVal - i, 0, 1);
  const a = frames[i]!;
  const b = frames[i + 1]!;
  return {
    persp: lerp(a.persp ?? PERSPECTIVE, b.persp ?? PERSPECTIVE, t),
    lean: lerp(a.lean ?? 0, b.lean ?? 0, t),
    z: lerp(a.z ?? 0, b.z ?? 0, t),
    dx: lerp(a.dx ?? 0, b.dx ?? 0, t),
    dy: lerp(a.dy ?? 0, b.dy ?? 0, t),
    w: lerp(a.w, b.w, t),
    h: lerp(a.h, b.h, t),
    bodyR: lerp(a.bodyR, b.bodyR, t),
    insetTop: lerp(a.insetTop, b.insetTop, t),
    insetBottom: lerp(a.insetBottom, b.insetBottom, t),
    insetX: lerp(a.insetX, b.insetX, t),
    screenR: lerp(a.screenR, b.screenR, t),
    xPct: lerp(a.xPct, b.xPct, t),
    yPx: lerp(a.yPx, b.yPx, t),
    rx: lerp(a.rx, b.rx, t),
    ry: lerp(a.ry, b.ry, t),
    glowX: lerp(a.glowX, b.glowX, t),
    glowY: lerp(a.glowY, b.glowY, t),
  };
}

/**
 * A beat's device: a frame's model or a made-up shape, its stage's aspect (picked so the stage hugs it), and the
 * angle it is seen from: turned toward the beat's text, gentler than a card's, so each beat has its own.
 */
type StoryModel = { id: string; label: string; model: FrameModel; aspect: number; view: View };

/**
 * A keyframe posed exactly as its model's front glass: the same numbers geometry() gives DeviceModel (size,
 * corner and screen box, rx/ry/lean, the push to the front face, the model's centring and its perspective,
 * 170 × --u in DeviceModel.module.css). The keyframe's width is the body's; `stage` is the model's stage
 * width that draws the body that wide. The glass lands on the model's front, so handing over to the model
 * only adds its sides, stand or dock.
 */
function modelPose(base: BeatStage, sm: StoryModel): { pose: Keyframe; stage: number; face?: string; lift?: Lift } {
  const g = geometry(sm.model, sm.aspect, sm.view);
  const n = (k: string) => parseFloat(g.vars[k] ?? "0");
  const stage = base.w / (n("--wp") / 100);
  const u = stage / 100;
  const w = base.w;
  const [arW = 1, arH = 1] = (g.vars["--ar"] ?? "1 / 1").split("/").map((x) => parseFloat(x));
  const [tx = 0, ty = 0] = [...g.transform.matchAll(/(-?[\d.]+)%/g)].map((m) => parseFloat(m[1]!));
  const pose: Keyframe = {
    ...base,
    w,
    h: (w * arH) / arW,
    bodyR: (n("--rad") / 100) * w,
    insetTop: n("--scr-y"),
    insetBottom: 100 - n("--scr-y") - n("--scr-h"),
    insetX: n("--scr-x"),
    screenR: u * n("--sr"),
    rx: n("--rx"),
    ry: n("--ry"),
    lean: n("--lean"),
    z: u * n("--du") * 0.5,
    persp: u * 170,
    dx: (tx / 100) * stage,
    dy: (ty / 100) * (stage / sm.aspect),
  };
  // a docked model's lift off its dock (--ux, --uy, --us: % of the stage, and a scale), in px for the glass
  const lift = g.vars["--ux"] ? { x: (n("--ux") / 100) * stage, y: (n("--uy") / 100) * (stage / sm.aspect), s: n("--us") } : undefined;
  return { pose, stage, face: g.vars["--dev-face"], lift };
}

/**
 * How far the Pixel Tablet is off its dock (Beat 4), so a reader sees it dock without hovering. One motion
 * from the Mirror: it rises a little as it travels toward the dock, which stands waiting, and comes down
 * onto it a little into the hold, faster at the end, so it lands rather than drifts in; then it stays docked.
 * Scrolling back lifts it again.
 */
function undockAt(p: number) {
  const from = HOLDS[3]![1];
  const land = HOLDS[4]![0] + 20 / SCROLL_VH;
  const x = clamp((p - from) / (land - from));
  const fall = clamp((x - 0.4) / 0.6);
  return smooth(clamp(x / 0.35)) * (1 - fall * fall);
}

/* the side dots; each jumps to a point in its beat's hold (Beat 3 before its Mirror sees someone) */
const at = (beat: number, f: number) => lerp(HOLDS[beat]![0], HOLDS[beat]![1], f);
const BEAT_DOTS = [
  { idx: 0, label: "Wall switch dashboard", targetP: 0.03 },
  { idx: 1, label: "Local Assist voice, Spark", targetP: at(1, 0.5) },
  { idx: 2, label: "Rich notifications", targetP: at(2, 0.5) },
  { idx: 3, label: "Studio mirror presence detection", targetP: at(3, 0.3) },
  { idx: 4, label: "Kitchen timers, Sendspin, and radios", targetP: at(4, 0.5) },
  { idx: 5, label: "Square, round, and future shapes", targetP: at(5, 0.6) },
] as const;

function formatTimer(totalSec: number) {
  const m = Math.floor(totalSec / 60);
  const sec = totalSec % 60;
  return `${m}:${String(sec).padStart(2, "0")}`;
}

/**
 * What a beat's example takes besides Glassworks itself: a Home Assistant blueprint to import, or a starter
 * prompt to generate your own Lovelace card against the entities the panel exposes.
 */
type BeatNeed = { kind: "Blueprint" | "Starter prompt"; name: string; href?: string; /** a blueprint's file, shown only when the build's catalog has it */ blueprint?: string };
const BEAT_NEEDS: Partial<Record<number, readonly BeatNeed[]>> = {
  // the doorbell notification, with its camera and Unlock button
  2: [
    {
      kind: "Blueprint",
      name: "Doorbell on Glassworks panels",
      href: "/blueprints#bp-notifications",
      blueprint: "panes/notifications/blueprints/doorbell-panels.yaml",
    },
  ],
  // the fade and side capsule can be built as a single-file Lovelace card reading camera_presence
  3: [{ kind: "Starter prompt", name: "Presence-aware mirror dashboard", href: "/blueprints#recipe-two-way-mirror-presence" }],
};

export type MorphRelease = { version: string; href: string };
export type MorphPost = { title: string; href: string };

export function MorphStory({
  release,
  post,
  frames,
  blueprints,
}: {
  release: MorphRelease;
  post?: MorphPost;
  /** the frames whose models the story draws: the S1 (Beats 0 and 2), the Mirror (3), the Pixel Tablet (4) */
  frames: { s1: FrameDesign; mirror: FrameDesign; tablet: FrameDesign };
  /** the files of the blueprints /blueprints lists (lib/blueprints.ts), so a beat links only one it has */
  blueprints: readonly string[];
}) {
  const sectionRef = useRef<HTMLElement>(null);
  const bezelRef = useRef<HTMLDivElement>(null);
  const beatModelRef = useRef<HTMLDivElement>(null);
  const glassRef = useRef<HTMLDivElement>(null);
  const compactAssistRef = useRef<HTMLDivElement>(null);
  const compactTimerRef = useRef<HTMLDivElement>(null);
  const lastScreenCtlRef = useRef<string | null>(null);
  const prevModelLiveRef = useRef<boolean>(true);
  const sheenRaf = useRef(0);

  const [p, setP] = useState(0);
  const [mounted, setMounted] = useState(false);
  const [compact, setCompact] = useState(false);
  const [reducedMotion, setReducedMotion] = useState(false);
  const [compactAssistInView, setCompactAssistInView] = useState(false);
  const [compactTimerInView, setCompactTimerInView] = useState(false);
  const [compactTabletHover, setCompactTabletHover] = useState(false);
  const [futureShape, setFutureShape] = useState<"square" | "round" | "bedside">("square");
  const [clickMorph, setClickMorph] = useState(false);
  // the window's width, for the phone layout's models (renderCompactFrame)
  const [vw, setVw] = useState(390);
  // a finale shape picked: the glass morphs to it, then its model takes over (selectFuture)
  const [morphing, setMorphing] = useState(false);

  // Interactive Home Assistant entities (persist live across every shape)
  const [kitchenOn, setKitchenOn] = useState(true);
  const [livingOn, setLivingOn] = useState(false);
  const [sendspinOn, setSendspinOn] = useState(true);
  const [temp, setTemp] = useState(70);
  const [doorLocked, setDoorLocked] = useState(true);

  // Beat 1: Real Assist Voice Overlay phase cycle (Thinking -> Speaking -> Done)
  const [assistPhase, setAssistPhase] = useState<AssistPhase>("listening");

  // Beat 2: Real NotificationOverlay card dismissed/collapsed state
  const [notifDismissed, setNotifDismissed] = useState(false);
  // the control that takes focus when the focused one goes with the card (Close or Dismiss) or the pill (Show)
  const notifFocus = useRef<string | null>(null);
  const showNotif = (shown: boolean, e: React.MouseEvent<HTMLElement>) => {
    if (e.currentTarget === document.activeElement) notifFocus.current = shown ? "notif-close" : "notif-show";
    setNotifDismissed(!shown);
  };
  useEffect(() => {
    const ctl = notifFocus.current;
    if (!ctl) return;
    notifFocus.current = null;
    // the live copy: the screen is drawn on the model and on the glass, and the hidden one is inert
    const all = document.querySelectorAll<HTMLElement>(`[data-story-ctl="${ctl}"]`);
    [...all].find((el) => !el.closest("[inert]"))?.focus();
  }, [notifDismissed]);

  // Beat 3: Studio Mirror presence collapse — scroll-driven on desktop (from MIRROR_PRESENCE_P), click-to-override
  const [mirrorOverride, setMirrorOverride] = useState<boolean | null>(null);
  const [compactMirrorPresent, setCompactMirrorPresent] = useState(true);

  // Beat 4: Real TimerDock + RingView live countdown (Pasta 4:12)
  const [timerSec, setTimerSec] = useState(252);
  const [timerPaused, setTimerPaused] = useState(false);

  useEffect(() => {
    const mqNarrow = window.matchMedia("(max-width: 900px)");
    const mqShort = window.matchMedia("(max-height: 620px)");
    const mqReduce = window.matchMedia("(prefers-reduced-motion: reduce)");
    const syncCompact = () => {
      setVw(window.innerWidth);
      setCompact(mqNarrow.matches || mqShort.matches || mqReduce.matches);
      setReducedMotion(mqReduce.matches);
      setMounted(true);
    };
    syncCompact();
    window.addEventListener("resize", syncCompact);
    mqNarrow.addEventListener("change", syncCompact);
    mqShort.addEventListener("change", syncCompact);
    mqReduce.addEventListener("change", syncCompact);
    return () => {
      window.removeEventListener("resize", syncCompact);
      mqNarrow.removeEventListener("change", syncCompact);
      mqShort.removeEventListener("change", syncCompact);
      mqReduce.removeEventListener("change", syncCompact);
    };
  }, []);

  useEffect(() => {
    if (!compact || !mounted || typeof IntersectionObserver === "undefined") return;
    const assistEl = compactAssistRef.current;
    const timerEl = compactTimerRef.current;
    const obs = new IntersectionObserver(
      (entries) => {
        for (const entry of entries) {
          if (entry.target === assistEl) setCompactAssistInView(entry.isIntersecting);
          if (entry.target === timerEl) setCompactTimerInView(entry.isIntersecting);
        }
      },
      { threshold: 0.15 },
    );
    if (assistEl) obs.observe(assistEl);
    if (timerEl) obs.observe(timerEl);
    return () => obs.disconnect();
  }, [compact, mounted]);

  useEffect(() => {
    if (compact) return;
    let cur = 0;
    let raf = 0;

    const target = () => {
      const el = sectionRef.current;
      if (!el) return 0;
      const r = el.getBoundingClientRect();
      return clamp(-r.top / Math.max(1, r.height - window.innerHeight));
    };

    const tick = () => {
      const t = target();
      cur = cur + (t - cur) * 0.18;
      const settled = Math.abs(t - cur) < 0.0004;
      if (settled) cur = t;
      setP(cur);
      // a visitor's own presence choice holds while the Mirror is on screen, and resets once they leave it
      if (!onMirror(morphSegment(cur))) {
        setMirrorOverride(null);
      }
      if (cur < 0.84) {
        setFutureShape("square");
        setClickMorph(false);
      }
      raf = settled ? 0 : requestAnimationFrame(tick);
    };

    const kick = () => {
      if (!raf) raf = requestAnimationFrame(tick);
    };
    kick();
    window.addEventListener("scroll", kick, { passive: true });
    window.addEventListener("resize", kick);
    return () => {
      cancelAnimationFrame(raf);
      window.removeEventListener("scroll", kick);
      window.removeEventListener("resize", kick);
    };
  }, [compact]);

  // Continuous Goldilocks coordinate seg in [0..5]
  const seg = morphSegment(p);
  // every beat's device, and the keyframes posed on them
  const models = useMemo(() => {
    // right of the text, a device turns left toward it (ry < 0); left of it, right (ry > 0)
    const s1 = (ry: number): StoryModel => ({ id: "s1", label: `${frames.s1.name} on a wall, running Glassworks`, model: frames.s1.model, aspect: 2, view: { rx: 6, ry } });
    return {
      beats: [
        s1(-16),
        { id: "wide", label: "A wide display on a speaker base, running Glassworks (an illustration)", model: WIDE_DISPLAY, aspect: 1.8, view: { rx: 9, ry: 18 } },
        s1(-14),
        { id: "mirror", label: `${frames.mirror.name} on its stand, running Glassworks`, model: frames.mirror.model, aspect: 0.45, view: { rx: 3, ry: 16 } },
        { id: "tablet", label: `${frames.tablet.name} on its speaker dock, running Glassworks`, model: frames.tablet.model, aspect: 1.6, view: { rx: 9, ry: -18 } },
      ] as StoryModel[],
      future: {
        square: { id: "square", label: "A square 4-inch wall switch, running Glassworks (an illustration)", model: SQUARE_SWITCH, aspect: 1, view: { rx: 5, ry: -12 } },
        round: { id: "round", label: "A round wall dial, running Glassworks (an illustration)", model: ROUND_DIAL, aspect: 1, view: { rx: 5, ry: -12 } },
        bedside: { id: "bedside", label: "A bedside display, running Glassworks (an illustration)", model: BEDSIDE_DISPLAY, aspect: 1.8, view: { rx: 9, ry: -14 } },
      } as Record<"square" | "round" | "bedside", StoryModel>,
    };
  }, [frames]);
  const modelOfBeat = (i: number) => (i === 5 ? models.future[futureShape] : models.beats[i]!);
  const modelOfShape = (shape: ShapeId): StoryModel =>
    shape === "wall"
      ? models.beats[0]!
      : shape === "kitchen"
        ? models.beats[1]!
        : shape === "mirror"
          ? models.beats[3]!
          : shape === "tablet"
            ? models.beats[4]!
            : models.future[shape];
  const poses = useMemo(
    () => BEAT_STAGES.map((k, i) => modelPose(i === 5 ? { ...k, w: FUTURE_W[futureShape] } : k, i === 5 ? models.future[futureShape] : models.beats[i]!)),
    [models, futureShape],
  );
  const keyframes = useMemo(() => poses.map((x) => x.pose), [poses]);
  const basePose = samplePose(seg, keyframes);
  // the beat the glass is at or nearest, and its device
  const nearBeat = Math.min(5, Math.max(0, Math.round(seg)));
  const near = poses[nearBeat]!;
  const nearModel = modelOfBeat(nearBeat);

  // Individual feature overlay opacities (ONE feature per beat, never stacked!)
  const assistO = compact ? 1 : windowO(seg, 1, 0.22, 0.56);
  const notifO = compact ? 1 : windowO(seg, 2, 0.22, 0.56);
  const mirrorPresenceO = compact ? 1 : windowO(seg, 3, 0.24, 0.58);
  const timerO = compact ? 1 : windowO(seg, 4, 0.22, 0.56);
  const raysO = compact ? 1 : windowO(seg, 4, 0.22, 0.54);

  // Spark steps through one run on its own while Beat 1 is on screen, and stops on the reply: a tap starts it over.
  // Leaving the beat sets it back to listening, so it runs again next time
  const assistVisible = compact ? compactAssistInView : assistO > 0.15;
  const [assistShown, setAssistShown] = useState(assistVisible);
  if (assistShown !== assistVisible) {
    setAssistShown(assistVisible);
    if (!assistVisible) setAssistPhase("listening");
  }
  useEffect(() => {
    if (!assistVisible || reducedMotion || assistPhase === "speaking") return;
    const ms = 2400;
    const id = window.setTimeout(() => setAssistPhase(nextAssistPhase), ms);
    return () => window.clearTimeout(id);
  }, [assistVisible, assistPhase, reducedMotion]);

  // Beat 3: Presence driven directly by scroll progress inside Beat 3's hold window (from MIRROR_PRESENCE_P)
  // with manual click-to-toggle override that resets when scrolling out of Beat 3
  const scrollMirrorPresent = p >= MIRROR_PRESENCE_P && onMirror(seg);
  const mirrorPresent = compact
    ? compactMirrorPresent
    : mirrorOverride !== null
      ? mirrorOverride
      : scrollMirrorPresent;

  const toggleMirrorPresence = () => {
    if (compact) {
      setCompactMirrorPresent((v) => !v);
    } else {
      setMirrorOverride(!mirrorPresent);
    }
  };

  // Beat 4: Tick real TimerDock RingView live while the timer beat is in view
  const timerVisible = compact ? compactTimerInView : timerO > 0.15;
  useEffect(() => {
    if (!timerVisible || timerPaused) return;
    const id = window.setInterval(() => {
      setTimerSec((prev) => (prev > 1 ? prev - 1 : 252));
    }, 1000);
    return () => window.clearInterval(id);
  }, [timerVisible, timerPaused]);

  const onPointerMove = (e: React.PointerEvent<HTMLDivElement>) => {
    if (e.pointerType !== "mouse" || window.matchMedia("(prefers-reduced-motion: reduce)").matches) return;
    const el = bezelRef.current;
    if (!el) return;
    const x = e.clientX;
    cancelAnimationFrame(sheenRaf.current);
    sheenRaf.current = requestAnimationFrame(() => {
      const r = el.getBoundingClientRect();
      const pct = 50 + ((x - r.left) / Math.max(1, r.width) - 0.5) * 76;
      el.style.setProperty("--sheen", `${pct.toFixed(1)}%`);
    });
  };

  const onPointerLeave = () => {
    cancelAnimationFrame(sheenRaf.current);
    bezelRef.current?.style.removeProperty("--sheen");
  };

  const jumpToBeat = (targetP: number) => {
    const el = sectionRef.current;
    if (!el) return;
    const span = el.offsetHeight - window.innerHeight;
    window.scrollTo({ top: el.offsetTop + span * targetP, behavior: "smooth" });
  };

  const morphTimer = useRef(0);
  const selectFuture = (next: "square" | "round" | "bedside") => {
    setClickMorph(true);
    setMorphing(true);
    setFutureShape(next);
    // the glass morphs to the new shape (.morphSmooth, 0.52s), then the shape's model takes over
    window.clearTimeout(morphTimer.current);
    morphTimer.current = window.setTimeout(() => setMorphing(false), 600);
  };

  const geom = {
    w: basePose.w,
    h: basePose.h,
    bodyR: basePose.bodyR,
    insetTop: basePose.insetTop,
    insetBottom: basePose.insetBottom,
    insetX: basePose.insetX,
    screenR: basePose.screenR,
  };

  // The device itself travels: one 3D model, a blend of the beat it leaves and the one it heads for
  // (blendModels), so its body morphs on the way and is exactly each beat's device while it holds. Its body
  // follows the glass's path (basePose: its width and centre); the glass now shows only for the finale's
  // tap-to-morph. A dock is drawn apart and stands still at its beat (docks, below).
  const segFrom = Math.min(4, Math.floor(seg));
  const segT = seg - segFrom;
  const fromModel = modelOfBeat(segFrom);
  const toModel = modelOfBeat(segFrom + 1);
  const travel: StoryModel = {
    id: "travel",
    label: (segT < 0.5 ? fromModel : toModel).label,
    model: blendModels(fromModel.model, toModel.model, segT),
    aspect: lerp(fromModel.aspect, toModel.aspect, segT),
    view: blendViews(fromModel.view, toModel.view, segT),
  };
  const tg = geometry(travel.model, travel.aspect, travel.view);
  const travelStage = basePose.w / (parseFloat(tg.vars["--wp"] ?? "100") / 100);
  // where its own fit puts the body in its stage, and the move that puts it on the glass's path instead
  const [ttx = 0, tty = 0] = [...tg.transform.matchAll(/(-?[\d.]+)%/g)].map((m) => parseFloat(m[1]!));
  const own = { x: (ttx / 100) * travelStage, y: (tty / 100) * (travelStage / travel.aspect) };
  const comp = { x: (basePose.dx ?? 0) - own.x, y: (basePose.dy ?? 0) - own.y };
  // the ground under a device (its floor or wall shadow) shows while it holds, not in the air; a docked
  // device's is its dock's, drawn with the dock
  const travelDocked = !!travel.model.parts?.some((x) => x.kind === "dock");
  const groundO = travelDocked ? 0 : 1 - smooth(clamp(Math.min(segT, 1 - segT) / 0.12));
  const modelO = compact || morphing ? 0 : 1;
  // the glass, in the finale's tap-to-morph, wears its device's front colour
  const faceO = compact || !near.face ? 0 : windowO(seg, nearBeat, 0.12, 0.62);
  const modelLive = modelO > 0.98;
  // a device on a desk or counter casts a soft shadow
  const dropO = compact || nearModel.model.mount !== "desk" ? 0 : windowO(seg, nearBeat, 0.12, 0.62);
  // a dock stands still at its beat, there before its device lands and until it has left
  const docks = [0, 1, 2, 3, 4, 5]
    .filter((j) => modelOfBeat(j).model.parts?.some((x) => x.kind === "dock"))
    .map((j) => ({ beat: j, o: compact || morphing ? 0 : windowO(seg, j, 0.15, 0.5) }))
    .filter((d) => d.o > 0.01);
  // the tablet lifted off its dock: one unified state drives both the 3D model's --undock lift and the Docked wire-ray label
  const [modelHover, setModelHover] = useState(false);
  // the dock state asked for with the Docked line's button, for a keyboard or a touch: it overrides the mouse's
  // hover until the visitor leaves the beat
  const [dockRequest, setDockRequest] = useState<"lifted" | "docked" | null>(null);
  const [liftBeat, setLiftBeat] = useState(nearBeat);
  if (liftBeat !== nearBeat) {
    setLiftBeat(nearBeat);
    setDockRequest(null);
  }
  // the tablet off its dock as the scroll brings it in (undockAt): a move and scale of the whole travelling
  // model about its body's centre, the same as the hover's lift of the body (geometry's --ux, --uy, --us)
  const tabletLift = poses[4]!.lift;
  const undock = compact || reducedMotion || morphing || !tabletLift ? 0 : undockAt(p);
  const liftK = travelStage / poses[4]!.stage;
  const lift = tabletLift && undock > 0 ? { x: tabletLift.x * undock * liftK, y: tabletLift.y * undock * liftK, s: 1 + tabletLift.s * undock } : undefined;
  // settled on its dock: the scroll's own lift is over, so hover and the button may lift it
  const tabletSettled = nearBeat === 4 && modelLive && segT === 0 && undock <= 0.005;
  const desktopHoverLift = tabletSettled && (dockRequest ? dockRequest === "lifted" : !reducedMotion && modelHover);
  const tabletLifted = desktopHoverLift || (nearBeat === 4 && !reducedMotion && undock > 0.005);
  const compactTabletLifted = dockRequest ? dockRequest === "lifted" : !reducedMotion && compactTabletHover;

  // Preserve keyboard focus across a modelLive handoff if focus was inside the outgoing screen representation
  useEffect(() => {
    if (prevModelLiveRef.current === modelLive) return;
    prevModelLiveRef.current = modelLive;
    if (compact || !mounted) return;
    const ctl = lastScreenCtlRef.current;
    if (!ctl) return;
    const outgoing = modelLive ? glassRef.current : beatModelRef.current;
    const incoming = modelLive ? beatModelRef.current : glassRef.current;
    const active = document.activeElement;
    const cameFromOutgoing = active === document.body || active === null || Boolean(outgoing?.contains(active));
    if (!cameFromOutgoing || !incoming) return;
    const target = incoming.querySelector<HTMLElement>(`[data-story-ctl="${ctl}"]`);
    target?.focus();
  }, [modelLive, compact, mounted]);

  const trackScreenFocus = (e: React.FocusEvent<HTMLDivElement>) => {
    const ctl = (e.target as HTMLElement | null)?.closest<HTMLElement>("[data-story-ctl]")?.dataset.storyCtl ?? null;
    lastScreenCtlRef.current = ctl;
  };
  // Mirror body presence factor (Beat 3): the glass looks silvered on its way to the Mirror
  const mirrorFactor = windowO(seg, 3, 0.24, 0.72);

  // Copy opacities synced smoothly to the 6 beats
  const copy0O = 1 - smooth(clamp((seg - 0.14) / 0.44));
  const copy1O = windowO(seg, 1, 0.24, 0.62);
  const copy2O = windowO(seg, 2, 0.24, 0.62);
  const copy3O = windowO(seg, 3, 0.24, 0.62);
  const copy4O = windowO(seg, 4, 0.24, 0.62);
  const copy5O = smooth(clamp((seg - 4.42) / 0.44));
  const ghostsO = smooth(clamp((seg - 4.52) / 0.38));

  const activeBeat = Math.min(5, Math.max(0, Math.round(seg)));
  const currentShape: ShapeId =
    seg < 0.5
      ? "wall"
      : seg < 1.5
        ? "kitchen"
        : seg < 2.5
          ? "wall"
          : seg < 3.5
            ? "mirror"
            : seg < 4.5
              ? "tablet"
              : futureShape;

  const activeLightsCount = (kitchenOn ? 2 : 0) + (livingOn ? 1 : 0);
  const haHeatRatio = clamp((temp - 60) / 20);
  const haHeating = temp >= 68;
  const timerFraction = clamp(timerSec / 300, 0.02, 1);
  const ringCircumference = 2 * Math.PI * 36.5;

  /*
   * ONE live, responsive Home Assistant Lovelace Sections dashboard powered by
   * CSS Grid + @container size queries so actual HA cards reflow as the glass morphs.
   */
  const renderLiveLovelace = (
    shape: ShapeId,
    highlightSendspin = false,
    collapsed = false,
    isInert = false,
    idScope = "d",
  ) => {
    const isRound = shape === "round";
    const isSolo = shape === "square" || shape === "round";
    return (
      <div
        className={`${s.haRoot} ${collapsed ? s.haRootCollapsed : ""}`}
        data-shape={shape}
        data-solo={isSolo || undefined}
        data-round={isRound || undefined}
        data-sendspin={highlightSendspin || undefined}
        inert={isInert || undefined}
      >
        {/* Top Home Assistant Badges / Chips Bar */}
        <div className={s.haBadges}>
          <span className={s.haBadge}>
            <span className={`${s.haBadgeDot} ${s.dotAmber}`} aria-hidden="true" />
            71°F
          </span>
          <span className={s.haBadge}>
            <span className={`${s.haBadgeDot} ${activeLightsCount > 0 ? s.dotAmber : s.dotMuted}`} aria-hidden="true" />
            {activeLightsCount} {activeLightsCount === 1 ? "light" : "lights"}
          </span>
          <span className={s.haBadge}>
            <span className={`${s.haBadgeDot} ${doorLocked ? s.dotGreen : s.dotOrange}`} aria-hidden="true" />
            {doorLocked ? "Locked" : "Unlocked"}
          </span>
          <span className={`${s.haBadge} ${s.haBadgeExtra}`}>
            <span className={`${s.haBadgeDot} ${s.dotBlue}`} aria-hidden="true" />
            {highlightSendspin ? "Sendspin" : "Armed"}
          </span>
        </div>

        {/* Live Responsive HA Sections Grid */}
        <div className={s.haGrid}>
          {/* Weather & Clock Card */}
          <div className={`${s.haCard} ${s.haWeatherCard}`}>
            <div className={s.haWeatherLeft}>
              <span className={s.haClock}>19:40</span>
              <span className={s.haWeatherCond}>71°F · Clear</span>
            </div>
            <div className={s.haWeatherRight}>
              <span className={s.haSunIcon} aria-hidden="true">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round">
                  <circle cx="12" cy="12" r="4" fill="#ffc107" stroke="#ffc107" />
                  <path
                    d="M12 3v2M12 19v2M3 12h2M19 12h2M5.6 5.6 7 7M17 17l1.4 1.4M5.6 18.4 7 17M17 7l1.4-1.4"
                    stroke="#ffc107"
                  />
                </svg>
              </span>
              <span className={s.haHiLo}>H:74° L:61°</span>
            </div>
          </div>

          {/* Authentic HA Thermostat / Climate Dial Card */}
          <div className={`${s.haCard} ${s.haClimateCard}`}>
            <div className={s.haClimateHead}>
              <span className={s.haCardTitle}>Living Room</span>
              <span className={`${s.haModePill} ${haHeating ? s.haModeHeat : s.haModeCool}`}>
                {haHeating ? "Heating" : "Cooling"}
              </span>
            </div>

            <div className={s.haDialStage}>
              <svg viewBox="0 0 110 110" className={s.haDialSvg} aria-hidden="true">
                <circle
                  cx="55"
                  cy="55"
                  r="43"
                  className={s.haDialTrack}
                  strokeDasharray="202 68"
                  strokeDashoffset="-34"
                />
                <circle
                  cx="55"
                  cy="55"
                  r="43"
                  className={haHeating ? s.haDialHeat : s.haDialCool}
                  strokeDasharray={`${(202 * haHeatRatio).toFixed(1)} 270`}
                  strokeDashoffset="-34"
                />
              </svg>
              <div className={s.haDialCenter}>
                <span className={s.haDialTemp}>{temp}°</span>
                <span className={s.haDialSub}>69° inside</span>
              </div>
            </div>

            <div className={s.haDialControls}>
              <button
                type="button"
                className={s.haStepBtn}
                data-story-ctl="temp-down"
                onClick={() => setTemp((t) => Math.max(60, t - 1))}
                aria-label="Decrease target temperature"
              >
                −
              </button>
              <span className={s.haDialTarget}>Target</span>
              <button
                type="button"
                className={s.haStepBtn}
                data-story-ctl="temp-up"
                onClick={() => setTemp((t) => Math.min(80, t + 1))}
                aria-label="Increase target temperature"
              >
                +
              </button>
            </div>
          </div>

          {/* Interactive HA Tile Card: Kitchen Lights with brightness slider */}
          <button
            type="button"
            className={`${s.haCard} ${s.haTile} ${s.haKitchenTile} ${kitchenOn ? s.haTileAmber : ""}`}
            onClick={() => setKitchenOn((v) => !v)}
            aria-pressed={kitchenOn}
          >
            <div className={s.haTileRow}>
              <span className={`${s.haCircle} ${kitchenOn ? s.haCircleAmber : s.haCircleOff}`} aria-hidden="true">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round">
                  <path d="M9 18h6M10 21h4M12 3a6 6 0 0 0-4 10.5c.8.7 1.2 1.5 1.3 2.5h5.4c.1-1 .5-1.8 1.3-2.5A6 6 0 0 0 12 3Z" />
                </svg>
              </span>
              <span className={s.haTileMeta}>
                <b>Kitchen</b>
                <small>{kitchenOn ? "80%" : "Off"}</small>
              </span>
            </div>
            <span className={s.haSlider} aria-hidden="true">
              <span className={s.haSliderFillAmber} style={{ width: kitchenOn ? "80%" : "0%" }} />
            </span>
          </button>

          {/* Interactive HA Tile Card: Living Room Lights */}
          <button
            type="button"
            className={`${s.haCard} ${s.haTile} ${s.haLivingTile} ${livingOn ? s.haTileAmber : ""}`}
            onClick={() => setLivingOn((v) => !v)}
            aria-pressed={livingOn}
          >
            <div className={s.haTileRow}>
              <span className={`${s.haCircle} ${livingOn ? s.haCircleAmber : s.haCircleOff}`} aria-hidden="true">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round">
                  <path d="M8 5h8l2 7H6l2-7ZM12 12v8M9 20h6" />
                </svg>
              </span>
              <span className={s.haTileMeta}>
                <b>Living Room</b>
                <small>{livingOn ? "55%" : "Off"}</small>
              </span>
            </div>
            <span className={s.haSlider} aria-hidden="true">
              <span className={s.haSliderFillAmber} style={{ width: livingOn ? "55%" : "0%" }} />
            </span>
          </button>

          {/* Interactive HA Tile Card: Front Door Lock */}
          <button
            type="button"
            className={`${s.haCard} ${s.haTile} ${s.haLockTile}`}
            onClick={() => setDoorLocked((l) => !l)}
            aria-pressed={!doorLocked}
          >
            <div className={s.haTileRow}>
              <span className={`${s.haCircle} ${doorLocked ? s.haCircleGreen : s.haCircleOrange}`} aria-hidden="true">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round">
                  <rect x="5" y="11" width="14" height="10" rx="2" />
                  <path d={doorLocked ? "M8 11V7a4 4 0 0 1 8 0v4" : "M8 11V7a4 4 0 0 1 7.6-1.6"} />
                </svg>
              </span>
              <span className={s.haTileMeta}>
                <b>Front Door</b>
                <small>{doorLocked ? "Locked" : "Unlocked"}</small>
              </span>
            </div>
          </button>

          {/* Interactive HA Tile Card: Sendspin Audio (highlighted on Beat 4 Kitchen/Bedside) */}
          <button
            type="button"
            className={`${s.haCard} ${s.haTile} ${s.haMediaTile} ${highlightSendspin && sendspinOn ? s.haTileBlue : ""}`}
            onClick={() => setSendspinOn((v) => !v)}
            aria-pressed={sendspinOn}
          >
            <div className={s.haTileRow}>
              <span className={`${s.haCircle} ${sendspinOn ? s.haCircleBlue : s.haCircleOff}`} aria-hidden="true">
                <MusicGlyph />
              </span>
              <span className={s.haTileMeta}>
                <b>Sendspin</b>
                <small>{sendspinOn ? "Playing · 3 rooms" : "Paused"}</small>
              </span>
            </div>
          </button>

          {/* HA Energy Sparkline Card */}
          <div className={`${s.haCard} ${s.haSparkCard}`}>
            <div className={s.haSparkHead}>
              <span className={s.haSparkLabel}>Home Power</span>
              <b className={s.haSparkVal}>1.4 kW</b>
            </div>
            <svg viewBox="0 0 180 42" className={s.haSparkSvg} preserveAspectRatio="none" aria-hidden="true">
              <defs>
                <linearGradient id={`ha-spark-${idScope}-${shape}-${highlightSendspin ? "s" : "n"}`} x1="0" y1="0" x2="0" y2="1">
                  <stop offset="0" stopColor="#03a9f4" stopOpacity="0.32" />
                  <stop offset="1" stopColor="#03a9f4" stopOpacity="0" />
                </linearGradient>
              </defs>
              <path
                d="M0 32 L20 28 L42 30 L64 20 L86 23 L108 14 L132 18 L156 10 L180 15 L180 42 L0 42 Z"
                fill={`url(#ha-spark-${idScope}-${shape}-${highlightSendspin ? "s" : "n"})`}
              />
              <path
                d="M0 32 L20 28 L42 30 L64 20 L86 23 L108 14 L132 18 L156 10 L180 15"
                fill="none"
                stroke="#03a9f4"
                strokeWidth="2"
                strokeLinecap="round"
                strokeLinejoin="round"
              />
            </svg>
          </div>

          {/* HA Front Porch Live Camera Card */}
          <div className={`${s.haCard} ${s.haCamCard}`}>
            <svg viewBox="0 0 220 96" className={s.haCamSvg} preserveAspectRatio="xMidYMid slice" aria-hidden="true">
              <rect width="220" height="96" fill="#131720" />
              <path d="M0 68 L220 68 L220 96 L0 96 Z" fill="#1a1f2d" />
              <path d="M64 68 L156 68 L184 96 L36 96 Z" fill="#232a3c" />
              <rect x="28" y="16" width="44" height="52" rx="3" fill="#191e2b" stroke="rgba(255,255,255,0.06)" />
              <rect x="148" y="16" width="44" height="52" rx="3" fill="#191e2b" stroke="rgba(255,255,255,0.06)" />
              <circle cx="110" cy="24" r="22" fill="#ffc107" fillOpacity="0.08" />
            </svg>
            <div className={s.haCamOverlay}>
              <span className={s.haCamBadge}>
                <span className={s.liveDot} />
                Front Porch
              </span>
              <span className={s.haCamFps}>Live</span>
            </div>
          </div>
        </div>
      </div>
    );
  };

  /* The screen's content at this point of the story: the dashboard and the beat's overlay, on the glass or the model */
  const renderScreenContent = (idScope = "d") => {
    const collapsed = mirrorPresent && mirrorPresenceO > 0.35;
    const dashboardInert = collapsed || assistO > 0.45;
    return (
      <div className={s.screenInner}>
        {renderLiveLovelace(currentShape, raysO > 0.35, collapsed, dashboardInert, idScope)}
        {renderRealVoiceOverlay(assistO)}
        {renderRealNotificationOverlay(notifO)}
        {renderMirrorPresenceOverlay(mirrorPresenceO)}
        {renderRealTimerOverlay(timerO)}
      </div>
    );
  };

  /* A beat's device as its 3D model (frame.json's, or storyModels.ts), with the live screen on it */
  const renderModel = (sm: StoryModel, id: string, shape: ShapeId, content: React.ReactNode, face?: React.ReactNode) => (
    <DeviceModel key={sm.id} id={id} model={sm.model} label={sm.label} aspect={sm.aspect} view={sm.view} face={face}>
      {/* the glass's own screen box (a size container), so the dashboard lays out the same on both */}
      <div className={s.modelScreen} data-round={shape === "round" || undefined} data-mirror={shape === "mirror" || undefined}>
        {content}
      </div>
    </DeviceModel>
  );

  /* Beat 4: lifts the tablet off its dock and puts it back, as hovering it does, for a keyboard or a touch */
  // `lifted` is what shows; the button asks for the other state. While the scroll lifts the tablet, it waits
  const renderDockToggle = (lifted: boolean, busy = false) => (
    <button
      type="button"
      className={s.dockToggle}
      aria-pressed={lifted}
      disabled={busy}
      onClick={() => setDockRequest(lifted ? "docked" : "lifted")}
    >
      {lifted ? "Put it back" : "Lift it off"}
    </button>
  );

  /* a beat's blueprints and dashboard cards that are published (BEAT_NEEDS), as links beside its own */
  const renderNeeds = (beat: number) =>
    BEAT_NEEDS[beat]
      ?.filter((n) => n.href && (!n.blueprint || blueprints.includes(n.blueprint)))
      .map((n) => (
        <Link key={n.name} href={n.href!} className={s.need}>
          <span className={s.needKind}>{n.kind}</span>
          {n.name} →
        </Link>
      ));

  /*
   * Beat 3: the Mirror's presence sensor in its forehead, on the model's front: it lights, and a ring ripples
   * out, when someone steps up. `opacity` fades it in as the device becomes the Mirror; its place is the middle
   * of the frame above the screen, wherever that is on the body as it morphs
   */
  const renderMirrorSensor = (m: FrameModel, opacity: number) => {
    if (opacity <= 0.05) return undefined;
    const lit = mirrorPresent && mirrorPresenceO > 0.35;
    const top = ((m.screen.top ?? m.bezel) / 2 / m.body.height) * 100;
    return (
      <div className={s.mirrorSensor} aria-hidden="true" style={{ opacity, top: `${top.toFixed(2)}%` }}>
        <span className={`${s.sensorLens} ${lit ? s.sensorLensLit : ""}`} />
        {lit && <span className={s.sensorPulse} />}
      </div>
    );
  };

  /*
   * Beat 1: Assist as Spark, the edge glow (EdgeGlow.kt + EdgeGlowOverlay.kt). The dashboard stays in view under a
   * light dim; the theme's two lights (Glassworks: sky on the left, violet on the right) ignite at the mic in the middle
   * of the top edge and run both ways round the screen's edge to meet at the bottom. Listening, the mic glows with the
   * voice and ripples run along the top; thinking, the lights shuttle along the top and bottom edges; speaking, the edge
   * breathes calmly with the reply. A glass caption at the bottom shows what was heard and the reply. A tap moves on.
   */
  const renderRealVoiceOverlay = (opacity: number) => {
    if (opacity <= 0.01) return null;
    const nextPhase = () => setAssistPhase(nextAssistPhase);

    const interactive = opacity > 0.45;
    // what the caption says, for a screen reader: a button's own text is not read, so it is announced here
    const said =
      assistPhase === "listening"
        ? "Assist is listening: How can I help?"
        : assistPhase === "thinking"
          ? "Assist is thinking: “Set a 5-minute pasta timer”"
          : "Assist says: Timer started for 5 minutes.";
    return (
      <>
        <div
          className={s.spark}
          data-phase={assistPhase}
          style={{
            opacity,
            pointerEvents: interactive ? "auto" : "none",
          }}
          inert={!interactive || undefined}
          data-story-ctl="voice-overlay"
          onClick={nextPhase}
          role="button"
          tabIndex={interactive ? 0 : -1}
          aria-label="Glassworks Assist, Spark edge glow: tap to move to the next step"
          onKeyDown={(e) => {
            if (e.key === "Enter" || e.key === " ") {
              e.preventDefault();
              nextPhase();
            }
          }}
        >
          <div className={s.sparkDim} aria-hidden="true" />
          {/* keyed by phase, so the lights ignite again when listening starts over */}
          <div key={assistPhase === "listening" ? "lit" : "on"} className={s.sparkRing} aria-hidden="true">
            <span className={s.sparkHaze} />
            <span className={s.sparkGlow} />
            <span className={s.sparkEdge} />
          </div>
          <span className={s.sparkMic} aria-hidden="true" />
          {assistPhase === "listening" && (
            <span className={s.sparkRipples} aria-hidden="true">
              <i />
              <i />
            </span>
          )}
          {assistPhase === "thinking" && (
            <span className={s.sparkShuttles} aria-hidden="true">
              <i />
              <i />
              <i />
              <i />
            </span>
          )}
          <div className={s.sparkCaption} aria-hidden="true">
            {assistPhase === "listening" && (
              <>
                <span className={`${s.sparkLabel} ${s.sparkLabelLit}`}>Listening</span>
                <p className={`${s.sparkMain} ${s.sparkMuted}`}>How can I help?</p>
              </>
            )}
            {assistPhase === "thinking" && (
              <>
                <span className={`${s.sparkLabel} ${s.sparkFaint}`}>Thinking</span>
                <p className={s.sparkMain}>“Set a 5-minute pasta timer”</p>
              </>
            )}
            {assistPhase === "speaking" && (
              <>
                <span className={s.sparkHeard}>“Set a 5-minute pasta timer”</span>
                <p className={s.sparkMain}>Timer started for 5 minutes.</p>
              </>
            )}
          </div>
        </div>
        <span className="visually-hidden" role="status">
          {interactive ? said : ""}
        </span>
      </>
    );
  };

  /*
   * Beat 2 — REAL Top-Right Notification Card ALONE (NotificationOverlay.kt + Glass.kt)
   */
  const renderRealNotificationOverlay = (opacity: number) => {
    if (opacity <= 0.01) return null;
    const slideX = (1 - opacity) * 16;

    const interactive = opacity > 0.45;
    return (
      <div className={s.overlaySlot} style={{ opacity, pointerEvents: "none" }} inert={!interactive || undefined}>
        {!notifDismissed ? (
          <div
            className={s.gwNotifCard}
            style={{
              transform: `translate3d(${slideX}px, 0, 0)`,
              pointerEvents: interactive ? "auto" : "none",
            }}
          >
            <div className={s.gwNotifHead}>
              <span className={s.gwNotifLabel}>Important · Just now</span>
              <button
                type="button"
                className={s.gwNotifClose}
                data-story-ctl="notif-close"
                onClick={(e) => showNotif(false, e)}
                aria-label="Close notification"
              >
                ×
              </button>
            </div>
            <div className={s.gwNotifTitle}>Front Door</div>
            <div className={s.gwNotifBody}>
              {doorLocked ? "Person detected at the porch" : "Front door unlocked"}
            </div>
            <div className={s.gwNotifPic} aria-hidden="true">
              <svg viewBox="0 0 220 82" className={s.gwNotifPicSvg} preserveAspectRatio="xMidYMid slice">
                <rect width="220" height="82" fill="#12151f" />
                <path d="M0 56 L220 56 L220 82 L0 82 Z" fill="#181c28" />
                <path d="M68 56 L152 56 L178 82 L42 82 Z" fill="#212738" />
                <circle cx="110" cy="31" r="8" fill="#38bdf8" fillOpacity="0.32" stroke="#38bdf8" strokeOpacity="0.65" />
                <path d="M96 55 C96 43 124 43 124 55 Z" fill="#a78bfa" fillOpacity="0.28" stroke="#a78bfa" strokeOpacity="0.6" />
              </svg>
            </div>
            <div className={s.gwNotifActions}>
              <button
                type="button"
                className={`${s.gwPillBtn} ${!doorLocked ? s.gwPillBtnOk : ""}`}
                data-story-ctl="notif-lock"
                onClick={() => setDoorLocked((l) => !l)}
              >
                {doorLocked ? "Unlock" : "Lock"}
              </button>
              <button
                type="button"
                className={s.gwPillBtn}
                data-story-ctl="notif-dismiss"
                onClick={(e) => showNotif(false, e)}
              >
                Dismiss
              </button>
            </div>
            <div className={s.gwTimeLeftTrack} aria-hidden="true">
              <div className={s.gwTimeLeftFill} />
            </div>
          </div>
        ) : (
          <button
            type="button"
            className={s.gwNotifPill}
            data-story-ctl="notif-show"
            style={{ pointerEvents: interactive ? "auto" : "none" }}
            onClick={(e) => showNotif(true, e)}
          >
            <b>1 notification</b>
            <span> · Show</span>
          </button>
        )}
      </div>
    );
  };

  /*
   * Beat 3 — Studio Mirror Presence Collapse:
   *   - Deep reflective mirror glass layer fades in across 90%+ of the 40" screen
   *   - Subtle top-rim presence micro-chip (`binary_sensor.presence: detected`)
   *   - Sleek ~36px frosted vertical side pill docked along the right inner edge
   *   - Clicking anywhere on the mirror toggles Room clear <-> Presence detected
   */
  const renderMirrorPresenceOverlay = (opacity: number) => {
    if (opacity <= 0.01) return null;
    const active = mirrorPresent;
    const interactive = opacity > 0.45;

    return (
      <div
        className={s.presenceLayer}
        style={{
          opacity,
          pointerEvents: "none",
        }}
        inert={!interactive || undefined}
      >
        {/* Pure deep reflective mirror glass + diagonal architectural sheen when presence collapses dashboard */}
        <div className={`${s.mirrorReflectGlass} ${active ? s.mirrorReflectGlassOn : ""}`} aria-hidden="true" />

        {/* Subtle monospace presence micro-chip at the top rim (dedicated toggle so expanded dashboard stays clickable) */}
        <button
          type="button"
          className={`${s.presenceChip} ${active ? s.presenceChipDetected : ""}`}
          data-story-ctl="mirror-presence-chip"
          style={{ pointerEvents: interactive ? "auto" : "none" }}
          onClick={toggleMirrorPresence}
          aria-pressed={active}
          aria-label={
            active
              ? "Presence detected — dashboard collapsed to side pill. Tap to show full dashboard."
              : "Room clear — full mirror dashboard visible. Tap to simulate presence detection."
          }
        >
          <span className={`${s.presenceDot} ${active ? s.presenceDotLit : ""}`} aria-hidden="true" />
          <span>{active ? "camera_presence: on" : "camera_presence: off"}</span>
        </button>

        {/* the capsule the dashboard leaves on the right edge while someone is there: the time, the weather,
            what's playing and how many lights are on, as the Mirror's own dashboard card has it */}
        <button
          type="button"
          className={`${s.mirrorSidePill} ${active ? s.mirrorSidePillOn : ""}`}
          data-story-ctl="mirror-side-pill"
          aria-hidden={!active}
          tabIndex={active && interactive ? 0 : -1}
          style={{ pointerEvents: active && interactive ? "auto" : "none" }}
          onClick={(e) => {
            // the pill fades away: focus goes to the presence chip beside it, if the pill had it
            const chip = e.currentTarget === document.activeElement && e.currentTarget.parentElement?.querySelector<HTMLElement>('[data-story-ctl="mirror-presence-chip"]');
            toggleMirrorPresence();
            if (chip) chip.focus();
          }}
          aria-label="Restore full mirror dashboard"
        >
          <span className={s.sidePillTime}>19:40</span>
          <span className={s.sidePillSep} aria-hidden="true" />
          <span className={s.sidePillItem}>
            <svg viewBox="0 0 24 24" className={s.sidePillIcon} aria-hidden="true">
              <circle cx="12" cy="12" r="4" fill="#ffc107" />
              <path d="M12 3v2M12 19v2M3 12h2M19 12h2M5.6 5.6 7 7M17 17l1.4 1.4M5.6 18.4 7 17M17 7l1.4-1.4" stroke="#ffc107" strokeWidth="1.8" strokeLinecap="round" />
            </svg>
            71°
          </span>
          {sendspinOn && (
            <>
              <span className={s.sidePillSep} aria-hidden="true" />
              <span className={s.sidePillEq} aria-hidden="true">
                <i />
                <i />
                <i />
              </span>
            </>
          )}
          <span className={s.sidePillSep} aria-hidden="true" />
          <span className={`${s.sidePillItem} ${activeLightsCount > 0 ? s.sidePillLightOn : ""}`}>
            <span className={s.sidePillLight} aria-hidden="true" />
            {activeLightsCount}
          </span>
        </button>
      </div>
    );
  };

  /*
   * Beat 4 — REAL Bottom-Right TimerDock + RingView ALONE (TimerDock.kt + RingView.kt)
   */
  const renderRealTimerOverlay = (opacity: number) => {
    if (opacity <= 0.01) return null;
    const liftY = (1 - opacity) * 12;
    const interactive = opacity > 0.45;

    return (
      <div className={s.overlaySlot} style={{ opacity, pointerEvents: "none" }} inert={!interactive || undefined}>
        <button
          type="button"
          className={`${s.timerDock} ${timerPaused ? s.timerDockPaused : ""}`}
          data-story-ctl="timer-dock"
          style={{
            transform: `translate3d(0, ${liftY}px, 0)`,
            pointerEvents: interactive ? "auto" : "none",
          }}
          onClick={() => setTimerPaused((v) => !v)}
          aria-label={`Pasta timer ${formatTimer(timerSec)}, ${timerPaused ? "paused, tap to resume" : "running, tap to pause"}`}
        >
          <span className={s.ringWrap}>
            <span className={s.ringHalo} aria-hidden="true" />
            <span className={s.ringGlow} aria-hidden="true" />
            <svg viewBox="0 0 80 80" className={s.ringSvg} aria-hidden="true">
              <circle cx="40" cy="40" r="36.5" className={s.ringTrack} />
              <circle
                cx="40"
                cy="40"
                r="36.5"
                className={timerPaused ? s.ringArcPaused : s.ringArc}
                strokeDasharray={`${(ringCircumference * timerFraction).toFixed(1)} ${ringCircumference.toFixed(1)}`}
              />
            </svg>
            <span className={s.ringSphere}>
              <span className={s.ringCore} aria-hidden="true" />
              <span className={s.ringShine} aria-hidden="true" />
              {timerPaused && (
                <span className={s.ringPauseBars} aria-hidden="true">
                  <span />
                  <span />
                </span>
              )}
              <span className={s.ringDigits}>{formatTimer(timerSec)}</span>
            </span>
          </span>
          <span className={s.ringTag}>Pasta</span>
        </button>
      </div>
    );
  };

  /*
   * Mobile (< 900px), short viewports (< 620px tall), & prefers-reduced-motion:
   * Clean vertical stack of all 6 concise beats — zero scroll-jacking, zero bottom dock,
   * zero horizontal overflow at 375px. Rendered alongside desktop before hydration so CSS
   * media queries pick the right initial layout with zero layout shift.
   */
  const renderCompactFrame = (
    shape: ShapeId,
    overlay?: "voice" | "notif" | "mirror-presence" | "kitchen-timer",
    interactiveSmooth?: boolean,
  ) => {
    const isMirrorCollapsed = overlay === "mirror-presence" && mirrorPresent;
    const dashboardInert = isMirrorCollapsed || overlay === "voice";
    const sm = modelOfShape(shape);
    // the stage that draws the body MOBILE_W wide; on a narrower screen it shrinks, and the screen's
    // content is drawn at the full size and zoomed down, so the dashboard keeps its layout
    const stage = MOBILE_W[shape] / (parseFloat(geometry(sm.model, sm.aspect, sm.view).vars["--wp"] ?? "100") / 100);
    const zoom = Math.min(1, (vw - 32) / stage);
    const isTablet = shape === "tablet";
    return (
      <div className={s.compactStage}>
        <div
          className={`${s.modelCompact} ${interactiveSmooth ? s.morphSmooth : ""}`}
          data-still={!isTablet || undefined}
          onPointerEnter={isTablet ? (e) => e.pointerType === "mouse" && setCompactTabletHover(true) : undefined}
          onPointerLeave={isTablet ? () => setCompactTabletHover(false) : undefined}
          style={{
            width: `min(calc(100vw - 32px), ${stage.toFixed(1)}px)`,
            ["--screen-zoom" as string]: zoom.toFixed(3),
            ["--story-undock" as string]: isTablet && compactTabletLifted ? 1 : 0,
          }}
        >
          {renderModel(
            sm,
            `home-${sm.id}-compact-${overlay ?? "base"}`,
            shape,
            <div className={s.screenInner}>
              {renderLiveLovelace(shape, overlay === "kitchen-timer", isMirrorCollapsed, dashboardInert, `c-${overlay ?? "base"}`)}
              {overlay === "voice" && renderRealVoiceOverlay(1)}
              {overlay === "notif" && renderRealNotificationOverlay(1)}
              {overlay === "mirror-presence" && renderMirrorPresenceOverlay(1)}
              {overlay === "kitchen-timer" && renderRealTimerOverlay(1)}
            </div>,
            overlay === "mirror-presence" ? renderMirrorSensor(sm.model, 1) : undefined,
          )}
        </div>
      </div>
    );
  };

  const compactView = (
    <section className={s.compactStack} aria-label="Glassworks across hardware shapes and features">
      {/* Beat 0: Wall Switch */}
      <div className={s.compactBeat}>
        <div className={s.compactCopy}>
          {post ? (
            <Link href={post.href} className={s.newPill}>
              <span className={s.newTag}>New</span>
              <b>{post.title}</b>
              <span className={s.newArrow}>→</span>
            </Link>
          ) : (
            <a href={release.href} className={s.eyebrow}>
              GLASSWORKS {release.version}
            </a>
          )}
          <h1 className={s.h1}>Your home, on the wall</h1>
          <p className={s.lede}>
            Your Home Assistant dashboard full screen, with local voice, synced music, and built-in radios — installed from Chrome in minutes.
          </p>
          <div className={s.ctas}>
            <a href={KILN} className={s.install}>
              <KilnGlyph />
              Install with Kiln →
            </a>
            <a href="#devices" className={s.quietLink}>
              Explore devices
            </a>
          </div>
        </div>
        {renderCompactFrame("wall")}
      </div>

      {/* Beat 1: Wide Kitchen Display + Assist's Spark edge glow */}
      <div ref={compactAssistRef} className={s.compactBeat}>
        <div className={s.compactCopy}>
          <span className={s.eyebrow}>LOCAL ASSIST VOICE</span>
          <h2 className={s.h2}>A screen that listens locally</h2>
          <p className={s.lede}>
            On-device wake word detection runs right on the hardware and hands off to your Home Assistant Assist pipeline. Spark lights the edge of the screen while you talk, so your dashboard stays in view.
          </p>
          <div className={s.ctas}>
            <Link href="/panes/assist" className={s.quietLink}>
              Assist pane →
            </Link>
            {renderNeeds(1)}
          </div>
        </div>
        {renderCompactFrame("kitchen", "voice")}
      </div>

      {/* Beat 2: Wall Switch + Real Top-Right Notification Card */}
      <div className={s.compactBeat}>
        <div className={s.compactCopy}>
          <span className={s.eyebrow}>RICH NOTIFICATIONS</span>
          <h2 className={s.h2}>A panel that keeps you in the loop</h2>
          <p className={s.lede}>
            Doorbell cameras, interactive action buttons, and priority alerts from your automations appear cleanly on the glass and fold away when you&rsquo;re done.
          </p>
          <div className={s.ctas}>
            <Link href="/docs/notifications" className={s.quietLink}>
              Notification docs →
            </Link>
            {renderNeeds(2)}
          </div>
        </div>
        {renderCompactFrame("wall", "notif")}
      </div>

      {/* Beat 3: Studio Mirror + Presence Detection Collapse */}
      <div className={s.compactBeat}>
        <div className={s.compactCopy}>
          <span className={s.eyebrow}>PRESENCE DETECTION</span>
          <h2 className={s.h2}>A mirror that gets out of your way</h2>
          <p className={s.lede}>
            Glassworks tells Home Assistant the moment you step up, without being fooled by a room light switch. Your dashboard can then tuck itself into a slim side pill, so the glass stays a real mirror.
          </p>
          <div className={s.ctas}>
            <Link href="/frames/lululemon-mirror" className={s.quietLink}>
              Mirror frame details →
            </Link>
            {renderNeeds(3)}
          </div>
        </div>
        {renderCompactFrame("mirror", "mirror-presence")}
      </div>

      {/* Beat 4: Pixel Tablet on its dock + Real TimerDock + what HA reads (ESPHome, Sendspin, Docked) */}
      <div ref={compactTimerRef} className={s.compactBeat}>
        <div className={s.compactCopy}>
          <span className={s.eyebrow}>TIMERS, MUSIC &amp; SENSORS</span>
          <h2 className={s.h2}>A fully native Home Assistant device</h2>
          <p className={s.lede}>
            Voice timers, updates, and sensors like the tablet&rsquo;s dock connect directly to Home Assistant over ESPHome, while synced audio streams straight from Music Assistant over Sendspin — no custom integration and no cloud.
          </p>
          <div className={s.ctas}>
            <Link href="/frames/google-pixel-tablet" className={s.quietLink}>
              Pixel Tablet details →
            </Link>
            <Link href="/panes/sendspin" className={s.quietLink}>
              Sendspin audio →
            </Link>
            {renderNeeds(4)}
          </div>
        </div>
        {renderCompactFrame("tablet", "kitchen-timer")}
        <div className={s.compactRays} aria-label="Native Home Assistant protocols">
          {wireRays(!compactTabletLifted).map((ray) => (
            <div key={ray.title} className={s.compactRayRow}>
              <span className={s.rayDot} aria-hidden="true" />
              <span className={s.compactRayDash} aria-hidden="true" />
              <span className={s.rayText}>
                <b>{ray.title}</b>
                <small>{ray.detail}</small>
              </span>
              {ray.title === "Docked" && renderDockToggle(compactTabletLifted)}
            </div>
          ))}
        </div>
      </div>

      {/* Beat 5: Square 4" + Future Shapes */}
      <div className={s.compactBeat}>
        {renderCompactFrame(futureShape, undefined, true)}
        <div className={s.compactGhosts}>
          <button
            type="button"
            className={`${s.compactGhostBtn} ${futureShape === "round" ? s.ghostActive : ""}`}
            onClick={() => selectFuture(futureShape === "round" ? "square" : "round")}
            aria-pressed={futureShape === "round"}
          >
            {futureShape === "round" ? 'Square 4"' : "Round Dial"}
          </button>
          <button
            type="button"
            className={`${s.compactGhostBtn} ${futureShape === "bedside" ? s.ghostActive : ""}`}
            onClick={() => selectFuture(futureShape === "bedside" ? "square" : "bedside")}
            aria-pressed={futureShape === "bedside"}
          >
            {futureShape === "bedside" ? 'Square 4"' : "Bedside Display"}
          </button>
        </div>
        <div className={s.compactCopy}>
          <span className={s.eyebrow}>ANY SHAPE</span>
          <h2 className={s.h2}>One open core for whatever&rsquo;s next</h2>
          <p className={s.lede}>
            Square switch boxes, round dials, bedside screens, or your own custom hardware — built on one open ESPHome core and installed safely with Kiln.
          </p>
          <div className={s.ctas}>
            <a href={KILN} className={s.install}>
              <KilnGlyph />
              Install with Kiln →
            </a>
            <Link href="/developers" className={s.quietLink}>
              For developers →
            </Link>
          </div>
        </div>
      </div>
    </section>
  );

  if (mounted && compact) {
    return compactView;
  }

  const ghostsActive = seg >= 4.98;

  const desktopView = (
    <section ref={sectionRef} className={s.story} aria-label="Glassworks across hardware shapes and features">
      <div className={s.sticky}>
        {/* Ambient radial light following the morphing glass */}
        <div
          className={s.ambient}
          aria-hidden="true"
          style={{
            background: `radial-gradient(38vw 46vh at ${basePose.glowX}% ${basePose.glowY}%, color-mix(in srgb, var(--sky) 13%, transparent), transparent 68%), radial-gradient(32vw 40vh at ${100 - basePose.glowX * 0.65}% ${basePose.glowY + 12}%, color-mix(in srgb, var(--violet) 9%, transparent), transparent 70%)`,
          }}
        />

        {/* Whisper-quiet right-edge vertical progress dots (6 tiny 4px dots, active elongates to 18px sky) */}
        <nav className={s.edgeRail} aria-label="Story progress">
          {BEAT_DOTS.map((dot) => {
            const active = activeBeat === dot.idx;
            return (
              <button
                key={dot.idx}
                type="button"
                className={`${s.edgeDot} ${active ? s.edgeDotActive : ""}`}
                onClick={() => jumpToBeat(dot.targetP)}
                aria-label={`Jump to ${dot.label}`}
                aria-current={active ? "step" : undefined}
              />
            );
          })}
        </nav>

        <div className={s.canvas}>
          {/* Beat 0 Copy (Left — Aqara S1 Plus Wall Switch) */}
          <div
            className={`${s.copyBlock} ${s.copyLeft}`}
            style={{
              opacity: copy0O,
              transform: `translateY(${(1 - copy0O) * -18}px)`,
              pointerEvents: copy0O > 0.35 ? "auto" : "none",
            }}
            aria-hidden={copy0O < 0.2}
            inert={copy0O <= 0.35 || undefined}
          >
            {post ? (
              <Link href={post.href} className={s.newPill}>
                <span className={s.newTag}>New</span>
                <b>{post.title}</b>
                <span className={s.newArrow}>→</span>
              </Link>
            ) : (
              <a href={release.href} className={s.eyebrow}>
                GLASSWORKS {release.version}
              </a>
            )}
            <h1 className={s.h1}>Your home, on the wall</h1>
            <p className={s.lede}>
              Your Home Assistant dashboard full screen, with local voice, synced music, and built-in radios — installed from Chrome in minutes.
            </p>
            <div className={s.ctas}>
              <a href={KILN} className={s.install}>
                <KilnGlyph />
                Install with Kiln →
              </a>
              <a href="#devices" className={s.quietLink}>
                Explore devices
              </a>
            </div>
          </div>

          {/* Beat 1 Copy (Right — Wide Kitchen Display + Assist's Spark edge glow) */}
          <div
            className={`${s.copyBlock} ${s.copyRight}`}
            style={{
              opacity: copy1O,
              transform: `translateY(${(1 - copy1O) * 16}px)`,
              pointerEvents: copy1O > 0.35 ? "auto" : "none",
            }}
            aria-hidden={copy1O < 0.2}
            inert={copy1O <= 0.35 || undefined}
          >
            <span className={s.eyebrow}>LOCAL ASSIST VOICE</span>
            <h2 className={s.h2}>A screen that listens locally</h2>
            <p className={s.lede}>
              On-device wake word detection runs right on the hardware and hands off to your Home Assistant Assist pipeline. Spark lights the edge of the screen while you talk, so your dashboard stays in view.
            </p>
            <div className={s.ctas}>
              <Link href="/panes/assist" className={s.quietLink}>
                Assist pane →
              </Link>
              {renderNeeds(1)}
            </div>
          </div>

          {/* Beat 2 Copy (Left — Wall Switch + Real Top-Right Notification Card) */}
          <div
            className={`${s.copyBlock} ${s.copyLeft}`}
            style={{
              opacity: copy2O,
              transform: `translateY(${(1 - copy2O) * 16}px)`,
              pointerEvents: copy2O > 0.35 ? "auto" : "none",
            }}
            aria-hidden={copy2O < 0.2}
            inert={copy2O <= 0.35 || undefined}
          >
            <span className={s.eyebrow}>RICH NOTIFICATIONS</span>
            <h2 className={s.h2}>A panel that keeps you in the loop</h2>
            <p className={s.lede}>
              Doorbell cameras, interactive action buttons, and priority alerts from your automations appear cleanly on the glass and fold away when you&rsquo;re done.
            </p>
            <div className={s.ctas}>
              <Link href="/docs/notifications" className={s.quietLink}>
                Notification docs →
              </Link>
              {renderNeeds(2)}
            </div>
          </div>

          {/* Beat 3 Copy (Right — lululemon Studio Mirror + Presence Collapse) */}
          <div
            className={`${s.copyBlock} ${s.copyRight}`}
            style={{
              opacity: copy3O,
              transform: `translateY(${(1 - copy3O) * 16}px)`,
              pointerEvents: copy3O > 0.35 ? "auto" : "none",
            }}
            aria-hidden={copy3O < 0.2}
            inert={copy3O <= 0.35 || undefined}
          >
            <span className={s.eyebrow}>PRESENCE DETECTION</span>
            <h2 className={s.h2}>A mirror that gets out of your way</h2>
            <p className={s.lede}>
              Glassworks tells Home Assistant the moment you step up, without being fooled by a room light switch. Your dashboard can then tuck itself into a slim side pill, so the glass stays a real mirror.
            </p>
            <div className={s.ctas}>
              <Link href="/frames/lululemon-mirror" className={s.quietLink}>
                Mirror frame details →
              </Link>
              {renderNeeds(3)}
            </div>
          </div>

          {/* Beat 4 Copy (Left — Pixel Tablet on its dock + TimerDock / Sendspin / Docked) */}
          <div
            className={`${s.copyBlock} ${s.copyLeft} ${s.copyNarrow}`}
            style={{
              opacity: copy4O,
              transform: `translateY(${(1 - copy4O) * 16}px)`,
              pointerEvents: copy4O > 0.35 ? "auto" : "none",
            }}
            aria-hidden={copy4O < 0.2}
            inert={copy4O <= 0.35 || undefined}
          >
            <span className={s.eyebrow}>TIMERS, MUSIC &amp; SENSORS</span>
            <h2 className={s.h2}>A fully native Home Assistant device</h2>
            <p className={s.lede}>
              Voice timers, updates, and sensors like the tablet&rsquo;s dock connect directly to Home Assistant over ESPHome, while synced audio streams straight from Music Assistant over Sendspin — no custom integration and no cloud.
            </p>
            <div className={s.ctas}>
              <Link href="/frames/google-pixel-tablet" className={s.quietLink}>
                Pixel Tablet details →
              </Link>
              <Link href="/panes/sendspin" className={s.quietLink}>
                Sendspin audio →
              </Link>
              {renderNeeds(4)}
            </div>
          </div>

          {/* Beat 5 Copy (Centered Below Hardware — Square 4" & Future Shapes) */}
          <div
            className={s.copyCenter}
            style={{
              opacity: copy5O,
              transform: `translateY(${(1 - copy5O) * 18}px)`,
              pointerEvents: copy5O > 0.35 ? "auto" : "none",
            }}
            aria-hidden={copy5O < 0.2}
            inert={copy5O <= 0.35 || undefined}
          >
            <span className={s.eyebrow}>ANY SHAPE</span>
            <h2 className={s.h2}>One open core for whatever&rsquo;s next</h2>
            <p className={s.ledeCenter}>
              Square switch boxes, round dials, bedside screens, or your own custom hardware — built on one open ESPHome core and installed safely with Kiln.
            </p>
            <div className={s.ctasCenter}>
              <a href={KILN} className={s.install}>
                <KilnGlyph />
                Install with Kiln →
              </a>
              <Link href="/developers" className={s.quietLink}>
                For developers →
              </Link>
            </div>
          </div>

          {/* Interactive Future Ghost Silhouettes flanking Beat 5 hardware */}
          <div
            className={s.ghostLayer}
            style={{
              opacity: ghostsO,
              pointerEvents: ghostsActive ? "auto" : "none",
            }}
            aria-hidden={!ghostsActive}
            inert={!ghostsActive || undefined}
          >
            <button
              type="button"
              className={`${s.ghost} ${s.ghostRound} ${futureShape === "round" ? s.ghostActive : ""}`}
              onClick={() => selectFuture(futureShape === "round" ? "square" : "round")}
              aria-pressed={futureShape === "round"}
            >
              <span>{futureShape === "round" ? 'SQUARE 4"' : "ROUND DIAL"}</span>
              <small>{futureShape === "round" ? "Return to square" : "Tap to morph"}</small>
            </button>

            <button
              type="button"
              className={`${s.ghost} ${s.ghostBedside} ${futureShape === "bedside" ? s.ghostActive : ""}`}
              onClick={() => selectFuture(futureShape === "bedside" ? "square" : "bedside")}
              aria-pressed={futureShape === "bedside"}
            >
              <span>{futureShape === "bedside" ? 'SQUARE 4"' : "BEDSIDE DISPLAY"}</span>
              <small>{futureShape === "bedside" ? "Return to square" : "Tap to morph"}</small>
            </button>
          </div>

          {/* each dock where its beat's device lands, standing still while the device travels to and from it */}
          {docks.map(({ beat, o }) => {
            const sm = modelOfBeat(beat);
            const kf = keyframes[beat]!;
            return (
              <div key={`dock-${beat}`} className={`${s.hardwareCarrier} ${s.dockCarrier}`} aria-hidden="true" style={{ transform: `translate3d(${kf.xPct}%, ${kf.yPx}px, 0)` }}>
                <div
                  className={`${s.beatModel} ${s.beatDock}`}
                  inert
                  style={{
                    width: `${poses[beat]!.stage.toFixed(1)}px`,
                    ["--dock-o" as string]: o.toFixed(3),
                    filter: `drop-shadow(0 22px 26px rgb(0 0 0 / calc(var(--drop-a) * ${o.toFixed(3)})))`,
                  }}
                >
                  <DeviceModel id={`home-dock-${beat}`} model={sm.model} label="" aspect={sm.aspect} view={sm.view} />
                </div>
              </div>
            );
          })}

          {/* THE TRAVELLING DEVICE (and the glass, for the finale's tap-to-morph) + BEAT 4 LIGHT RAYS */}
          <div
            className={s.hardwareCarrier}
            style={{
              transform: `translate3d(${basePose.xPct}%, ${basePose.yPx}px, 0)`,
            }}
          >
            {/* the glass and the tablet's model share one shadow, cast by whichever shows, so the swap keeps it */}
            <div
              className={s.devices}
              style={{ filter: dropO > 0.01 ? `drop-shadow(0 22px 26px rgb(0 0 0 / calc(var(--drop-a) * ${dropO.toFixed(3)})))` : undefined }}
            >
              {modelO > 0.01 && (
                <div
                  ref={beatModelRef}
                  className={s.beatModel}
                  // only the Pixel Tablet lifts off its dock; the wide display's speaker base holds it
                  data-still={nearBeat !== 4 || undefined}
                  style={{
                    width: `${travelStage.toFixed(1)}px`,
                    opacity: modelO,
                    pointerEvents: modelLive ? "auto" : "none",
                    ["--story-undock" as string]: desktopHoverLift ? 1 : 0,
                  }}
                  inert={!modelLive || undefined}
                  onFocusCapture={trackScreenFocus}
                  onPointerEnter={(e) => e.pointerType === "mouse" && setModelHover(true)}
                  onPointerLeave={() => setModelHover(false)}
                >
                  <div
                    className={s.travel}
                    style={{
                      transform: `translate(${comp.x.toFixed(2)}px, ${comp.y.toFixed(2)}px)${lift ? ` translate(${lift.x.toFixed(2)}px, ${lift.y.toFixed(2)}px) scale(${lift.s.toFixed(4)})` : ""}`,
                      transformOrigin: `calc(50% + ${own.x.toFixed(2)}px) calc(50% + ${own.y.toFixed(2)}px)`,
                      ["--ground-o" as string]: groundO.toFixed(3),
                    }}
                  >
                    {renderModel(travel, "home-travel", currentShape, renderScreenContent("dm"), renderMirrorSensor(travel.model, mirrorFactor))}
                  </div>
                </div>
              )}

              <div
                ref={glassRef}
                className={`${s.hardwareScale} ${clickMorph ? s.morphSmooth : ""}`}
                inert={modelLive || undefined}
                onFocusCapture={trackScreenFocus}
                style={{
                  opacity: modelLive ? 0 : 1,
                  transform: `scale(var(--bezel-scale, 1)) translate(${basePose.dx}px, ${basePose.dy}px) perspective(${basePose.persp}px) rotateX(${basePose.rx}deg) rotateY(${basePose.ry}deg) rotateX(${basePose.lean}deg) translateZ(${basePose.z}px)`,
                }}
              >
                <div className={s.bezelRow}>
                  <div
                    ref={bezelRef}
                    className={`${s.bezel} ${clickMorph ? s.morphSmooth : ""}`}
                    onPointerMove={onPointerMove}
                    onPointerLeave={onPointerLeave}
                    style={{
                      // the Pixel Tablet's front, as its model paints it (Porcelain, or Hazel on a dark page: the
                      // model's light-dark() --dev-face), on the glass itself, border included (.bezel's --face layer)
                      ["--face" as string]: near.face ?? "transparent",
                      ["--face-o" as string]: faceO.toFixed(3),
                      ["--drop-o" as string]: dropO.toFixed(3),
                      width: `${geom.w}px`,
                      height: `${geom.h}px`,
                      borderRadius: `${geom.bodyR}px`,
                    }}
                  >
                    {/* Mirror silvered body surface fading smoothly across forehead & chin */}
                    <div className={s.mirrorBodySurface} aria-hidden="true" style={{ opacity: mirrorFactor }} />
                    {/* Mirror forehead camera/presence lens + sky-blue pulse ripple */}
                    {mirrorFactor > 0.05 && (
                      <div className={s.mirrorSensor} aria-hidden="true" style={{ opacity: mirrorFactor }}>
                        <span
                          className={`${s.sensorLens} ${mirrorPresent && mirrorPresenceO > 0.35 ? s.sensorLensLit : ""}`}
                        />
                        {mirrorPresent && mirrorPresenceO > 0.35 && <span className={s.sensorPulse} />}
                      </div>
                    )}
                    {/* Full-body diagonal reflection sheen */}
                    <div
                      className={s.glare}
                      aria-hidden="true"
                      style={{
                        ["--glare-a" as string]: (
                          0.11 +
                          mirrorFactor * (mirrorPresent && mirrorPresenceO > 0.35 ? 0.17 : 0.09)
                        ).toFixed(2),
                      }}
                    />

                    {/* Inner screen with smoothly interpolated real frame inset */}
                    <div
                      className={`${s.screen} ${clickMorph ? s.morphSmooth : ""}`}
                      data-round={currentShape === "round" || undefined}
                      data-mirror={currentShape === "mirror" || undefined}
                      style={{
                        top: `${geom.insetTop}%`,
                        bottom: `${geom.insetBottom}%`,
                        left: `${geom.insetX}%`,
                        right: `${geom.insetX}%`,
                        borderRadius: `${geom.screenR}px`,
                      }}
                    >
                      {renderScreenContent("dg")}
                    </div>
                  </div>

                </div>

              </div>
            </div>

            <div className={s.raysAnchor} style={{ width: `calc(${geom.w.toFixed(1)}px * var(--bezel-scale, 1))` }}>
              {/* Beat 4: 3 dashed lines from the device to what Home Assistant reads; flat, outside the glass's 3D pose */}
              {raysO > 0.01 && (
                <div
                  className={s.wireRays}
                  style={{
                    opacity: raysO,
                    transform: `translate3d(${(1 - raysO) * -14}px, 0, 0)`,
                  }}
                  aria-hidden={raysO < 0.2}
                >
                  {wireRays(!tabletLifted).map((ray, idx) => (
                    <div key={ray.title} className={s.wireRay}>
                      <svg viewBox="0 0 88 12" className={s.wireRaySvg} aria-hidden="true">
                        <defs>
                          {/* user space: a flat line's box has no height, so a bounding-box gradient would paint nothing */}
                          <linearGradient id={`ray-grad-${idx}`} gradientUnits="userSpaceOnUse" x1="0" y1="6" x2="82" y2="6">
                            <stop offset="0%" stopColor="#38bdf8" stopOpacity="0.9" />
                            <stop offset="100%" stopColor="#a78bfa" stopOpacity="0.9" />
                          </linearGradient>
                        </defs>
                        <line
                          x1="0"
                          y1="6"
                          x2="82"
                          y2="6"
                          stroke={`url(#ray-grad-${idx})`}
                          strokeWidth="1.5"
                          strokeDasharray="5 5"
                          className={s.wireRayDash}
                          style={{ animationDelay: `${idx * -0.45}s` }}
                        />
                        <circle cx="84" cy="6" r="2.5" fill="#a78bfa" />
                      </svg>
                      <div className={s.rayText}>
                        <b>{ray.title}</b>
                        <small>{ray.detail}</small>
                      </div>
                      {ray.title === "Docked" && raysO > 0.45 && modelLive && renderDockToggle(tabletLifted, !tabletSettled)}
                    </div>
                  ))}
                </div>
              )}
            </div>
          </div>
        </div>
      </div>
    </section>
  );

  if (!mounted) {
    return (
      <>
        {desktopView}
        {compactView}
      </>
    );
  }

  return desktopView;
}
