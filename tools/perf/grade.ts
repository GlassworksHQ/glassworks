/* Clarity grade (MIT): the letter of the Glassworks perf test, computed from a summary and
 * rubric.json. Show it as `${rubric.name} ${grade}` ("Clarity A"); the name lives in rubric.json.
 *
 * The letter is derived, so it is computed where it is shown: the marketing site (and later the
 * shared frame package, #114) imports this module with a frame's summary (tools/perf/summary.py,
 * glassworks.perf-summary/3) and tools/perf/page/rubric.json. tools/perf/grade.py computes the same
 * letter from the same two files; test/grade-cases.json and test/test_grade.py's parity test keep
 * them equal. rubric.json is the single source of the thresholds and the letter rules: don't copy a
 * rule into this file.
 *
 * Plain TypeScript with erasable types only, so node runs it as it is (node --test).
 */

export type Band = "good" | "needs-improvement" | "poor";
export type Level = string | null;

export interface Rule { good_max: number; poor_above?: number; poor_at?: number }

export interface Rubric {
  name: string;           // "Clarity": show a grade as `${name} ${grade}`
  version: number;
  clarity: number;        // Clarity's version: v1, the one Glassworks 1.0.0 ships with; a summary without it is pre-release
  /** The first day (UTC) a v1 summary can be from: the first v1 (2026-11-14) also said 1 (rubric.json) */
  clarity_measured_since: string;
  protocol_version: number;
  metrics: Record<string, Rule>;
  grade: {
    pages: string[];
    tasks: Record<string, string>;
    scroll_levels: { level: string; frames_band?: Band[]; fps_min?: number; p95_max_ms?: number }[];
    update_levels: { level: string; latency_p95_max_ms: number; frames_band?: Band[] }[];
    camera_pages: string[];
    camera_levels: { level: string; first_frame_max_ms: number; shown_min_pct: number; max_gap_max_ms?: number; stall_total_max_ms?: number }[];
    /** Rubric 3 (#211): HA's more-info dialog, the slowest of a view's dialogs to settle. */
    dialog_levels: { level: string; settled_max_ms: number; frames_band?: Band[] }[];
    below: string;
    /** Rubric 2: a frame without a touchscreen isn't graded on these tasks; each is `value` on every page, which every level accepts. */
    no_touch: { tasks: string[]; value: string; reason: string };
    levels: { level: string; accepts: Record<string, string[]> }[];
    letters: { letter: string; meaning: string; needs: Record<string, string> }[];
    fallback: { letter: string; meaning: string };
    start_cap: { band: Band; letter: string };
  };
}

export interface FrameRun { timed_out?: boolean; lost?: boolean; p95_ms?: number | null; jank_pct?: number | null; frozen_pct?: number | null; fps?: number | null; n?: number | null }

/** One run's live updates: change to screen, and the frames that had a change to draw. */
export interface UpdateRun { timed_out?: boolean; lost?: boolean; latency_n?: number | null; latency_median_ms?: number | null; latency_p95_ms?: number | null; frames?: FrameRun | null }

/** One run's live camera streams (#174), the worst stream's numbers. */
export interface CameraRun { timed_out?: boolean; lost?: boolean; stalls_reported?: boolean; uncovered_ms?: number | null; first_frame_ms?: number | null; shown_pct?: number | null; max_gap_ms?: number | null; stall_total_ms?: number | null }

/** One run's more-info dialogs (rubric 3, #211): how many ran and failed, the slowest to settle
 * (null when one failed), and the open animations' frames. */
export interface DialogRun { timed_out?: boolean; lost?: boolean; n?: number | null; failed?: number | null; settled_ms?: number | null; frames?: FrameRun | null }

