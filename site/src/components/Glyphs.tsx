/** 24-grid glyphs at 1.6px stroke, built from the mark's geometry. */
import type { SVGProps } from "react";

type GlyphProps = SVGProps<SVGSVGElement>;

const base = {
  viewBox: "0 0 24 24",
  fill: "none",
  stroke: "currentColor",
  strokeWidth: 1.6,
  strokeLinecap: "round" as const,
  strokeLinejoin: "round" as const,
  "aria-hidden": true,
};

export const FrameGlyph = (props: GlyphProps) => (
  <svg {...base} {...props}>
    <rect x="2" y="6" width="20" height="12" rx="3.5" />
  </svg>
);

export const PaneGlyph = (props: GlyphProps) => (
  <svg {...base} {...props}>
    <rect x="2" y="6" width="20" height="12" rx="3.5" strokeOpacity={0.35} />
    <rect
      x="5.5"
      y="9.5"
      width="8"
      height="5"
      rx="1.2"
      fill="url(#gw-lit)"
      stroke="none"
    />
  </svg>
);

export const KilnGlyph = (props: GlyphProps) => (
  <svg {...base} {...props}>
    <path d="M4 20V11a8 8 0 0 1 16 0v9z" />
    <path
      d="M9 20v-4.5a3 3 0 0 1 6 0V20z"
      fill="url(#gw-ember)"
      stroke="none"
    />
    <path d="M2.5 20h19" />
  </svg>
);

/* pane icons */
export const MicGlyph = (props: GlyphProps) => (
  <svg {...base} {...props}>
    <rect x="9" y="3" width="6" height="11" rx="3" />
    <path d="M5.5 11a6.5 6.5 0 0 0 13 0M12 17.5V21" />
  </svg>
);

export const MusicGlyph = (props: GlyphProps) => (
  <svg {...base} {...props}>
    <path d="M9 18V6l10-2v12" />
    <circle cx="6.5" cy="18" r="2.5" />
    <circle cx="16.5" cy="16" r="2.5" />
  </svg>
);

export const TimerGlyph = (props: GlyphProps) => (
  <svg {...base} {...props}>
    <circle cx="12" cy="13.5" r="7.5" />
    <path d="M12 13.5V9.5M9.5 2.5h5" />
  </svg>
);

export const RadioGlyph = (props: GlyphProps) => (
  <svg {...base} {...props}>
    <circle cx="12" cy="12" r="2" />
    <path d="M7.8 7.8a6 6 0 0 0 0 8.4M16.2 7.8a6 6 0 0 1 0 8.4M5 5a10 10 0 0 0 0 14M19 5a10 10 0 0 1 0 14" />
  </svg>
);

export const SensorGlyph = (props: GlyphProps) => (
  <svg {...base} {...props}>
    <circle cx="12" cy="12" r="3" />
    <path d="M12 2.5v3M12 18.5v3M2.5 12h3M18.5 12h3M5.3 5.3l2.1 2.1M16.6 16.6l2.1 2.1M5.3 18.7l2.1-2.1M16.6 7.4l2.1-2.1" />
  </svg>
);

export const BaseGlyph = (props: GlyphProps) => (
  <svg {...base} {...props}>
    <rect x="4" y="6" width="16" height="12" rx="3" />
    <path d="M8 18v2M16 18v2M9 12h6" />
  </svg>
);

export const GitHubGlyph = (props: GlyphProps) => (
  <svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true" {...props}>
    <path d="M12 2a10 10 0 0 0-3.16 19.49c.5.09.68-.22.68-.48v-1.7c-2.78.6-3.37-1.34-3.37-1.34-.45-1.16-1.11-1.47-1.11-1.47-.91-.62.07-.6.07-.6 1 .07 1.53 1.03 1.53 1.03.9 1.52 2.34 1.08 2.91.83.09-.65.35-1.09.63-1.34-2.22-.25-4.55-1.11-4.55-4.94 0-1.09.39-1.98 1.03-2.68-.1-.25-.45-1.27.1-2.64 0 0 .84-.27 2.75 1.02a9.56 9.56 0 0 1 5 0c1.91-1.29 2.75-1.02 2.75-1.02.55 1.37.2 2.39.1 2.64.64.7 1.03 1.59 1.03 2.68 0 3.84-2.34 4.68-4.57 4.93.36.31.68.92.68 1.85v2.74c0 .27.18.58.69.48A10 10 0 0 0 12 2Z" />
  </svg>
);

