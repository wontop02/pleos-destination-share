import * as fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export const fixturePath = fileURLToPath(new URL('../../harness/fixtures/demo.json', import.meta.url));
export const cliPath = fileURLToPath(new URL('../../harness/cli.js', import.meta.url));
export const demo = JSON.parse(await fs.readFile(fixturePath, 'utf8'));
export const START = Date.parse('2026-11-04T00:00:00.000Z');

export function issue(number, overrides = {}) {
  return { ...structuredClone(demo.issues[0]), number, title: `[FICTIONAL] Task ${number}`, ...overrides };
}

export function snapshot(issues = [issue(1)], overrides = {}) {
  return { version: 1, repository: 'wontop02/pleos-destination-share', fictional: true, completedDependencies: [], issues, ...overrides };
}

export async function tempRoot(t) {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'pleos-harness-test-'));
  t.after(() => fs.rm(root, { recursive: true, force: true }));
  return root;
}

export const errorCode = expected => error => error?.code === expected;