export interface PageSummary {
  inp_ms?: number | null;
  open_ms?: number | null;
  blocking_ms?: number | null;   // rubric 2: the main thread's blocking from the first screen drawn to usable
  scroll?: { runs?: FrameRun[] } & Record<string, unknown>;
  updates?: { runs?: UpdateRun[] } & Record<string, unknown>;
  camera?: { runs?: CameraRun[] } & Record<string, unknown>;
  dialog?: { runs?: DialogRun[] } & Record<string, unknown>;   // HA's more-info dialogs (#211)
  timed_out_runs?: number;     // runs in which the page didn't finish within the runner's deadline (#202)
  lost_runs?: number;          // runs in which the panel closed the page's browser page (its page was killed or crashed)
  lost_why?: string[];         // why, in words
}

export interface Summary {
  clarity?: number | null;
  measured_on?: string | null;
  protocol_version?: number | null;
  /** false for a frame without a touchscreen (rubric 2): its scroll isn't graded. Absent: it has touch. */
  touch?: boolean;
  webrtc_transport?: { transport?: string | null; chosen_by?: string | null; reason?: string | null; possibly_limited?: boolean } | null;
  /** "lan": the fake Home Assistant reached the panel over the LAN (run.sh --ha-transport lan), not adb reverse. */
  ha_transport?: "lan";
  pages?: Record<string, PageSummary>;
  restart_ms?: number | null;
  gaps?: string[];
  /** Why a measurement is missing that can't change the letter (missing_settled_by): said, not a gap. */
  unmeasured?: string[];
  not_comparable?: string | null;
}

export interface TierInputs { inp_ms?: number | null; load_ms?: number | null; blocking_ms?: number | null; scroll_level?: string | null; updates_level?: string | null; camera_level?: string | null; dialog_level?: string | null }
/** Per task: a band (tap, updates, load) or, for scroll, its level. */
export type Bands = Record<string, string | null>;

export interface Grade {
  status: "graded" | "re-measure" | "not-comparable";
  grade: string | null;
  meaning: string | null;
  why?: string;
  rubric_version?: number;
  capped_by_start?: boolean;
  provisional?: boolean;
  missing?: string[];
  bands?: Record<string, Bands>;
  levels?: Record<string, Level>;
  start?: { value: number | null; band: Band | null };
  limits?: { page: string; needs: string; tasks: string[] }[];
  /** Protocol 7: the camera video's path (lan-udp, lan-turn, adb). Letters over different paths aren't comparable. */
  transport?: string | null;
  /** Over adb: heavy's cameras may be limited by the test's link. Never changes the letter. */
  transport_caveat?: string | null;
  /** On a frame without touch (rubric 2): the tasks not graded there, and why. */
  not_applicable?: Record<string, string>;
  /** Only when something is missing and it can't change the letter: what decides the letter instead (settledBy). */
  missing_settled_by?: SettledBy;
}

/** Why missing measurements can't change a letter: the restart caps it, or what was measured keeps it from the next letter up. */
export interface SettledBy { capped_by_start: boolean; limits: { page: string; needs: string; tasks: string[] }[] }

export const TRANSPORT_CAVEAT = "heavy's cameras went through the test's adb link and may be limited by it, not by the panel";

const ORDER: Record<Band, number> = { good: 0, "needs-improvement": 1, poor: 2 };

export function band(value: number | null | undefined, rule: Rule): Band | null {
  if (value === null || value === undefined) return null;
  if (rule.poor_at !== undefined && value >= rule.poor_at) return "poor";
  if (rule.poor_above !== undefined && value > rule.poor_above) return "poor";
  return value <= rule.good_max ? "good" : "needs-improvement";
}

export function worst(bands: (Band | null)[]): Band | null {
  const bs = bands.filter((b): b is Band => !!b);
  return bs.length ? bs.reduce((a, b) => (ORDER[b] > ORDER[a] ? b : a)) : null;
}

/** One run's frames band: the worst of p95, jank and frozen frames. */
export function frameBand(st: FrameRun | null | undefined, rubric: Rubric): Band | null {
  if (!st || !st.n) return null;
  const m = rubric.metrics;
  return worst([band(st.p95_ms, m.frame_p95_ms), band(st.jank_pct, m.frame_jank_pct), band(st.frozen_pct, m.frame_frozen_pct)]);
}

