"use client";

import Link from "next/link";
import { useEffect, useRef, useState } from "react";
import { KILN } from "@/lib/links";
import { KilnGlyph } from "../Glyphs";
import s from "./MorphStory.module.css";

/*
 * Concept D: One single physical piece of dark precision glass that glides across an open
 * architectural canvas and morphs its exact width, height, corner radius, tilt, ambient
 * light, and internal Home Assistant layout across four scroll keyframes (plus live Round
 * Dial and Bedside Display morphs at the finale).
 */

type ShapeId = "wall" | "tablet" | "mirror" | "square" | "round" | "bedside";
type RailShape = "wall" | "tablet" | "mirror" | "square" | "round";

type Keyframe = {
  w: number;
  h: number;
  r: number;
  xPct: number; // % of stage width
  yPx: number;
  rx: number;
  ry: number;
  glowX: number; // % of viewport width
  glowY: number; // % of viewport height
};

const KEYFRAMES: Keyframe[] = [
  // 0: Wall Switch · Aqara S1 Plus (right)
  { w: 490, h: 248, r: 24, xPct: 22, yPx: -10, rx: 2, ry: -6, glowX: 72, glowY: 48 },
  // 1: Countertop Tablet · Any Android (left)
  { w: 456, h: 314, r: 28, xPct: -23, yPx: -10, rx: 1.5, ry: 5.5, glowX: 27, glowY: 48 },
  // 2: Full-Length Mirror · lululemon Studio Mirror (right, tall)
  { w: 228, h: 478, r: 26, xPct: 24, yPx: -8, rx: 1, ry: -5, glowX: 74, glowY: 48 },
  // 3: Compact Square · Square 4" Switch (center)
  { w: 236, h: 236, r: 30, xPct: 0, yPx: -84, rx: 0, ry: 0, glowX: 50, glowY: 38 },
];

const MOBILE_SHAPES: Record<ShapeId, { w: number; h: number; r: number }> = {
  wall: { w: 332, h: 174, r: 20 },
  tablet: { w: 320, h: 224, r: 24 },
  mirror: { w: 186, h: 364, r: 24 },
  square: { w: 214, h: 214, r: 28 },
  round: { w: 216, h: 216, r: 108 },
  bedside: { w: 308, h: 152, r: 26 },
};

const RAIL_ITEMS: { id: RailShape; label: string; kf: number }[] = [
  { id: "wall", label: "Wall", kf: 0 },
  { id: "tablet", label: "Tablet", kf: 1 },
  { id: "mirror", label: "Mirror", kf: 2 },
  { id: "square", label: "Square", kf: 3 },
  { id: "round", label: "Round", kf: 3 },
];

const clamp = (v: number, a = 0, b = 1) => Math.min(b, Math.max(a, v));
const smooth = (t: number) => t * t * (3 - 2 * t);
const lerp = (a: number, b: number, t: number) => a + (b - a) * t;

/** Bell curve opacity: 1 around `center`, smoothly 0 outside `[center - span, center + span]`. */
function windowO(p: number, center: number, inner: number, outer: number) {
  const d = Math.abs(p - center);
  if (d <= inner) return 1;
  if (d >= outer) return 0;
  return 1 - smooth((d - inner) / (outer - inner));
}

