// node --test tools/perf/test/grade.test.mjs  (no dependencies; node strips grade.ts's types)
// grade.ts must give test/grade-cases.json's answers, the same ones grade.py gives (test_grade.py).
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import * as G from "../grade.ts";

const rubric = JSON.parse(readFileSync(new URL("../page/rubric.json", import.meta.url)));
const data = JSON.parse(readFileSync(new URL("./grade-cases.json", import.meta.url)));

test("the cases are for this rubric", () => {
  assert.equal(data.rubric_version, rubric.version);
});

for (const c of data.bands) {
  test(`band ${c.metric} ${c.value} -> ${c.band}`, () => {
    assert.equal(G.band(c.value, rubric.metrics[c.metric]), c.band);
  });
}

for (const c of data.cases) {
  test(`grade: ${c.name}`, () => {
    const g = G.gradeBands(c.tiers, c.start_ms, rubric, c.touch !== false);   // touch: false, rubric 2
    const got = {};
    for (const k of Object.keys(c.expect)) if (k !== "limits" && k in g) got[k] = g[k];
    got.limits = G.nextLimits(g, rubric);
    assert.deepEqual(got, c.expect);
    // Only when something is missing and it can't change the letter (grade.py the same).
    assert.equal("missing_settled_by" in g, "missing_settled_by" in c.expect);
  });
}

test("frames band per run, then the median band (the worse middle for an even count)", () => {
  const good = { n: 100, p95_ms: 16.67, jank_pct: 0, frozen_pct: 0 };
  const poor = { n: 100, p95_ms: 50, jank_pct: 0, frozen_pct: 0 };
  assert.equal(G.medianBand([G.frameBand(good, rubric), G.frameBand(poor, rubric)]), "poor");
  assert.equal(G.frameBand({ n: 0 }, rubric), null);
});

test("another protocol is re-measured, not graded", () => {
  const g = G.gradeSummary({ protocol_version: rubric.protocol_version + 1, pages: {} }, rubric);
  assert.equal(g.status, "re-measure");
  assert.equal(g.grade, null);
});

test("an earlier Clarity's summary, or the first v1's, is re-measured under v1's rules, and says so", () => {
  assert.deepEqual([rubric.clarity, rubric.protocol_version, rubric.version], [1, 1, 2]);
  for (const v of [2, 3]) {
    const g = G.gradeSummary({ clarity: v, protocol_version: v, measured_on: "2026-11-17", pages: {} }, rubric);
    assert.deepEqual([g.status, g.grade], ["re-measure", null]);
    assert.equal(g.why, `measured with Clarity v${v}; these rules are Clarity v1's: re-measure`);
  }
  // The numbers started again at 1 (2026-11-19): the first v1 (2026-11-14) also said 1.
  const first = G.gradeSummary({ clarity: 1, protocol_version: 1, measured_on: "2026-11-14", pages: {} }, rubric);
  assert.deepEqual([first.status, first.why],
    ["re-measure", "measured with the first Clarity v1 (2026-11-14), before the version started again at 1: re-measure"]);
  for (const day of [rubric.clarity_measured_since, "2026-11-20", undefined]) {
    assert.equal(G.gradeSummary({ clarity: 1, protocol_version: 1, measured_on: day, pages: {} }, rubric).status, "graded", String(day));
  }
});

test("a measurement that isn't comparable shows no letter", () => {
  const g = G.gradeSummary({ protocol_version: rubric.protocol_version, pages: {}, not_comparable: "not the default zoom" }, rubric);
  assert.deepEqual([g.status, g.grade], ["not-comparable", null]);
});

test("frames and median bands", () => {
  for (const c of data.frames) assert.equal(G.frameBand(c.stats, rubric), c.band, JSON.stringify(c));
  for (const c of data.median_bands) assert.equal(G.medianBand(c.bands), c.band, JSON.stringify(c));
});

test("scroll levels per run and their median (rubric v5)", () => {
  for (const c of data.scroll_levels) assert.equal(G.scrollLevel(c.stats, rubric), c.level, c.name);
  for (const c of data.update_levels) assert.equal(G.updateLevel(c.run, rubric), c.level, c.name);
  for (const c of data.camera_levels) assert.equal(G.cameraLevel(c.run, rubric), c.level, c.name);
  assert.ok(data.dialog_levels.length);
  for (const c of data.dialog_levels) assert.equal(G.dialogLevel(c.run, rubric), c.level, c.name);
  for (const c of data.median_levels) assert.equal(G.medianLevel(c.levels, rubric), c.level, JSON.stringify(c));
});
