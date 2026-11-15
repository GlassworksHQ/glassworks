"use client";

import Link from "next/link";
import { useRef } from "react";
import { preload } from "react-dom";
import { DeviceModel, FrameCardBody, frameCardClass, useTilt, type FrameDesign } from "@glassworks/frame-card";
import s from "./FrameCard.module.css";

/** The frame's render on the model's screen, served from frames/ by src/app/(site)/frames/[slug]/render.webp
 *  (the same image as Kiln's picker); a frame without one keeps the card's example dashboard. */
export const renderUrl = (f: FrameDesign) => (f.render ? `/frames/${f.id}/render.webp` : undefined);

/**
 * A frame as @glassworks/frame-card draws it: the same card as Kiln's picker, here a link to the
 * frame's page, with the site's own line under it (`children`). FrameGrid lays them out.
 */
export function FrameCard({ f, children }: { f: FrameDesign; children?: React.ReactNode }) {
  const tilt = useTilt<HTMLAnchorElement>();
  return (
    <Link {...tilt} href={`/frames/${f.id}`} className={`glass ${frameCardClass} ${s.link}`}>
      <FrameCardBody f={f} as="h3" screen={renderUrl(f)} />
      {children}
    </Link>
  );
}

/**
 * The frame's model in its page's hero. It leans toward a mouse pointer like the cards, and the
 * reflection follows it (the package's --sheen): light moves, glass doesn't. Its render is the page's
 * largest paint, so it loads at once.
 */
export function HeroModel({ f }: { f: FrameDesign }) {
  const { ref, onPointerMove, onPointerLeave } = useTilt<HTMLDivElement>();
  const frame = useRef(0);
  const screen = renderUrl(f);
  // in <head>, so the fetch starts before the body is parsed, as next/image's priority did
  if (screen) preload(screen, { as: "image", fetchPriority: "high" });
  // a mouse only, and one style write per frame at most, like the tilt
  const sheen = (e: React.PointerEvent<HTMLDivElement>) => {
    onPointerMove(e);
    if (e.pointerType !== "mouse" || matchMedia("(prefers-reduced-motion: reduce)").matches) return;
    const el = e.currentTarget;
    const x = e.clientX;
    cancelAnimationFrame(frame.current);
    frame.current = requestAnimationFrame(() => {
      const r = el.getBoundingClientRect();
      el.style.setProperty("--sheen", `${50 + ((x - r.left) / r.width - 0.5) * 70}%`);
    });
  };
  const leave = (e: React.PointerEvent<HTMLDivElement>) => {
    onPointerLeave();
    cancelAnimationFrame(frame.current);
    e.currentTarget.style.removeProperty("--sheen");
  };
  return (
    <div ref={ref} className={s.hero} onPointerMove={sheen} onPointerLeave={leave}>
      <div className={s.lean}>
        <DeviceModel id={`${f.id}-hero`} model={f.model} label={`${f.name} running Glassworks, an illustration`} screen={screen} priority />
      </div>
    </div>
  );
}
