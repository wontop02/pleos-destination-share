import * as fs from 'node:fs/promises';
import path from 'node:path';
import { randomUUID } from 'node:crypto';
import { MAX_CHECKPOINT_BYTES, QueueError, canonical, fail, integer, requireValue, runId } from './policy.js';
import { createState, isTerminal, transition, validateState } from './queue.js';

async function existing(target) {
  try { return await fs.lstat(target); } catch (error) {
    if (error.code === 'ENOENT') return null;
    throw error;
  }
}

function ioError(error) {
  if (error instanceof QueueError) return error;
  return new QueueError('STORAGE_ERROR', `${error.code ?? 'I/O failure'}: ${error.message}`);
}

export class QueueStore {
  constructor({ repoRoot = process.cwd(), clock = Date.now } = {}) {
    requireValue(typeof repoRoot === 'string' && repoRoot.length > 0, 'repoRoot must be a directory path', 'UNSAFE_PATH');
    this.repoRoot = path.resolve(repoRoot);
    this.clock = clock;
  }

  async storage(create = false) {
    try {
      const rootStat = await fs.lstat(this.repoRoot);
      requireValue(rootStat.isDirectory() && !rootStat.isSymbolicLink(), 'repository root must be a real directory', 'UNSAFE_PATH');
      const root = await fs.realpath(this.repoRoot);
      const base = path.join(root, '.harness');
      for (const directory of [base, path.join(base, 'runs'), path.join(base, 'locks')]) {
        let stat = await existing(directory);
        if (!stat && create) {
          try { await fs.mkdir(directory, { mode: 0o700 }); } catch (error) {
            if (error.code !== 'EEXIST') throw error;
          }
          stat = await existing(directory);
        }
        requireValue(stat?.isDirectory() && !stat.isSymbolicLink(), `storage directory is missing or unsafe: ${directory}`, stat ? 'UNSAFE_PATH' : 'NOT_FOUND');
        requireValue(await fs.realpath(directory) === directory, `storage directory redirects outside its expected location: ${directory}`, 'UNSAFE_PATH');
      }
      return { runs: path.join(base, 'runs'), locks: path.join(base, 'locks') };
    } catch (error) { throw ioError(error); }
  }

  async loadFile(filename, expectedId) {
    const stat = await existing(filename);
    requireValue(stat !== null, `run ${expectedId} does not exist`, 'NOT_FOUND');
    requireValue(stat.isFile() && !stat.isSymbolicLink(), `checkpoint is not a regular file: ${filename}`, 'UNSAFE_PATH');
    requireValue(stat.size <= MAX_CHECKPOINT_BYTES, 'checkpoint exceeds the 4 MiB limit', 'INVALID_STATE');
    let state;
    try { state = JSON.parse(await fs.readFile(filename, 'utf8')); } catch (error) {
      if (error instanceof SyntaxError) fail('CORRUPT_STATE', `checkpoint JSON is corrupt: ${expectedId}`);
      throw error;
    }
    try { validateState(state); } catch (error) {
      if (error instanceof QueueError && error.code === 'INVALID_INPUT') fail('INVALID_STATE', error.message);
      throw error;
    }
    requireValue(state.runId === expectedId, 'checkpoint run ID differs from its filename', 'INVALID_STATE');
    return state;
  }

  async read(id) {
    runId(id);
    try {
      const paths = await this.storage();
      return await this.loadFile(path.join(paths.runs, `${id}.json`), id);
    } catch (error) { throw ioError(error); }
  }

  async scan(paths) {
    const states = [];
    for (const entry of await fs.readdir(paths.runs, { withFileTypes: true })) {
      requireValue(entry.isFile() && !entry.isSymbolicLink(), `unsafe item in run storage: ${entry.name}`, 'UNSAFE_PATH');
      if (/^\.[a-z0-9][a-z0-9_-]{0,63}\.[0-9]+\.[a-f0-9-]+\.tmp$/.test(entry.name)) continue;
      requireValue(entry.name.endsWith('.json'), `unexpected file in run storage: ${entry.name}`, 'UNSAFE_PATH');
      const id = entry.name.slice(0, -5);
      runId(id);
      states.push(await this.loadFile(path.join(paths.runs, entry.name), id));
    }
    requireValue(states.filter(state => !isTerminal(state)).length <= 1, 'multiple live runs exist; coordinator recovery is required', 'RUN_CONFLICT');
    return states;
  }

