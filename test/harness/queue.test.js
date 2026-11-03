import test from 'node:test';
import assert from 'node:assert/strict';
import { POLICY, POLICY_HASH, hash, normalizeSnapshot, selection } from '../../harness/policy.js';
import { createState, describeState, transition, validateState, waitingFor } from '../../harness/queue.js';
import { START, demo, errorCode, issue, snapshot } from './helpers.js';

function machine(input = demo) {
  let now = START;
  let state = createState('test', input, () => now);
  return {
    get state() { return state; },
    setTime(value) { now = value; },
    step(action) { state = transition(state, action, () => now); return state; },
  };
}

test('priority, creation time, number and cap freeze exactly five approved issues', () => {
  const input = snapshot([
    issue(9, { priority: 'p2' }), issue(8, { priority: 'p1' }),
    issue(7, { priority: 'p0', createdAt: '2026-11-02T00:00:00.000Z' }),
    issue(6, { priority: 'p0' }), issue(5, { priority: 'p0' }),
    issue(4, { priority: 'p0', createdAt: '2026-10-31T00:00:00.000Z' }), issue(3, { priority: 'p1' }),
  ]);
  const run = machine(input);
  assert.deepEqual(run.state.selectedIssues, [4, 5, 6, 7, 3]);
  assert.deepEqual(describeState(run.state).deferred, [8, 9]);
  assert.equal(POLICY.maxIssues, 5);
  assert.equal(POLICY.maxAttempts, 3);
  assert.equal(Date.parse(run.state.deadlineAt) - START, 120 * 60000);
  assert.equal(run.state.policyHash, POLICY_HASH);
  input.issues.unshift(issue(1, { createdAt: '2020-01-01T00:00:00.000Z' }));
  assert.deepEqual(run.state.selectedIssues, [4, 5, 6, 7, 3]);
  assert.throws(() => run.step({ type: 'resume', snapshot: input }), errorCode('SNAPSHOT_MISMATCH'));
  assert.deepEqual(run.state.selectedIssues, [4, 5, 6, 7, 3]);
});

test('unready, wrong approver, app kind and out-of-policy scopes are excluded with reasons', () => {
  const input = snapshot([
    issue(1, { ready: false }), issue(2, { approvedBy: 'someone-else' }),
    issue(3, { kind: 'prototype' }), issue(4, { scope: ['app/main.js'] }),
    issue(5, { scope: ['README.md.evil'] }), issue(6, { scope: ['.codex/agents-evil/foo'] }),
    issue(7, { kind: 'docs', scope: ['.github/ISSUE_TEMPLATE/task.md', 'AGENTS.md'] }),
    issue(8, { scope: ['test/harness/suite.test.js', 'package.json'] }),
  ]);
  const selected = selection(normalizeSnapshot(input));
  assert.deepEqual(selected.selectedIssues, [7, 8]);
  assert.deepEqual(selected.excluded.map(item => item.number), [1, 2, 3, 4, 5, 6]);
  assert.ok(selected.excluded.every(item => item.reasons.length > 0));
  assert.throws(() => machine({ ...input, repository: 'other/repository' }), errorCode('POLICY_MISMATCH'));
});

test('unsafe scopes, absent fields and malformed approvals are rejected before freezing', () => {
  for (const scope of ['/harness/', '../harness/', 'harness/../app.js', 'harness\\queue.js', 'C:/harness/a', 'harness//a', './harness/a', 'harness/./a', 'harness/\0a']) {
    assert.throws(() => machine(snapshot([issue(1, { scope: [scope] })])), errorCode('UNSAFE_PATH'), scope);
  }
  const missing = issue(1);
  delete missing.goal;
  assert.throws(() => machine(snapshot([missing])), errorCode('INVALID_INPUT'));
  assert.throws(() => machine(snapshot([issue(1, { ready: 'true' })])), errorCode('INVALID_INPUT'));
  assert.throws(() => machine(snapshot([issue(1, { acceptanceCriteria: [] })])), errorCode('INVALID_INPUT'));
  assert.throws(() => machine(snapshot([issue(1), issue(1)])), errorCode('INVALID_INPUT'));
  assert.throws(() => machine(snapshot([issue(1, { createdAt: '2026-02-30T00:00:00.000Z' })])), errorCode('INVALID_INPUT'));
});

