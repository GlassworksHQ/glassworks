"use client";

import s from "./Search.module.css";

/** Opens the search dialog (SearchDialog listens for this event, and for ⌘K). */
export const openSearch = () => window.dispatchEvent(new Event("gw:search"));

const Glass = () => (
  <svg viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round">
    <circle cx="11" cy="11" r="6.5" />
    <path d="m16 16 4.5 4.5" />
  </svg>
);

export function SearchButton({ variant = "side" }: { variant?: "side" | "big" | "icon" }) {
  if (variant === "icon") {
    return (
      <button type="button" className={s.icon} onClick={openSearch} aria-label="Search the docs">
        <Glass />
      </button>
    );
  }
  return (
    <button type="button" className={`${s.btn} ${variant === "big" ? s.big : ""}`} onClick={openSearch}>
      <Glass />
      Search the docs
      <kbd>⌘K</kbd>
    </button>
  );
}
