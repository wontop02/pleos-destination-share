import {
  POLICY, POLICY_HASH, STATE_VERSION, canonical, hash, integer, normalizeSnapshot,
  object, requireValue, runId, selection, text, timestamp,
} from './policy.js';

const STATES = ['running', 'waiting', 'stopped', 'expired', 'complete', 'archived'];
const ENTRY_STATES = ['queued', 'active', 'blocked', 'complete'];
const terminal = status => ['complete', 'archived'].includes(status);
export const isTerminal = state => terminal(state.status);

export function clockTime(clock = Date.now) {
  const value = clock();
  const milliseconds = value instanceof Date ? value.getTime() : value;
  requireValue(Number.isSafeInteger(milliseconds) && milliseconds >= 0, 'clock must return a valid Date or nonnegative integer milliseconds');
  return new Date(milliseconds).toISOString();
}

function completedNumbers(state) {
  return new Set([
    ...state.snapshot.completedDependencies,
    ...state.acknowledgements.map(item => item.number),
    ...state.entries.filter(entry => entry.status === 'complete').map(entry => entry.number),
  ]);
}

export function waitingFor(state, number) {
  const issueMap = new Map(state.snapshot.issues.map(issue => [issue.number, issue]));
  const entryMap = new Map(state.entries.map(entry => [entry.number, entry]));
  const done = completedNumbers(state);
  const reaches = (from, target, seen = new Set()) => {
    if (done.has(from)) return false;
    if (from === target) return true;
    if (seen.has(from)) return false;
    seen.add(from);
    return (issueMap.get(from)?.dependencies ?? []).some(dependency => reaches(dependency, target, seen));
  };
  return issueMap.get(number).dependencies.filter(dependency => !done.has(dependency)).map(dependency => {
    const entry = entryMap.get(dependency);
    let reason = 'waiting';
    if (entry?.status === 'blocked') reason = entry.attempts >= POLICY.maxAttempts ? 'attempts-exhausted' : 'blocked';
    else if (reaches(dependency, number)) reason = 'cycle';
    else if (!issueMap.has(dependency)) reason = 'missing';
    else if (!entry) reason = 'outside-frozen-batch';
    return { number: dependency, reason };
  });
}

function phase(state) {
  if (state.entries.every(entry => entry.status === 'complete')) return 'complete';
  if (state.activeIssue !== null || state.entries.some(entry => entry.status === 'queued' && waitingFor(state, entry.number).length === 0)) return 'running';
  return 'waiting';
}

export function createState(id, input, clock = Date.now) {
  runId(id);
  const snapshot = normalizeSnapshot(input);
  const createdAt = clockTime(clock);
  const selectedIssues = selection(snapshot).selectedIssues;
  const state = {
    version: STATE_VERSION, revision: 0, runId: id,
    policy: structuredClone(POLICY), policyHash: POLICY_HASH,
    snapshot, snapshotHash: hash(snapshot), selectedIssues,
    createdAt, updatedAt: createdAt,
    deadlineAt: new Date(Date.parse(createdAt) + POLICY.maxMinutes * 60000).toISOString(),
    status: 'running', activeIssue: null,
    entries: selectedIssues.map(number => ({ number, status: 'queued', attempts: 0, claims: [] })),
    acknowledgements: [], stops: [], archive: null,
  };
  state.status = phase(state);
  return validateState(state);
}

function lastClaim(entry) {
  return entry.claims.at(-1);
}

function endClaim(entry, at, outcome, details = {}) {
  Object.assign(lastClaim(entry), { endedAt: at, outcome, ...details });
}