test('block releases one claim, skips dependents and lets independent work continue', () => {
  const run = machine();
  run.step({ type: 'next' });
  assert.equal(run.state.activeIssue, 101);
  const claimed = run.state;
  assert.equal(run.step({ type: 'next' }), claimed);
  assert.equal(run.state.entries[0].attempts, 1);
  run.step({ type: 'block', issue: 101, reason: 'fictional SDK missing', condition: 'coordinator supplies fictional SDK' });
  assert.equal(run.state.activeIssue, null);
  assert.deepEqual(waitingFor(run.state, 102), [{ number: 101, reason: 'blocked' }]);
  run.step({ type: 'next' });
  assert.equal(run.state.activeIssue, 103);
  run.step({ type: 'complete', issue: 103, evidence: 'fictional coordinator acknowledgement' });
  assert.equal(run.state.status, 'waiting');
  assert.equal(run.step({ type: 'next' }).activeIssue, null);
  run.step({ type: 'retry', issue: 101 });
  run.step({ type: 'next' });
  assert.equal(run.state.activeIssue, 101);
  assert.equal(run.state.entries[0].attempts, 2);
  assert.equal(run.state.entries[0].claims[0].reason, 'fictional SDK missing');
  assert.equal(run.state.entries[0].claims[0].resumeCondition, 'coordinator supplies fictional SDK');
  run.step({ type: 'complete', issue: 101, evidence: 'coordinator accepted fictional result' });
  run.step({ type: 'next' });
  assert.equal(run.state.activeIssue, 102);
  run.step({ type: 'complete', issue: 102, evidence: 'coordinator accepted fictional documentation' });
  assert.equal(run.state.status, 'complete');
  const completed = run.state;
  assert.equal(run.step({ type: 'next' }), completed);
  assert.equal(describeState(run.state).next, null);
  assert.throws(() => run.step({ type: 'retry', issue: 101 }), errorCode('RUN_COMPLETE'));
});

test('closed metadata does not satisfy dependencies; explicit coordinator acknowledgement does', () => {
  const run = machine(snapshot([
    issue(1, { dependencies: [90] }), issue(90, { ready: false, closed: true }),
  ]));
  assert.equal(run.state.status, 'waiting');
  assert.deepEqual(waitingFor(run.state, 1), [{ number: 90, reason: 'outside-frozen-batch' }]);
  assert.equal(run.step({ type: 'next' }).activeIssue, null);
  run.step({ type: 'ack-dependency', issue: 90, evidence: 'trusted coordinator checked external completion' });
  assert.equal(run.state.status, 'running');
  const acknowledged = run.state;
  assert.equal(run.step({ type: 'ack-dependency', issue: 90, evidence: 'same acknowledged dependency' }), acknowledged);
  run.step({ type: 'next' });
  assert.equal(run.state.activeIssue, 1);
  assert.throws(() => run.step({ type: 'ack-dependency', issue: 1, evidence: 'cannot bypass active claim' }), errorCode('SELECTED_DEPENDENCY'));
  assert.throws(() => run.step({ type: 'ack-dependency', issue: 999, evidence: 'unrelated' }), errorCode('UNKNOWN_DEPENDENCY'));
  assert.match(describeState(run.state).trust, /does not verify GitHub/);
});

test('trusted initial completion list excludes completed candidates and unblocks dependencies', () => {
  const run = machine(snapshot([issue(1, { dependencies: [90] }), issue(90)], { completedDependencies: [90] }));
  assert.deepEqual(run.state.selectedIssues, [1]);
  assert.deepEqual(waitingFor(run.state, 1), []);
  run.step({ type: 'next' });
  assert.equal(run.state.activeIssue, 1);
});

test('missing and cyclic dependencies leave independent issues usable and never complete the run', () => {
  const run = machine(snapshot([
    issue(1, { dependencies: [2] }), issue(2, { dependencies: [1] }),
    issue(3, { dependencies: [999] }), issue(4, { priority: 'p2' }),
  ]));
  assert.deepEqual(waitingFor(run.state, 1), [{ number: 2, reason: 'cycle' }]);
  assert.deepEqual(waitingFor(run.state, 3), [{ number: 999, reason: 'missing' }]);
  run.step({ type: 'next' });
  assert.equal(run.state.activeIssue, 4);
  run.step({ type: 'complete', issue: 4, evidence: 'independent fictional task acknowledged' });
  assert.equal(run.state.status, 'waiting');
  run.step({ type: 'ack-dependency', issue: 999, evidence: 'coordinator supplied missing dependency completion' });
  run.step({ type: 'next' });
  assert.equal(run.state.activeIssue, 3);
  run.step({ type: 'complete', issue: 3, evidence: 'coordinator acknowledgement' });
  assert.equal(run.state.status, 'waiting');
  assert.equal(run.step({ type: 'next' }).status, 'waiting');
});

