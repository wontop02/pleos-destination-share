import { createHash } from 'node:crypto';

export const MAX_JSON_BYTES = 2 * 1024 * 1024;
export const MAX_CHECKPOINT_BYTES = 4 * 1024 * 1024;
// Archive may copy a 10,000-code-unit reason twice, including six-byte JSON escapes.
// Normal writes also leave 8 KiB for the small resume/expiry bookkeeping changes.
export const MAX_OPERATION_CHECKPOINT_BYTES = MAX_CHECKPOINT_BYTES - 128 * 1024;
export const MAX_CONTROL_CHECKPOINT_BYTES = MAX_CHECKPOINT_BYTES - 120 * 1024;
export const STATE_VERSION = 1;

// Changes to this trusted policy require a new reviewed implementation, not issue input.
export const POLICY = Object.freeze({
  repository: 'wontop02/pleos-destination-share',
  approver: 'wontop02',
  kinds: Object.freeze(['harness', 'docs']),
  paths: Object.freeze([
    'harness/', 'test/harness/', 'docs/harness/', 'package.json', 'README.md',
    'AGENTS.md', '.gitignore', '.codex/agents/', '.codex/config.toml',
    '.github/ISSUE_TEMPLATE/', '.github/pull_request_template.md',
  ]),
  maxIssues: 5,
  maxAttempts: 3,
  maxMinutes: 120,
});

export class QueueError extends Error {
  constructor(code, message) {
    super(message);
    this.name = 'QueueError';
    this.code = code;
  }
}

export function fail(code, message) {
  throw new QueueError(code, message);
}

export function requireValue(condition, message, code = 'INVALID_INPUT') {
  if (!condition) fail(code, message);
}

export function canonical(value) {
  if (Array.isArray(value)) return `[${value.map(canonical).join(',')}]`;
  if (value !== null && typeof value === 'object') {
    return `{${Object.keys(value).sort().map(key => `${JSON.stringify(key)}:${canonical(value[key])}`).join(',')}}`;
  }
  return JSON.stringify(value);
}

export function hash(value) {
  return createHash('sha256').update(canonical(value)).digest('hex');
}

export const POLICY_HASH = hash(POLICY);

export function object(value, label, keys, required = keys) {
  requireValue(value !== null && typeof value === 'object' && !Array.isArray(value), `${label} must be an object`);
  requireValue(Object.keys(value).every(key => keys.includes(key)), `${label} has an unknown field`);
  requireValue(required.every(key => Object.hasOwn(value, key)), `${label} is missing a required field`);
}

export function text(value, label, max = 10000) {
  requireValue(typeof value === 'string' && value.trim().length > 0 && value.length <= max, `${label} must be nonempty text of at most ${max} characters`);
  return value;
}

export function integer(value, label, min = 1, max = Number.MAX_SAFE_INTEGER) {
  requireValue(Number.isSafeInteger(value) && value >= min && value <= max, `${label} must be an integer from ${min} to ${max}`);
  return value;
}

export function timestamp(value, label) {
  requireValue(typeof value === 'string' && /^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{3}Z$/.test(value)
    && Number.isFinite(Date.parse(value)) && new Date(value).toISOString() === value, `${label} must be a canonical UTC timestamp (YYYY-MM-DDTHH:mm:ss.sssZ)`);
  return value;
}

export function runId(value) {
  requireValue(typeof value === 'string' && /^[a-z0-9][a-z0-9_-]{0,63}$/.test(value), 'run ID must contain 1–64 lowercase letters, digits, underscores or hyphens, starting with a letter or digit', 'UNSAFE_PATH');
  return value;
}

export function safeScope(value) {
  text(value, 'scope', 500);
  requireValue(!value.includes('\\') && !value.startsWith('/') && !/^[A-Za-z]:/.test(value)
    && !value.includes('\0') && !/[\r\n]/.test(value), 'scope must be a safe repository-relative path', 'UNSAFE_PATH');
  const segments = value.replace(/\/$/, '').split('/');
  requireValue(segments.every(segment => segment !== '' && segment !== '.' && segment !== '..'), 'scope cannot contain empty, dot or traversal segments', 'UNSAFE_PATH');
  return value;
}

export function allowedScope(scope) {
  return POLICY.paths.some(allowed => allowed.endsWith('/') ? scope.startsWith(allowed) : scope === allowed);
}

