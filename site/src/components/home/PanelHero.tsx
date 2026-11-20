"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import type { FrameDesign } from "@glassworks/frame-card";
import { HeroModel } from "../FrameCard";
import { SupportTag } from "../DeviceBits";
import { KilnGlyph, MicGlyph, TimerGlyph } from "../Glyphs";
import { KILN } from "@/lib/links";
import s from "./PanelHero.module.css";

export type ShowcaseFrame = {
  frame: FrameDesign;
  /** Real millimeter dimensions formatted from frame.model.body */
  dimensions: string;
  /** Optional Clarity grade label (e.g. "Clarity B") */
  clarity?: string;
  /** Direct Kiln URL when the frame installs via Kiln */
  kilnHref?: string;
  page: string;
};

export type HeroRelease = { name: string; version: string; href: string };
export type HeroPost = { title: string; href: string };

type OverlayMode = "dashboard" | "voice" | "timer" | "alert";

const OVERLAYS: { id: OverlayMode; label: string }[] = [
  { id: "dashboard", label: "Dashboard" },
  { id: "voice", label: "Voice" },
  { id: "timer", label: "Timer" },
  { id: "alert", label: "Alert" },
];

function formatTimer(sec: number) {
  const m = Math.floor(sec / 60);
  const rem = sec % 60;
  return `${String(m).padStart(2, "0")}:${String(rem).padStart(2, "0")}`;
}