function sampleKeyframes(p: number): Keyframe {
  const scaled = clamp(p) * (KEYFRAMES.length - 1);
  const i = Math.min(KEYFRAMES.length - 2, Math.floor(scaled));
  const t = smooth(scaled - i);
  const a = KEYFRAMES[i]!;
  const b = KEYFRAMES[i + 1]!;
  return {
    w: lerp(a.w, b.w, t),
    h: lerp(a.h, b.h, t),
    r: lerp(a.r, b.r, t),
    xPct: lerp(a.xPct, b.xPct, t),
    yPx: lerp(a.yPx, b.yPx, t),
    rx: lerp(a.rx, b.rx, t),
    ry: lerp(a.ry, b.ry, t),
    glowX: lerp(a.glowX, b.glowX, t),
    glowY: lerp(a.glowY, b.glowY, t),
  };
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

  // Interactive Home Assistant states inside the living glass
  const [kitchenOn, setKitchenOn] = useState(true);
  const [livingOn, setLivingOn] = useState(false);
  const [temp, setTemp] = useState(70);

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
      cur = cur + (t - cur) * 0.14;
      const settled = Math.abs(t - cur) < 0.0005;
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

  const scrollToKf = (kfIndex: number, future: "square" | "round" | "bedside" = "square") => {
    if (compact) {
      const map: ShapeId[] = ["wall", "tablet", "mirror", future];
      setMobileShape(map[kfIndex] ?? "wall");
      return;
    }
    const el = sectionRef.current;
    if (!el) return;
    const targetP = kfIndex / (KEYFRAMES.length - 1);
    const span = el.offsetHeight - window.innerHeight;
    if (kfIndex === 3) {
      setClickMorph(true);
      setFutureShape(future);
    } else {
      setClickMorph(false);
      setFutureShape("square");
    }
    window.scrollTo({ top: el.offsetTop + span * targetP, behavior: "smooth" });
  };

  const selectFuture = (next: "square" | "round" | "bedside") => {
    if (compact) {
      setMobileShape(next);
      return;
    }
    setClickMorph(true);
    setFutureShape(next);
  };

  // Current interpolated desktop pose
  const basePose = sampleKeyframes(p);
  const atFinale = p > 0.85;

  let w = basePose.w;
  let h = basePose.h;
  let r = basePose.r;
  if (!compact && atFinale && futureShape === "round") {
    w = 232;
    h = 232;
    r = 116;
  } else if (!compact && atFinale && futureShape === "bedside") {
    w = 344;
    h = 168;
    r = 28;
  }

  // Copy layer opacities (desktop scroll choreography)
  const copy0O = 1 - smooth(clamp((p - 0.1) / 0.1));
  const copy1O = windowO(p, 0.333, 0.075, 0.155);
  const copy2O = windowO(p, 0.666, 0.075, 0.155);
  const copy3O = smooth(clamp((p - 0.83) / 0.11));
  const ghostsO = smooth(clamp((p - 0.85) / 0.11));

  // Screen opacities inside the single bezel
  const screenWallO = compact ? (mobileShape === "wall" ? 1 : 0) : 1 - smooth(clamp((p - 0.12) / 0.12));
  const screenTabletO = compact ? (mobileShape === "tablet" ? 1 : 0) : windowO(p, 0.333, 0.08, 0.16);
  const screenMirrorO = compact ? (mobileShape === "mirror" ? 1 : 0) : windowO(p, 0.666, 0.08, 0.16);
  const finaleBaseO = compact ? 1 : smooth(clamp((p - 0.82) / 0.12));
  const screenSquareO = compact
    ? mobileShape === "square"
      ? 1
      : 0
    : finaleBaseO * (futureShape === "square" ? 1 : 0);
  const screenRoundO = compact
    ? mobileShape === "round"
      ? 1
      : 0
    : finaleBaseO * (futureShape === "round" ? 1 : 0);
  const screenBedsideO = compact
    ? mobileShape === "bedside"
      ? 1
      : 0
    : finaleBaseO * (futureShape === "bedside" ? 1 : 0);

  const activeRail: RailShape = compact
    ? mobileShape === "bedside"
      ? "square"
      : mobileShape
    : p < 0.17
      ? "wall"
      : p < 0.5
        ? "tablet"
        : p < 0.83
          ? "mirror"
          : futureShape === "round"
            ? "round"
            : "square";

  const tempArcDash = 220;
  const tempArcOffset = tempArcDash * (1 - clamp((temp - 60) / 20));

  const renderScreens = () => (
    <div className={s.screenInner}>
      {/* Screen 0: Wall Switch (2:1 Aqara S1 Plus) */}
      <div className={s.face} style={{ opacity: screenWallO, pointerEvents: screenWallO > 0.5 ? "auto" : "none" }}>
        <div className={s.wallTop}>
          <span className={s.wallClock}>19:40</span>
          <span className={s.assistPill}>
            <span className={s.skyDot} aria-hidden="true" />
            ASSIST READY
          </span>
        </div>
        <div className={s.wallTiles}>
          <button
            type="button"
            className={`${s.tile} ${kitchenOn ? s.tileWarm : ""}`}
            onClick={() => setKitchenOn((v) => !v)}
            aria-pressed={kitchenOn}
          >
            <span className={s.tileKicker}>Kitchen</span>
            <b className={s.tileVal}>{kitchenOn ? "80% warm" : "Off"}</b>
          </button>
          <div className={`${s.tile} ${s.tileSky}`}>
            <span className={s.tileKicker}>Sendspin</span>
            <b className={s.tileVal}>Synced · 3 rooms</b>
            <span className={s.eqMini} aria-hidden="true">
              <span />
              <span />
              <span />
              <span />
            </span>
          </div>
        </div>
        <div className={s.assistRim} aria-hidden="true" />
      </div>

      {/* Screen 1: Countertop Tablet */}
      <div className={s.face} style={{ opacity: screenTabletO, pointerEvents: screenTabletO > 0.5 ? "auto" : "none" }}>
        <div className={s.tabTop}>
          <span className={s.tabTitle}>Home</span>
          <span className={s.clarityTag}>CLARITY A</span>
        </div>
        <div className={s.tabGrid}>
          <button
            type="button"
            className={`${s.tile} ${kitchenOn ? s.tileWarm : ""}`}
            onClick={() => setKitchenOn((v) => !v)}
            aria-pressed={kitchenOn}
          >
            <span className={s.tileKicker}>Kitchen</span>
            <b className={s.tileVal}>{kitchenOn ? "80%" : "Off"}</b>
          </button>
          <button
            type="button"
            className={`${s.tile} ${livingOn ? s.tileSky : ""}`}
            onClick={() => setLivingOn((v) => !v)}
            aria-pressed={livingOn}
          >
            <span className={s.tileKicker}>Living Room</span>
            <b className={s.tileVal}>{livingOn ? "65%" : "Off"}</b>
          </button>
          <div className={s.tile}>
            <span className={s.tileKicker}>Front Door</span>
            <b className={s.tileValLive}>
              <span className={s.greenDot} aria-hidden="true" />
              Camera live
            </b>
          </div>
          <div className={s.tile}>
            <span className={s.tileKicker}>Presence</span>
            <b className={s.tileVal}>Camera · 1.1m</b>
          </div>
        </div>
      </div>

      {/* Screen 2: Full-Length Mirror */}
      <div className={`${s.face} ${s.mirrorFace}`} style={{ opacity: screenMirrorO, pointerEvents: screenMirrorO > 0.5 ? "auto" : "none" }}>
        <div className={s.mirrorGlass} aria-hidden="true" />
        <div className={s.mirrorTop}>
          <span className={s.mirrorClock}>07:15</span>
          <span className={s.mirrorWeather}>64° · Clear</span>
        </div>
        <div className={s.mirrorSpace} aria-hidden="true" />
        <div className={s.mirrorBottom}>
          <div className={s.mirrorPillLit}>
            <span>Morning</span>
            <b>On</b>
          </div>
          <div className={s.mirrorPill}>
            <span>Standup</span>
            <b>09:30</b>
          </div>
        </div>
      </div>

      {/* Screen 3a: Square 4" Switch */}
      <div className={`${s.face} ${s.dialFace}`} style={{ opacity: screenSquareO, pointerEvents: screenSquareO > 0.5 ? "auto" : "none" }}>
        <span className={s.dialKicker}>SQUARE 4&Prime; SWITCH</span>
        <div className={s.dialRingWrap}>
          <svg viewBox="0 0 100 100" className={s.dialSvg} aria-hidden="true">
            <circle cx="50" cy="50" r="38" className={s.dialTrack} />
            <circle
              cx="50"
              cy="50"
              r="38"
              className={s.dialArc}
              strokeDasharray={tempArcDash}
              strokeDashoffset={tempArcOffset}
            />
          </svg>
          <span className={s.dialTemp}>{temp}°</span>
        </div>
        <div className={s.dialControls}>
          <button type="button" className={s.dialStep} onClick={() => setTemp((t) => Math.max(60, t - 1))} aria-label="Decrease temperature">
            −
          </button>
          <span className={s.dialMode}>Heating</span>
          <button type="button" className={s.dialStep} onClick={() => setTemp((t) => Math.min(80, t + 1))} aria-label="Increase temperature">
            +
          </button>
        </div>
      </div>

      {/* Screen 3b: Circular Round Dial */}
      <div className={`${s.face} ${s.roundFace}`} style={{ opacity: screenRoundO, pointerEvents: screenRoundO > 0.5 ? "auto" : "none" }}>
        <svg viewBox="0 0 120 120" className={s.roundSvg} aria-hidden="true">
          <circle cx="60" cy="60" r="50" className={s.dialTrack} />
          <circle
            cx="60"
            cy="60"
            r="50"
            className={s.dialArc}
            strokeDasharray={314}
            strokeDashoffset={314 * (1 - clamp((temp - 60) / 20))}
          />
        </svg>
        <div className={s.roundCenter}>
          <span className={s.dialKicker}>ROUND DIAL</span>
          <span className={s.roundTemp}>{temp}°</span>
          <div className={s.dialControls}>
            <button type="button" className={s.dialStep} onClick={() => setTemp((t) => Math.max(60, t - 1))} aria-label="Decrease temperature">
              −
            </button>
            <button type="button" className={s.dialStep} onClick={() => setTemp((t) => Math.min(80, t + 1))} aria-label="Increase temperature">
              +
            </button>
          </div>
        </div>
      </div>

      {/* Screen 3c: Bedside Display */}
      <div className={`${s.face} ${s.bedsideFace}`} style={{ opacity: screenBedsideO, pointerEvents: screenBedsideO > 0.5 ? "auto" : "none" }}>
        <div className={s.bedsideTop}>
          <span className={s.dialKicker}>BEDSIDE DISPLAY</span>
          <span className={s.bedsidePill}>Night mode</span>
        </div>
        <div className={s.bedsideClock}>06:45</div>
        <div className={s.bedsideFoot}>
          <span>Alarm · 07:00</span>
          <span>Bedroom · Quiet</span>
        </div>
      </div>
    </div>
  );

  // Mobile & prefers-reduced-motion: calm, zero-scroll-jack interactive morphing stage
  if (compact) {
    const dims = MOBILE_SHAPES[mobileShape];
    return (
      <section className={s.compactWrap} aria-label="Glassworks across hardware shapes">
        <div className={s.compactStage}>
          <div
            ref={bezelRef}
            className={`${s.bezel} ${s.morphSmooth} ${mobileShape === "mirror" ? s.bezelMirror : ""}`}
            style={{
              width: `min(100%, ${dims.w}px)`,
              height: `${dims.h}px`,
              borderRadius: `${dims.r}px`,
            }}
          >
            <div className={s.glare} aria-hidden="true" />
            <div className={s.screen} style={{ borderRadius: `${Math.max(12, dims.r - 10)}px` }}>
              {renderScreens()}
            </div>
          </div>
        </div>

        <div className={s.compactRail} role="tablist" aria-label="Hardware form factors">
          {(
            [
              ["wall", "Wall"],
              ["tablet", "Tablet"],
              ["mirror", "Mirror"],
              ["square", "Square"],
              ["round", "Round"],
              ["bedside", "Bedside"],
            ] as const
          ).map(([id, label]) => (
            <button
              key={id}
              type="button"
              role="tab"
              aria-selected={mobileShape === id}
              className={`${s.railBtn} ${mobileShape === id ? s.railActive : ""}`}
              onClick={() => setMobileShape(id)}
            >
              {label}
            </button>
          ))}
        </div>

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
                <Link href="/frames/generic-android" className={s.quietLink}>
                  Generic Android details →
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
        {/* Ambient radial light that follows the single piece of glass */}
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
              <Link href="/frames/generic-android" className={s.quietLink}>
                Generic Android details →
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
              <span>{futureShape === "round" ? "SQUARE 4\"" : "ROUND DIAL"}</span>
              <small>{futureShape === "round" ? "Return to square" : "Tap to morph"}</small>
            </button>

            <button
              type="button"
              className={`${s.ghost} ${s.ghostBedside} ${futureShape === "bedside" ? s.ghostActive : ""}`}
              onClick={() => selectFuture(futureShape === "bedside" ? "square" : "bedside")}
              aria-pressed={futureShape === "bedside"}
            >
              <span>{futureShape === "bedside" ? "SQUARE 4\"" : "BEDSIDE DISPLAY"}</span>
              <small>{futureShape === "bedside" ? "Return to square" : "Tap to morph"}</small>
            </button>
          </div>

          {/* THE SINGLE MORPHING PIECE OF GLASS */}
          <div
            className={s.hardwareCarrier}
            style={{
              transform: `translate3d(${basePose.xPct}%, ${basePose.yPx}px, 0)`,
            }}
          >
            <div
              ref={bezelRef}
              className={`${s.bezel} ${clickMorph ? s.morphSmooth : ""} ${screenMirrorO > 0.5 ? s.bezelMirror : ""}`}
              onPointerMove={onPointerMove}
              onPointerLeave={onPointerLeave}
              style={{
                width: `${w}px`,
                height: `${h}px`,
                borderRadius: `${r}px`,
                transform: `perspective(1200px) rotateX(${basePose.rx}deg) rotateY(${basePose.ry}deg) scale(var(--bezel-scale, 1))`,
              }}
            >
              <div className={s.glare} aria-hidden="true" />
              <div className={s.screen} style={{ borderRadius: `${Math.max(12, r - 10)}px` }}>
                {renderScreens()}
              </div>
            </div>
          </div>
        </div>

        {/* Quiet Bottom Interactive Shape Rail */}
        <div className={s.railWrap}>
          <div className={s.rail} role="tablist" aria-label="Hardware shapes">
            {RAIL_ITEMS.map((item) => {
              const active = activeRail === item.id;
              return (
                <button
                  key={item.id}
                  type="button"
                  role="tab"
                  aria-selected={active}
                  className={`${s.railBtn} ${active ? s.railActive : ""}`}
                  onClick={() => scrollToKf(item.kf, item.id === "round" ? "round" : "square")}
                >
                  {item.label}
                </button>
              );
            })}
          </div>
        </div>
      </div>
    </section>
  );
}