export function normalizeSnapshot(input) {
  object(input, 'snapshot', ['version', 'repository', 'completedDependencies', 'issues', 'fictional'], ['version', 'repository', 'completedDependencies', 'issues']);
  requireValue(input.version === 1, 'unsupported snapshot version', 'UNSUPPORTED_VERSION');
  requireValue(input.repository === POLICY.repository, `repository must be ${POLICY.repository}`, 'POLICY_MISMATCH');
  if (Object.hasOwn(input, 'fictional')) requireValue(typeof input.fictional === 'boolean', 'fictional must be a boolean');
  requireValue(Array.isArray(input.issues) && input.issues.length <= 1000, 'issues must be an array of at most 1000 entries');
  const numbers = new Set();
  const issues = input.issues.map((issue, index) => {
    const label = `issues[${index}]`;
    const required = ['number', 'title', 'goal', 'body', 'kind', 'priority', 'createdAt', 'ready', 'approvedBy', 'scope', 'acceptanceCriteria', 'verification', 'dependencies'];
    object(issue, label, [...required, 'closed'], required);
    integer(issue.number, `${label}.number`);
    requireValue(!numbers.has(issue.number), `duplicate issue #${issue.number}`);
    numbers.add(issue.number);
    for (const field of ['title', 'goal', 'body', 'kind', 'approvedBy', 'verification']) text(issue[field], `${label}.${field}`, field === 'body' ? 50000 : 10000);
    requireValue(['p0', 'p1', 'p2'].includes(issue.priority), `${label}.priority must be p0, p1 or p2`);
    timestamp(issue.createdAt, `${label}.createdAt`);
    requireValue(typeof issue.ready === 'boolean', `${label}.ready must be a boolean`);
    requireValue(Array.isArray(issue.scope) && issue.scope.length > 0 && issue.scope.length <= 100, `${label}.scope must contain 1–100 paths`);
    issue.scope.forEach(safeScope);
    requireValue(new Set(issue.scope).size === issue.scope.length, `${label}.scope contains duplicates`);
    requireValue(Array.isArray(issue.acceptanceCriteria) && issue.acceptanceCriteria.length > 0 && issue.acceptanceCriteria.length <= 100, `${label}.acceptanceCriteria must contain 1–100 conditions`);
    issue.acceptanceCriteria.forEach((item, i) => text(item, `${label}.acceptanceCriteria[${i}]`));
    const dependencies = numberList(issue.dependencies, `${label}.dependencies`);
    if (Object.hasOwn(issue, 'closed')) requireValue(typeof issue.closed === 'boolean', `${label}.closed must be a boolean`);
    return { ...issue, dependencies };
  });
  const result = { ...input, issues, completedDependencies: numberList(input.completedDependencies, 'completedDependencies') };
  requireValue(Buffer.byteLength(canonical(result)) <= MAX_JSON_BYTES, 'snapshot exceeds the 2 MiB limit');
  return structuredClone(result);
}

export function numberList(value, label) {
  requireValue(Array.isArray(value) && value.length <= 1000, `${label} must be an array of at most 1000 issue numbers`);
  value.forEach(number => integer(number, label));
  requireValue(new Set(value).size === value.length, `${label} contains duplicates`);
  return [...value];
}

export function selection(snapshot) {
  const excluded = [];
  const eligible = snapshot.issues.filter(issue => {
    const reasons = [];
    if (snapshot.completedDependencies.includes(issue.number)) reasons.push('already completed by coordinator attestation');
    if (!issue.ready) reasons.push('not ready');
    if (issue.approvedBy !== POLICY.approver) reasons.push(`not approved by ${POLICY.approver}`);
    if (!POLICY.kinds.includes(issue.kind)) reasons.push('work kind is outside harness/docs');
    if (!issue.scope.every(allowedScope)) reasons.push('scope is outside the trusted path allowlist');
    if (reasons.length) excluded.push({ number: issue.number, reasons });
    return reasons.length === 0;
  }).sort((a, b) => a.priority.localeCompare(b.priority) || a.createdAt.localeCompare(b.createdAt) || a.number - b.number);
  return { selectedIssues: eligible.slice(0, POLICY.maxIssues).map(issue => issue.number), excluded,
    deferred: eligible.slice(POLICY.maxIssues).map(issue => issue.number) };
}
