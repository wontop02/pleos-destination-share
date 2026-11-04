import { connect as connectHttp2, constants, sensitiveHeaders } from 'node:http2';
import { createPrivateKey, KeyObject, randomUUID, sign } from 'node:crypto';
import { open } from 'node:fs/promises';

const endpoints = Object.freeze({
  sandbox: 'https://api.sandbox.push.apple.com',
  production: 'https://api.push.apple.com',
});
const environmentKeys = Object.freeze(['PLEOS_APNS_TEAM_ID', 'PLEOS_APNS_KEY_ID', 'PLEOS_APNS_BUNDLE_ID',
  'PLEOS_APNS_KEY_FILE', 'PLEOS_APNS_ENVIRONMENT']);
export const MAX_APNS_KEY_FILE_BYTES = 16 * 1024;
const MAX_PAYLOAD_BYTES = 4096;
const MAX_RESPONSE_BYTES = 4096;
const TOKEN_REFRESH_SECONDS = 50 * 60;
const tokenPattern = /^(?:[a-f0-9]{2}){1,512}$/;
const configError = () => new TypeError('Invalid explicit APNs configuration; require team ID, key ID, bundle ID, P-256 key file and sandbox/production environment');

function validateIdentity({ teamId, keyId, bundleId, environment }) {
  if (typeof teamId !== 'string' || !/^[A-Z0-9]{10}$/.test(teamId) ||
      typeof keyId !== 'string' || !/^[A-Z0-9]{10}$/.test(keyId) ||
      typeof bundleId !== 'string' || bundleId.length > 255 ||
      !/^[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)+$/.test(bundleId) ||
      !Object.hasOwn(endpoints, environment)) throw configError();
}
function validateKey(privateKey) {
  if (!(privateKey instanceof KeyObject) || privateKey.type !== 'private' ||
      privateKey.asymmetricKeyType !== 'ec' || privateKey.asymmetricKeyDetails?.namedCurve !== 'prime256v1') {
    throw configError();
  }
}

export class ApnsDeliveryError extends Error {
  constructor(code) { super(`APNs delivery failed: ${code}`); this.code = code; }
}