export function transition(current, action, clock = Date.now) {
  validateState(current);
  object(action, 'action', ['type', 'issue', 'reason', 'condition', 'evidence', 'snapshot'], ['type']);
  const allowed = ['next', 'block', 'retry', 'complete', 'ack-dependency', 'stop', 'resume', 'archive'];
  requireValue(allowed.includes(action.type), 'unknown queue action');
  const at = clockTime(clock);
  requireValue(Date.parse(at) >= Date.parse(current.updatedAt), 'clock moved behind the last committed checkpoint', 'CLOCK_REGRESSION');
  if (action.type === 'resume') {
    const snapshot = normalizeSnapshot(action.snapshot);
    requireValue(hash(snapshot) === current.snapshotHash, 'resume snapshot differs from the frozen snapshot', 'SNAPSHOT_MISMATCH');
  }
  if (current.status === 'archived') requireValue(false, 'archived runs cannot be changed or resumed', 'RUN_ARCHIVED');
  if (action.type !== 'archive' && Date.parse(at) >= Date.parse(current.deadlineAt) && !terminal(current.status)) {
    if (action.type !== 'next') requireValue(false, 'run reached its fixed deadline; resume cannot extend it', 'TIME_LIMIT');
    if (current.status === 'expired') return current;
    return validateState({ ...structuredClone(current), revision: current.revision + 1, updatedAt: at, status: 'expired' });
  }
  if (current.status === 'expired' && action.type !== 'archive') requireValue(false, 'run reached its fixed deadline', 'TIME_LIMIT');
  if (current.status === 'complete') {
    requireValue(['next', 'resume', 'archive'].includes(action.type), 'completed runs cannot claim or change issues', 'RUN_COMPLETE');
    if (action.type !== 'archive') return current;
  }
  if (action.type === 'next' && (current.activeIssue !== null || current.status === 'stopped')) return current;
  if (action.type === 'resume' && current.status !== 'stopped') return current;
  if (action.type === 'stop' && current.status === 'stopped') return current;
  requireValue(current.status !== 'stopped' || ['resume', 'archive'].includes(action.type), 'run is stopped; explicitly resume with the frozen snapshot first', 'RUN_STOPPED');

  const state = structuredClone(current);
  const entry = state.entries.find(item => item.number === action.issue);
  const requireActive = () => {
    integer(action.issue, 'issue');
    requireValue(entry && state.activeIssue === action.issue && entry.status === 'active', `issue #${action.issue} is not the active claim`, 'NOT_ACTIVE');
  };
  switch (action.type) {
    case 'next': {
      const candidate = state.entries.find(item => item.status === 'queued' && waitingFor(state, item.number).length === 0);
      if (!candidate) return current;
      requireValue(candidate.attempts < POLICY.maxAttempts, 'issue reached its claim limit', 'ATTEMPTS_EXHAUSTED');
      candidate.attempts += 1;
      candidate.status = 'active';
      candidate.claims.push({ attempt: candidate.attempts, claimedAt: at, endedAt: null, outcome: null, reason: null, resumeCondition: null, evidence: null });
      state.activeIssue = candidate.number;
      break;
    }
    case 'block':
      requireActive();
      text(action.reason, 'reason');
      text(action.condition, 'condition');
      endClaim(entry, at, 'blocked', { reason: action.reason, resumeCondition: action.condition });
      entry.status = 'blocked';
      state.activeIssue = null;
      break;
    case 'retry':
      integer(action.issue, 'issue');
      requireValue(entry?.status === 'blocked', 'only an explicitly blocked issue can be retried', 'NOT_BLOCKED');
      requireValue(entry.attempts < POLICY.maxAttempts, `issue #${action.issue} reached ${POLICY.maxAttempts} claims`, 'ATTEMPTS_EXHAUSTED');
      entry.status = 'queued';
      break;
    case 'complete':
      requireActive();
      text(action.evidence, 'coordinator acknowledgement evidence');
      endClaim(entry, at, 'complete', { evidence: action.evidence });
      entry.status = 'complete';
      state.activeIssue = null;
      break;
    case 'ack-dependency': {
      integer(action.issue, 'issue');
      text(action.evidence, 'coordinator acknowledgement evidence');
      requireValue(!entry, 'selected issues must complete through their active claim', 'SELECTED_DEPENDENCY');
      requireValue(state.entries.some(item => state.snapshot.issues.find(issue => issue.number === item.number).dependencies.includes(action.issue)), 'issue is not a dependency of this frozen batch', 'UNKNOWN_DEPENDENCY');
      if (completedNumbers(state).has(action.issue)) return current;
      state.acknowledgements.push({ number: action.issue, at, evidence: action.evidence });
      break;
    }
    case 'stop':
      text(action.reason, 'reason');
      text(action.condition, 'condition');
      requireValue(state.stops.length < 1000, 'run stop history limit reached');
      state.stops.push({ at, reason: action.reason, resumeCondition: action.condition, resumedAt: null });
      state.status = 'stopped';
      break;
    case 'resume':
      state.stops.at(-1).resumedAt = at;
      break;
    case 'archive':
      text(action.reason, 'reason');
      if (state.activeIssue !== null) {
        const active = state.entries.find(item => item.number === state.activeIssue);
        endClaim(active, at, 'archived', { reason: action.reason, resumeCondition: 'Run archived; a new batch requires coordinator review.' });
        active.status = 'blocked';
        state.activeIssue = null;
      }
      state.archive = { at, reason: action.reason };
      state.status = 'archived';
      break;
  }
  if (!['stop', 'archive'].includes(action.type)) state.status = phase(state);
  state.revision += 1;
  state.updatedAt = at;
  return validateState(state);
}

