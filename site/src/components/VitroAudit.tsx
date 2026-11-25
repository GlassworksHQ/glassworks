"use client";

import { useDeferredValue, useMemo, useState } from "react";
import { SAMPLE_DASHBOARD, auditDashboard } from "@/lib/vitroCompat";
import { manage as m } from "./Manage";
import s from "./VitroAudit.module.css";

/** The checker's reasons and notes, with their `code` spans shown as code. */
function Ticked({ text }: { text: string }) {
  return (
    <>
      {text.split("`").map((part, i) => (i % 2 === 1 ? <code key={i}>{part}</code> : part))}
    </>
  );
}

export function VitroAudit() {
  const [text, setText] = useState("");
  const deferred = useDeferredValue(text);
  const result = useMemo(() => (deferred.trim() === "" ? null : auditDashboard(deferred)), [deferred]);

  return (
    <section id="vitro-check" className={s.section} aria-labelledby="vitro-check-heading">
      <div className={s.head}>
        <span className="label">Vitro compatibility check</span>
        <h2 id="vitro-check-heading">Will your dashboard run natively?</h2>
        <p>
          Paste your Lovelace YAML or JSON to see which cards Vitro draws itself and which ones would start the web engine, and why. It follows the same rules as
          the panel, assuming every custom card and card-mod is installed. Scripts and AI agents can read those rules from{" "}
          <a href="/vitro-compat.json">/vitro-compat.json</a> and check dashboards on their own machine.
        </p>
        <p className={s.privacy}>
          <span className={s.badge} aria-hidden="true">
            ●
          </span>
          Runs 100% in your browser — your dashboard YAML never leaves your device.
        </p>
      </div>

      <div className={`${m.card} ${s.card}`}>
        <div className={s.bar}>
          <label htmlFor="vitro-check-input" className={s.label}>
            Dashboard YAML or JSON
          </label>
          <div className={s.actions}>
            <button type="button" className={s.btn} onClick={() => setText(SAMPLE_DASHBOARD)}>
              Load sample
            </button>
            {text !== "" && (
              <button type="button" className={s.btn} onClick={() => setText("")}>
                Clear
              </button>
            )}
          </div>
        </div>
        <textarea
          id="vitro-check-input"
          className={s.input}
          value={text}
          onChange={(e) => setText(e.target.value)}
          spellCheck={false}
          autoCapitalize="off"
          autoCorrect="off"
          rows={12}
          placeholder={"views:\n  - title: Home\n    cards:\n      - type: tile\n        entity: light.kitchen"}
        />

        <div className={s.result} role="status" aria-live="polite">
          {!result && <p className={s.hint}>Paste a dashboard above, or load the sample. The result shows up as you type.</p>}

          {result && !result.ok && <p className={s.error}>{result.error}</p>}

          {result?.ok && (
            <>
              <p className={result.isFullyNative ? s.pass : s.fail}>
                <strong>{result.isFullyNative ? "PASS" : "FAIL"}</strong>
                {result.isFullyNative
                  ? " 100% Vitro-native — zero GeckoView triggers"
                  : ` ${result.totals.tier3} card${result.totals.tier3 === 1 ? "" : "s"} will boot GeckoView`}
              </p>
              <p className={s.totals}>
                Tier 1 (core) {result.totals.tier1} · Tier 2 (native HACS) {result.totals.tier2} · Tier 3 (GeckoView) {result.totals.tier3}
              </p>

              {result.views.length > 0 ? (
                <div className={s.tableWrap}>
                  <table className={s.table}>
                    <thead>
                      <tr>
                        <th scope="col">View</th>
                        <th scope="col">Tier 1</th>
                        <th scope="col">Tier 2</th>
                        <th scope="col">Tier 3</th>
                        <th scope="col">Result</th>
                      </tr>
                    </thead>
                    <tbody>
                      {result.views.map((v, i) => (
                        <tr key={`${v.name}-${i}`}>
                          <th scope="row">
                            {v.name} <span className={s.faint}>{v.type}</span>
                          </th>
                          <td>{v.tier1}</td>
                          <td>{v.tier2}</td>
                          <td>{v.tier3}</td>
                          <td className={v.native ? s.okInk : s.workInk}>{v.native ? "Native" : "Boots GeckoView"}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              ) : (
                <p className={s.hint}>This dashboard has no views yet.</p>
              )}

              {result.triggers.length > 0 && (
                <div className={s.group}>
                  <h3>Cards that boot GeckoView</h3>
                  <ul className={s.list}>
                    {result.triggers.map((t, i) => (
                      <li key={`${t.path}-${i}`}>
                        <code>{t.type}</code>
                        <span>{t.path}</span>
                        {t.reasons.length > 0 && (
                          <span className={s.why}>
                            <Ticked text={t.reasons.join("; ")} />
                          </span>
                        )}
                      </li>
                    ))}
                  </ul>
                </div>
              )}

              {result.warnings.length > 0 && (
                <div className={s.group}>
                  <h3>Notes</h3>
                  <ul className={s.list}>
                    {result.warnings.map((w, i) => (
                      <li key={`${w}-${i}`}>
                        <span>
                          <Ticked text={w} />
                        </span>
                      </li>
                    ))}
                  </ul>
                </div>
              )}
            </>
          )}
        </div>
      </div>
    </section>
  );
}