/** Lazy TLS HTTP/2 APNs transport. The injected connector is for explicit mock tests only. */
export function createApnsProvider(config, { clock = Date.now, connect = connectHttp2, requestTimeoutMs = 5000 } = {}) {
  validateIdentity(config);
  validateKey(config.privateKey);
  if (typeof clock !== 'function' || typeof connect !== 'function' ||
      !Number.isSafeInteger(requestTimeoutMs) || requestTimeoutMs < 1 || requestTimeoutMs > 10_000) {
    throw configError();
  }
  const { teamId, keyId, bundleId, environment } = config;
  let privateKey = config.privateKey;
  let cachedToken;
  let session;
  let closed = false;
  const sessions = new Set();
  const pending = new Set();
  function authorization() {
    const issuedAt = Math.floor(Number(clock()) / 1000);
    if (!Number.isSafeInteger(issuedAt) || issuedAt < 0) throw new ApnsDeliveryError('invalid_clock');
    if (!cachedToken || issuedAt < cachedToken.issuedAt || issuedAt - cachedToken.issuedAt >= TOKEN_REFRESH_SECONDS) {
      const header = Buffer.from(JSON.stringify({ alg: 'ES256', kid: keyId })).toString('base64url');
      const claims = Buffer.from(JSON.stringify({ iss: teamId, iat: issuedAt })).toString('base64url');
      const signingInput = `${header}.${claims}`;
      const signature = sign('sha256', Buffer.from(signingInput), { key: privateKey, dsaEncoding: 'ieee-p1363' });
      cachedToken = { value: `${signingInput}.${signature.toString('base64url')}`, issuedAt };
    }
    return `bearer ${cachedToken.value}`;
  }
  function connection() {
    if (session && !session.closed && !session.destroyed) return session;
    // These options cannot be overridden by environment variables or provider configuration.
    const connected = connect(endpoints[environment], { minVersion: 'TLSv1.2', rejectUnauthorized: true });
    session = connected;
    sessions.add(connected);
    const connectionTimer = setTimeout(() => connected.destroy(), requestTimeoutMs).unref();
    connected.once('connect', () => clearTimeout(connectionTimer));
    connected.on('error', () => { if (session === connected) session = undefined; connected.destroy(); });
    connected.on('close', () => { clearTimeout(connectionTimer); sessions.delete(connected); if (session === connected) session = undefined; });
    connected.on('goaway', () => { if (session === connected) session = undefined; connected.close(); });
    return connected;
  }

  return {
    send({ token, payload, signal, timeoutMs = requestTimeoutMs }) {
      if (closed) return Promise.reject(new ApnsDeliveryError('closed'));
      if (signal?.aborted) return Promise.reject(new ApnsDeliveryError('cancelled'));
      if (typeof token !== 'string' || !tokenPattern.test(token) ||
          !Number.isSafeInteger(timeoutMs) || timeoutMs < 1) {
        return Promise.reject(new ApnsDeliveryError('invalid_request'));
      }
      let body;
      try { body = Buffer.from(JSON.stringify(payload)); }
      catch { return Promise.reject(new ApnsDeliveryError('invalid_payload')); }
      if (body.length > MAX_PAYLOAD_BYTES) return Promise.reject(new ApnsDeliveryError('payload_too_large'));
      return new Promise((fulfill, fail) => {
        let stream;
        let connected;
        let timer;
        let done = false;
        let status;
        let size = 0;
        let chunks = [];
        const finish = (error, result) => {
          if (done) return;
          done = true;
          clearTimeout(timer);
          signal?.removeEventListener('abort', aborted);
          pending.delete(cancel);
          chunks = [];
          if (error) fail(error); else fulfill(result);
        };
        const cancel = (code = 'cancelled') => {
          finish(new ApnsDeliveryError(code));
          if (stream && !stream.destroyed && !stream.closed) stream.close(constants.NGHTTP2_CANCEL);
          if (connected?.connecting) connected.destroy();
        };
        const aborted = () => cancel();
        pending.add(cancel);
        signal?.addEventListener('abort', aborted, { once: true });
        timer = setTimeout(() => cancel('timeout'), Math.min(timeoutMs, requestTimeoutMs)).unref();
        try {
          const headers = { ':method': 'POST', ':path': `/3/device/${token}`,
            authorization: authorization(), 'apns-push-type': 'pushtotalk',
            'apns-topic': `${bundleId}.voip-ptt`, 'apns-priority': '10', 'apns-expiration': '0',
            'apns-id': randomUUID(), 'content-type': 'application/json', 'content-length': body.length,
            [sensitiveHeaders]: [':path', 'authorization'] };
          connected = connection();
          stream = connected.request(headers);
          stream.on('response', (headers) => { status = Number(headers[':status']); });
          stream.on('data', (chunk) => {
            if (done) return;
            size += chunk.length;
            if (size > MAX_RESPONSE_BYTES) cancel('response_too_large');
            else chunks.push(chunk);
          });
          // Error text from sockets or APNs is never propagated: it may contain credentials or paths.
          stream.on('error', () => finish(new ApnsDeliveryError('transport_error')));
          stream.on('aborted', () => finish(new ApnsDeliveryError('transport_aborted')));
          stream.on('close', () => finish(new ApnsDeliveryError('transport_closed')));
          stream.on('end', () => {
            if (done) return;
            if (status === undefined) return cancel('transport_closed');
            if (!Number.isSafeInteger(status) || status < 200 || status > 599) return cancel('invalid_response');
            let reason;
            if (status !== 200 && size) {
              try {
                const value = JSON.parse(Buffer.concat(chunks, size).toString('utf8'));
                if (typeof value?.reason !== 'string' || !/^[A-Za-z]{1,80}$/.test(value.reason)) return cancel('invalid_response');
                reason = value.reason;
              } catch { return cancel('invalid_response'); }
            }
            finish(null, { status, ...(reason ? { reason } : {}) });
          });
          if (signal?.aborted) cancel();
          else stream.end(body);
        } catch { cancel('transport_error'); }
      });
    },
    close() {
      if (closed) return;
      closed = true;
      for (const cancel of [...pending]) cancel('closed');
      for (const connected of sessions) connected.destroy();
      sessions.clear();
      session = undefined;
      cachedToken = undefined;
      privateKey = undefined;
    },
  };
}

/** Only CLI startup calls this function. No secret discovery and no network connection here. */
export async function loadApnsProviderFromEnvironment(env = process.env, options = {}) {
  const configuredKeys = Object.keys(env).filter((name) => name.startsWith('PLEOS_APNS_') && env[name] !== undefined);
  if (!configuredKeys.length) return null;
  if (configuredKeys.some((name) => !environmentKeys.includes(name)) ||
      environmentKeys.some((name) => typeof env[name] !== 'string' || !env[name])) throw configError();
  const identity = { teamId: env.PLEOS_APNS_TEAM_ID, keyId: env.PLEOS_APNS_KEY_ID,
    bundleId: env.PLEOS_APNS_BUNDLE_ID, environment: env.PLEOS_APNS_ENVIRONMENT };
  validateIdentity(identity);
  if (env.PLEOS_APNS_KEY_FILE.length > 4096 || env.PLEOS_APNS_KEY_FILE.includes('\0')) throw configError();
  let file;
  const bytes = Buffer.alloc(MAX_APNS_KEY_FILE_BYTES + 1);
  try {
    file = await open(env.PLEOS_APNS_KEY_FILE, 'r');
    const stat = await file.stat();
    if (!stat.isFile() || stat.size < 1 || stat.size > MAX_APNS_KEY_FILE_BYTES) throw configError();
    let length = 0;
    while (length < bytes.length) {
      const part = await file.read(bytes, length, bytes.length - length, null);
      if (!part.bytesRead) break;
      length += part.bytesRead;
    }
    if (length > MAX_APNS_KEY_FILE_BYTES) throw configError();
    const privateKey = createPrivateKey({ key: bytes.subarray(0, length), format: 'pem', type: 'pkcs8' });
    validateKey(privateKey);
    return createApnsProvider({ ...identity, privateKey }, options);
  } catch { throw configError(); }
  finally { bytes.fill(0); if (file) await file.close(); }
}