function validateClaim(claim, index, state, previousEndedAt) {
  object(claim, 'claim', ['attempt', 'claimedAt', 'endedAt', 'outcome', 'reason', 'resumeCondition', 'evidence']);
  requireValue(claim.attempt === index + 1, 'claim attempt sequence is invalid');
  timestamp(claim.claimedAt, 'claim.claimedAt');
  requireValue(claim.claimedAt >= previousEndedAt && claim.claimedAt <= state.updatedAt && claim.claimedAt < state.deadlineAt, 'claim timestamp is outside the run interval');
  if (claim.endedAt === null) {
    requireValue(claim.outcome === null && claim.reason === null && claim.resumeCondition === null && claim.evidence === null, 'unfinished claim contains an outcome');
  } else {
    timestamp(claim.endedAt, 'claim.endedAt');
    requireValue(claim.endedAt >= claim.claimedAt && claim.endedAt <= state.updatedAt, 'claim end timestamp is invalid');
    requireValue(['blocked', 'complete', 'archived'].includes(claim.outcome), 'claim outcome is invalid');
    if (claim.outcome === 'complete') {
      text(claim.evidence, 'claim.evidence');
      requireValue(claim.reason === null && claim.resumeCondition === null, 'completed claim contains block fields');
    } else {
      text(claim.reason, 'claim.reason');
      text(claim.resumeCondition, 'claim.resumeCondition');
      requireValue(claim.evidence === null, 'blocked claim contains completion evidence');
    }
    requireValue(claim.outcome === 'archived' || claim.endedAt < state.deadlineAt, 'claim ended beyond the run deadline');
  }
}

