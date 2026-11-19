// A frame's agent (#641, docs/frame-agent.md): frame.json lists what it does in the spec's words, and nothing else.
import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { agentProblems, keyProblems, type FrameDesign } from '../src';

const FRAMES = join(__dirname, '../../../frames');
const ids = readdirSync(FRAMES, { withFileTypes: true }).filter((d) => d.isDirectory()).map((d) => d.name);
const read = (id: string) => JSON.parse(readFileSync(join(FRAMES, id, 'design', 'frame.json'), 'utf8')) as FrameDesign;

describe('agentProblems', () => {
  it.each(ids)('%s: its agent passes', (id) => {
    expect(agentProblems(read(id))).toEqual([]);
  });

  it('knows which frames have an agent', () => {
    expect(ids.filter((id) => read(id).agent).sort()).toEqual(['aqara-s1-plus']);
    expect(keyProblems(read('aqara-s1-plus'))).toEqual([]);
  });

  it('passes a frame without one', () => {
    expect(agentProblems({ kind: 'device' })).toEqual([]);
  });

  it.each([
    ['an agent on a tested device', { kind: 'tested', agent: { capabilities: ['time-zone'] } }, ["agent: a tested device has no code of its own; its base's agent is used"]],
    ['a list instead of an object', { kind: 'full', agent: ['time-zone'] }, ['agent: should be { "capabilities": [...] }']],
    ['no capabilities', { kind: 'full', agent: {} }, ['agent.capabilities: list what the agent does; leave `agent` out for none']],
    ['an empty list', { kind: 'full', agent: { capabilities: [] } }, ['agent.capabilities: list what the agent does; leave `agent` out for none']],
    ['an unknown word', { kind: 'full', agent: { capabilities: ['root-shell'] } }, ['agent.capabilities: "root-shell" isn\'t a capability (docs/frame-agent.md#capabilities)']],
    ['a word twice', { kind: 'full', agent: { capabilities: ['time-zone', 'time-zone'] } }, ['agent.capabilities: "time-zone" is listed twice']],
    ['a field the spec has no use for', { kind: 'full', agent: { capabilities: ['time-zone'], version: 5 } }, ['agent.version: not an agent field (docs/frame-agent.md#discovery)']],
  ])('fails %s', (_, f, why) => {
    expect(agentProblems(f as FrameDesign)).toEqual(why);
  });
});
