"use client";

import Link from "next/link";
import { useEffect, useMemo, useRef, useState } from "react";
import { DeviceModel, geometry, type FrameDesign, type FrameModel, type View } from "@glassworks/frame-card";
import { KILN } from "@/lib/links";
import { KilnGlyph, MusicGlyph } from "../Glyphs";
import s from "./MorphStory.module.css";
import { BEDSIDE_DISPLAY, ROUND_DIAL, SQUARE_SWITCH, WIDE_DISPLAY } from "./storyModels";

/*
 * Concept D (6-Beat Goldilocks Edition). Every beat is a real 3D device, drawn by @glassworks/frame-card:
 * the frames' own models (S1, Mirror, Pixel Tablet) or made-up shapes (storyModels.ts). Between beats one
 * piece of glass travels; at each beat it lands exactly on that device's front and hands over to its model.
 *
 * One single physical piece of dark precision glass gliding across 6 clean, airy scroll beats
 * (680vh total, ~113vh per beat with ~55% steady hold + ~45% smooth cubic morph).
 *
 * Each beat spotlights ONE hardware shape + ONE real Glassworks feature over the live
 * CSS-Grid + @container-reflowing Home Assistant Lovelace dashboard:
 *   Beat 0: Aqara S1 Plus Wall Switch (right) — Clean unobstructed HA dashboard
 *   Beat 1: Wide Kitchen Display (left) — REAL Assist Voice Orb (OrbArt.kt + VoiceOverlay.kt)
 *   Beat 2: Wall Switch Landscape (right) — REAL Top-Right Notification Card (NotificationOverlay.kt)
 *   Beat 3: lululemon Studio Mirror (left) — Presence Detection collapse to side pill + reflective glass
 *   Beat 4: Google Pixel Tablet (right) — REAL TimerDock + Sendspin + 3 light lines to what HA reads, its dock
 *           state among them. As it settles, the glass hands over to the tablet's own 3D model from
 *           @glassworks/frame-card, on its speaker dock; lift it off (hover) and the Docked line reads Off.
 *   Beat 5: Compact Square 4" Switch (center) — Solo HA Thermostat + Round/Bedside ghost silhouettes
 */

type ShapeId = "wall" | "tablet" | "mirror" | "kitchen" | "square" | "round" | "bedside";
type AssistPhase = "thinking" | "speaking" | "done";

type Keyframe = {
  w: number;
  h: number;
  bodyR: number;
  insetTop: number; // %
  insetBottom: number; // %
  insetX: number; // %
  screenR: number;
  xPct: number; // % of stage width
  yPx: number;
  rx: number;
  ry: number;
  glowX: number;
  glowY: number;
  /* the glass's 3D pose; Beat 1 takes all of these from the Pixel Tablet's model (tabletPose) */
  persp?: number; // px
  lean?: number; // deg, a second rotateX after rotateY, as DeviceModel's front
  z?: number; // px, translateZ
  dx?: number; // px
  dy?: number; // px
};

const PERSPECTIVE = 1200;

