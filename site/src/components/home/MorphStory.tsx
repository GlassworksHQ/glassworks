"use client";

import Link from "next/link";
import { useEffect, useRef, useState } from "react";
import { KILN } from "@/lib/links";
import { KilnGlyph, MicGlyph, MusicGlyph, TimerGlyph } from "../Glyphs";
import s from "./MorphStory.module.css";

/*
 * Concept D: One single physical piece of dark precision glass holding steady on generous
 * scroll plateaus, then smoothly morphing its exact physical proportions, inner screen inset,
 * corner radius, tilt, mirror stand, and ambient light.
 *
 * Inside the screen lives ONE real, responsive Home Assistant Lovelace Sections dashboard
 * powered by CSS Grid and @container size queries — so the actual HA cards physically reflow
 * as the glass changes shape (zero image crossfading). Visitors can also test live Glassworks
 * overlays (Assist, Notification, Timer, Music) over whichever shape is currently on screen.
 */

type ShapeId = "wall" | "tablet" | "mirror" | "square" | "round" | "bedside";
type RailShape = "wall" | "tablet" | "mirror" | "square" | "round";
type FeatureMode = "dashboard" | "assist" | "notification" | "timer" | "music";

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
};

/* Physically accurate proportions from frames/<id>/design/frame.json */
const KEYFRAMES: Keyframe[] = [
  // 0: Wall Switch · Aqara S1 Plus (172 × 86 mm, bezel 3.5 mm, radius 2.5 mm)
  {
    w: 500,
    h: 250,
    bodyR: 10,
    insetTop: 4,
    insetBottom: 4,
    insetX: 2.2,
    screenR: 5,
    xPct: 22,
    yPx: -12,
    rx: 2,
    ry: -6,
    glowX: 72,
    glowY: 47,
  },
  // 1: Countertop Tablet · Google Pixel Tablet (258 × 169 mm, bezel 11 mm, radius 12 mm)
  {
    w: 468,
    h: 306,
    bodyR: 22,
    insetTop: 6.5,
    insetBottom: 6.5,
    insetX: 4.3,
    screenR: 8,
    xPct: -22,
    yPx: -12,
    rx: 1.5,
    ry: 5.5,
    glowX: 28,
    glowY: 47,
  },
  // 2: Full-Length Mirror · lululemon Studio Mirror (536 × 1336 mm, top 12.7%, bottom 21.1%)
  {
    w: 216,
    h: 520,
    bodyR: 6,
    insetTop: 12.7,
    insetBottom: 21.1,
    insetX: 3.5,
    screenR: 3,
    xPct: 24,
    yPx: -22,
    rx: 1,
    ry: -5,
    glowX: 74,
    glowY: 46,
  },
  // 3: Compact Square · Square 4" Switch (center)
  {
    w: 244,
    h: 244,
    bodyR: 20,
    insetTop: 5,
    insetBottom: 5,
    insetX: 5,
    screenR: 12,
    xPct: 0,
    yPx: -88,
    rx: 0,
    ry: 0,
    glowX: 50,
    glowY: 36,
  },
];

const FUTURE_GEOMETRY: Record<
  "square" | "round" | "bedside",
  Pick<Keyframe, "w" | "h" | "bodyR" | "insetTop" | "insetBottom" | "insetX" | "screenR">
> = {
  square: { w: 244, h: 244, bodyR: 20, insetTop: 5, insetBottom: 5, insetX: 5, screenR: 12 },
  round: { w: 238, h: 238, bodyR: 119, insetTop: 5, insetBottom: 5, insetX: 5, screenR: 119 },
  bedside: { w: 356, h: 174, bodyR: 22, insetTop: 6, insetBottom: 6, insetX: 4, screenR: 14 },
};

const MOBILE_SHAPES: Record<
  ShapeId,
  Pick<Keyframe, "w" | "h" | "bodyR" | "insetTop" | "insetBottom" | "insetX" | "screenR">
> = {
  wall: { w: 336, h: 168, bodyR: 8, insetTop: 4, insetBottom: 4, insetX: 2.2, screenR: 4 },
  tablet: { w: 324, h: 212, bodyR: 18, insetTop: 6.5, insetBottom: 6.5, insetX: 4.3, screenR: 7 },
  mirror: { w: 174, h: 416, bodyR: 5, insetTop: 12.7, insetBottom: 21.1, insetX: 3.5, screenR: 3 },
  square: { w: 218, h: 218, bodyR: 18, insetTop: 5, insetBottom: 5, insetX: 5, screenR: 10 },
  round: { w: 218, h: 218, bodyR: 109, insetTop: 5, insetBottom: 5, insetX: 5, screenR: 109 },
  bedside: { w: 316, h: 156, bodyR: 20, insetTop: 6, insetBottom: 6, insetX: 4, screenR: 12 },
};

