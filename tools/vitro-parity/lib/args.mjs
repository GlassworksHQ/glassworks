// capture.mjs's command line: the command, then only known options, each with its operand.
//
// Touches: nothing (pure functions).

/** The options capture.mjs takes. Every one needs a value. */
export const OPTIONS = { root: 'dir', frames: 'id,id', cases: 'id,id', templates: 'file' };
/** Its switches, which take none: where the capture's Home Assistant comes from (see capture.mjs). */
export const FLAGS = { fakeha: 'the fake HA, from each case\'s fixture', 'real-ha': 'the HA in ha.json (a local corpus only)' };

/**
 * Parses `<cmd> [--name value ...]`. Throws on an unknown option, an option given twice, a missing operand (the
 * next word is absent or another option) and a stray word, so a typo never quietly widens or empties a run.
 */
export function parseArgs(argv) {
  const [cmd, ...rest] = argv;
  if (!cmd || cmd.startsWith('--')) throw new Error('missing the command (prepare or capture)');
  const o = { cmd };
  for (let i = 0; i < rest.length; i++) {
    const word = rest[i];
    if (!word.startsWith('--')) throw new Error(`unexpected argument ${word}`);
    const name = word.slice(2);
    if (name in FLAGS) {
      if (name in o) throw new Error(`${word} given twice`);
      o[name] = true;
      continue;
    }
    if (!(name in OPTIONS)) throw new Error(`unknown option ${word} (known: ${[...Object.keys(OPTIONS), ...Object.keys(FLAGS)].map((k) => '--' + k).join(', ')})`);
    if (name in o) throw new Error(`${word} given twice`);
    const value = rest[i + 1];
    if (value === undefined || value.startsWith('--') || value === '') throw new Error(`${word} needs a value (<${OPTIONS[name]}>)`);
    o[name] = value;
    i++;
  }
  if (o.fakeha && o['real-ha']) throw new Error('--fakeha and --real-ha: one or the other');
  return o;
}

/**
 * The ids in [requested] (a comma list, or undefined for all) picked from [ids]. Throws when a requested id is
 * not among them: an unknown frame or case would otherwise select nothing and the run would pass.
 */
export function pick(requested, ids, what) {
  if (requested === undefined) return new Set(ids);
  const want = requested.split(',').map((s) => s.trim()).filter(Boolean);
  const unknown = want.filter((w) => !ids.includes(w));
  if (!want.length || unknown.length) throw new Error(`no ${what} ${unknown.join(', ') || '(empty list)'}; known: ${ids.join(', ')}`);
  return new Set(want);
}
