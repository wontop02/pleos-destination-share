import test from 'node:test';
import assert from 'node:assert/strict';
import * as fs from 'node:fs/promises';
import path from 'node:path';
import { QueueStore } from '../../harness/store.js';
import { MAX_CHECKPOINT_BYTES, MAX_CONTROL_CHECKPOINT_BYTES, MAX_OPERATION_CHECKPOINT_BYTES } from '../../harness/policy.js';
import { transition } from '../../harness/queue.js';
import { START, demo, errorCode, issue, snapshot, tempRoot } from './helpers.js';

const checkpoint = (root, id = 'demo') => path.join(root, '.harness', 'runs', `${id}.json`);
const lockfile = root => path.join(root, '.harness', 'locks', 'queue.lock');
const encodedCheckpoint = state => `${JSON.stringify(state)}\n`;
const checkpointBytes = state => Buffer.byteLength(encodedCheckpoint(state));
const maximumEscapedReason = '\0'.repeat(10000);

async function setup(t, input = demo) {
  const root = await tempRoot(t);
  let now = START;
  const store = new QueueStore({ repoRoot: root, clock: () => now });
  const state = await store.init('demo', input);
  return { root, store, state, setTime(value) { now = value; } };
}

// Reach the exact admission boundary through supported operations, without editing a checkpoint.
async function fillStopBudget(store, state, input) {
  const stop = { type: 'stop', reason: '\0'.repeat(9000), condition: '\0'.repeat(9000) };
  while (checkpointBytes(transition(state, stop, () => START)) <= MAX_OPERATION_CHECKPOINT_BYTES - 1024) {
    state = await store.update('demo', state.revision, stop);
    state = await store.update('demo', state.revision, { type: 'resume', snapshot: input });
  }
  const minimum = { type: 'stop', reason: 'r', condition: 'c' };
  const extra = MAX_OPERATION_CHECKPOINT_BYTES - checkpointBytes(transition(state, minimum, () => START));
  assert.ok(extra >= 0);
  const totalTextBytes = extra + 2;
  const escapedText = length => '\0'.repeat(Math.floor(length / 6)) + 'r'.repeat(length % 6);
  const finalStop = { type: 'stop', reason: escapedText(Math.floor(totalTextBytes / 2)), condition: escapedText(Math.ceil(totalTextBytes / 2)) };
  assert.ok(finalStop.reason.length <= 10000 && finalStop.condition.length <= 10000);
  assert.equal(checkpointBytes(transition(state, finalStop, () => START)), MAX_OPERATION_CHECKPOINT_BYTES);
  return store.update('demo', state.revision, finalStop);
}

test('persisted state is versioned and can be loaded by another store instance', async t => {
  const { root, store, state } = await setup(t);
  assert.equal(state.version, 1);
  assert.equal(state.revision, 0);
  const next = await store.update('demo', 0, { type: 'next' });
  assert.equal(next.activeIssue, 101);
  const reopened = new QueueStore({ repoRoot: root, clock: () => START });
  assert.deepEqual(await reopened.read('demo'), next);
  assert.deepEqual(await reopened.update('demo', 1, { type: 'next' }), next);
  assert.deepEqual(await fs.readdir(path.join(root, '.harness', 'locks')), []);
  assert.deepEqual(await fs.readdir(path.join(root, '.harness', 'runs')), ['demo.json']);
});

test('duplicate initialization and a second live run preserve the existing checkpoint', async t => {
  const { root, store } = await setup(t);
  const original = await fs.readFile(checkpoint(root), 'utf8');
  await assert.rejects(store.init('demo', snapshot()), errorCode('DUPLICATE_RUN'));
  await assert.rejects(store.init('other', snapshot()), errorCode('RUN_CONFLICT'));
  assert.equal(await fs.readFile(checkpoint(root), 'utf8'), original);
  await assert.rejects(fs.access(checkpoint(root, 'other')), { code: 'ENOENT' });
});

test('stale revisions, invalid operations and changed resume snapshot never replace a checkpoint', async t => {
  const { root, store } = await setup(t);
  await store.update('demo', 0, { type: 'next' });
  await store.update('demo', 1, { type: 'stop', reason: 'shared outage', condition: 'access restored' });
  const original = await fs.readFile(checkpoint(root), 'utf8');
  await assert.rejects(store.update('demo', 1, { type: 'resume', snapshot: demo }), errorCode('STALE_REVISION'));
  const changed = structuredClone(demo);
  changed.issues.push(issue(104));
  await assert.rejects(store.update('demo', 2, { type: 'resume', snapshot: changed }), errorCode('SNAPSHOT_MISMATCH'));
  await assert.rejects(store.update('demo', 2, { type: 'complete', issue: 101, evidence: 'still stopped' }), errorCode('RUN_STOPPED'));
  assert.equal(await fs.readFile(checkpoint(root), 'utf8'), original);
});

