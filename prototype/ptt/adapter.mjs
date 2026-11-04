import { randomBytes } from 'node:crypto';

export const MAX_PTT_TOKEN_BYTES = 512;
export const PTT_DEFAULTS = Object.freeze({
  leaseMs: 60 * 60 * 1000,
  maxConcurrent: 4,
  maxQueued: 64,
  timeoutMs: 5000,
  maxQueueAgeMs: 10_000,
});
const maximums = Object.freeze({
  leaseMs: PTT_DEFAULTS.leaseMs, maxConcurrent: 16, maxQueued: 256,
  timeoutMs: 10_000, maxQueueAgeMs: 30_000,
});
const uuid = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i;
const objectWith = (value, keys) => value !== null && typeof value === 'object' && !Array.isArray(value) &&
  Object.keys(value).length === keys.length && keys.every((key) => Object.hasOwn(value, key));

export class PttError extends Error {
  constructor(status, code, message, state) {
    super(message);
    this.status = status;
    this.code = code;
    this.state = state;
  }
}

export function pttMutation(body, withToken) {
  const keys = withToken ? ['channelUUID', 'token', 'expectedRevision'] : ['channelUUID', 'expectedRevision'];
  if (!objectWith(body, keys) || typeof body.channelUUID !== 'string' || !uuid.test(body.channelUUID) ||
      !Number.isSafeInteger(body.expectedRevision) || body.expectedRevision < 0 ||
      (withToken && (typeof body.token !== 'string' || body.token.length < 2 ||
        body.token.length > MAX_PTT_TOKEN_BYTES * 2 || !/^(?:[a-f0-9]{2})+$/i.test(body.token)))) {
    throw new PttError(400, 'invalid_ptt_registration', '올바른 채널 UUID, 가변 길이 hex 토큰과 등록 revision이 필요합니다.');
  }
  return { channelUUID: body.channelUUID.toLowerCase(), expectedRevision: body.expectedRevision,
    ...(withToken ? { token: body.token.toLowerCase() } : {}) };
}

