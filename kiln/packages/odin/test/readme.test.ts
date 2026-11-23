import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { expect, it } from 'vitest';
import { flashRecovery } from './readme-example';

it('the README example compiles and exports flashRecovery', () => {
  expect(typeof flashRecovery).toBe('function');
});

it('the README shows readme-example.ts line for line', () => {
  const example = readFileSync(join(__dirname, 'readme-example.ts'), 'utf8').split('\n').slice(1).join('\n').trim();
  const readme = readFileSync(join(__dirname, '../README.md'), 'utf8');
  const block = /## Use\n\n```ts\n([\s\S]*?)```/.exec(readme)?.[1]?.trim();
  expect(block).toBe(example);
});
