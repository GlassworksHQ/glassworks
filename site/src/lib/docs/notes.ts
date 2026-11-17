/*
 * The words for a research note's front matter. A note says which topic it is about and how sure it
 * is (AGENTS.md → Document the break-in work):
 *
 *   ---
 *   topic: boot
 *   evidence: verified
 *   ---
 *
 * The build fails on a value that isn't listed here. A note without front matter is still published,
 * under "Not sorted yet". The keys' order is the order topics are shown in.
 */
export const TOPICS = {
  hardware: "Hardware",
  boot: "Boot and root",
  access: "adb and access",
  install: "Install and Kiln",
  android: "App and Android",
  display: "Screen and media",
  radio: "Radio",
  panes: "Panes and Home Assistant",
  perf: "Performance and Clarity",
  method: "Bring-up method",
} as const;

export const EVIDENCE = {
  verified: "Verified",
  read: "Read",
  untested: "Untested",
} as const;

export type Topic = keyof typeof TOPICS;
export type Evidence = keyof typeof EVIDENCE;

/** A note's topic and evidence from its front matter, or the reasons they can't be read. */
export function noteMeta(file: string, data: Record<string, string | boolean>): { topic?: Topic; evidence?: Evidence; errors: string[] } {
  const errors: string[] = [];
  const pick = <K extends string>(key: string, words: Record<K, string>): K | undefined => {
    const v = data[key];
    if (v === undefined) return undefined;
    if (typeof v === "string" && v in words) return v as K;
    errors.push(`${file}: front matter ${key}: "${v}" isn't one of ${Object.keys(words).join(", ")} (site/src/lib/docs/notes.ts)`);
  };
  return { topic: pick("topic", TOPICS), evidence: pick("evidence", EVIDENCE), errors };
}
