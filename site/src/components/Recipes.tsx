"use client";

import { useState } from "react";
import type { DashboardRecipe } from "@/lib/recipes";
import { manage as m } from "./Manage";
import s from "./Recipes.module.css";

function CopyPromptButton({ prompt }: { prompt: string }) {
  const [state, setState] = useState<"idle" | "copied" | "failed">("idle");

  async function copy() {
    try {
      await navigator.clipboard.writeText(prompt);
      setState("copied");
    } catch {
      setState("failed");
    }
    setTimeout(() => setState("idle"), 2200);
  }

  return (
    <button type="button" className={s.copyBtn} onClick={copy}>
      {state === "copied" ? "Copied prompt ✓" : state === "failed" ? "Expand & copy manually" : "Copy prompt"}
    </button>
  );
}

export function RecipeList({ recipes, compact = false }: { recipes: readonly DashboardRecipe[]; compact?: boolean }) {
  return (
    <div className={compact ? s.compactList : s.grid}>
      {recipes.map((r) => (
        <article key={r.id} id={`recipe-${r.id}`} className={`${m.card} ${s.card} ${compact ? s.cardCompact : ""}`}>
          <div className={s.head}>
            <span className={s.formFactor}>{r.formFactor}</span>
            <CopyPromptButton prompt={r.prompt} />
          </div>

          <h3 className={s.title}>{r.title}</h3>
          <p className={s.summary}>{r.summary}</p>

          <div className={s.entities} aria-label="Entities used">
            {r.entities.map((e) => (
              <code key={e}>{e}</code>
            ))}
          </div>

          {!compact && r.tips.length > 0 && (
            <ul className={s.tips}>
              {r.tips.map((t) => (
                <li key={t}>{t}</li>
              ))}
            </ul>
          )}

          <details className={s.fold}>
            <summary>View starter prompt</summary>
            <pre className={s.pre}>
              <code>{r.prompt}</code>
            </pre>
          </details>
        </article>
      ))}
    </div>
  );
}