test('occupied lock fails closed and operator recovery leaves the old state readable', async t => {
  const { root, store } = await setup(t);
  const original = await fs.readFile(checkpoint(root), 'utf8');
  const owner = JSON.stringify({ pid: 999999999, owner: 'fictional-stale-owner' });
  await fs.writeFile(lockfile(root), owner, { flag: 'wx' });
  await assert.rejects(store.update('demo', 0, { type: 'next' }), errorCode('LOCKED'));
  await assert.rejects(store.init('other', snapshot()), errorCode('LOCKED'));
  assert.equal(await fs.readFile(lockfile(root), 'utf8'), owner);
  assert.equal(await fs.readFile(checkpoint(root), 'utf8'), original);
  assert.equal((await store.read('demo')).revision, 0);
  // Explicit test-operator recovery, after verifying the fictional owner is absent.
  await fs.unlink(lockfile(root));
  assert.equal((await store.update('demo', 0, { type: 'next' })).revision, 1);
});

test('concurrent writers serialize and cannot spend two attempts or replace a newer revision', async t => {
  const { root, store } = await setup(t);
  const other = new QueueStore({ repoRoot: root, clock: () => START });
  const results = await Promise.allSettled([
    store.update('demo', 0, { type: 'next' }), other.update('demo', 0, { type: 'next' }),
  ]);
  assert.equal(results.filter(result => result.status === 'fulfilled').length, 1);
  const rejected = results.find(result => result.status === 'rejected');
  assert.ok(['LOCKED', 'STALE_REVISION'].includes(rejected.reason.code));
  const loaded = await other.read('demo');
  assert.equal(loaded.revision, 1);
  assert.equal(loaded.entries[0].attempts, 1);
  const original = await fs.readFile(checkpoint(root), 'utf8');
  await assert.rejects(store.update('demo', 0, { type: 'block', issue: 101, reason: 'old writer', condition: 'retry' }), errorCode('STALE_REVISION'));
  assert.equal(await fs.readFile(checkpoint(root), 'utf8'), original);
});

test('different run IDs also share one repository lock and one live run', async t => {
  const root = await tempRoot(t);
  const left = new QueueStore({ repoRoot: root, clock: () => START });
  const right = new QueueStore({ repoRoot: root, clock: () => START });
  const results = await Promise.allSettled([left.init('left', snapshot()), right.init('right', snapshot())]);
  assert.equal(results.filter(result => result.status === 'fulfilled').length, 1);
  assert.ok(['LOCKED', 'RUN_CONFLICT'].includes(results.find(result => result.status === 'rejected').reason.code));
  assert.equal((await fs.readdir(path.join(root, '.harness', 'runs'))).length, 1);
});

test('archive explicitly releases unfinished work for another batch without claiming completion', async t => {
  const { root, store } = await setup(t);
  await store.update('demo', 0, { type: 'next' });
  await store.update('demo', 1, { type: 'stop', reason: 'user stop', condition: 'coordinator decision' });
  await assert.rejects(store.init('next-batch', snapshot()), errorCode('RUN_CONFLICT'));
  const archived = await store.update('demo', 2, { type: 'archive', reason: 'coordinator ends incomplete fictional batch' });
  assert.equal(archived.status, 'archived');
  assert.equal(archived.activeIssue, null);
  assert.equal(archived.entries[0].status, 'blocked');
  assert.equal(archived.entries[0].attempts, 1);
  assert.equal(archived.entries[0].claims[0].outcome, 'archived');
  const archiveBytes = await fs.readFile(checkpoint(root), 'utf8');
  await assert.rejects(store.update('demo', 3, { type: 'resume', snapshot: demo }), errorCode('RUN_ARCHIVED'));
  assert.equal(await fs.readFile(checkpoint(root), 'utf8'), archiveBytes);
  const newRun = await store.init('next-batch', snapshot());
  assert.equal(newRun.status, 'running');
  assert.equal((await fs.readdir(path.join(root, '.harness', 'runs'))).length, 2);
});

