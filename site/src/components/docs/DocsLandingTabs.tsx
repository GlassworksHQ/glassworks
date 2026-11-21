"use client";

import { useId, useState, type ReactNode } from "react";
import d from "./Docs.module.css";
import { onTabKey } from "./tabs";

type TrackKey = "user" | "developers" | "research" | "all";

const TABS: { id: TrackKey; label: string }[] = [
  { id: "user", label: "User Guide" },
  { id: "developers", label: "Developers" },
  { id: "research", label: "Hardware Research" },
  { id: "all", label: "All docs" },
];

/**
 * Interactive 3-track switcher on /docs. Defaults to User Guide so newcomers land on calm,
 * approachable setup and feature guides, while developers and hardware hackers switch tracks in one click.
 */
export function DocsLandingTabs({
  user,
  developers,
  research,
}: {
  user: ReactNode;
  developers: ReactNode;
  research: ReactNode;
}) {
  const [tab, setTab] = useState<TrackKey>("user");
  const ids = TABS.map((t) => t.id);
  const base = useId();
  const tabId = (id: TrackKey) => `${base}-tab-${id}`;
  const panel = `${base}-panel`;

  return (
    <>
      <div className={d.landingTabs} role="tablist" aria-label="Choose documentation track">
        {TABS.map((t) => {
          const on = tab === t.id;
          return (
            <button
              key={t.id}
              type="button"
              role="tab"
              id={tabId(t.id)}
              aria-selected={on}
              aria-controls={panel}
              tabIndex={on ? 0 : -1}
              className={`${d.landingTab} ${on ? d.landingTabOn : ""}`}
              onClick={() => setTab(t.id)}
              onKeyDown={(e) => onTabKey(e, ids, tab, setTab)}
            >
              {t.label}
            </button>
          );
        })}
      </div>

      {/* one panel every tab controls: All docs shows its three sections at once */}
      <div role="tabpanel" id={panel} aria-labelledby={tabId(tab)}>
        <div hidden={tab !== "user" && tab !== "all"}>{user}</div>
        <div hidden={tab !== "developers" && tab !== "all"}>{developers}</div>
        <div hidden={tab !== "research" && tab !== "all"}>{research}</div>
      </div>
    </>
  );
}