export function PanelHero({
  frames,
  release,
  post,
}: {
  frames: ShowcaseFrame[];
  release: HeroRelease;
  post?: HeroPost;
}) {
  const [selectedId, setSelectedId] = useState(frames[0]?.frame.id ?? "");
  const [overlay, setOverlay] = useState<OverlayMode>("dashboard");
  const [timerSec, setTimerSec] = useState(252); // 04:12
  const [timerPaused, setTimerPaused] = useState(false);
  const [unlocked, setUnlocked] = useState(false);

  const current = frames.find((f) => f.frame.id === selectedId) ?? frames[0];

  useEffect(() => {
    if (overlay !== "timer" || timerPaused) return;
    const id = window.setInterval(() => {
      setTimerSec((prev) => (prev > 1 ? prev - 1 : 252));
    }, 1000);
    return () => window.clearInterval(id);
  }, [overlay, timerPaused]);

  const installHref = current?.kilnHref ?? KILN;
  const installLabel = current?.kilnHref ? `Install on ${current.frame.name}` : "Install with Kiln";

  return (
    <section className={s.card} aria-labelledby="hero-title">
      <div className={s.copy}>
        {post && (
          <Link className={s.newPill} href={post.href}>
            <span className={s.newTag}>New</span>
            <b>{post.title}</b>
            <span className={s.newRead}>Read the post →</span>
          </Link>
        )}

        <div className={s.status}>
          <a className={s.statusChip} href={release.href}>
            {release.name} <b>{release.version}</b>
          </a>
          <span className={s.statusChip}>
            <b>ESPHome</b> native
          </span>
        </div>

        <h1 id="hero-title" className={s.h1}>
          Your Home Assistant dashboard, on the wall.
        </h1>

        <p className={s.lede}>
          Turn an Aqara wall panel, smart mirror, or Android tablet into one native ESPHome device — full-screen
          dashboard, local Assist voice, synced music, and built-in radios, installed from Chrome.
        </p>

        <div className={s.ctas}>
          <a className={s.install} href={installHref}>
            <KilnGlyph />
            {installLabel}
          </a>
          <a className={s.secondary} href="#devices">
            Explore devices
          </a>
        </div>

        {/* Subtle "On the glass" hairline instrument control strip */}
        <div className={s.instrument}>
          <div className={s.controlRow}>
            <span className={s.controlLabel}>Hardware</span>
            <div className={s.pills} role="tablist" aria-label="Preview hardware models">
              {frames.map((item) => {
                const active = item.frame.id === current?.frame.id;
                return (
                  <button
                    key={item.frame.id}
                    type="button"
                    role="tab"
                    aria-selected={active}
                    className={`${s.pill} ${active ? s.pillOn : ""}`}
                    onClick={() => setSelectedId(item.frame.id)}
                  >
                    {item.frame.name}
                  </button>
                );
              })}
            </div>
          </div>

          <div className={s.controlRow}>
            <span className={s.controlLabel}>On the glass</span>
            <div className={s.pills} role="tablist" aria-label="Glassworks overlay previews">
              {OVERLAYS.map((ov) => {
                const active = overlay === ov.id;
                return (
                  <button
                    key={ov.id}
                    type="button"
                    role="tab"
                    aria-selected={active}
                    className={`${s.pill} ${active ? s.pillLit : ""}`}
                    onClick={() => setOverlay(ov.id)}
                  >
                    {ov.label}
                  </button>
                );
              })}
            </div>
          </div>
        </div>
      </div>

      {current && (
        <div className={s.stage}>
          <figure className={s.figure}>
            <div className={s.modelSlot}>
              <HeroModel key={current.frame.id} f={current.frame} />

              {overlay === "voice" && (
                <div className={s.hud} role="status" aria-live="polite">
                  <span className={s.hudIconLit} aria-hidden="true">
                    <MicGlyph />
                  </span>
                  <div className={s.hudText}>
                    <b>&ldquo;Turn off the downstairs lights&rdquo;</b>
                    <span>Assist · Local wake word</span>
                  </div>
                  <div className={s.wave} aria-hidden="true">
                    {[42, 84, 100, 62, 92, 48].map((h, i) => (
                      <span key={i} style={{ ["--h" as string]: `${h}%`, animationDelay: `${i * 0.12}s` }} />
                    ))}
                  </div>
                </div>
              )}

              {overlay === "timer" && (
                <div className={s.hud} role="status" aria-live="polite">
                  <span className={s.hudIconLit} aria-hidden="true">
                    <TimerGlyph />
                  </span>
                  <div className={s.hudText}>
                    <b>Pasta</b>
                    <span>{timerPaused ? "Paused · On-device timer" : "Running · On-device timer"}</span>
                  </div>
                  <span className={s.hudClock}>{formatTimer(timerSec)}</span>
                  <button type="button" className={s.hudBtn} onClick={() => setTimerPaused((p) => !p)}>
                    {timerPaused ? "Resume" : "Pause"}
                  </button>
                  <span className={s.hudProgress} aria-hidden="true">
                    <span style={{ width: `${Math.max(4, Math.min(100, (timerSec / 300) * 100))}%` }} />
                  </span>
                </div>
              )}

              {overlay === "alert" && (
                <div className={s.hud} role="status" aria-live="polite">
                  <span className={s.hudLiveDot} aria-hidden="true" />
                  <div className={s.hudText}>
                    <b>Front doorbell</b>
                    <span>{unlocked ? "Front door unlocked" : "Person at front porch · Live 0.4 s"}</span>
                  </div>
                  <button
                    type="button"
                    className={`${s.hudBtn} ${unlocked ? s.hudBtnOk : ""}`}
                    onClick={() => setUnlocked((u) => !u)}
                  >
                    {unlocked ? "Unlocked" : "Unlock"}
                  </button>
                </div>
              )}
            </div>

            <figcaption className={s.caption}>
              <Link href={current.page} className={s.captionName}>
                {current.frame.name}
              </Link>
              <span className={s.dot} aria-hidden="true">
                ·
              </span>
              <span className={s.dims}>{current.dimensions}</span>
              <span className={s.dot} aria-hidden="true">
                ·
              </span>
              {current.clarity ? (
                <span className={s.clarityChip}>{current.clarity}</span>
              ) : (
                <SupportTag support={current.frame.status} />
              )}
              <Link href={current.page} className={s.captionLink}>
                Details ›
              </Link>
            </figcaption>
          </figure>
        </div>
      )}
    </section>
  );
}