test('deadline survives disk reload and rejected late resume preserves the stopped checkpoint', async t => {
  const { root, store, setTime } = await setup(t);
  await store.update('demo', 0, { type: 'next' });
  await store.update('demo', 1, { type: 'stop', reason: 'shared auth outage', condition: 'auth restored' });
  const original = await fs.readFile(checkpoint(root), 'utf8');
  setTime(START + 120 * 60000);
  await assert.rejects(store.update('demo', 2, { type: 'resume', snapshot: demo }), errorCode('TIME_LIMIT'));
  assert.equal(await fs.readFile(checkpoint(root), 'utf8'), original);
  const expired = await store.update('demo', 2, { type: 'next' });
  assert.equal(expired.status, 'expired');
  assert.equal(expired.activeIssue, 101);
  assert.equal(expired.entries[0].attempts, 1);
  await assert.rejects(store.init('cannot-bypass-expiry', snapshot()), errorCode('RUN_CONFLICT'));
  await store.update('demo', 3, { type: 'archive', reason: 'coordinator closes expired run' });
  await store.init('new-reviewed-batch', snapshot());
});

test('corrupt, unsupported and malformed checkpoints remain byte-for-byte untouched', async t => {
  const { root, store, state } = await setup(t);
  const changedPolicy = structuredClone(state);
  changedPolicy.policy.maxMinutes = 121;
  const changedSnapshot = structuredClone(state);
  changedSnapshot.snapshot.issues[0].body = 'silently changed';
  const cases = [
    ['{not JSON', 'CORRUPT_STATE'],
    [JSON.stringify({ ...state, version: 999 }), 'UNSUPPORTED_VERSION'],
    [JSON.stringify({ ...state, revision: 'zero' }), 'INVALID_STATE'],
    [JSON.stringify({ ...state, unexpected: true }), 'INVALID_STATE'],
    [JSON.stringify(changedPolicy), 'POLICY_MISMATCH'],
    [JSON.stringify(changedSnapshot), 'SNAPSHOT_MISMATCH'],
    [JSON.stringify({ ...state, entries: [] }), 'INVALID_STATE'],
  ];
  for (const [bytes, code] of cases) {
    await fs.writeFile(checkpoint(root), bytes);
    await assert.rejects(store.read('demo'), errorCode(code));
    await assert.rejects(store.update('demo', 0, { type: 'next' }), errorCode(code));
    assert.equal(await fs.readFile(checkpoint(root), 'utf8'), bytes);
    await assert.rejects(store.init('demo', demo), errorCode('DUPLICATE_RUN'));
    assert.equal(await fs.readFile(checkpoint(root), 'utf8'), bytes);
  }
});

test('interrupted temporary write is ignored, stale lock is retained and committed state is readable', async t => {
  const { root, store } = await setup(t);
  const original = await fs.readFile(checkpoint(root), 'utf8');
  const temporary = path.join(root, '.harness', 'runs', '.demo.999999.01234567-0123-0123-0123-0123456789ab.tmp');
  await fs.writeFile(temporary, '{incomplete candidate checkpoint');
  await fs.writeFile(lockfile(root), 'fictional interrupted writer');
  assert.equal((await new QueueStore({ repoRoot: root }).read('demo')).revision, 0);
  await assert.rejects(store.update('demo', 0, { type: 'next' }), errorCode('LOCKED'));
  assert.equal(await fs.readFile(checkpoint(root), 'utf8'), original);
  await fs.unlink(lockfile(root));
  const next = await store.update('demo', 0, { type: 'next' });
  assert.equal(next.activeIssue, 101);
  assert.equal(await fs.readFile(temporary, 'utf8'), '{incomplete candidate checkpoint');
});

test('unsafe run IDs fail before creating storage', async t => {
  const root = await tempRoot(t);
  const store = new QueueStore({ repoRoot: root });
  for (const id of ['../escape', '/absolute', 'a/b', 'a\\b', '.', '..', 'UPPER', '', 'a'.repeat(65), 'a%2fescape']) {
    await assert.rejects(store.init(id, demo), errorCode('UNSAFE_PATH'), id);
    await assert.rejects(store.read(id), errorCode('UNSAFE_PATH'), id);
  }
  assert.deepEqual(await fs.readdir(root), []);
});

test('storage directory symlinks cannot redirect writes outside storage', async t => {
  for (const component of ['.harness', 'runs', 'locks']) {
    const root = await tempRoot(t);
    const outside = await tempRoot(t);
    if (component === '.harness') await fs.symlink(outside, path.join(root, '.harness'), 'dir');
    else {
      await fs.mkdir(path.join(root, '.harness'));
      if (component === 'locks') await fs.mkdir(path.join(root, '.harness', 'runs'));
      await fs.symlink(outside, path.join(root, '.harness', component), 'dir');
    }
    const store = new QueueStore({ repoRoot: root, clock: () => START });
    await assert.rejects(store.init('demo', demo), errorCode('UNSAFE_PATH'));
    assert.deepEqual(await fs.readdir(outside), []);
  }
});