export const DiscordGlyph = (props: GlyphProps) => (
  <svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true" {...props}>
    <path d="M20.317 4.37a19.791 19.791 0 0 0-4.885-1.515.074.074 0 0 0-.079.037c-.21.375-.444.864-.608 1.25a18.27 18.27 0 0 0-5.487 0 12.64 12.64 0 0 0-.617-1.25.077.077 0 0 0-.079-.037A19.736 19.736 0 0 0 3.677 4.37a.07.07 0 0 0-.032.027C.533 9.046-.32 13.58.099 18.057a.082.082 0 0 0 .031.057 19.9 19.9 0 0 0 5.993 3.03.078.078 0 0 0 .084-.028c.462-.63.874-1.295 1.226-1.994a.076.076 0 0 0-.041-.106 13.107 13.107 0 0 1-1.872-.892.077.077 0 0 1-.008-.128 10.2 10.2 0 0 0 .372-.292.074.074 0 0 1 .077-.01c3.928 1.793 8.18 1.793 12.062 0a.074.074 0 0 1 .078.01c.12.098.246.198.373.292a.077.077 0 0 1-.006.127 12.299 12.299 0 0 1-1.873.892.077.077 0 0 0-.041.107c.36.698.772 1.362 1.225 1.993a.076.076 0 0 0 .084.028 19.839 19.839 0 0 0 6.002-3.03.077.077 0 0 0 .032-.054c.5-5.177-.838-9.674-3.549-13.66a.061.061 0 0 0-.031-.028zM8.02 15.33c-1.183 0-2.157-1.085-2.157-2.419 0-1.333.956-2.419 2.157-2.419 1.21 0 2.176 1.096 2.157 2.42 0 1.333-.956 2.418-2.157 2.418zm7.975 0c-1.183 0-2.157-1.085-2.157-2.419 0-1.333.955-2.419 2.157-2.419 1.21 0 2.176 1.096 2.157 2.42 0 1.333-.946 2.418-2.157 2.418z" />
  </svg>
);

/* install ratings */
export const UndoGlyph = (props: GlyphProps) => (
  <svg {...base} {...props}>
    <path d="M9 14 4 9l5-5" />
    <path d="M4 9h10.5a5.5 5.5 0 0 1 0 11H11" />
  </svg>
);

export const EditGlyph = (props: GlyphProps) => (
  <svg {...base} {...props}>
    <path d="M4 20h4L19 9l-4-4L4 16z" />
    <path d="m13.5 6.5 4 4" />
  </svg>
);

export const KeyGlyph = (props: GlyphProps) => (
  <svg {...base} {...props}>
    <circle cx="8" cy="16" r="4" />
    <path d="m11 13 9-9M16.5 7.5l2.5 2.5M14.5 9.5l2 2" />
  </svg>
);

export const ScrewdriverGlyph = (props: GlyphProps) => (
  <svg {...base} {...props}>
    <path d="m15 3.5 5.5 5.5-3.5 3.5-5.5-5.5z" />
    <path d="m12 10-7 7M5 17l-1.5 3.5L7 19" />
  </svg>
);

export const AppsGlyph = (props: GlyphProps) => (
  <svg {...base} {...props}>
    <rect x="4" y="4" width="6.5" height="6.5" rx="1.8" />
    <rect x="13.5" y="4" width="6.5" height="6.5" rx="1.8" />
    <rect x="4" y="13.5" width="6.5" height="6.5" rx="1.8" />
    <rect x="13.5" y="13.5" width="6.5" height="6.5" rx="1.8" />
  </svg>
);

export const EraserGlyph = (props: GlyphProps) => (
  <svg {...base} {...props}>
    <path d="M10 20h10" />
    <path d="M4.6 14.6 13.8 5.4a2 2 0 0 1 2.8 0l3 3a2 2 0 0 1 0 2.8L11 19.8H9L4.6 15.4a.6.6 0 0 1 0-.8z" />
    <path d="m9 10 6 6" />
  </svg>
);