/* Scroll plateaus (midpoints for pill navigation) */
const RAIL_SHAPES: { id: RailShape; label: string; targetP: number; future?: "square" | "round" }[] = [
  { id: "wall", label: "Wall", targetP: 0.06 },
  { id: "tablet", label: "Tablet", targetP: 0.35 },
  { id: "mirror", label: "Mirror", targetP: 0.63 },
  { id: "square", label: "Square", targetP: 0.92, future: "square" },
  { id: "round", label: "Round", targetP: 0.92, future: "round" },
];

const FEATURE_ITEMS: { id: FeatureMode; label: string }[] = [
  { id: "dashboard", label: "Dashboard" },
  { id: "assist", label: "Assist" },
  { id: "notification", label: "Notification" },
  { id: "timer", label: "Timer" },
  { id: "music", label: "Music" },
];

const ASSIST_PROMPTS = [
  {
    short: "5-min timer",
    transcript: "Set a 5-minute pasta timer",
    response: "Timer started for 5 minutes",
  },
  {
    short: "Kitchen lights",
    transcript: "Turn off the kitchen lights",
    response: "Done — kitchen lights turned off",
  },
] as const;

const clamp = (v: number, a = 0, b = 1) => Math.min(b, Math.max(a, v));
const smooth = (t: number) => t * t * (3 - 2 * t);
const lerp = (a: number, b: number, t: number) => a + (b - a) * t;

/**
 * Piecewise dwell + fast-morph curve mapping scroll progress p (0..1) to continuous
 * keyframe coordinate s (0..3):
 *   0.00 – 0.16: HOLD at 0 (Wall)
 *   0.16 – 0.25: Smooth morph 0 → 1
 *   0.25 – 0.44: HOLD at 1 (Tablet)
 *   0.44 – 0.53: Smooth morph 1 → 2
 *   0.53 – 0.72: HOLD at 2 (Mirror)
 *   0.72 – 0.81: Smooth morph 2 → 3
 *   0.81 – 1.00: HOLD at 3 (Square / Future Shapes)
 */
function morphSegment(p: number): number {
  const x = clamp(p);
  if (x <= 0.16) return 0;
  if (x < 0.25) return smooth((x - 0.16) / 0.09);
  if (x <= 0.44) return 1;
  if (x < 0.53) return 1 + smooth((x - 0.44) / 0.09);
  if (x <= 0.72) return 2;
  if (x < 0.81) return 2 + smooth((x - 0.72) / 0.09);
  return 3;
}

function windowO(val: number, center: number, inner: number, outer: number) {
  const d = Math.abs(val - center);
  if (d <= inner) return 1;
  if (d >= outer) return 0;
  return 1 - smooth((d - inner) / (outer - inner));
}