/* Physically accurate proportions from frames/<id>/design/frame.json */
const KEYFRAMES: Keyframe[] = [
  // 0: Wall Switch · Aqara S1 Plus (Right, 2:1, 500 × 250; square corners, as frame.json)
  {
    w: 500,
    h: 250,
    bodyR: 0,
    insetTop: 4,
    insetBottom: 4,
    insetX: 2.2,
    screenR: 0,
    xPct: 22,
    yPx: -4,
    rx: 2,
    ry: -6,
    glowX: 72,
    glowY: 48,
  },
  // 1: Wide Kitchen Display · Assist voice (Left, 440 × 240)
  {
    w: 440,
    h: 240,
    bodyR: 18,
    insetTop: 5,
    insetBottom: 5,
    insetX: 3.2,
    screenR: 10,
    xPct: -22,
    yPx: -4,
    rx: 1.5,
    ry: 5.5,
    glowX: 28,
    glowY: 48,
  },
  // 2: Wall Switch Landscape · Notifications (Right, 2:1, 500 × 250)
  {
    w: 500,
    h: 250,
    bodyR: 0,
    insetTop: 4,
    insetBottom: 4,
    insetX: 2.2,
    screenR: 0,
    xPct: 22,
    yPx: -4,
    rx: 1.5,
    ry: -5.5,
    glowX: 72,
    glowY: 48,
  },
  // 3: Full-Length Mirror · lululemon Studio Mirror on its stand (Left; its model sizes the rest)
  {
    w: 168,
    h: 419,
    bodyR: 6,
    insetTop: 12.7,
    insetBottom: 21.1,
    insetX: 3.5,
    screenR: 3,
    xPct: -23,
    yPx: -8,
    rx: 1,
    ry: 5,
    glowX: 27,
    glowY: 46,
  },
  // 4: Google Pixel Tablet on its dock · timers, Sendspin, Docked (Right, room for right light rays).
  //    The size, corners, insets and angles here are replaced by tabletPose(), from the tablet's model.
  {
    w: 468,
    h: 306,
    bodyR: 22,
    insetTop: 6.5,
    insetBottom: 6.5,
    insetX: 4.3,
    screenR: 8,
    xPct: 7,
    yPx: -4,
    rx: 1.5,
    ry: -4,
    glowX: 60,
    glowY: 48,
  },
  // 5: Compact Square · Square 4" Switch + Future Shapes (Center, 244 × 244)
  {
    w: 244,
    h: 244,
    bodyR: 20,
    insetTop: 5,
    insetBottom: 5,
    insetX: 5,
    screenR: 12,
    xPct: 0,
    yPx: -82,
    rx: 0,
    ry: 0,
    glowX: 50,
    glowY: 38,
  },
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

const BEAT_DOTS = [
  { idx: 0, label: "Wall switch dashboard", targetP: 0.03 },
  { idx: 1, label: "Local Assist voice orb", targetP: 0.21 },
  { idx: 2, label: "Rich notifications", targetP: 0.39 },
  { idx: 3, label: "Studio mirror presence detection", targetP: 0.55 },
  { idx: 4, label: "Kitchen timers, Sendspin, and radios", targetP: 0.77 },
  { idx: 5, label: "Square, round, and future shapes", targetP: 0.96 },
] as const;

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

/**
 * Goldilocks dwell/morph pacing across 6 beats (total height 680vh, ~113vh per beat):
 * Beat 3 gets a generous 0.52 – 0.65 hold window so scroll triggers Mirror presence collapse
 * cleanly at the midpoint (p >= 0.585):
 *   Beat 0 hold: 0.000 – 0.085 | morph 0.085 – 0.160
 *   Beat 1 hold: 0.160 – 0.255 | morph 0.255 – 0.335
 *   Beat 2 hold: 0.335 – 0.440 | morph 0.440 – 0.520
 *   Beat 3 hold: 0.520 – 0.650 | morph 0.650 – 0.725  (p < 0.585 clear, p >= 0.585 presence detected)
 *   Beat 4 hold: 0.725 – 0.820 | morph 0.820 – 0.895
 *   Beat 5 hold: 0.895 – 1.000
 */
function morphSegment(p: number): number {
  const x = clamp(p);
  if (x <= 0.085) return 0;
  if (x < 0.16) return smooth((x - 0.085) / 0.075);
  if (x <= 0.255) return 1;
  if (x < 0.335) return 1 + smooth((x - 0.255) / 0.08);
  if (x <= 0.44) return 2;
  if (x < 0.52) return 2 + smooth((x - 0.44) / 0.08);
  if (x <= 0.65) return 3;
  if (x < 0.725) return 3 + smooth((x - 0.65) / 0.075);
  if (x <= 0.82) return 4;
  if (x < 0.895) return 4 + smooth((x - 0.82) / 0.075);
  return 5;
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
function modelPose(base: Keyframe, sm: StoryModel): { pose: Keyframe; stage: number; face?: string } {
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
  return { pose, stage, face: g.vars["--dev-face"] };
}

/* Each beat's hold (morphSegment): the p where the glass sits still on its device */
const HOLDS: [number, number][] = [
  [0, 0.085],
  [0.16, 0.255],
  [0.335, 0.44],
  [0.52, 0.65],
  [0.725, 0.82],
  [0.895, 1],
];

/** The model shows only inside its beat's hold, where it and the glass coincide: in just after, out just before */
function holdO(p: number, beat: number) {
  const [a, b] = HOLDS[beat]!;
  const fadeIn = beat === 0 ? 1 : smooth(clamp((p - a - 0.002) / 0.016));
  const fadeOut = beat === HOLDS.length - 1 ? 1 : 1 - smooth(clamp((p - b + 0.018) / 0.016));
  return fadeIn * fadeOut;
}

function formatTimer(totalSec: number) {
  const m = Math.floor(totalSec / 60);
  const sec = totalSec % 60;
  return `${m}:${String(sec).padStart(2, "0")}`;
}

export type MorphRelease = { version: string; href: string };
export type MorphPost = { title: string; href: string };

export function MorphStory({
  release,
  post,
  frames,
}: {
  release: MorphRelease;
  post?: MorphPost;
  /** the frames whose models the story draws: the S1 (Beats 0 and 2), the Mirror (3), the Pixel Tablet (4) */
  frames: { s1: FrameDesign; mirror: FrameDesign; tablet: FrameDesign };
}) {
  const sectionRef = useRef<HTMLElement>(null);
  const bezelRef = useRef<HTMLDivElement>(null);
  const sheenRaf = useRef(0);

  const [p, setP] = useState(0);
  const [compact, setCompact] = useState(false);
  const [reducedMotion, setReducedMotion] = useState(false);
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
  const [assistPhase, setAssistPhase] = useState<AssistPhase>("thinking");

  // Beat 2: Real NotificationOverlay card dismissed/collapsed state
  const [notifDismissed, setNotifDismissed] = useState(false);

  // Beat 3: Studio Mirror presence collapse — scroll-driven on desktop (p >= 0.585), click-to-override
  const [mirrorOverride, setMirrorOverride] = useState<boolean | null>(null);
  const [compactMirrorPresent, setCompactMirrorPresent] = useState(true);

  // Beat 4: Real TimerDock + RingView live countdown (Pasta 4:12)
  const [timerSec, setTimerSec] = useState(252);
  const [timerPaused, setTimerPaused] = useState(false);

  useEffect(() => {
    const mqNarrow = window.matchMedia("(max-width: 900px)");
    const mqReduce = window.matchMedia("(prefers-reduced-motion: reduce)");
    const syncCompact = () => {
      setVw(window.innerWidth);
      setCompact(mqNarrow.matches || mqReduce.matches);
      setReducedMotion(mqReduce.matches);
    };
    syncCompact();
    window.addEventListener("resize", syncCompact);
    mqNarrow.addEventListener("change", syncCompact);
    mqReduce.addEventListener("change", syncCompact);
    return () => {
      window.removeEventListener("resize", syncCompact);
      mqNarrow.removeEventListener("change", syncCompact);
      mqReduce.removeEventListener("change", syncCompact);
    };
  }, []);

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
      if (cur < 0.48 || cur > 0.69) {
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
    () => KEYFRAMES.map((k, i) => modelPose(i === 5 ? { ...k, w: FUTURE_W[futureShape] } : k, i === 5 ? models.future[futureShape] : models.beats[i]!)),
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

  // Auto-cycle Assist Orb states cleanly while Beat 1 is on screen
  const assistVisible = compact || assistO > 0.15;
  useEffect(() => {
    if (!assistVisible || reducedMotion) return;
    const ms = assistPhase === "thinking" ? 2600 : assistPhase === "speaking" ? 2800 : 1800;
    const id = window.setTimeout(() => {
      setAssistPhase((prev) => (prev === "thinking" ? "speaking" : prev === "speaking" ? "done" : "thinking"));
    }, ms);
    return () => window.clearTimeout(id);
  }, [assistVisible, assistPhase, reducedMotion]);

  // Beat 3: Presence driven directly by scroll progress inside Beat 3's hold window (p >= 0.585)
  // with manual click-to-toggle override that resets when scrolling out of Beat 3
  const scrollMirrorPresent = p >= 0.585 && seg >= 2.45 && seg <= 3.55;
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

  // Beat 4: Tick real TimerDock RingView live while Kitchen Display is on screen
  const timerVisible = compact || timerO > 0.15;
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

  // The glass takes on the device's front colour on its way in (the Pixel Tablet's Porcelain or Hazel) and
  // lands exactly on its model's front (modelPose). The model takes over only inside the beat's hold, where
  // the two coincide, so the hand-over adds the sides, stand or dock and nothing doubles; it hands back
  // before the glass moves on. It fades in over the glass, which stays opaque under it (their fronts are the
  // same) so nothing turns see-through, and the glass hides once the model is whole.
  const faceO = compact || !near.face ? 0 : windowO(seg, nearBeat, 0.12, 0.62);
  const modelO = compact || morphing ? 0 : holdO(p, nearBeat);
  const modelLive = modelO > 0.98;
  // a device on a desk or counter casts one soft shadow, from whichever of glass and model shows (a
  // standing mirror's model casts its own on the floor)
  const dropO = compact || nearModel.model.mount !== "desk" ? 0 : windowO(seg, nearBeat, 0.12, 0.62);
  // the tablet lifted off its dock: the model's own hover lift (mouse only), which the Docked line follows
  const [modelHover, setModelHover] = useState(false);
  const tabletLifted = nearBeat === 4 && modelLive && modelHover && !reducedMotion;
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
  const renderLiveLovelace = (shape: ShapeId, highlightSendspin = false, collapsed = false) => {
    const isRound = shape === "round";
    const isSolo = shape === "square" || shape === "round";
    return (
      <div
        className={`${s.haRoot} ${collapsed ? s.haRootCollapsed : ""}`}
        data-shape={shape}
        data-solo={isSolo || undefined}
        data-round={isRound || undefined}
        data-sendspin={highlightSendspin || undefined}
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
            {highlightSendspin ? "Thread + Zigbee" : "Armed"}
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
                onClick={() => setTemp((t) => Math.max(60, t - 1))}
                aria-label="Decrease target temperature"
              >
                −
              </button>
              <span className={s.haDialTarget}>Target</span>
              <button
                type="button"
                className={s.haStepBtn}
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
                <linearGradient id={`ha-spark-${shape}-${highlightSendspin ? "s" : "n"}`} x1="0" y1="0" x2="0" y2="1">
                  <stop offset="0" stopColor="#03a9f4" stopOpacity="0.32" />
                  <stop offset="1" stopColor="#03a9f4" stopOpacity="0" />
                </linearGradient>
              </defs>
              <path
                d="M0 32 L20 28 L42 30 L64 20 L86 23 L108 14 L132 18 L156 10 L180 15 L180 42 L0 42 Z"
                fill={`url(#ha-spark-${shape}-${highlightSendspin ? "s" : "n"})`}
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
  const renderScreenContent = () => (
    <div className={s.screenInner}>
      {renderLiveLovelace(currentShape, raysO > 0.35, mirrorPresent && mirrorPresenceO > 0.35)}
      {renderRealVoiceOverlay(assistO)}
      {renderRealNotificationOverlay(notifO)}
      {renderMirrorPresenceOverlay(mirrorPresenceO)}
      {renderRealTimerOverlay(timerO)}
    </div>
  );

  /* A beat's device as its 3D model (frame.json's, or storyModels.ts), with the live screen on it */
  const renderModel = (sm: StoryModel, id: string, shape: ShapeId, content: React.ReactNode) => (
    <DeviceModel key={sm.id} id={id} model={sm.model} label={sm.label} aspect={sm.aspect} view={sm.view}>
      {/* the glass's own screen box (a size container), so the dashboard lays out the same on both */}
      <div className={s.modelScreen} data-round={shape === "round" || undefined} data-mirror={shape === "mirror" || undefined}>
        {content}
      </div>
    </DeviceModel>
  );

  /*
   * Beat 1 — REAL Assist Voice Overlay (OrbArt.kt + VoiceOverlay.kt)
   */
  const renderRealVoiceOverlay = (opacity: number) => {
    if (opacity <= 0.01) return null;
    const nextPhase = () =>
      setAssistPhase((prev) => (prev === "thinking" ? "speaking" : prev === "speaking" ? "done" : "thinking"));

    return (
      <div
        className={s.voiceScrim}
        style={{
          opacity,
          pointerEvents: opacity > 0.45 ? "auto" : "none",
        }}
        onClick={nextPhase}
        role="button"
        tabIndex={opacity > 0.45 ? 0 : -1}
        aria-label="Glassworks Assist voice overlay — tap to cycle voice state"
        onKeyDown={(e) => {
          if (e.key === "Enter" || e.key === " ") {
            e.preventDefault();
            nextPhase();
          }
        }}
      >
        <div className={s.orbStage} style={{ transform: `scale(${0.86 + opacity * 0.14})` }}>
          <div
            className={`${s.orbGlow} ${s.orbGlowThink}`}
            style={{ opacity: assistPhase === "thinking" ? 1 : 0 }}
            aria-hidden="true"
          />
          <div
            className={`${s.orbGlow} ${s.orbGlowSky}`}
            style={{ opacity: assistPhase === "speaking" ? 1 : 0 }}
            aria-hidden="true"
          />
          <div
            className={`${s.orbGlow} ${s.orbGlowGreen}`}
            style={{ opacity: assistPhase === "done" ? 1 : 0 }}
            aria-hidden="true"
          />

          {assistPhase === "speaking" && (
            <>
              <span className={`${s.orbRipple} ${s.orbRippleA}`} aria-hidden="true" />
              <span className={`${s.orbRipple} ${s.orbRippleB}`} aria-hidden="true" />
            </>
          )}

          <div className={s.orbSphere}>
            <div className={s.orbClip}>
              <div
                className={s.orbSwirl}
                style={{ opacity: assistPhase === "thinking" ? 0.95 : 0 }}
                aria-hidden="true"
              />
              <div
                className={s.orbCoreSky}
                style={{ opacity: assistPhase === "speaking" ? 1 : 0 }}
                aria-hidden="true"
              />
              <div
                className={s.orbCoreGreen}
                style={{ opacity: assistPhase === "done" ? 1 : 0 }}
                aria-hidden="true"
              />
              <div className={s.orbShine} aria-hidden="true" />
            </div>

            <svg
              viewBox="0 0 112 112"
              className={`${s.orbCheck} ${assistPhase === "done" ? s.orbCheckShow : ""}`}
              aria-hidden="true"
            >
              <path d="M40 57.5 l11 11 l22 -24" />
            </svg>
          </div>
        </div>

        <div className={s.voiceTexts}>
          {assistPhase === "thinking" && (
            <>
              <span className={`${s.voiceLabel} ${s.voiceLabelFaint}`}>Thinking</span>
              <p className={s.voiceMain}>“Set a 5-minute pasta timer”</p>
            </>
          )}
          {assistPhase === "speaking" && (
            <>
              <span className={s.voiceQuote}>“Set a 5-minute pasta timer”</span>
              <p className={s.voiceMain}>Timer started for 5 minutes.</p>
            </>
          )}
          {assistPhase === "done" && (
            <>
              <span className={`${s.voiceLabel} ${s.voiceLabelGreen}`}>Done</span>
              <p className={s.voiceMain}>Timer started for 5 minutes.</p>
            </>
          )}
        </div>

        <span
          className={s.voiceHint}
          style={{ opacity: assistPhase === "done" ? 0 : 0.85 }}
          aria-hidden="true"
        >
          Tap anywhere to cancel
        </span>
      </div>
    );
  };

  /*
   * Beat 2 — REAL Top-Right Notification Card ALONE (NotificationOverlay.kt + Glass.kt)
   */
  const renderRealNotificationOverlay = (opacity: number) => {
    if (opacity <= 0.01) return null;
    const slideX = (1 - opacity) * 16;

    return (
      <div className={s.overlaySlot} style={{ opacity, pointerEvents: "none" }}>
        {!notifDismissed ? (
          <div
            className={s.gwNotifCard}
            style={{
              transform: `translate3d(${slideX}px, 0, 0)`,
              pointerEvents: opacity > 0.45 ? "auto" : "none",
            }}
          >
            <div className={s.gwNotifHead}>
              <span className={s.gwNotifLabel}>Important · Just now</span>
              <button
                type="button"
                className={s.gwNotifClose}
                onClick={() => setNotifDismissed(true)}
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
                onClick={() => setDoorLocked((l) => !l)}
              >
                {doorLocked ? "Unlock" : "✓ Unlocked"}
              </button>
              <button
                type="button"
                className={s.gwPillBtn}
                onClick={() => setNotifDismissed(true)}
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
            style={{ pointerEvents: opacity > 0.45 ? "auto" : "none" }}
            onClick={() => setNotifDismissed(false)}
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

    return (
      <div
        className={s.presenceLayer}
        style={{
          opacity,
          pointerEvents: opacity > 0.45 ? "auto" : "none",
        }}
        onClick={toggleMirrorPresence}
        role="button"
        tabIndex={opacity > 0.45 ? 0 : -1}
        aria-pressed={active}
        aria-label={
          active
            ? "Presence detected — dashboard collapsed to side pill. Tap to show full dashboard."
            : "Room clear — full mirror dashboard visible. Tap to simulate presence detection."
        }
        onKeyDown={(e) => {
          if (e.key === "Enter" || e.key === " ") {
            e.preventDefault();
            toggleMirrorPresence();
          }
        }}
      >
        {/* Pure deep reflective mirror glass + diagonal architectural sheen when presence collapses dashboard */}
        <div className={`${s.mirrorReflectGlass} ${active ? s.mirrorReflectGlassOn : ""}`} aria-hidden="true" />

        {/* Subtle monospace presence micro-chip at the top rim */}
        <div className={`${s.presenceChip} ${active ? s.presenceChipDetected : ""}`}>
          <span className={`${s.presenceDot} ${active ? s.presenceDotLit : ""}`} aria-hidden="true" />
          <span>{active ? "binary_sensor.presence: detected" : "binary_sensor.presence: clear"}</span>
        </div>

        {/* Sleek ~36px frosted vertical side pill docked on the right inner edge */}
        <div className={`${s.mirrorSidePill} ${active ? s.mirrorSidePillOn : ""}`} aria-hidden={!active}>
          <span className={s.sidePillTime}>
            <span>07</span>
            <span>15</span>
          </span>
          <span className={s.sidePillSep} aria-hidden="true" />
          <span className={s.sidePillTemp}>64°</span>
          <span
            className={`${s.sidePillLight} ${activeLightsCount > 0 ? s.sidePillLightOn : ""}`}
            aria-hidden="true"
          />
          <span className={s.sidePillExpand} aria-hidden="true">
            ‹
          </span>
        </div>
      </div>
    );
  };

  /*
   * Beat 4 — REAL Bottom-Right TimerDock + RingView ALONE (TimerDock.kt + RingView.kt)
   */
  const renderRealTimerOverlay = (opacity: number) => {
    if (opacity <= 0.01) return null;
    const liftY = (1 - opacity) * 12;

    return (
      <div className={s.overlaySlot} style={{ opacity, pointerEvents: "none" }}>
        <button
          type="button"
          className={`${s.timerDock} ${timerPaused ? s.timerDockPaused : ""}`}
          style={{
            transform: `translate3d(0, ${liftY}px, 0)`,
            pointerEvents: opacity > 0.45 ? "auto" : "none",
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
   * Mobile (< 900px) & prefers-reduced-motion:
   * Clean vertical stack of all 6 concise beats — zero scroll-jacking, zero bottom dock,
   * zero horizontal overflow at 375px.
   */
  if (compact) {
    const renderCompactFrame = (
      shape: ShapeId,
      overlay?: "voice" | "notif" | "mirror-presence" | "kitchen-timer",
      interactiveSmooth?: boolean,
    ) => {
      const isMirrorCollapsed = overlay === "mirror-presence" && mirrorPresent;
      const sm = modelOfShape(shape);
      // the stage that draws the body MOBILE_W wide; on a narrower screen it shrinks, and the screen's
      // content is drawn at the full size and zoomed down, so the dashboard keeps its layout
      const stage = MOBILE_W[shape] / (parseFloat(geometry(sm.model, sm.aspect, sm.view).vars["--wp"] ?? "100") / 100);
      const zoom = Math.min(1, (vw - 32) / stage);
      return (
        <div className={s.compactStage}>
          <div
            className={`${s.modelCompact} ${interactiveSmooth ? s.morphSmooth : ""}`}
            data-still={shape !== "tablet" || undefined}
            style={{ width: `min(calc(100vw - 32px), ${stage.toFixed(1)}px)`, ["--screen-zoom" as string]: zoom.toFixed(3) }}
          >
            {renderModel(
              sm,
              `home-${sm.id}-compact`,
              shape,
              <div className={s.screenInner}>
                {renderLiveLovelace(shape, overlay === "kitchen-timer", isMirrorCollapsed)}
                {overlay === "voice" && renderRealVoiceOverlay(1)}
                {overlay === "notif" && renderRealNotificationOverlay(1)}
                {overlay === "mirror-presence" && renderMirrorPresenceOverlay(1)}
                {overlay === "kitchen-timer" && renderRealTimerOverlay(1)}
              </div>,
            )}
          </div>
        </div>
      );
    };

    return (
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

        {/* Beat 1: Wide Kitchen Display + Real Assist Orb */}
        <div className={s.compactBeat}>
          <div className={s.compactCopy}>
            <span className={s.eyebrow}>LOCAL ASSIST VOICE</span>
            <h2 className={s.h2}>A screen that listens locally</h2>
            <p className={s.lede}>
              On-device wake word detection and Home Assistant Assist run right on the hardware, with the same calm glass orb on every screen.
            </p>
            <div className={s.ctas}>
              <Link href="/panes/assist" className={s.quietLink}>
                Assist pane →
              </Link>
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
              Step up and Glassworks senses you without being fooled by a room light switch, tucking your dashboard into a slim side pill so the glass stays a real mirror.
            </p>
            <div className={s.ctas}>
              <Link href="/frames/lululemon-mirror" className={s.quietLink}>
                Mirror frame details →
              </Link>
            </div>
          </div>
          {renderCompactFrame("mirror", "mirror-presence")}
        </div>

        {/* Beat 4: Pixel Tablet on its dock + Real TimerDock + what HA reads (ESPHome, Sendspin, Docked) */}
        <div className={s.compactBeat}>
          <div className={s.compactCopy}>
            <span className={s.eyebrow}>TIMERS, MUSIC &amp; SENSORS</span>
            <h2 className={s.h2}>A fully native Home Assistant device</h2>
            <p className={s.lede}>
              Voice timers, synced Music Assistant playback and sensors like the tablet&rsquo;s dock reach Home Assistant over ESPHome. No custom integration, no cloud, and updates arrive under Settings → Updates.
            </p>
          </div>
          {renderCompactFrame("tablet", "kitchen-timer")}
          <div className={s.compactRays} aria-label="Native Home Assistant protocols">
            {wireRays(true).map((ray) => (
              <div key={ray.title} className={s.compactRayRow}>
                <span className={s.rayDot} aria-hidden="true" />
                <span className={s.compactRayDash} aria-hidden="true" />
                <span className={s.rayText}>
                  <b>{ray.title}</b>
                  <small>{ray.detail}</small>
                </span>
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
  }

  return (
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

          {/* Beat 1 Copy (Right — Wide Kitchen Display + Real Assist Orb) */}
          <div
            className={`${s.copyBlock} ${s.copyRight}`}
            style={{
              opacity: copy1O,
              transform: `translateY(${(1 - copy1O) * 16}px)`,
              pointerEvents: copy1O > 0.35 ? "auto" : "none",
            }}
            aria-hidden={copy1O < 0.2}
          >
            <span className={s.eyebrow}>LOCAL ASSIST VOICE</span>
            <h2 className={s.h2}>A screen that listens locally</h2>
            <p className={s.lede}>
              On-device wake word detection and Home Assistant Assist run right on the hardware, with the same calm glass orb on every screen.
            </p>
            <div className={s.ctas}>
              <Link href="/panes/assist" className={s.quietLink}>
                Assist pane →
              </Link>
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
          >
            <span className={s.eyebrow}>PRESENCE DETECTION</span>
            <h2 className={s.h2}>A mirror that gets out of your way</h2>
            <p className={s.lede}>
              Step up and Glassworks senses you without being fooled by a room light switch, tucking your dashboard into a slim side pill so the glass stays a real mirror.
            </p>
            <div className={s.ctas}>
              <Link href="/frames/lululemon-mirror" className={s.quietLink}>
                Mirror frame details →
              </Link>
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
          >
            <span className={s.eyebrow}>TIMERS, MUSIC &amp; SENSORS</span>
            <h2 className={s.h2}>A fully native Home Assistant device</h2>
            <p className={s.lede}>
              Voice timers, synced Music Assistant playback and sensors like the tablet&rsquo;s dock reach Home Assistant over ESPHome. No custom integration, no cloud, and updates arrive under Settings → Updates.
            </p>
            <div className={s.ctas}>
              <Link href="/frames/google-pixel-tablet" className={s.quietLink}>
                Pixel Tablet details →
              </Link>
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
              pointerEvents: ghostsO > 0.4 ? "auto" : "none",
            }}
            aria-hidden={ghostsO < 0.2}
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

          {/* THE SINGLE MORPHING PIECE OF GLASS + MIRROR STAND + BEAT 4 LIGHT RAYS */}
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
                  className={s.beatModel}
                  // only the Pixel Tablet lifts off its dock; the wide display's speaker base holds it
                  data-still={nearBeat !== 4 || undefined}
                  style={{ width: `${near.stage.toFixed(1)}px`, opacity: modelO, pointerEvents: modelLive ? "auto" : "none" }}
                  inert={!modelLive}
                  onPointerEnter={(e) => e.pointerType === "mouse" && setModelHover(true)}
                  onPointerLeave={() => setModelHover(false)}
                >
                  {renderModel(nearModel, `home-${nearModel.id}`, currentShape, renderScreenContent())}
                </div>
              )}

              <div
                className={`${s.hardwareScale} ${clickMorph ? s.morphSmooth : ""}`}
                inert={modelLive}
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
                      {renderScreenContent()}
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
}
