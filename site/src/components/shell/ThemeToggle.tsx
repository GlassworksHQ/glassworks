"use client";

import { useSyncExternalStore } from "react";
import s from "./Shell.module.css";

/*
 * The theme follows the OS until someone picks one here. The pick is <html data-theme>, kept in
 * localStorage and set before the first paint by THEME_SCRIPT, so a pinned theme never flashes.
 */
type Theme = "system" | "light" | "dark";
const KEY = "theme";
const NEXT: Record<Theme, Theme> = { system: "light", light: "dark", dark: "system" };
const NAME: Record<Theme, string> = { system: "Theme: system", light: "Theme: light", dark: "Theme: dark" };

export const THEME_SCRIPT = `try{var t=localStorage.getItem("${KEY}");if(t==="light"||t==="dark")document.documentElement.dataset.theme=t}catch(e){}`;

const listeners = new Set<() => void>();
const read = (): Theme => (document.documentElement.dataset.theme as Theme | undefined) ?? "system";

function pick(t: Theme) {
  const root = document.documentElement;
  if (t === "system") delete root.dataset.theme;
  else root.dataset.theme = t;
  try {
    if (t === "system") localStorage.removeItem(KEY);
    else localStorage.setItem(KEY, t);
  } catch {}
  listeners.forEach((l) => l());
}

const subscribe = (l: () => void) => {
  listeners.add(l);
  return () => listeners.delete(l);
};

export function ThemeToggle() {
  const theme = useSyncExternalStore(subscribe, read, () => "system" as Theme);
  return (
    <button type="button" className={s.theme} onClick={() => pick(NEXT[theme])} aria-label={`${NAME[theme]}. Switch to ${NEXT[theme]}.`} title={NAME[theme]}>
      <svg viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round">
        {theme === "light" ? (
          <>
            <circle cx="12" cy="12" r="4" />
            <path d="M12 3v2M12 19v2M3 12h2M19 12h2M5.6 5.6 7 7M17 17l1.4 1.4M5.6 18.4 7 17M17 7l1.4-1.4" />
          </>
        ) : theme === "dark" ? (
          <path d="M20 14.5A8 8 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5Z" />
        ) : (
          <>
            <circle cx="12" cy="12" r="8" />
            <path d="M12 4a8 8 0 0 1 0 16Z" fill="currentColor" />
          </>
        )}
      </svg>
    </button>
  );
}