/** In-memory registration CAS and bounded, best-effort delivery. A provider is always explicit. */
export function createPttAdapter({ provider = null, clock = Date.now, isActive, limits: overrides = {} } = {}) {
  if (provider !== null && (typeof provider?.send !== 'function' || typeof provider?.close !== 'function')) {
    throw new TypeError('PTT provider must implement send and close');
  }
  if (typeof clock !== 'function' || typeof isActive !== 'function') throw new TypeError('Invalid PTT lifecycle hooks');
  const limits = { ...PTT_DEFAULTS, ...overrides };
  for (const [name, value] of Object.entries(limits)) {
    if (!Object.hasOwn(maximums, name) || !Number.isSafeInteger(value) || value < 1 || value > maximums[name]) {
      throw new TypeError(`Invalid PTT limit: ${name}`);
    }
  }
  const configured = provider !== null;
  const states = new Map();
  const tokenOwners = new Map();
  const active = new Set();
  let queued = [];
  let queueTimer;
  let drainScheduled = false;
  let closed = false;
  let closing;
  const now = () => Number(clock());
  const key = (room, member) => `${room.id}:${member.id}`;
  const stateFor = (room, member) => {
    const name = key(room, member);
    if (!states.has(name)) states.set(name, { room, member, revision: 0, registration: null });
    return states.get(name);
  };
  const response = (state) => ({
    configured, status: !configured ? 'not-configured' : state.registration ? 'registered' : 'unregistered',
    revision: state.revision,
    registration: state.registration ? { id: state.registration.id, channelUUID: state.registration.channelUUID,
      expiresAt: state.registration.expiresAt } : null,
    serverTime: now(),
  });
  const increment = (state) => {
    if (state.revision >= Number.MAX_SAFE_INTEGER) {
      throw new PttError(409, 'ptt_revision_exhausted', '등록 revision 상한에 도달했습니다.', response(state));
    }
    state.revision += 1;
  };
  const current = (job, at = now()) => !closed &&
    states.get(key(job.room, job.member)) === job.state && job.state.registration === job.registration &&
    isActive(job.room, job.member) && isActive(job.room, job.sender) &&
    job.registration.expiresAt > at && job.room.expiresAt > at &&
    job.message.acceptedAt <= at && job.message.autoPlayUntil > at && job.deadline > at &&
    job.room.messages.has(job.message.id);

  function scheduleDrain() {
    if (closed || drainScheduled) return;
    drainScheduled = true;
    queueMicrotask(() => { drainScheduled = false; drain(); });
  }
  function finish(job) {
    if (job.finished) return;
    job.finished = true;
    clearTimeout(job.timer);
    active.delete(job);
    scheduleDrain();
  }
  function cancel(job) {
    if (job.finished) return;
    // Mark finished before abort: a provider may resolve synchronously in its abort handler.
    finish(job);
    job.controller.abort();
  }
  function cancelWhere(predicate) {
    queued = queued.filter((job) => !predicate(job));
    for (const job of [...active]) if (predicate(job)) cancel(job);
    armQueueTimer();
  }
  function clearRegistration(state, bumpRevision = true) {
    const registration = state.registration;
    if (!registration) return;
    state.registration = null;
    if (tokenOwners.get(registration.token) === state) tokenOwners.delete(registration.token);
    cancelWhere((job) => job.registration === registration);
    registration.token = '';
    if (bumpRevision) increment(state);
  }
  function expire(state, at = now()) {
    if (state.registration && (state.registration.expiresAt <= at || !isActive(state.room, state.member))) {
      clearRegistration(state);
    }
  }
  function checked(room, member, expectedRevision) {
    const state = stateFor(room, member);
    expire(state);
    if (state.revision !== expectedRevision) {
      throw new PttError(409, 'ptt_revision_conflict', '최신 등록 상태를 다시 조회해 주세요.', response(state));
    }
    if (state.revision >= Number.MAX_SAFE_INTEGER) increment(state);
    return state;
  }

  function armQueueTimer() {
    clearTimeout(queueTimer);
    queueTimer = undefined;
    if (!closed && queued.length) {
      const delay = Math.max(1, Math.min(...queued.map((job) => job.deadline)) - now());
      queueTimer = setTimeout(drain, delay).unref();
    }
  }
  function start(job) {
    if (!current(job)) return;
    job.controller = new AbortController();
    job.finished = false;
    active.add(job);
    const timeoutMs = Math.max(1, Math.min(limits.timeoutMs, job.deadline - now()));
    job.timer = setTimeout(() => cancel(job), timeoutMs).unref();
    const payload = { v: 1, roomId: job.room.id, channelUUID: job.registration.channelUUID,
      registrationId: job.registration.id, message: { ...job.message, sender: { ...job.message.sender } },
      serverTime: now(), roomExpiresAt: job.room.expiresAt };
    let sent;
    try {
      // No await between the last liveness check and handing the request to the provider.
      if (!current(job)) { cancel(job); return; }
      sent = provider.send({ token: job.registration.token, payload,
        signal: job.controller.signal, timeoutMs });
    } catch { finish(job); return; }
    Promise.resolve(sent).then((result) => {
      if (!job.finished && !job.controller.signal.aborted &&
          (result?.status === 410 || (result?.status === 400 &&
            ['BadDeviceToken', 'DeviceTokenNotForTopic'].includes(result.reason))) &&
          job.state.registration === job.registration) {
        clearRegistration(job.state);
      }
      finish(job);
    }, () => finish(job));
  }
  function drain() {
    if (closed) return;
    queued = queued.filter((job) => current(job));
    while (active.size < limits.maxConcurrent && queued.length) start(queued.shift());
    armQueueTimer();
  }

  return {
    get(room, member) {
      const state = stateFor(room, member);
      expire(state);
      return response(state);
    },
    register(room, member, input) {
      const state = checked(room, member, input.expectedRevision);
      if (configured) {
        const owner = tokenOwners.get(input.token);
        if (owner && owner !== state) {
          expire(owner);
          if (tokenOwners.has(input.token)) {
            throw new PttError(409, 'ptt_token_in_use', '다른 참여자가 사용하는 임시 토큰입니다.', response(state));
          }
        }
        clearRegistration(state, false);
        state.registration = { id: randomBytes(12).toString('hex'), channelUUID: input.channelUUID,
          token: input.token, expiresAt: Math.min(now() + limits.leaseMs, room.expiresAt) };
        tokenOwners.set(input.token, state);
      }
      increment(state);
      return response(state);
    },
    unregister(room, member, input) {
      const state = checked(room, member, input.expectedRevision);
      if (state.registration && state.registration.channelUUID !== input.channelUUID) {
        throw new PttError(409, 'ptt_channel_conflict', '현재 등록된 채널과 일치하지 않습니다.', response(state));
      }
      clearRegistration(state, false);
      // Even an empty DELETE fences an older in-flight POST.
      increment(state);
      return response(state);
    },
    notify(room, sender, message) {
      if (!configured || closed) return;
      const at = now();
      for (const member of room.members.values()) {
        if (member === sender) continue;
        const state = states.get(key(room, member));
        if (!state) continue;
        expire(state, at);
        const registration = state.registration;
        if (!registration) continue;
        const job = { room, member, sender, state, registration, message,
          deadline: Math.min(at + limits.maxQueueAgeMs, message.autoPlayUntil, room.expiresAt, registration.expiresAt) };
        if (!current(job, at)) continue;
        if (active.size < limits.maxConcurrent && queued.length === 0) start(job);
        else if (queued.length < limits.maxQueued) queued.push(job);
      }
      armQueueTimer();
    },
    removeMember(room, member) {
      const state = states.get(key(room, member));
      if (state) { clearRegistration(state, false); states.delete(key(room, member)); }
      cancelWhere((job) => job.room === room && (job.member === member || job.sender === member));
    },
    removeRoom(room) {
      for (const state of [...states.values()]) if (state.room === room) {
        clearRegistration(state, false);
        states.delete(key(room, state.member));
      }
      cancelWhere((job) => job.room === room);
    },
    cleanup() {
      const at = now();
      for (const state of states.values()) expire(state, at);
      cancelWhere((job) => !current(job, at));
      scheduleDrain();
    },
    stats: () => ({ configured, registrations: tokenOwners.size, queued: queued.length, active: active.size }),
    close() {
      if (closing) return closing;
      closed = true;
      clearTimeout(queueTimer);
      queued = [];
      for (const job of [...active]) cancel(job);
      for (const state of states.values()) if (state.registration) state.registration.token = '';
      tokenOwners.clear();
      states.clear();
      try { closing = Promise.resolve(provider?.close()); }
      catch { closing = Promise.resolve(); }
      return closing;
    },
  };
}