/** The middle band of several runs, the worse of the two middles for an even count. */
export function medianBand(bands: (Band | null)[]): Band | null {
  const bs = bands.filter((b): b is Band => !!b).sort((a, b) => ORDER[a] - ORDER[b]);
  return bs.length ? bs[Math.floor(bs.length / 2)] : null;
}

/** A run record that stands for a task the view never got to measure, measured as failing: the view didn't finish
 * within the runner's deadline (timed_out), or its browser page was closed (lost). grade.py's failed_run is the same. */
export function failedRun(run: { timed_out?: boolean; lost?: boolean } | null | undefined): boolean {
  return !!run && !!(run.timed_out || run.lost);
}

/** One run's scroll level: the first of rubric.json's scroll_levels whose rule holds, or
 * `below` (measured, below fluid); null when it wasn't measured. */
export function scrollLevel(st: FrameRun | null | undefined, rubric: Rubric): string | null {
  if (failedRun(st)) return rubric.grade.below;     // the view didn't finish, or its page was lost: measured, failed
  if (!st || !st.n) return null;
  for (const lv of rubric.grade.scroll_levels) {
    if (lv.frames_band) {
      const b = frameBand(st, rubric);
      if (b !== null && lv.frames_band.includes(b)) return lv.level;
    } else if ((st.fps ?? 0) >= (lv.fps_min as number) && st.p95_ms !== null && st.p95_ms !== undefined && st.p95_ms <= (lv.p95_max_ms as number)) {
      return lv.level;
    }
  }
  return rubric.grade.below;
}

/** One run's live-updates level from its change-to-screen p95 (and, for premium, the frames that had
 * a change to draw), or `below`; null when it wasn't measured. */
export function updateLevel(run: UpdateRun | null | undefined, rubric: Rubric): string | null {
  if (failedRun(run)) return rubric.grade.below;
  if (!run || !run.latency_n || run.latency_p95_ms === null || run.latency_p95_ms === undefined) return null;
  for (const lv of rubric.grade.update_levels) {
    if (run.latency_p95_ms > lv.latency_p95_max_ms) continue;
    if (lv.frames_band) {
      const b = frameBand(run.frames, rubric);
      if (b === null || !lv.frames_band.includes(b)) continue;
    }
    return lv.level;
  }
  return rubric.grade.below;
}

/** One run's more-info level (rubric 3): the slowest dialog's time to settled and, for premium, the
 * open animations' frames; `below` when a dialog never opened or settled, or the view didn't get to
 * them; null when they weren't measured. */
export function dialogLevel(run: DialogRun | null | undefined, rubric: Rubric): string | null {
  if (failedRun(run)) return rubric.grade.below;
  if (!run || !run.n) return null;
  if (run.failed || run.settled_ms === null || run.settled_ms === undefined) return rubric.grade.below;
  for (const lv of rubric.grade.dialog_levels) {
    if (run.settled_ms > lv.settled_max_ms) continue;
    if (lv.frames_band) {
      const b = frameBand(run.frames, rubric);
      if (b === null || !lv.frames_band.includes(b)) continue;
    }
    return lv.level;
  }
  return rubric.grade.below;
}

/** One run's camera level from its worst stream: first frame, frames shown, the longest gap and the
 * gaps over 500 ms added up; `below` when a stream showed no frame; null without a camera record. */