function samplePose(sVal: number): Keyframe {
  const i = Math.min(KEYFRAMES.length - 2, Math.floor(clamp(sVal, 0, KEYFRAMES.length - 1)));
  const t = clamp(sVal - i, 0, 1);
  const a = KEYFRAMES[i]!;
  const b = KEYFRAMES[i + 1]!;
  return {
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

function formatTimer(totalSec: number) {
  const m = Math.floor(totalSec / 60);
  const sec = totalSec % 60;
  return `${String(m).padStart(2, "0")}:${String(sec).padStart(2, "0")}`;
}

export type MorphRelease = { version: string; href: string };
export type MorphPost = { title: string; href: string };

export function MorphStory({
  release,
  post,
}: {
  release: MorphRelease;
  post?: MorphPost;
}) {
  const sectionRef = useRef<HTMLElement>(null);
  const bezelRef = useRef<HTMLDivElement>(null);
  const sheenRaf = useRef(0);

  const [p, setP] = useState(0);
  const [compact, setCompact] = useState(false);
  const [mobileShape, setMobileShape] = useState<ShapeId>("wall");
  const [futureShape, setFutureShape] = useState<"square" | "round" | "bedside">("square");
  const [clickMorph, setClickMorph] = useState(false);

  // Interactive Home Assistant entities (persist seamlessly across every shape!)
  const [kitchenOn, setKitchenOn] = useState(true);
  const [livingOn, setLivingOn] = useState(false);
  const [sendspinOn, setSendspinOn] = useState(true);
  const [temp, setTemp] = useState(70);
  const [doorLocked, setDoorLocked] = useState(true);

  // Interactive native feature overlay state
  const [feature, setFeature] = useState<FeatureMode>("dashboard");

  // Assist live 3-step cycle
  const [assistPromptIdx, setAssistPromptIdx] = useState(0);
  const [assistPhase, setAssistPhase] = useState<"listening" | "transcript" | "done">("listening");

  // Live countdown timer state
  const [timerSec, setTimerSec] = useState(252); // 04:12
  const [timerPaused, setTimerPaused] = useState(false);

  // Sendspin music state
  const [musicPlaying, setMusicPlaying] = useState(true);

  useEffect(() => {
    const mqNarrow = window.matchMedia("(max-width: 900px)");
    const mqReduce = window.matchMedia("(prefers-reduced-motion: reduce)");
    const syncCompact = () => setCompact(mqNarrow.matches || mqReduce.matches);
    syncCompact();
    mqNarrow.addEventListener("change", syncCompact);
    mqReduce.addEventListener("change", syncCompact);
    return () => {
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
      cur = cur + (t - cur) * 0.16;
      const settled = Math.abs(t - cur) < 0.0004;
      if (settled) cur = t;
      setP(cur);
      if (cur < 0.76) {
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

  // Assist automatic 3-step live voice interaction
  useEffect(() => {
    if (feature !== "assist") return;
    const t1 = window.setTimeout(() => setAssistPhase("transcript"), 1150);
    const t2 = window.setTimeout(() => setAssistPhase("done"), 2550);
    return () => {
      window.clearTimeout(t1);
      window.clearTimeout(t2);
    };
  }, [feature, assistPromptIdx]);

  // Live timer tick
  useEffect(() => {
    if (feature !== "timer" || timerPaused) return;
    const id = window.setInterval(() => {
      setTimerSec((prev) => (prev > 1 ? prev - 1 : 252));
    }, 1000);
    return () => window.clearInterval(id);
  }, [feature, timerPaused]);

  const openFeature = (next: FeatureMode) => {
    setFeature(next);
    if (next === "assist") {
      setAssistPhase("listening");
    }
  };

  const selectAssistPrompt = (idx: number) => {
    setAssistPromptIdx(idx);
    setAssistPhase("listening");
  };

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

  const jumpToShape = (item: (typeof RAIL_SHAPES)[number]) => {
    if (compact) {
      setMobileShape(item.id);
      return;
    }
    const el = sectionRef.current;
    if (!el) return;
    const span = el.offsetHeight - window.innerHeight;
    if (item.future) {
      setClickMorph(true);
      setFutureShape(item.future);
    } else {
      setClickMorph(false);
      setFutureShape("square");
    }
    window.scrollTo({ top: el.offsetTop + span * item.targetP, behavior: "smooth" });
  };

  const selectFuture = (next: "square" | "round" | "bedside") => {
    if (compact) {
      setMobileShape(next);
      return;
    }
    setClickMorph(true);
    setFutureShape(next);
  };

  // Continuous piecewise-held keyframe coordinate s in [0..3]
  const seg = morphSegment(p);
  const basePose = samplePose(seg);
  const atFinale = seg >= 2.85;

  let geom = {
    w: basePose.w,
    h: basePose.h,
    bodyR: basePose.bodyR,
    insetTop: basePose.insetTop,
    insetBottom: basePose.insetBottom,
    insetX: basePose.insetX,
    screenR: basePose.screenR,
  };
  if (!compact && atFinale && futureShape !== "square") {
    geom = FUTURE_GEOMETRY[futureShape];
  }

  // Mirror body + tubular stand presence factor (0..1)
  const mirrorFactor = compact ? (mobileShape === "mirror" ? 1 : 0) : windowO(seg, 2, 0.2, 0.78);

  // Copy opacities locked steady across each plateau
  const copy0O = 1 - smooth(clamp((seg - 0.1) / 0.45));
  const copy1O = windowO(seg, 1, 0.25, 0.65);
  const copy2O = windowO(seg, 2, 0.25, 0.65);
  const copy3O = smooth(clamp((seg - 2.45) / 0.45));
  const ghostsO = smooth(clamp((seg - 2.55) / 0.4));

  const activeRail: RailShape = compact
    ? mobileShape === "bedside"
      ? "square"
      : mobileShape
    : seg < 0.5
      ? "wall"
      : seg < 1.5
        ? "tablet"
        : seg < 2.5
          ? "mirror"
          : futureShape === "round"
            ? "round"
            : "square";

  const currentShape: ShapeId = compact
    ? mobileShape
    : seg < 0.5
      ? "wall"
      : seg < 1.5
        ? "tablet"
        : seg < 2.5
          ? "mirror"
          : futureShape;

  const isRoundActive = currentShape === "round";
  const isSoloDial = currentShape === "square" || currentShape === "round";
  const activeLightsCount = (kitchenOn ? 2 : 0) + (livingOn ? 1 : 0);

  const activeAssist = ASSIST_PROMPTS[assistPromptIdx]!;
  const timerPct = Math.max(4, Math.min(100, (timerSec / 300) * 100));
  const haHeatRatio = clamp((temp - 60) / 20);
  const haHeating = temp >= 68;

  /*
   * ONE live, responsive Home Assistant Lovelace Sections dashboard!
   * Cards physically reflow via CSS Grid + @container size queries as the glass morphs.
   */
  const renderLiveLovelace = () => (
    <div className={s.screenInner}>
      <div
        className={s.haRoot}
        data-shape={currentShape}
        data-solo={isSoloDial || undefined}
        data-round={isRoundActive || undefined}
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
            Armed
          </span>
        </div>

        {/* Live Responsive HA Sections Grid — cards physically reflow across shapes */}
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
                  <path d="M12 3v2M12 19v2M3 12h2M19 12h2M5.6 5.6 7 7M17 17l1.4 1.4M5.6 18.4 7 17M17 7l1.4-1.4" stroke="#ffc107" />
                </svg>
              </span>
              <span className={s.haHiLo}>H:74° L:61°</span>
            </div>
          </div>

          {/* Authentic HA Thermostat / Climate Dial Card (becomes solo centerpiece on Square & Round!) */}
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

          {/* Interactive HA Tile Card: Kitchen Lights with real HA brightness bar */}
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

          {/* Interactive HA Tile Card: Hallway / Front Door Lock */}
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

          {/* Interactive HA Tile Card: Sendspin Audio */}
          <button
            type="button"
            className={`${s.haCard} ${s.haTile} ${s.haMediaTile}`}
            onClick={() => setSendspinOn((v) => !v)}
            aria-pressed={sendspinOn}
          >
            <div className={s.haTileRow}>
              <span className={`${s.haCircle} ${sendspinOn ? s.haCircleBlue : s.haCircleOff}`} aria-hidden="true">
                <MusicGlyph />
              </span>
              <span className={s.haTileMeta}>
                <b>Sendspin</b>
                <small>{sendspinOn ? "Playing · 3 rooms" : "Idle"}</small>
              </span>
            </div>
          </button>

          {/* HA Energy / History Sparkline Card (naturally reveals on Tablet & Mirror!) */}
          <div className={`${s.haCard} ${s.haSparkCard}`}>
            <div className={s.haSparkHead}>
              <span className={s.haSparkLabel}>Home Power</span>
              <b className={s.haSparkVal}>1.4 kW</b>
            </div>
            <svg viewBox="0 0 180 42" className={s.haSparkSvg} preserveAspectRatio="none" aria-hidden="true">
              <defs>
                <linearGradient id="ha-spark-fill" x1="0" y1="0" x2="0" y2="1">
                  <stop offset="0" stopColor="#03a9f4" stopOpacity="0.32" />
                  <stop offset="1" stopColor="#03a9f4" stopOpacity="0" />
                </linearGradient>
              </defs>
              <path
                d="M0 32 L20 28 L42 30 L64 20 L86 23 L108 14 L132 18 L156 10 L180 15 L180 42 L0 42 Z"
                fill="url(#ha-spark-fill)"
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

          {/* HA Front Door Live Camera Card (naturally reveals on Tablet & Mirror!) */}
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

      {/* ================= LIVE GLASSWORKS FEATURE OVERLAYS (over ANY shape!) ================= */}
      {feature !== "dashboard" && (
        <div className={`${s.overlayLayer} ${feature === "notification" || feature === "timer" ? s.overlayDim : ""}`}>
          {/* 1. Assist Voice Overlay */}
          {feature === "assist" && (
            <div className={s.assistOverlay} role="status" aria-live="polite">
              <div className={s.assistCard}>
                <div className={s.assistHead}>
                  <span className={s.assistIconLit} aria-hidden="true">
                    <MicGlyph />
                  </span>
                  <span className={s.assistState}>
                    {assistPhase === "listening"
                      ? "Listening…"
                      : assistPhase === "transcript"
                        ? "Assist"
                        : "Home Assistant"}
                  </span>
                  <div className={`${s.wave} ${assistPhase === "done" ? s.waveDone : ""}`} aria-hidden="true">
                    {[38, 86, 100, 58, 92, 46].map((barH, i) => (
                      <span key={i} style={{ ["--h" as string]: `${barH}%`, animationDelay: `${i * 0.11}s` }} />
                    ))}
                  </div>
                </div>
                <p className={s.assistLine}>
                  {assistPhase === "listening" ? "Say a command…" : `“${activeAssist.transcript}”`}
                </p>
                {assistPhase === "done" && <p className={s.assistReply}>{activeAssist.response}</p>}
                <div className={s.assistChips}>
                  {ASSIST_PROMPTS.map((pr, idx) => (
                    <button
                      key={pr.short}
                      type="button"
                      className={`${s.ovChip} ${assistPromptIdx === idx ? s.ovChipOn : ""}`}
                      onClick={() => selectAssistPrompt(idx)}
                    >
                      {pr.short}
                    </button>
                  ))}
                </div>
              </div>
              <div className={s.assistBottomGlow} aria-hidden="true" />
            </div>
          )}

          {/* 2. Rich Home Assistant Notification / Doorbell Overlay */}
          {feature === "notification" && (
            <div className={s.centerOverlay} role="status" aria-live="polite">
              <div className={s.notifCard}>
                <div className={s.notifCam} aria-hidden="true">
                  <svg viewBox="0 0 240 120" className={s.notifSvg}>
                    <rect width="240" height="120" fill="#121622" />
                    <path d="M0 88 L240 88 L240 120 L0 120 Z" fill="#181d2c" />
                    <path d="M74 88 L166 88 L196 120 L44 120 Z" fill="#21283b" />
                    <circle cx="120" cy="52" r="10" fill="#38bdf8" fillOpacity="0.35" stroke="#38bdf8" strokeOpacity="0.7" />
                    <path d="M103 86 C103 71 137 71 137 86 Z" fill="#a78bfa" fillOpacity="0.3" stroke="#a78bfa" strokeOpacity="0.65" />
                  </svg>
                  <span className={s.liveTag}>
                    <span className={s.liveDot} />
                    Live
                  </span>
                </div>
                <div className={s.notifBody}>
                  <b>Front Door</b>
                  <span>{!doorLocked ? "Front door unlocked" : "Person detected at the porch"}</span>
                  <div className={s.ovBtns}>
                    <button
                      type="button"
                      className={!doorLocked ? s.ovBtnOk : s.ovBtnLit}
                      onClick={() => setDoorLocked((l) => !l)}
                    >
                      {!doorLocked ? "✓ Unlocked" : "Unlock door"}
                    </button>
                    <button type="button" className={s.ovBtn} onClick={() => setFeature("dashboard")}>
                      Dismiss
                    </button>
                  </div>
                </div>
              </div>
            </div>
          )}

          {/* 3. Voice Timer Overlay */}
          {feature === "timer" && (
            <div className={s.centerOverlay} role="status" aria-live="polite">
              <div className={s.timerCard}>
                <div className={s.timerTop}>
                  <span className={s.timerTitle}>
                    <TimerGlyph />
                    Pasta
                  </span>
                  <span className={s.timerMeta}>{timerPaused ? "Paused" : "Running"}</span>
                </div>
                <div className={s.timerClock}>{formatTimer(timerSec)}</div>
                <div className={s.timerTrack} aria-hidden="true">
                  <div className={s.timerFill} style={{ width: `${timerPct}%` }} />
                </div>
                <div className={s.ovBtns}>
                  <button type="button" className={s.ovBtnLit} onClick={() => setTimerSec((sec) => sec + 60)}>
                    +1 min
                  </button>
                  <button type="button" className={s.ovBtn} onClick={() => setTimerPaused((v) => !v)}>
                    {timerPaused ? "Resume" : "Pause"}
                  </button>
                </div>
              </div>
            </div>
          )}

          {/* 4. Music Assistant / Sendspin Overlay */}
          {feature === "music" && (
            <div className={s.musicOverlay} role="status" aria-live="polite">
              <div className={s.musicBar}>
                <span className={s.musicIconLit} aria-hidden="true">
                  <MusicGlyph />
                </span>
                <div className={s.musicInfo}>
                  <b>Midnight Reflections</b>
                  <span>Kitchen + Living Room</span>
                </div>
                <div className={`${s.musicEq} ${!musicPlaying ? s.musicEqOff : ""}`} aria-hidden="true">
                  {[54, 100, 44, 82].map((barH, i) => (
                    <span key={i} style={{ ["--h" as string]: `${barH}%`, animationDelay: `${i * 0.14}s` }} />
                  ))}
                </div>
                <button
                  type="button"
                  className={s.ovBtn}
                  onClick={() => setMusicPlaying((m) => !m)}
                  aria-label={musicPlaying ? "Pause music" : "Play music"}
                >
                  {musicPlaying ? "Pause" : "Play"}
                </button>
              </div>
            </div>
          )}
        </div>
      )}
    </div>
  );

  const renderControlDock = (isCompact: boolean) => (
    <div className={isCompact ? s.compactDock : s.dock}>
      <div className={s.dockGroup} role="tablist" aria-label="Hardware shapes">
        <span className={s.dockLabel}>Shape</span>
        {RAIL_SHAPES.map((item) => {
          const active = activeRail === item.id;
          return (
            <button
              key={item.id}
              type="button"
              role="tab"
              aria-selected={active}
              className={`${s.railBtn} ${active ? s.railActive : ""}`}
              onClick={() => jumpToShape(item)}
            >
              {item.label}
            </button>
          );
        })}
        {isCompact && (
          <button
            type="button"
            role="tab"
            aria-selected={mobileShape === "bedside"}
            className={`${s.railBtn} ${mobileShape === "bedside" ? s.railActive : ""}`}
            onClick={() => setMobileShape("bedside")}
          >
            Bedside
          </button>
        )}
      </div>

      <span className={s.dockSep} aria-hidden="true" />

      <div className={s.dockGroup} role="tablist" aria-label="Try Glassworks features">
        <span className={s.dockLabel}>Try feature</span>
        {FEATURE_ITEMS.map((item) => {
          const active = feature === item.id;
          return (
            <button
              key={item.id}
              type="button"
              role="tab"
              aria-selected={active}
              className={`${s.railBtn} ${active ? s.railFeatureActive : ""}`}
              onClick={() => openFeature(item.id)}
            >
              {item.label}
            </button>
          );
        })}
      </div>
    </div>
  );

  // Mobile (< 900px) & prefers-reduced-motion: calm tap-to-morph stage with zero scroll-jack
  if (compact) {
    const dims = MOBILE_SHAPES[mobileShape];
    return (
      <section className={s.compactWrap} aria-label="Glassworks across hardware shapes">
        <div className={s.compactStage}>
          <div className={s.mirrorAssembly}>
            <div
              ref={bezelRef}
              className={`${s.bezel} ${s.morphSmooth} ${mobileShape === "mirror" ? s.bezelMirror : ""}`}
              style={{
                width: `min(calc(100vw - 32px), ${dims.w}px)`,
                height: `${dims.h}px`,
                borderRadius: `${dims.bodyR}px`,
              }}
            >
              <div className={s.glare} aria-hidden="true" />
              <div
                className={`${s.screen} ${s.morphSmooth}`}
                data-round={mobileShape === "round" || undefined}
                style={{
                  top: `${dims.insetTop}%`,
                  bottom: `${dims.insetBottom}%`,
                  left: `${dims.insetX}%`,
                  right: `${dims.insetX}%`,
                  borderRadius: `${dims.screenR}px`,
                }}
              >
                {renderLiveLovelace()}
              </div>
            </div>
            <div
              className={s.mirrorStand}
              aria-hidden="true"
              style={{
                width: `min(calc(100vw - 32px), ${dims.w}px)`,
                opacity: mirrorFactor,
                transform: `translateY(${(1 - mirrorFactor) * -10}px) scaleY(${0.6 + mirrorFactor * 0.4})`,
              }}
            />
          </div>
        </div>

        {renderControlDock(true)}

        <div className={s.compactCopy}>
          {mobileShape === "wall" && (
            <>
              {post ? (
                <Link href={post.href} className={s.newPill}>
                  <span className={s.newTag}>New</span>
                  <b>{post.title}</b>
                </Link>
              ) : (
                <a href={release.href} className={s.eyebrow}>
                  GLASSWORKS {release.version}
                </a>
              )}
              <h1 className={s.h1}>Your home, on the wall.</h1>
              <p className={s.lede}>
                Full-screen Home Assistant dashboard, local voice, synced music, and built-in radios — installed from Chrome in minutes.
              </p>
              <div className={s.ctas}>
                <a href={KILN} className={s.install}>
                  <KilnGlyph />
                  Install with Kiln →
                </a>
                <a href="#devices" className={s.quietLink}>
                  Supported devices
                </a>
              </div>
            </>
          )}

          {mobileShape === "tablet" && (
            <>
              <span className={s.eyebrow}>ANY ANDROID TABLET</span>
              <h1 className={s.h1}>Or the tablet in your drawer.</h1>
              <p className={s.lede}>
                Same dashboard, local Assist voice, and lighting-aware camera presence — no root required. Tested on the Pixel Tablet.
              </p>
              <div className={s.ctas}>
                <Link href="/frames/google-pixel-tablet" className={s.quietLink}>
                  Pixel Tablet details →
                </Link>
              </div>
            </>
          )}

          {mobileShape === "mirror" && (
            <>
              <span className={s.eyebrow}>LULULEMON STUDIO MIRROR</span>
              <h1 className={s.h1}>Even behind a 40-inch mirror.</h1>
              <p className={s.lede}>
                Your morning weather, calendar, and scenes tuck cleanly to the edges while the glass stays a real mirror.
              </p>
              <div className={s.ctas}>
                <Link href="/frames/lululemon-mirror" className={s.quietLink}>
                  Mirror frame details →
                </Link>
              </div>
            </>
          )}

          {(mobileShape === "square" || mobileShape === "round" || mobileShape === "bedside") && (
            <>
              <span className={s.eyebrow}>ONE OPEN CORE</span>
              <h1 className={s.h1}>One open core for whatever&rsquo;s next.</h1>
              <p className={s.lede}>
                Square switch boxes, round dials, bedside screens, or your own custom hardware — one ESPHome device, installed safely with Kiln.
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
            </>
          )}
        </div>
      </section>
    );
  }

  return (
    <section ref={sectionRef} className={s.story} aria-label="Glassworks across hardware shapes">
      <div className={s.sticky}>
        {/* Ambient radial light following the morphing glass */}
        <div
          className={s.ambient}
          aria-hidden="true"
          style={{
            background: `radial-gradient(38vw 46vh at ${basePose.glowX}% ${basePose.glowY}%, color-mix(in srgb, var(--sky) 13%, transparent), transparent 68%), radial-gradient(32vw 40vh at ${100 - basePose.glowX * 0.65}% ${basePose.glowY + 12}%, color-mix(in srgb, var(--violet) 9%, transparent), transparent 70%)`,
          }}
        />

        <div className={s.canvas}>
          {/* Keyframe 0 Copy (Left) */}
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
            <h1 className={s.h1}>Your home, on the wall.</h1>
            <p className={s.lede}>
              Full-screen Home Assistant dashboard, local voice, synced music, and built-in radios — installed from Chrome in minutes.
            </p>
            <div className={s.ctas}>
              <a href={KILN} className={s.install}>
                <KilnGlyph />
                Install with Kiln →
              </a>
              <a href="#devices" className={s.quietLink}>
                Supported devices
              </a>
            </div>
          </div>

          {/* Keyframe 1 Copy (Right) */}
          <div
            className={`${s.copyBlock} ${s.copyRight}`}
            style={{
              opacity: copy1O,
              transform: `translateY(${(1 - copy1O) * 16}px)`,
              pointerEvents: copy1O > 0.35 ? "auto" : "none",
            }}
            aria-hidden={copy1O < 0.2}
          >
            <span className={s.eyebrow}>ANY ANDROID TABLET</span>
            <h2 className={s.h2}>Or the tablet in your drawer.</h2>
            <p className={s.lede}>
              Same dashboard, local Assist voice, and lighting-aware camera presence — no root required. Tested on the Pixel Tablet.
            </p>
            <div className={s.ctas}>
              <Link href="/frames/google-pixel-tablet" className={s.quietLink}>
                Pixel Tablet details →
              </Link>
            </div>
          </div>

          {/* Keyframe 2 Copy (Left) */}
          <div
            className={`${s.copyBlock} ${s.copyLeft}`}
            style={{
              opacity: copy2O,
              transform: `translateY(${(1 - copy2O) * 16}px)`,
              pointerEvents: copy2O > 0.35 ? "auto" : "none",
            }}
            aria-hidden={copy2O < 0.2}
          >
            <span className={s.eyebrow}>LULULEMON STUDIO MIRROR</span>
            <h2 className={s.h2}>Even behind a 40-inch mirror.</h2>
            <p className={s.lede}>
              Your morning weather, calendar, and scenes tuck cleanly to the edges while the glass stays a real mirror.
            </p>
            <div className={s.ctas}>
              <Link href="/frames/lululemon-mirror" className={s.quietLink}>
                Mirror frame details →
              </Link>
            </div>
          </div>

          {/* Keyframe 3 Copy (Centered Below Hardware) */}
          <div
            className={s.copyCenter}
            style={{
              opacity: copy3O,
              transform: `translateY(${(1 - copy3O) * 18}px)`,
              pointerEvents: copy3O > 0.35 ? "auto" : "none",
            }}
            aria-hidden={copy3O < 0.2}
          >
            <h2 className={s.h2}>One open core for whatever&rsquo;s next.</h2>
            <p className={s.ledeCenter}>
              Square switch boxes, round dials, bedside screens, or your own custom hardware — one ESPHome device, installed safely with Kiln.
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

          {/* Interactive Future Ghost Silhouettes flanking the centered Keyframe 3 hardware */}
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

          {/* THE SINGLE MORPHING PIECE OF GLASS + MIRROR STAND */}
          <div
            className={s.hardwareCarrier}
            style={{
              transform: `translate3d(${basePose.xPct}%, ${basePose.yPx}px, 0)`,
            }}
          >
            <div
              className={s.hardwareScale}
              style={{
                transform: `perspective(1200px) rotateX(${basePose.rx}deg) rotateY(${basePose.ry}deg) scale(var(--bezel-scale, 1))`,
              }}
            >
              <div
                ref={bezelRef}
                className={`${s.bezel} ${clickMorph ? s.morphSmooth : ""}`}
                onPointerMove={onPointerMove}
                onPointerLeave={onPointerLeave}
                style={{
                  width: `${geom.w}px`,
                  height: `${geom.h}px`,
                  borderRadius: `${geom.bodyR}px`,
                }}
              >
                {/* Mirror silvered body surface fading smoothly across forehead & chin */}
                <div className={s.mirrorBodySurface} aria-hidden="true" style={{ opacity: mirrorFactor }} />
                {/* Full-body diagonal reflection sheen */}
                <div
                  className={s.glare}
                  aria-hidden="true"
                  style={{ ["--glare-a" as string]: (0.11 + mirrorFactor * 0.1).toFixed(2) }}
                />

                {/* Inner screen with smoothly interpolated real frame inset */}
                <div
                  className={`${s.screen} ${clickMorph ? s.morphSmooth : ""}`}
                  data-round={isRoundActive || undefined}
                  style={{
                    top: `${geom.insetTop}%`,
                    bottom: `${geom.insetBottom}%`,
                    left: `${geom.insetX}%`,
                    right: `${geom.insetX}%`,
                    borderRadius: `${geom.screenR}px`,
                  }}
                >
                  {renderLiveLovelace()}
                </div>
              </div>

              {/* Tubular U-shaped metal stand extending cleanly below the Studio Mirror */}
              <div
                className={s.mirrorStand}
                aria-hidden="true"
                style={{
                  width: `${geom.w}px`,
                  opacity: mirrorFactor,
                  transform: `translateY(${(1 - mirrorFactor) * -12}px) scaleY(${0.55 + mirrorFactor * 0.45})`,
                }}
              />
            </div>
          </div>
        </div>

        {/* Quiet Bottom Control Dock: Shape + Try Feature */}
        <div className={s.railWrap}>{renderControlDock(false)}</div>
      </div>
    </section>
  );
}
