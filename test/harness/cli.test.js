import test from 'node:test';
import assert from 'node:assert/strict';
import * as fs from 'node:fs/promises';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { cliPath, demo, fixturePath, tempRoot } from './helpers.js';

function cli(root, command, options = {}, expectedCode = 0, run = 'demo') {
  const args = [cliPath, command, '--repo-root', root, '--run', run];
  for (const [name, value] of Object.entries(options)) args.push(`--${name}`, String(value));
  const result = spawnSync(process.execPath, args, { encoding: 'utf8', timeout: 10000 });
  assert.equal(result.error, undefined);
  assert.equal(result.status, expectedCode, result.stderr || result.stdout);
  return JSON.parse(expectedCode === 0 ? result.stdout : result.stderr);
}

test('separate CLI processes reload, block, continue, stop, resume and finish the frozen demo', async t => {
  const root = await tempRoot(t);
  let output = cli(root, 'init', { snapshot: fixturePath });
  assert.equal(output.state.revision, 0);
  assert.equal(output.state.snapshot.fictional, true);
  assert.deepEqual(output.selectedIssues, [101, 102, 103]);
  const deadline = output.state.deadlineAt;
  output = cli(root, 'next', { revision: 0 });
  assert.equal(output.next.number, 101);
  assert.equal(output.state.entries[0].attempts, 1);
  assert.equal(cli(root, 'next', { revision: 1 }).state.revision, 1);
  output = cli(root, 'block', { revision: 1, issue: 101, reason: 'fictional resource unavailable', condition: 'fictional resource restored' });
  assert.equal(output.state.revision, 2);
  output = cli(root, 'next', { revision: 2 });
  assert.equal(output.next.number, 103);
  output = cli(root, 'stop', { revision: 3, reason: 'shared authentication outage', condition: 'coordinator verifies authentication' });
  assert.equal(output.next, null);
  assert.equal(output.state.activeIssue, 103);
  assert.equal(cli(root, 'status').state.status, 'stopped');
  output = cli(root, 'resume', { revision: 4, snapshot: fixturePath });
  assert.equal(output.next.number, 103);
  assert.equal(output.state.entries[2].attempts, 1);
  assert.equal(output.state.deadlineAt, deadline);
  assert.equal(output.state.stops[0].reason, 'shared authentication outage');
  assert.equal(cli(root, 'resume', { revision: 5, snapshot: fixturePath }).state.revision, 5);
  output = cli(root, 'complete', { revision: 5, issue: 103, evidence: 'fictional coordinator acknowledgement 103' });
  assert.equal(output.state.status, 'waiting');
  output = cli(root, 'retry', { revision: 6, issue: 101 });
  assert.equal(output.state.entries[0].attempts, 1);
  output = cli(root, 'next', { revision: 7 });
  assert.equal(output.next.number, 101);
  assert.equal(output.state.entries[0].attempts, 2);
  cli(root, 'complete', { revision: 8, issue: 101, evidence: 'fictional coordinator acknowledgement 101' });
  output = cli(root, 'next', { revision: 9 });
  assert.equal(output.next.number, 102);
  output = cli(root, 'complete', { revision: 10, issue: 102, evidence: 'fictional coordinator acknowledgement 102' });
  assert.equal(output.state.status, 'complete');
  assert.equal(output.state.revision, 11);
  assert.equal(cli(root, 'next', { revision: 11 }).next, null);
  const saved = await fs.readFile(path.join(root, '.harness', 'runs', 'demo.json'), 'utf8');
  assert.equal(cli(root, 'complete', { revision: 11, issue: 102, evidence: 'duplicate' }, 1).error.code, 'RUN_COMPLETE');
  assert.equal(await fs.readFile(path.join(root, '.harness', 'runs', 'demo.json'), 'utf8'), saved);
});

test('CLI archive keeps blocked batch incomplete and permits an explicit new run ID', async t => {
  const root = await tempRoot(t);
  cli(root, 'init', { snapshot: fixturePath });
  cli(root, 'next', { revision: 0 });
  cli(root, 'block', { revision: 1, issue: 101, reason: 'resource missing', condition: 'resource restored' });
  assert.equal(cli(root, 'init', { snapshot: fixturePath }, 1, 'new-demo').error.code, 'RUN_CONFLICT');
  const archived = cli(root, 'archive', { revision: 2, reason: 'fictional coordinator starts a reviewed new batch' });
  assert.equal(archived.state.status, 'archived');
  assert.ok(archived.state.entries.some(entry => entry.status !== 'complete'));
  assert.equal(cli(root, 'resume', { revision: 3, snapshot: fixturePath }, 1).error.code, 'RUN_ARCHIVED');
  assert.equal(cli(root, 'init', { snapshot: fixturePath }, 0, 'new-demo').state.revision, 0);
  assert.equal(cli(root, 'status').state.status, 'archived');
});

test('CLI validates arguments, requires revision and treats issue strings as inert data', async t => {
  const root = await tempRoot(t);
  const marker = path.join(root, 'must-not-exist');
  const input = structuredClone(demo);
  input.issues[0].body = `$(touch ${marker}); import('node:fs').then(fs => fs.writeFileSync('${marker}', 'bad'));`;
  const inputPath = path.join(root, 'fictional-input.json');
  await fs.writeFile(inputPath, JSON.stringify(input));
  cli(root, 'init', { snapshot: inputPath });
  assert.equal(cli(root, 'next', {}, 1).error.code, 'INVALID_INPUT');
  assert.equal(cli(root, 'next', { revision: 0, 'enable-app': true }, 1).error.code, 'INVALID_INPUT');
  assert.equal(cli(root, 'next', { revision: 0 }).next.body, input.issues[0].body);
  await assert.rejects(fs.access(marker), { code: 'ENOENT' });
  assert.equal(cli(root, 'status', {}, 1, '../outside').error.code, 'UNSAFE_PATH');
  assert.equal(cli(root, 'next', { revision: 0 }, 1).error.code, 'STALE_REVISION');
});