export function cameraLevel(run: CameraRun | null | undefined, rubric: Rubric): string | null {
  if (!run) return null;
  if (failedRun(run)) return rubric.grade.below;
  if (run.first_frame_ms === null || run.first_frame_ms === undefined || run.shown_pct === null || run.shown_pct === undefined) return rubric.grade.below;
  if (run.stalls_reported === false) {
    // Stalls n/a (no freeze counts, or counts that left part of the window out): not measured,
    // unless the first frame and frames shown already fail every level, as no stalls could lift.
    const ff = run.first_frame_ms, shown = run.shown_pct;
    return rubric.grade.camera_levels.some((lv) => ff <= lv.first_frame_max_ms && shown >= lv.shown_min_pct) ? null : rubric.grade.below;
  }
  for (const lv of rubric.grade.camera_levels) {
    if (run.first_frame_ms > lv.first_frame_max_ms || run.shown_pct < lv.shown_min_pct) continue;
    if (lv.max_gap_max_ms !== undefined) {
      if (run.max_gap_ms === null || run.max_gap_ms === undefined || run.max_gap_ms > lv.max_gap_max_ms) continue;
      // A gap open at the counts' end could run on into what they left out (#265): open.
      if (run.max_gap_ms + (run.uncovered_ms ?? 0) > lv.max_gap_max_ms) return null;
    }
    if (lv.stall_total_max_ms !== undefined) {
      if (run.stall_total_ms === null || run.stall_total_ms === undefined || run.stall_total_ms >= lv.stall_total_max_ms) continue;
      // Under the bar as counted, maybe not in what the counts left out (#265): open.
      if (run.stall_total_ms + (run.uncovered_ms ?? 0) >= lv.stall_total_max_ms) return null;
    }
    return lv.level;
  }
  return rubric.grade.below;
}

/** The middle scroll level of several runs, the worse of the two middles for an even count. */
export function medianLevel(levels: (string | null)[], rubric: Rubric): string | null {
  const order = rubric.grade.scroll_levels.map((l) => l.level).concat([rubric.grade.below]);
  const ls = levels.filter((l): l is string => !!l).sort((a, b) => order.indexOf(a) - order.indexOf(b));
  return ls.length ? ls[Math.floor(ls.length / 2)] : null;
}

/** Each task's band on one page. The camera task is `none` on a page without streams; on a frame without
 * touch (touch false), each no_touch task is its `value`, whatever was measured. */
export function tierBands(tier: TierInputs | undefined, rubric: Rubric, page?: string, touch = true): Bands {
  const t = tier || {};
  const out: Bands = {
    tap: band(t.inp_ms, rubric.metrics.inp_ms),
    scroll: t.scroll_level ?? null,
    updates: t.updates_level ?? null,
    load: band(t.load_ms, rubric.metrics.load_ms),
    camera: page !== undefined && rubric.grade.camera_pages.includes(page) ? (t.camera_level ?? null) : "none",
    blocking: band(t.blocking_ms, rubric.metrics.blocking_ms),
    dialog: t.dialog_level ?? null,
  };
  if (touch === false) for (const task of rubric.grade.no_touch.tasks) out[task] = rubric.grade.no_touch.value;
  return out;
}

export function pageLevel(bands: Bands, rubric: Rubric): Level {
  for (const lv of rubric.grade.levels) {
    if (Object.entries(lv.accepts).every(([task, ok]) => bands[task] !== null && ok.includes(bands[task] as string))) return lv.level;
  }
  return null;
}

/** The tasks that keep a page from `level`. */
export function limits(bands: Bands, level: string, rubric: Rubric): string[] {
  const lv = rubric.grade.levels.find((l) => l.level === level);
  if (!lv) return [];
  return Object.entries(lv.accepts).filter(([task, ok]) => bands[task] === null || !ok.includes(bands[task] as string)).map(([task]) => task);
}

function levelRank(level: Level, rubric: Rubric): number {
  const order = rubric.grade.levels.map((l) => l.level);
  return level === null ? 0 : order.length - order.indexOf(level);
}

/** The best each task can be: the first of its levels (scroll, live updates, the camera, dialogs), else good. Every
 * level accepts it, so a missing measurement set to it holds no page back: gradeBands grades with it for the best case. */
export function bestValue(task: string, rubric: Rubric): string {
  const levels = (rubric.grade as unknown as Record<string, unknown>)[rubric.grade.tasks[task]];
  return Array.isArray(levels) ? (levels[0] as { level: string }).level : "good";
}