test('checkpoint and lock symlinks do not overwrite their outside targets', async t => {
  const { root, store } = await setup(t);
  const outside = await tempRoot(t);
  const target = path.join(outside, 'state.json');
  const original = await fs.readFile(checkpoint(root), 'utf8');
  await fs.writeFile(target, original);
  await fs.unlink(checkpoint(root));
  await fs.symlink(target, checkpoint(root));
  await assert.rejects(store.read('demo'), errorCode('UNSAFE_PATH'));
  await assert.rejects(store.update('demo', 0, { type: 'next' }), errorCode('UNSAFE_PATH'));
  assert.equal(await fs.readFile(target, 'utf8'), original);
  await fs.unlink(checkpoint(root));
  await fs.writeFile(checkpoint(root), original);
  await fs.symlink(target, lockfile(root));
  await assert.rejects(store.update('demo', 0, { type: 'next' }), errorCode('LOCKED'));
  assert.equal(await fs.readFile(target, 'utf8'), original);
  assert.ok((await fs.lstat(lockfile(root))).isSymbolicLink());
});

test('a second corrupted checkpoint or contradictory live run fails closed before any write', async t => {
  const { root, store, state } = await setup(t);
  const original = await fs.readFile(checkpoint(root), 'utf8');
  await fs.writeFile(checkpoint(root, 'other'), '{bad');
  await assert.rejects(store.update('demo', 0, { type: 'next' }), errorCode('CORRUPT_STATE'));
  assert.equal(await fs.readFile(checkpoint(root), 'utf8'), original);
  await fs.writeFile(checkpoint(root, 'other'), JSON.stringify({ ...state, runId: 'other' }));
  await assert.rejects(store.update('demo', 0, { type: 'next' }), errorCode('RUN_CONFLICT'));
  assert.equal(await fs.readFile(checkpoint(root), 'utf8'), original);
});

test('legacy near-hard-cap checkpoints are not truncated to make an oversized archive fit', async t => {
  const { root, store, state } = await setup(t, snapshot());
  const nearLimit = structuredClone(state);
  nearLimit.revision = 500;
  const stop = { at: state.createdAt, reason: '한'.repeat(10000), resumeCondition: 'c'.repeat(10000), resumedAt: state.createdAt };
  const bytes = value => `${JSON.stringify(value)}\n`;
  while (true) {
    const candidate = { ...nearLimit, stops: [...nearLimit.stops, stop] };
    if (Buffer.byteLength(bytes(candidate)) > MAX_CHECKPOINT_BYTES) break;
    nearLimit.stops.push(structuredClone(stop));
  }
  const original = bytes(nearLimit);
  assert.ok(Buffer.byteLength(original) <= MAX_CHECKPOINT_BYTES);
  assert.ok(Buffer.byteLength(original) > MAX_CHECKPOINT_BYTES - 50000);
  await fs.writeFile(checkpoint(root), original);
  await assert.rejects(store.update('demo', 500, { type: 'archive', reason: maximumEscapedReason }), errorCode('CHECKPOINT_TOO_LARGE'));
  assert.equal(await fs.readFile(checkpoint(root), 'utf8'), original);
  assert.equal((await store.read('demo')).revision, 500);
  assert.deepEqual(await fs.readdir(path.join(root, '.harness', 'runs')), ['demo.json']);
});

