// The recipe's app ids and components are the ones the generic app's build and manifest have, and the
// build for each Android version is the one its minSdk allows.
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { APPS, GENERIC, LEGACY, appFor } from '../src';

const android = join(__dirname, '../../android');
const gradle = readFileSync(join(android, 'build.gradle.kts'), 'utf8');
const manifest = readFileSync(join(android, 'src/main/AndroidManifest.xml'), 'utf8');

describe('the two builds', () => {
  it('have the build\'s application ids', () => {
    expect(gradle).toContain(`applicationId = "${GENERIC.package}"`);
    expect(gradle).toMatch(/create\("legacy"\)[\s\S]*?applicationIdSuffix = "\.legacy"/);
    expect(LEGACY.package).toBe(`${GENERIC.package}.legacy`);
  });

  it('name the manifest\'s activity, home alias and device admin', () => {
    for (const app of APPS) {
      const cls = (c: string) => c.split('/')[1]!;
      expect(manifest).toContain(`android:name="${cls(app.activity)}"`);
      expect(manifest).toMatch(new RegExp(`android:name="${cls(app.home).replace(/\./g, '\\.')}"[\\s\\S]*?category.HOME`));
      expect(manifest).toContain(`android:name="${cls(app.admin!)}"`);
    }
  });

  it('cover API 23 to 25 with the legacy build (its minSdk) and 26 on with Glassworks', () => {
    expect(gradle).toMatch(/withBuildType\("legacy"\)[\s\S]*?v\.minSdk = 23/);
    expect(appFor(22)).toBeUndefined();
    expect([23, 24, 25].map((s) => appFor(s))).toEqual([LEGACY, LEGACY, LEGACY]);
    expect([26, 29, 34, 37].map((s) => appFor(s))).toEqual([GENERIC, GENERIC, GENERIC, GENERIC]);
  });
});