export const BackupGlyph = (props: GlyphProps) => (
  <svg {...base} {...props}>
    <rect x="3" y="4" width="18" height="4.5" rx="1.2" />
    <path d="M5 8.5V18a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8.5" />
    <path d="M12 11.5v5.5M9.5 14.5 12 17l2.5-2.5" />
  </svg>
);

export const RefreshGlyph = (props: GlyphProps) => (
  <svg {...base} {...props}>
    <path d="M19.5 11A7.5 7.5 0 0 0 6 6.6L4 8.5" />
    <path d="M4 4v4.5h4.5" />
    <path d="M4.5 13A7.5 7.5 0 0 0 18 17.4l2-1.9" />
    <path d="M20 20v-4.5h-4.5" />
  </svg>
);

export const CheckGlyph = (props: GlyphProps) => (
  <svg {...base} {...props}>
    <circle cx="12" cy="12" r="8.5" />
    <path d="m8.5 12.2 2.4 2.4 4.6-5" />
  </svg>
);

/* hardware */
export const ChipGlyph = (props: GlyphProps) => (
  <svg {...base} {...props}>
    <rect x="6.5" y="6.5" width="11" height="11" rx="2" />
    <rect x="10" y="10" width="4" height="4" rx=".8" />
    <path d="M9.5 3.5v3M14.5 3.5v3M9.5 17.5v3M14.5 17.5v3M3.5 9.5h3M3.5 14.5h3M17.5 9.5h3M17.5 14.5h3" />
  </svg>
);

export const SpeakerGlyph = (props: GlyphProps) => (
  <svg {...base} {...props}>
    <path d="M4 9.5h3.5L12 5.5v13l-4.5-4H4z" />
    <path d="M15.5 9.5a3.5 3.5 0 0 1 0 5M18 7a7 7 0 0 1 0 10" />
  </svg>
);

export const DeviceGlyph = (props: GlyphProps) => (
  <svg {...base} {...props}>
    <rect x="6" y="3" width="12" height="18" rx="2.5" />
    <path d="M10.5 18h3" />
  </svg>
);

/** Difficulty as a meter: bars fill to the level (1 easy, 2 medium, 3 hard). */
export const DifficultyGlyph = ({
  level,
  ...props
}: GlyphProps & { level: 0 | 1 | 2 | 3 }) => (
  <svg {...base} {...props}>
    {[0, 1, 2].map((i) => (
      <rect
        key={i}
        x={4.5 + i * 6}
        y={16 - i * 4.5}
        width="3.5"
        height={4.5 + i * 4.5}
        rx="1.2"
        fill={i < level ? "currentColor" : "none"}
      />
    ))}
  </svg>
);

/** The Difficulty field's label: a gauge, so it never reads as a rating itself. */
export const GaugeGlyph = (props: GlyphProps) => (
  <svg {...base} {...props}>
    <path d="M3.5 17a8.5 8.5 0 1 1 17 0" />
    <path d="M12 17 15.5 10.5" />
    <circle cx="12" cy="17" r="1.2" fill="currentColor" stroke="none" />
  </svg>
);

export const RulerGlyph = (props: GlyphProps) => (
  <svg {...base} {...props}>
    <rect x="2.5" y="8" width="19" height="8" rx="1.5" />
    <path d="M6.5 8v3M10.5 8v4M14.5 8v3M18.5 8v4" />
  </svg>
);

export const BookGlyph = (props: GlyphProps) => (
  <svg {...base} {...props}>
    <path d="M5 4.5A1.5 1.5 0 0 1 6.5 3H19v15H6.5A1.5 1.5 0 0 0 5 19.5Z" />
    <path d="M5 19.5A1.5 1.5 0 0 0 6.5 21H19" />
  </svg>
);

export const FeedGlyph = (props: GlyphProps) => (
  <svg {...base} strokeWidth={1.8} {...props}>
    <path d="M5 5a14 14 0 0 1 14 14M5 11a8 8 0 0 1 8 8" />
    <circle cx="6" cy="18" r="1.2" fill="currentColor" />
  </svg>
);

/** Layers: work built on other work (a frame's credits). */
export const CreditGlyph = (props: GlyphProps) => (
  <svg {...base} {...props}>
    <path d="m12 4 8 4-8 4-8-4z" />
    <path d="m4 12 8 4 8-4" />
    <path d="m4 16 8 4 8-4" />
  </svg>
);