test('full ordinary stop budget still permits resume and maximum escaped active-claim archive', async t => {
  const input = snapshot();
  const { root, store } = await setup(t, input);
  let state = await store.update('demo', 0, { type: 'next' });
  const claimedAt = state.entries[0].claims[0].claimedAt;
  state = await fillStopBudget(store, state, input);
  assert.equal(state.status, 'stopped');
  assert.equal(state.activeIssue, 1);
  assert.equal(checkpointBytes(state), MAX_OPERATION_CHECKPOINT_BYTES);
  assert.equal(Buffer.byteLength(await fs.readFile(checkpoint(root))), MAX_OPERATION_CHECKPOINT_BYTES);
  state = await store.update('demo', state.revision, { type: 'resume', snapshot: input });
  assert.ok(checkpointBytes(state) > MAX_OPERATION_CHECKPOINT_BYTES);
  assert.ok(checkpointBytes(state) <= MAX_CONTROL_CHECKPOINT_BYTES);
  assert.equal(state.activeIssue, 1);
  assert.equal(state.entries[0].attempts, 1);
  const original = await fs.readFile(checkpoint(root), 'utf8');
  await assert.rejects(store.update('demo', state.revision, { type: 'stop', reason: maximumEscapedReason, condition: maximumEscapedReason }), errorCode('CHECKPOINT_TOO_LARGE'));
  assert.equal(await fs.readFile(checkpoint(root), 'utf8'), original);
  assert.equal((await store.read('demo')).revision, state.revision);
  const previousStops = structuredClone(state.stops);
  assert.equal(Buffer.byteLength(JSON.stringify(maximumEscapedReason)) - 2, 60000);
  state = await store.update('demo', state.revision, { type: 'archive', reason: maximumEscapedReason });
  assert.equal(state.status, 'archived');
  assert.equal(state.activeIssue, null);
  assert.equal(state.entries[0].attempts, 1);
  assert.equal(state.entries[0].claims[0].claimedAt, claimedAt);
  assert.equal(state.entries[0].claims[0].outcome, 'archived');
  assert.equal(state.entries[0].claims[0].reason, maximumEscapedReason);
  assert.equal(state.archive.reason, maximumEscapedReason);
  assert.deepEqual(state.stops, previousStops);
  assert.ok(checkpointBytes(state) > MAX_CONTROL_CHECKPOINT_BYTES);
  assert.ok(checkpointBytes(state) <= MAX_CHECKPOINT_BYTES);
  assert.deepEqual(await store.read('demo'), state);
  await store.init('next-batch', input);
});

test('full ordinary budget retains expiry bookkeeping and archive headroom after the deadline', async t => {
  const input = snapshot();
  const { root, store, setTime } = await setup(t, input);
  let state = await store.update('demo', 0, { type: 'next' });
  state = await fillStopBudget(store, state, input);
  state = await store.update('demo', state.revision, { type: 'resume', snapshot: input });
  setTime(START + 120 * 60000);
  state = await store.update('demo', state.revision, { type: 'next' });
  assert.equal(state.status, 'expired');
  assert.equal(state.activeIssue, 1);
  assert.ok(checkpointBytes(state) > MAX_OPERATION_CHECKPOINT_BYTES);
  assert.ok(checkpointBytes(state) <= MAX_CONTROL_CHECKPOINT_BYTES);
  const original = await fs.readFile(checkpoint(root), 'utf8');
  await assert.rejects(store.update('demo', state.revision, { type: 'resume', snapshot: input }), errorCode('TIME_LIMIT'));
  assert.equal(await fs.readFile(checkpoint(root), 'utf8'), original);
  const previousStops = structuredClone(state.stops);
  state = await store.update('demo', state.revision, { type: 'archive', reason: maximumEscapedReason });
  assert.equal(state.status, 'archived');
  assert.equal(state.entries[0].claims[0].outcome, 'archived');
  assert.equal(state.entries[0].attempts, 1);
  assert.deepEqual(state.stops, previousStops);
  assert.ok(checkpointBytes(state) <= MAX_CHECKPOINT_BYTES);
  await store.init('after-deadline', input);
});

test('dependency acknowledgements cannot consume reserved archive capacity', async t => {
  const dependencies = Array.from({ length: 1000 }, (_, index) => index + 2);
  const input = snapshot([issue(1, { dependencies })]);
  const { root, store, state: initial } = await setup(t, input);
  let state = initial;
  let rejected = false;
  for (const number of dependencies) {
    const action = { type: 'ack-dependency', issue: number, evidence: maximumEscapedReason };
    if (checkpointBytes(transition(state, action, () => START)) > MAX_OPERATION_CHECKPOINT_BYTES) {
      const original = await fs.readFile(checkpoint(root), 'utf8');
      await assert.rejects(store.update('demo', state.revision, action), errorCode('CHECKPOINT_TOO_LARGE'));
      assert.equal(await fs.readFile(checkpoint(root), 'utf8'), original);
      assert.equal((await store.read('demo')).revision, state.revision);
      rejected = true;
      break;
    }
    state = await store.update('demo', state.revision, action);
  }
  assert.equal(rejected, true);
  assert.equal(state.status, 'waiting');
  assert.ok(state.acknowledgements.length > 0);
  assert.ok(checkpointBytes(state) <= MAX_OPERATION_CHECKPOINT_BYTES);
  const previousAcknowledgements = structuredClone(state.acknowledgements);
  state = await store.update('demo', state.revision, { type: 'archive', reason: maximumEscapedReason });
  assert.equal(state.status, 'archived');
  assert.deepEqual(state.acknowledgements, previousAcknowledgements);
  assert.ok(checkpointBytes(state) <= MAX_CHECKPOINT_BYTES);
  await store.init('after-ack-capacity', snapshot());
});
