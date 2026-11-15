"use client";

import { useTilt } from "@glassworks/frame-card";
import type { ReactNode } from "react";

/**
 * A box whose 3D scene (PaneLift) leans toward a mouse pointer, by the package's useTilt. data-lift
 * is what lifts the pane on hover and keyboard focus.
 */
export function TiltStage({ className, children }: { className?: string; children: ReactNode }) {
  const tilt = useTilt<HTMLDivElement>();
  return (
    <div {...tilt} className={className} data-lift="">
      {children}
    </div>
  );
}