export function validateState(state) {
  object(state, 'checkpoint', ['version', 'revision', 'runId', 'policy', 'policyHash', 'snapshot', 'snapshotHash', 'selectedIssues', 'createdAt', 'updatedAt', 'deadlineAt', 'status', 'activeIssue', 'entries', 'acknowledgements', 'stops', 'archive']);
  requireValue(state.version === STATE_VERSION, 'unsupported checkpoint version', 'UNSUPPORTED_VERSION');
  integer(state.revision, 'revision', 0);
  runId(state.runId);
  requireValue(canonical(state.policy) === canonical(POLICY) && state.policyHash === POLICY_HASH && hash(state.policy) === state.policyHash, 'checkpoint policy differs from the trusted policy', 'POLICY_MISMATCH');
  const snapshot = normalizeSnapshot(state.snapshot);
  requireValue(hash(snapshot) === state.snapshotHash, 'checkpoint snapshot hash does not match', 'SNAPSHOT_MISMATCH');
  requireValue(canonical(state.selectedIssues) === canonical(selection(snapshot).selectedIssues), 'frozen issue membership or order changed');
  timestamp(state.createdAt, 'createdAt');
  timestamp(state.updatedAt, 'updatedAt');
  timestamp(state.deadlineAt, 'deadlineAt');
  requireValue(state.updatedAt >= state.createdAt && Date.parse(state.deadlineAt) === Date.parse(state.createdAt) + POLICY.maxMinutes * 60000, 'fixed run timestamps are invalid');
  requireValue(STATES.includes(state.status), 'run status is invalid');
  requireValue(state.activeIssue === null || state.selectedIssues.includes(state.activeIssue), 'active issue is outside the frozen batch');
  requireValue(Array.isArray(state.entries) && state.entries.length === state.selectedIssues.length, 'checkpoint entries do not match the frozen batch');
  for (const [index, entry] of state.entries.entries()) {
    object(entry, 'entry', ['number', 'status', 'attempts', 'claims']);
    requireValue(entry.number === state.selectedIssues[index] && ENTRY_STATES.includes(entry.status), 'entry membership or status is invalid');
    integer(entry.attempts, 'attempts', 0, POLICY.maxAttempts);
    requireValue(Array.isArray(entry.claims) && entry.claims.length === entry.attempts, 'claim count differs from attempts');
    let previousEndedAt = state.createdAt;
    entry.claims.forEach((claim, claimIndex) => {
      validateClaim(claim, claimIndex, state, previousEndedAt);
      requireValue(claimIndex === entry.claims.length - 1 || claim.outcome === 'blocked', 'only the final claim may be active or completed');
      previousEndedAt = claim.endedAt;
    });
    const last = lastClaim(entry);
    if (entry.status === 'active') requireValue(entry.number === state.activeIssue && last?.endedAt === null, 'active entry has no matching live claim');
    else requireValue(entry.number !== state.activeIssue && (!last || last.endedAt !== null), 'inactive entry has a live claim');
    if (entry.status === 'queued') requireValue(entry.attempts < POLICY.maxAttempts && (!last || last.outcome === 'blocked'), 'queued entry is completed or exhausted');
    if (entry.status === 'blocked') requireValue(['blocked', 'archived'].includes(last?.outcome), 'blocked entry has no block record');
    if (entry.status === 'complete') requireValue(last?.outcome === 'complete', 'completed entry has no coordinator acknowledgement');
    if (last?.outcome === 'archived') requireValue(state.status === 'archived', 'archived claim exists in a live run');
  }
  requireValue(state.entries.filter(entry => entry.status === 'active').length === (state.activeIssue === null ? 0 : 1), 'run contains overlapping active claims');
  requireValue(Array.isArray(state.acknowledgements) && state.acknowledgements.length <= 1000, 'dependency acknowledgement list is invalid');
  const acknowledged = new Set(snapshot.completedDependencies);
  for (const acknowledgement of state.acknowledgements) {
    object(acknowledgement, 'acknowledgement', ['number', 'at', 'evidence']);
    integer(acknowledgement.number, 'acknowledgement.number');
    requireValue(!state.selectedIssues.includes(acknowledgement.number) && !acknowledged.has(acknowledgement.number), 'duplicate or selected dependency acknowledgement');
    requireValue(state.entries.some(entry => snapshot.issues.find(issue => issue.number === entry.number).dependencies.includes(acknowledgement.number)), 'acknowledgement is outside the frozen batch dependencies');
    timestamp(acknowledgement.at, 'acknowledgement.at');
    requireValue(acknowledgement.at >= state.createdAt && acknowledgement.at <= state.updatedAt && acknowledgement.at < state.deadlineAt, 'acknowledgement timestamp is outside the run interval');
    text(acknowledgement.evidence, 'acknowledgement.evidence');
    acknowledged.add(acknowledgement.number);
  }
  requireValue(Array.isArray(state.stops) && state.stops.length <= 1000, 'stop history is invalid');
  let previousStop = state.createdAt;
  state.stops.forEach((stop, index) => {
    object(stop, 'stop', ['at', 'reason', 'resumeCondition', 'resumedAt']);
    timestamp(stop.at, 'stop.at');
    text(stop.reason, 'stop.reason');
    text(stop.resumeCondition, 'stop.resumeCondition');
    requireValue(stop.at >= previousStop && stop.at <= state.updatedAt && stop.at < state.deadlineAt, 'stop timestamp is invalid');
    if (stop.resumedAt !== null) {
      timestamp(stop.resumedAt, 'stop.resumedAt');
      requireValue(stop.resumedAt >= stop.at && stop.resumedAt <= state.updatedAt && stop.resumedAt < state.deadlineAt, 'resume timestamp is invalid');
      previousStop = stop.resumedAt;
    } else requireValue(index === state.stops.length - 1 && ['stopped', 'expired', 'archived'].includes(state.status), 'unresolved stop exists in a running state');
  });
  if (state.status === 'stopped') requireValue(state.stops.at(-1)?.resumedAt === null, 'stopped run has no unresolved stop record');
  if (state.status === 'archived') {
    object(state.archive, 'archive', ['at', 'reason']);
    timestamp(state.archive.at, 'archive.at');
    text(state.archive.reason, 'archive.reason');
    requireValue(state.archive.at === state.updatedAt && state.activeIssue === null, 'archive must release the active claim');
  } else requireValue(state.archive === null, 'live run contains archive metadata');
  if (state.status === 'expired') requireValue(state.updatedAt >= state.deadlineAt, 'run expired before its deadline');
  if (['running', 'waiting', 'complete'].includes(state.status)) requireValue(state.status === phase(state), 'run phase contradicts its issue states');
  return state;
}

export function describeState(state) {
  validateState(state);
  const issues = new Map(state.snapshot.issues.map(issue => [issue.number, issue]));
  return {
    state,
    next: state.status === 'running' && state.activeIssue !== null ? issues.get(state.activeIssue) : null,
    queue: state.entries.map(entry => {
      const claim = lastClaim(entry);
      return { number: entry.number, title: issues.get(entry.number).title, status: entry.status, attempts: entry.attempts,
        blocked: entry.status === 'blocked' ? { reason: claim.reason, resumeCondition: claim.resumeCondition } : null,
        waitingFor: entry.status === 'complete' ? [] : waitingFor(state, entry.number) };
    }),
    ...selection(state.snapshot),
    trust: 'Approvals, dependency completion and completion evidence are trusted coordinator attestations. This queue does not verify GitHub state or gate merges.',
  };
}