  async locked(id, create, operation) {
    runId(id);
    const paths = await this.storage(create);
    const lockPath = path.join(paths.locks, 'queue.lock');
    let handle;
    try { handle = await fs.open(lockPath, 'wx', 0o600); } catch (error) {
      if (error.code === 'EEXIST') fail('LOCKED', 'repository queue lock is occupied; verify its owner has stopped before explicit operator recovery. No lock was removed.');
      throw ioError(error);
    }
    const lockStat = await handle.stat();
    let result;
    let failure;
    try {
      await handle.writeFile(`${JSON.stringify({ pid: process.pid, runId: id, owner: randomUUID() })}\n`);
      await handle.sync();
      // Recheck directories after obtaining the lock; never follow storage symlinks.
      const checked = await this.storage();
      requireValue(checked.runs === paths.runs && checked.locks === paths.locks, 'storage location changed while acquiring lock', 'UNSAFE_PATH');
      result = await operation(paths);
    } catch (error) { failure = ioError(error); }
    try {
      await handle.close();
      const current = await existing(lockPath);
      requireValue(current?.isFile() && !current.isSymbolicLink() && current.ino === lockStat.ino && current.dev === lockStat.dev, 'repository lock ownership changed before cleanup', 'LOCK_CLEANUP_ERROR');
      await fs.unlink(lockPath);
    } catch (error) {
      failure = new QueueError('LOCK_CLEANUP_ERROR', `Repository lock cleanup failed (${error.code ?? 'I/O error'}). The checkpoint operation may have committed; read status before retrying and verify the lock owner before operator recovery.`);
    }
    if (failure) throw failure;
    return result;
  }

  async checkpoint(paths, state) {
    validateState(state);
    const payload = `${JSON.stringify(state)}\n`;
    requireValue(Buffer.byteLength(payload) <= MAX_CHECKPOINT_BYTES, 'checkpoint would exceed the 4 MiB UTF-8 limit; previous checkpoint is preserved', 'CHECKPOINT_TOO_LARGE');
    const filename = path.join(paths.runs, `${state.runId}.json`);
    const current = await existing(filename);
    requireValue(!current || current.isFile() && !current.isSymbolicLink(), 'checkpoint path is unsafe', 'UNSAFE_PATH');
    const temporary = path.join(paths.runs, `.${state.runId}.${process.pid}.${randomUUID()}.tmp`);
    let handle;
    let renamed = false;
    let failure;
    try {
      handle = await fs.open(temporary, 'wx', 0o600);
      await handle.writeFile(payload);
      await handle.sync();
      await handle.close();
      handle = undefined;
      await fs.rename(temporary, filename);
      renamed = true;
      // Flush the rename when supported. A failure here must not pretend the old state remains.
      const directory = await fs.open(paths.runs, 'r');
      try { await directory.sync(); } finally { await directory.close(); }
    } catch (error) { failure = error; }
    try {
      if (handle) await handle.close();
      await fs.unlink(temporary).catch(error => { if (error.code !== 'ENOENT') throw error; });
    } catch (error) { failure ??= error; }
    if (failure) {
      if (renamed) fail('COMMIT_OUTCOME_UNKNOWN', `Checkpoint rename completed but durability or cleanup failed (${failure.code ?? 'I/O error'}); read status before retrying.`);
      throw failure;
    }
  }

  async init(id, snapshot) {
    runId(id);
    // Validate untrusted input before creating any storage or lock.
    const state = createState(id, snapshot, this.clock);
    return this.locked(id, true, async paths => {
      const filename = path.join(paths.runs, `${id}.json`);
      requireValue(!await existing(filename), `run ${id} already exists; initialization never overwrites it`, 'DUPLICATE_RUN');
      const states = await this.scan(paths);
      requireValue(!states.some(item => !isTerminal(item)), 'a live run already exists; finish or explicitly archive it before starting another batch', 'RUN_CONFLICT');
      await this.checkpoint(paths, state);
      return state;
    });
  }

  async update(id, expectedRevision, action) {
    runId(id);
    integer(expectedRevision, 'expected revision', 0);
    return this.locked(id, false, async paths => {
      const states = await this.scan(paths);
      const current = states.find(state => state.runId === id);
      requireValue(current, `run ${id} does not exist`, 'NOT_FOUND');
      requireValue(current.revision === expectedRevision, `stale revision: expected ${expectedRevision}, current ${current.revision}`, 'STALE_REVISION');
      if (!isTerminal(current)) requireValue(!states.some(state => state.runId !== id && !isTerminal(state)), 'another live run already owns this repository queue', 'RUN_CONFLICT');
      const next = transition(current, action, this.clock);
      if (canonical(next) !== canonical(current)) await this.checkpoint(paths, next);
      return next;
    });
  }
}