/** [letter, capped]: the first letter whose every page need holds, then the start cap (never raises it). */
function letterOf(levels: Record<string, Level>, start: Band | null, rubric: Rubric): [string, boolean] {
  const R = rubric.grade;
  let letter = R.fallback.letter;
  for (const l of R.letters) {
    if (Object.entries(l.needs).every(([t, need]) => levelRank(levels[t] ?? null, rubric) >= levelRank(need, rubric))) {
      letter = l.letter;
      break;
    }
  }
  // Letters rank by their place in the table (A+ first), the fallback last; never by string order.
  const order = R.letters.map((l) => l.letter).concat([R.fallback.letter]);
  const capped = start === R.start_cap.band && order.indexOf(letter) < order.indexOf(R.start_cap.letter);
  return [capped ? R.start_cap.letter : letter, capped];
}

/** The letter from each page's inputs (median tap and load, median-of-runs frames bands) and the restart. A missing
 * measurement fails whatever needs it, and a missing restart caps nothing. The grade is provisional only when what is
 * missing could change the letter: when the best case (each missing band, and a missing restart, at the best it can
 * be) and the worst (each missing band failing, and a missing restart slow enough to cap) give different letters; the
 * start cap applies in both. When they agree, missing_settled_by says what decides the letter instead (grade.py). */
export function gradeBands(tiers: Record<string, TierInputs>, startMs: number | null | undefined, rubric: Rubric, touch = true): Grade {
  const R = rubric.grade;
  const bands: Record<string, Bands> = {};
  const levels: Record<string, Level> = {};
  const bestLevels: Record<string, Level> = {};
  for (const t of R.pages) {
    bands[t] = tierBands((tiers || {})[t], rubric, t, touch);
    levels[t] = pageLevel(bands[t], rubric);
    bestLevels[t] = pageLevel(Object.fromEntries(Object.entries(bands[t]).map(([task, b]) => [task, b ?? bestValue(task, rubric)])), rubric);
  }
  const start = band(startMs, rubric.metrics.start_ms);
  const missing: string[] = [];
  for (const t of R.pages) for (const [task, b] of Object.entries(bands[t])) if (b === null) missing.push(`${t}.${task}`);
  if (!start) missing.push("start");
  const [letter, capped] = letterOf(levels, start, rubric);
  // The worst case is the letter, but for a missing restart: the letter doesn't cap it, the worst case does.
  const [lowest] = letterOf(levels, start ?? R.start_cap.band, rubric);
  const [highest] = letterOf(bestLevels, start ?? "good", rubric);
  const meaning = (R.letters.find((l) => l.letter === letter) || R.fallback).meaning;
  const g: Grade = {
    status: "graded", grade: letter, meaning, rubric_version: rubric.version, capped_by_start: capped,
    provisional: highest !== lowest, missing, bands, levels, start: { value: startMs ?? null, band: start },
  };
  if (missing.length > 0 && highest === lowest) g.missing_settled_by = settledBy(g, rubric);
  if (touch === false) g.not_applicable = Object.fromEntries(R.no_touch.tasks.map((t) => [t, R.no_touch.reason]));
  return g;
}

/** Why a grade's missing measurements can't change its letter: the restart caps it (measured, and slow), and
 * nextLimits without the missing tasks, so what measured already keeps it from the next letter up. Both empty when
 * the next letter up doesn't need the missing pages (or there is none). */
export function settledBy(g: Grade, rubric: Rubric): SettledBy {
  const missing = new Set(g.missing || []);
  const limits = nextLimits(g, rubric)
    .map((x) => ({ ...x, tasks: x.tasks.filter((k) => !missing.has(`${x.page}.${k}`)) }))
    .filter((x) => x.tasks.length > 0);
  return { capped_by_start: g.start?.band === rubric.grade.start_cap.band && g.grade === rubric.grade.start_cap.letter, limits };
}

/** What keeps a grade from the next letter up. Empty for the best letter. */
export function nextLimits(g: Grade, rubric: Rubric): { page: string; needs: string; tasks: string[] }[] {
  const letters = rubric.grade.letters.map((l) => l.letter);
  if (g.grade === null || g.grade === letters[0] || !g.bands || !g.levels) return [];
  const up = letters.includes(g.grade) ? letters[letters.indexOf(g.grade) - 1] : letters[letters.length - 1];
  const needs = rubric.grade.letters.find((l) => l.letter === up)!.needs;
  return rubric.grade.pages
    .filter((t) => t in needs && levelRank(g.levels![t] ?? null, rubric) < levelRank(needs[t], rubric))
    .map((t) => ({ page: t, needs: needs[t], tasks: limits(g.bands![t], needs[t], rubric) }));
}

