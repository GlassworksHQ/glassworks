"use client";

import Link from "next/link";
import { useEffect, useRef, useState } from "react";
import { KILN } from "@/lib/links";
import { KilnGlyph, MusicGlyph } from "../Glyphs";
import s from "./MorphStory.module.css";

/*
 * Concept D: One single physical piece of dark precision glass gliding smoothly across 4 beats
 * (380vh total, short gentle settling at each beat — no frozen holds, no bottom pill dock).
 *
 * Underneath lives ONE real, responsive Home Assistant Lovelace Sections dashboard powered by
 * CSS Grid + @container size queries so actual HA cards physically reflow across shapes.
 * Woven directly into the scroll beats are faithful reproductions of Glassworks' REAL Android
 * overlays from this repo:
 *   - Beat 1 (Pixel Tablet): OrbArt.kt + VoiceOverlay.kt (glass sphere, Sky+Violet conic swirl,
 *     ambient glow, 90% scrim, Inter Light transcript & status label).
 *   - Beat 2 (lululemon Studio Mirror): NotificationOverlay.kt (top-right #16171c glass card,
 *     AMBER "IMPORTANT · JUST NOW", rounded porch image, glass pills, SKY→VIOLET TimeLeft bar)
 *     + TimerDock.kt / RingView.kt (bottom-right amber countdown ring, inner glass sphere, PASTA tag).
 */

type ShapeId = "wall" | "tablet" | "mirror" | "square" | "round" | "bedside";
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
    yPx: -4,
    rx: 2,
    ry: -6,
    glowX: 72,
    glowY: 48,
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
    yPx: -4,
    rx: 1.5,
    ry: 5.5,
    glowX: 28,
    glowY: 48,
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
    yPx: -10,
    rx: 1,
    ry: -5,
    glowX: 74,
    glowY: 47,
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
    yPx: -82,
    rx: 0,
    ry: 0,
    glowX: 50,
    glowY: 38,
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
  tablet: { w: 330, h: 216, bodyR: 18, insetTop: 6.5, insetBottom: 6.5, insetX: 4.3, screenR: 7 },
  mirror: { w: 196, h: 472, bodyR: 6, insetTop: 12.7, insetBottom: 21.1, insetX: 3.5, screenR: 3 },
  square: { w: 220, h: 220, bodyR: 18, insetTop: 5, insetBottom: 5, insetX: 5, screenR: 10 },
  round: { w: 220, h: 220, bodyR: 110, insetTop: 5, insetBottom: 5, insetX: 5, screenR: 110 },
  bedside: { w: 316, h: 156, bodyR: 20, insetTop: 6, insetBottom: 6, insetX: 4, screenR: 12 },
};

const clamp = (v: number, a = 0, b = 1) => Math.min(b, Math.max(a, v));
const smooth = (t: number) => t * t * (3 - 2 * t);
const lerp = (a: number, b: number, t: number) => a + (b - a) * t;

/**
 * Fluid scroll curve across 380vh with short, gentle ~5-6% settling at each beat
 * (zero long flat dead zones):
 *   0.00 – 0.05: Beat 0 settle (Wall Switch)
 *   0.05 – 0.30: Smooth morph 0 → 1
 *   0.30 – 0.36: Beat 1 settle (Pixel Tablet + Assist Orb)
 *   0.36 – 0.63: Smooth morph 1 → 2
 *   0.63 – 0.69: Beat 2 settle (Studio Mirror + Notification Card & TimerDock)
 *   0.69 – 0.94: Smooth morph 2 → 3
 *   0.94 – 1.00: Beat 3 settle (Square 4" + Future Shapes)
 */