test('whole-run stop and explicit resume retain active claim, stop history, attempts and deadline', () => {
  const run = machine();
  run.step({ type: 'next' });
  const claim = structuredClone(run.state.entries[0].claims[0]);
  const deadline = run.state.deadlineAt;
  run.setTime(START + 1000);
  run.step({ type: 'stop', reason: 'shared repository access outage', condition: 'access restored' });
  assert.equal(run.state.activeIssue, 101);
  assert.equal(describeState(run.state).next, null);
  const stopped = run.state;
  assert.equal(run.step({ type: 'next' }), stopped);
  assert.throws(() => run.step({ type: 'complete', issue: 101, evidence: 'still stopped' }), errorCode('RUN_STOPPED'));
  run.setTime(START + 2000);
  run.step({ type: 'resume', snapshot: demo });
  assert.equal(run.state.activeIssue, 101);
  assert.deepEqual(run.state.entries[0].claims[0], claim);
  assert.equal(run.state.entries[0].attempts, 1);
  assert.equal(run.state.deadlineAt, deadline);
  assert.equal(run.state.stops[0].reason, 'shared repository access outage');
  assert.equal(run.state.stops[0].resumedAt, new Date(START + 2000).toISOString());
  const resumed = run.state;
  assert.equal(run.step({ type: 'resume', snapshot: demo }), resumed);
  assert.equal(run.step({ type: 'next' }), resumed);
});

test('three claims exhaust retries without resetting attempts or silently completing', () => {
  const run = machine(snapshot());
  for (let attempt = 1; attempt <= 3; attempt += 1) {
    run.step({ type: 'next' });
    assert.equal(run.state.entries[0].attempts, attempt);
    run.step({ type: 'block', issue: 1, reason: `fictional failure ${attempt}`, condition: 'coordinator fixes resource' });
    if (attempt < 3) run.step({ type: 'retry', issue: 1 });
  }
  const exhausted = run.state;
  assert.throws(() => run.step({ type: 'retry', issue: 1 }), errorCode('ATTEMPTS_EXHAUSTED'));
  assert.equal(run.state, exhausted);
  assert.equal(run.step({ type: 'next' }).status, 'waiting');
  assert.equal(run.state.entries[0].claims.length, 3);
  run.step({ type: 'archive', reason: 'coordinator ends incomplete fictional batch' });
  assert.equal(run.state.status, 'archived');
  assert.equal(run.state.entries[0].status, 'blocked');
  assert.throws(() => run.step({ type: 'resume', snapshot: snapshot() }), errorCode('RUN_ARCHIVED'));
});

test('injected time expires at the fixed deadline and resume cannot extend it', () => {
  const run = machine(snapshot());
  run.step({ type: 'next' });
  run.setTime(START + 60000);
  run.step({ type: 'stop', reason: 'user stop', condition: 'user resumes' });
  const before = run.state;
  run.setTime(START + 120 * 60000);
  assert.throws(() => run.step({ type: 'resume', snapshot: snapshot() }), errorCode('TIME_LIMIT'));
  assert.equal(run.state, before);
  run.step({ type: 'next' });
  assert.equal(run.state.status, 'expired');
  assert.equal(run.state.activeIssue, 1);
  assert.equal(run.state.entries[0].attempts, 1);
  assert.equal(run.state.deadlineAt, before.deadlineAt);
  assert.throws(() => run.step({ type: 'resume', snapshot: snapshot() }), errorCode('TIME_LIMIT'));
  assert.throws(() => run.step({ type: 'complete', issue: 1, evidence: 'too late' }), errorCode('TIME_LIMIT'));
  run.step({ type: 'archive', reason: 'deadline ended incomplete batch' });
  assert.equal(run.state.activeIssue, null);
  assert.equal(run.state.entries[0].claims[0].outcome, 'archived');
  assert.equal(run.state.entries[0].attempts, 1);
});

test('checkpoint validation rejects policy, membership, revision and claim corruption', () => {
  const valid = machine().state;
  for (const mutate of [
    state => { state.version = 999; },
    state => { state.revision = '0'; },
    state => { state.policy.maxIssues = 6; state.policyHash = hash(state.policy); },
    state => { state.snapshot.issues[0].goal = 'changed'; },
    state => { state.selectedIssues.reverse(); },
    state => { state.entries[0].attempts = 4; },
    state => { state.entries[0].status = 'complete'; },
    state => { state.status = 'complete'; },
    state => { state.unexpected = true; },
  ]) {
    const invalid = structuredClone(valid);
    mutate(invalid);
    assert.throws(() => validateState(invalid));
  }
  assert.equal(validateState(valid), valid);
});