/** A summary's measurements -> each page's inputs and the restart. */
export function summaryInputs(summary: Summary, rubric: Rubric): [Record<string, TierInputs>, number | null] {
  const tiers: Record<string, TierInputs> = {};
  for (const t of rubric.grade.pages) {
    const p = (summary.pages || {})[t];
    if (!p) continue;
    tiers[t] = {
      inp_ms: p.inp_ms ?? null,
      load_ms: p.open_ms ?? null,
      blocking_ms: p.blocking_ms ?? null,
      scroll_level: medianLevel((p.scroll?.runs || []).map((r) => scrollLevel(r, rubric)), rubric),
      updates_level: medianLevel((p.updates?.runs || []).map((r) => updateLevel(r, rubric)), rubric),
      camera_level: medianLevel((p.camera?.runs || []).map((r) => cameraLevel(r, rubric)), rubric),
      dialog_level: medianLevel((p.dialog?.runs || []).map((r) => dialogLevel(r, rubric)), rubric),
    };
  }
  return [tiers, summary.restart_ms ?? null];
}

/**
 * The letter for a summary, at render time. status:
 * - "graded": show the letter (and "provisional" when it is set: something wasn't measured that could change
 *   the letter, or the summary lists gaps; missing_settled_by says why what is missing can't);
 * - "re-measure": the summary was measured with a pre-release version of Clarity (no `clarity`), another
 *   version, the first v1 (before clarity_measured_since) or another protocol than these rules are for;
 *   show "re-measure", not a letter;
 * - "not-comparable": the measurement says why (e.g. not at the default zoom); show no letter.
 */
export function gradeSummary(summary: Summary, rubric: Rubric): Grade {
  // Not comparable first: it says why there is nothing to grade, whatever the protocol.
  if (summary.not_comparable) return { status: "not-comparable", grade: null, meaning: null, why: summary.not_comparable };
  if (summary.clarity !== rubric.clarity) {
    // Before v1 (pre-release protocols 1 to 8): the numbers can repeat, so the marker decides. A
    // summary of another Clarity version (v1 under rubric 2) is graded only by its own rules.
    const why = summary.clarity === null || summary.clarity === undefined
      ? `measured with a pre-release version of Clarity (protocol ${summary.protocol_version ?? "unknown"}): re-measure`
      : `measured with Clarity v${summary.clarity}; these rules are Clarity v${rubric.clarity}'s: re-measure`;
    return { status: "re-measure", grade: null, meaning: null, why };
  }
  if (summary.measured_on && summary.measured_on < rubric.clarity_measured_since) {
    // The numbers started again at 1 on 2026-11-19, as on 2026-11-14: the first v1's summaries also say 1.
    return { status: "re-measure", grade: null, meaning: null,
             why: "measured with the first Clarity v1 (2026-11-14), before the version started again at 1: re-measure" };
  }
  if (summary.protocol_version !== rubric.protocol_version) {
    return { status: "re-measure", grade: null, meaning: null,
             why: `measured with protocol ${summary.protocol_version ?? "unknown"}, these rules are for protocol ${rubric.protocol_version}` };
  }
  const [tiers, start] = summaryInputs(summary, rubric);
  // A summary says touch: false only for a frame without a touchscreen (summary.py); without it, touch.
  const g = gradeBands(tiers, start, rubric, summary.touch !== false);
  // Missing measurements only when they could change the letter (gradeBands); a gap always.
  g.provisional = !!g.provisional || (summary.gaps || []).length > 0;
  g.limits = nextLimits(g, rubric);
  g.transport = summary.webrtc_transport?.transport ?? null;
  g.transport_caveat = summary.webrtc_transport?.possibly_limited ? TRANSPORT_CAVEAT : null;
  return g;
}