function morphSegment(p: number): number {
  const x = clamp(p);
  if (x <= 0.05) return 0;
  if (x < 0.3) return smooth((x - 0.05) / 0.25);
  if (x <= 0.36) return 1;
  if (x < 0.63) return 1 + smooth((x - 0.36) / 0.27);
  if (x <= 0.69) return 2;
  if (x < 0.94) return 2 + smooth((x - 0.69) / 0.25);
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
  return `${m}:${String(sec).padStart(2, "0")}`;
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
  const [futureShape, setFutureShape] = useState<"square" | "round" | "bedside">("square");
  const [clickMorph, setClickMorph] = useState(false);

  // Interactive Home Assistant entities (persist live across every shape)
  const [kitchenOn, setKitchenOn] = useState(true);
  const [livingOn, setLivingOn] = useState(false);
  const [sendspinOn, setSendspinOn] = useState(true);
  const [temp, setTemp] = useState(70);
  const [doorLocked, setDoorLocked] = useState(true);

  // Live Assist Voice Overlay phase cycle (Beat 1: Thinking -> Speaking -> Done)
  const [assistPhase, setAssistPhase] = useState<AssistPhase>("thinking");

  // Live TimerDock + RingView state (Beat 2: Pasta 4:12 ticking live)
  const [timerSec, setTimerSec] = useState(252);
  const [timerPaused, setTimerPaused] = useState(false);

  // Notification card dismissed state on Beat 2 (tapping x dismisses briefly or restores)
  const [notifDismissed, setNotifDismissed] = useState(false);

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
      cur = cur + (t - cur) * 0.2;
      const settled = Math.abs(t - cur) < 0.0004;
      if (settled) cur = t;
      setP(cur);
      if (cur < 0.78) {
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

  // Continuous piecewise-fluid keyframe coordinate s in [0..3]
  const seg = morphSegment(p);
  const basePose = samplePose(seg);
  const atFinale = seg >= 2.86;

  // Overlay opacities tied directly to Beat 1 (Assist) and Beat 2 (Notification + TimerDock)
  const assistO = compact ? 1 : windowO(seg, 1, 0.2, 0.56);
  const mirrorOverlaysO = compact ? 1 : windowO(seg, 2, 0.2, 0.56);

  // Calmly cycle the real Assist Orb states (Thinking -> Speaking -> Done) while Beat 1 is visible
  const assistVisible = compact || assistO > 0.15;
  useEffect(() => {
    if (!assistVisible) return;
    const ms = assistPhase === "thinking" ? 2600 : assistPhase === "speaking" ? 2800 : 1800;
    const id = window.setTimeout(() => {
      setAssistPhase((prev) => (prev === "thinking" ? "speaking" : prev === "speaking" ? "done" : "thinking"));
    }, ms);
    return () => window.clearTimeout(id);
  }, [assistVisible, assistPhase]);

  // Tick the real TimerDock RingView live while Beat 2 is visible
  const timerVisible = compact || mirrorOverlaysO > 0.15;
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

  const selectFuture = (next: "square" | "round" | "bedside") => {
    setClickMorph(true);
    setFutureShape(next);
  };

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
  const mirrorFactor = windowO(seg, 2, 0.22, 0.72);

  // Copy opacities synced smoothly to the 4 beats
  const copy0O = 1 - smooth(clamp((seg - 0.12) / 0.44));
  const copy1O = windowO(seg, 1, 0.22, 0.62);
  const copy2O = windowO(seg, 2, 0.22, 0.62);
  const copy3O = smooth(clamp((seg - 2.42) / 0.44));
  const ghostsO = smooth(clamp((seg - 2.52) / 0.38));

  const currentShape: ShapeId =
    seg < 0.5 ? "wall" : seg < 1.5 ? "tablet" : seg < 2.5 ? "mirror" : futureShape;

  const activeLightsCount = (kitchenOn ? 2 : 0) + (livingOn ? 1 : 0);
  const haHeatRatio = clamp((temp - 60) / 20);
  const haHeating = temp >= 68;
  const timerFraction = clamp(timerSec / 300, 0.02, 1);
  const ringCircumference = 2 * Math.PI * 36.5;

  /*
   * ONE live, responsive Home Assistant Lovelace Sections dashboard powered by
   * CSS Grid + @container size queries so actual HA cards reflow as the glass morphs.
   */
  const renderLiveLovelace = (shape: ShapeId) => {
    const isRound = shape === "round";
    const isSolo = shape === "square" || shape === "round";
    return (
      <div
        className={s.haRoot}
        data-shape={shape}
        data-solo={isSolo || undefined}
        data-round={isRound || undefined}
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

          {/* HA Energy Sparkline Card */}
          <div className={`${s.haCard} ${s.haSparkCard}`}>
            <div className={s.haSparkHead}>
              <span className={s.haSparkLabel}>Home Power</span>
              <b className={s.haSparkVal}>1.4 kW</b>
            </div>
            <svg viewBox="0 0 180 42" className={s.haSparkSvg} preserveAspectRatio="none" aria-hidden="true">
              <defs>
                <linearGradient id={`ha-spark-${shape}`} x1="0" y1="0" x2="0" y2="1">
                  <stop offset="0" stopColor="#03a9f4" stopOpacity="0.32" />
                  <stop offset="1" stopColor="#03a9f4" stopOpacity="0" />
                </linearGradient>
              </defs>
              <path
                d="M0 32 L20 28 L42 30 L64 20 L86 23 L108 14 L132 18 L156 10 L180 15 L180 42 L0 42 Z"
                fill={`url(#ha-spark-${shape})`}
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

  /*
   * REAL Assist Voice Overlay (OrbArt.kt + VoiceOverlay.kt):
   *   - 90% Ambient Stage backdrop scrim over the dashboard
   *   - Floating translucent glass sphere (white @ 0.04, 1px white @ 0.13, inset shade, specular 32%/24%)
   *   - Two-color Sky+Violet conic thinking swirl -> Sky listening/speaking core + ripples -> Green done + check
   *   - Calm spaced uppercase label / quoted prompt + Inter Light transcript below, "TAP ANYWHERE TO CANCEL" hint
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
        aria-label="Glassworks Assist voice overlay preview — tap to cycle voice state"
        onKeyDown={(e) => {
          if (e.key === "Enter" || e.key === " ") {
            e.preventDefault();
            nextPhase();
          }
        }}
      >
        <div className={s.orbStage} style={{ transform: `scale(${0.86 + opacity * 0.14})` }}>
          {/* 300dp Ambient radial glow behind sphere (OrbArt.glow) */}
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

          {/* Two slow Sky speaking ripples (VoiceOverlay.Ring) */}
          {assistPhase === "speaking" && (
            <>
              <span className={`${s.orbRipple} ${s.orbRippleA}`} aria-hidden="true" />
              <span className={`${s.orbRipple} ${s.orbRippleB}`} aria-hidden="true" />
            </>
          )}

          {/* Outer Glass Sphere (OrbArt.sphere: white@0.04, 1px white@0.13, inset shade, outer shadow) */}
          <div className={s.orbSphere}>
            {/* Clipped soft cores inside sphere */}
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
              {/* Always-on specular highlight at (32%, 24%) (OrbArt.shine) */}
              <div className={s.orbShine} aria-hidden="true" />
            </div>

            {/* Done Checkmark SVG (VoiceOverlay.Check: M40 57.5 l11 11 l22 -24 in 112x112) */}
            <svg
              viewBox="0 0 112 112"
              className={`${s.orbCheck} ${assistPhase === "done" ? s.orbCheckShow : ""}`}
              aria-hidden="true"
            >
              <path d="M40 57.5 l11 11 l22 -24" />
            </svg>
          </div>
        </div>

        {/* Calm label + main Inter Light text slots (VoiceOverlay.Slot) */}
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
   * REAL NotificationOverlay.kt (top-right night glass card) + REAL TimerDock.kt / RingView.kt
   * (bottom-right amber countdown ring & dark glass orb with PASTA pill tag)
   */
  const renderRealMirrorOverlays = (opacity: number) => {
    if (opacity <= 0.01) return null;
    const slideOffset = (1 - opacity) * 10;

    return (
      <div
        className={s.mirrorOverlayLayer}
        style={{
          opacity,
          pointerEvents: "none",
        }}
      >
        {/* 1. REAL Top-Right NotificationOverlay Card (#16171c glass, 1px white@0.12, AMBER label, SKY→VIOLET TimeLeft) */}
        {!notifDismissed ? (
          <div
            className={s.gwNotifCard}
            style={{
              transform: `translate3d(0, ${-slideOffset}px, 0)`,
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
              <svg viewBox="0 0 220 92" className={s.gwNotifPicSvg} preserveAspectRatio="xMidYMid slice">
                <rect width="220" height="92" fill="#12151f" />
                <path d="M0 64 L220 64 L220 92 L0 92 Z" fill="#181c28" />
                <path d="M68 64 L152 64 L178 92 L42 92 Z" fill="#212738" />
                <circle cx="110" cy="36" r="8.5" fill="#38bdf8" fillOpacity="0.32" stroke="#38bdf8" strokeOpacity="0.65" />
                <path d="M96 63 C96 50 124 50 124 63 Z" fill="#a78bfa" fillOpacity="0.28" stroke="#a78bfa" strokeOpacity="0.6" />
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

        {/* 2. REAL Bottom-Right TimerDock + RingView (80dp ring scaled, amber clockwise arc, dark glass sphere, PASTA tag) */}
        <button
          type="button"
          className={`${s.timerDock} ${timerPaused ? s.timerDockPaused : ""}`}
          style={{
            transform: `translate3d(0, ${slideOffset}px, 0)`,
            pointerEvents: opacity > 0.45 ? "auto" : "none",
          }}
          onClick={() => setTimerPaused((v) => !v)}
          aria-label={`Pasta timer ${formatTimer(timerSec)}, ${timerPaused ? "paused, tap to resume" : "running, tap to pause"}`}
        >
          <span className={s.ringWrap}>
            <span className={s.ringHalo} aria-hidden="true" />
            <span className={s.ringGlow} aria-hidden="true" />
            <svg viewBox="0 0 80 80" className={s.ringSvg} aria-hidden="true">
              {/* Track circle */}
              <circle cx="40" cy="40" r="36.5" className={s.ringTrack} />
              {/* Clockwise progress arc from 12 o'clock */}
              <circle
                cx="40"
                cy="40"
                r="36.5"
                className={timerPaused ? s.ringArcPaused : s.ringArc}
                strokeDasharray={`${(ringCircumference * timerFraction).toFixed(1)} ${ringCircumference.toFixed(1)}`}
              />
            </svg>
            {/* Inner 62dp dark glass sphere with subtle amber core and 32%/24% specular shine */}
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
          {/* Name tag pill under ring (RingView: #0a0b0f @ 0.82, 1px white @ 0.07, uppercase MU) */}
          <span className={s.ringTag}>Pasta</span>
        </button>
      </div>
    );
  };

  /*
   * Mobile (< 900px) & prefers-reduced-motion:
   * Clean vertical stack of the 4 concise beats — zero scroll-jacking, zero bottom pill dock,
   * zero horizontal overflow at 375px.
   */
  if (compact) {
    const renderCompactFrame = (
      shape: ShapeId,
      overlay?: "voice" | "mirror",
      interactiveSmooth?: boolean,
    ) => {
      const d = MOBILE_SHAPES[shape];
      const isMirror = shape === "mirror";
      return (
        <div className={s.compactStage}>
          <div className={s.mirrorAssembly}>
            <div
              className={`${s.bezel} ${interactiveSmooth ? s.morphSmooth : ""} ${isMirror ? s.bezelMirror : ""}`}
              style={{
                width: `min(calc(100vw - 32px), ${d.w}px)`,
                height: `${d.h}px`,
                borderRadius: `${d.bodyR}px`,
              }}
            >
              {isMirror && <div className={s.mirrorBodySurface} aria-hidden="true" />}
              <div
                className={s.glare}
                aria-hidden="true"
                style={{ ["--glare-a" as string]: isMirror ? "0.21" : "0.11" }}
              />
              <div
                className={`${s.screen} ${interactiveSmooth ? s.morphSmooth : ""}`}
                data-round={shape === "round" || undefined}
                style={{
                  top: `${d.insetTop}%`,
                  bottom: `${d.insetBottom}%`,
                  left: `${d.insetX}%`,
                  right: `${d.insetX}%`,
                  borderRadius: `${d.screenR}px`,
                }}
              >
                <div className={s.screenInner}>
                  {renderLiveLovelace(shape)}
                  {overlay === "voice" && renderRealVoiceOverlay(1)}
                  {overlay === "mirror" && renderRealMirrorOverlays(1)}
                </div>
              </div>
            </div>
            {isMirror && (
              <div
                className={s.mirrorStand}
                aria-hidden="true"
                style={{ width: `min(calc(100vw - 32px), ${d.w}px)` }}
              />
            )}
          </div>
        </div>
      );
    };

    return (
      <section className={s.compactStack} aria-label="Glassworks across hardware shapes">
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
          {renderCompactFrame("wall")}
        </div>

        {/* Beat 1: Pixel Tablet + Real Assist Voice Orb */}
        <div className={s.compactBeat}>
          <div className={s.compactCopy}>
            <span className={s.eyebrow}>LOCAL ASSIST VOICE</span>
            <h2 className={s.h2}>Talk to your home, privately.</h2>
            <p className={s.lede}>
              On-device wake word detection and Home Assistant Assist, on a tablet in your drawer or a switch on your wall — with no root required.
            </p>
            <div className={s.ctas}>
              <Link href="/frames/google-pixel-tablet" className={s.quietLink}>
                Pixel Tablet details →
              </Link>
            </div>
          </div>
          {renderCompactFrame("tablet", "voice")}
        </div>

        {/* Beat 2: Studio Mirror + Real Notification Card & TimerDock */}
        <div className={s.compactBeat}>
          <div className={s.compactCopy}>
            <span className={s.eyebrow}>NOTIFICATIONS &amp; TIMERS</span>
            <h2 className={s.h2}>Alerts and timers, right when they matter.</h2>
            <p className={s.lede}>
              Doorbell cards, visual voice timers, and synced Music Assistant playback float cleanly over your dashboard — even behind a 40-inch mirror.
            </p>
            <div className={s.ctas}>
              <Link href="/frames/lululemon-mirror" className={s.quietLink}>
                Mirror frame details →
              </Link>
            </div>
          </div>
          {renderCompactFrame("mirror", "mirror")}
        </div>

        {/* Beat 3: Square 4" + Future Shapes */}
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
            <h2 className={s.h2}>One open core for whatever&rsquo;s next.</h2>
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
          </div>
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

          {/* Beat 1 Copy (Right — Pixel Tablet + Real Assist Voice Orb) */}
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
            <h2 className={s.h2}>Talk to your home, privately.</h2>
            <p className={s.lede}>
              On-device wake word detection and Home Assistant Assist, on a tablet in your drawer or a switch on your wall — with no root required.
            </p>
            <div className={s.ctas}>
              <Link href="/frames/google-pixel-tablet" className={s.quietLink}>
                Pixel Tablet details →
              </Link>
            </div>
          </div>

          {/* Beat 2 Copy (Left — lululemon Studio Mirror + Real Notifications & Timers) */}
          <div
            className={`${s.copyBlock} ${s.copyLeft}`}
            style={{
              opacity: copy2O,
              transform: `translateY(${(1 - copy2O) * 16}px)`,
              pointerEvents: copy2O > 0.35 ? "auto" : "none",
            }}
            aria-hidden={copy2O < 0.2}
          >
            <span className={s.eyebrow}>NOTIFICATIONS &amp; TIMERS</span>
            <h2 className={s.h2}>Alerts and timers, right when they matter.</h2>
            <p className={s.lede}>
              Doorbell cards, visual voice timers, and synced Music Assistant playback float cleanly over your dashboard — even behind a 40-inch mirror.
            </p>
            <div className={s.ctas}>
              <Link href="/frames/lululemon-mirror" className={s.quietLink}>
                Mirror frame details →
              </Link>
            </div>
          </div>

          {/* Beat 3 Copy (Centered Below Hardware — Square 4" & Future Shapes) */}
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

          {/* Interactive Future Ghost Silhouettes flanking the centered Beat 3 hardware */}
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
                  data-round={currentShape === "round" || undefined}
                  style={{
                    top: `${geom.insetTop}%`,
                    bottom: `${geom.insetBottom}%`,
                    left: `${geom.insetX}%`,
                    right: `${geom.insetX}%`,
                    borderRadius: `${geom.screenR}px`,
                  }}
                >
                  <div className={s.screenInner}>
                    {renderLiveLovelace(currentShape)}
                    {renderRealVoiceOverlay(assistO)}
                    {renderRealMirrorOverlays(mirrorOverlaysO)}
                  </div>
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
      </div>
    </section>
  );
}
