#!/usr/bin/env node
import * as fs from 'node:fs/promises';
import { MAX_JSON_BYTES, QueueError, integer, requireValue } from './policy.js';
import { describeState } from './queue.js';
import { QueueStore } from './store.js';

const common = ['repo-root', 'run'];
const commands = {
  init: ['snapshot'], status: [], next: ['revision'],
  block: ['revision', 'issue', 'reason', 'condition'],
  retry: ['revision', 'issue'], complete: ['revision', 'issue', 'evidence'],
  'ack-dependency': ['revision', 'issue', 'evidence'],
  stop: ['revision', 'reason', 'condition'], resume: ['revision', 'snapshot'],
  archive: ['revision', 'reason'],
};

function parse(args) {
  const [command, ...rest] = args;
  requireValue(Object.hasOwn(commands, command ?? ''), 'expected command: init, status, next, block, retry, complete, ack-dependency, stop, resume or archive');
  const options = {};
  for (let index = 0; index < rest.length; index += 2) {
    const option = rest[index];
    requireValue(option.startsWith('--') && [...common, ...commands[command]].includes(option.slice(2)), `unknown option: ${option}`);
    const name = option.slice(2);
    requireValue(!Object.hasOwn(options, name), `duplicate option: ${option}`);
    requireValue(index + 1 < rest.length, `missing value for ${option}`);
    options[name] = rest[index + 1];
  }
  for (const name of ['run', ...commands[command]]) requireValue(Object.hasOwn(options, name), `--${name} is required`);
  for (const name of ['revision', 'issue']) {
    if (Object.hasOwn(options, name)) {
      requireValue(/^\d+$/.test(options[name]), `--${name} must be an integer`);
      options[name] = integer(Number(options[name]), name, name === 'revision' ? 0 : 1);
    }
  }
  return { command, options };
}

async function snapshotFile(filename) {
  const stat = await fs.stat(filename);
  requireValue(stat.isFile() && stat.size <= MAX_JSON_BYTES, 'snapshot must be a regular JSON file of at most 2 MiB');
  try { return JSON.parse(await fs.readFile(filename, 'utf8')); } catch (error) {
    if (error instanceof SyntaxError) throw new QueueError('INVALID_INPUT', 'snapshot JSON is malformed');
    throw error;
  }
}

async function main() {
  if (process.argv.length === 3 && ['--help', '-h', 'help'].includes(process.argv[2])) {
    process.stdout.write('Local trusted-snapshot queue; no GitHub requests or agent execution.\n');
    for (const [command, options] of Object.entries(commands)) process.stdout.write(`node harness/cli.js ${command} --run ID [--repo-root PATH] ${options.map(option => `--${option} VALUE`).join(' ')}\n`);
    return;
  }
  const { command, options } = parse(process.argv.slice(2));
  const store = new QueueStore({ repoRoot: options['repo-root'] ?? process.cwd() });
  let state;
  if (command === 'init') state = await store.init(options.run, await snapshotFile(options.snapshot));
  else if (command === 'status') state = await store.read(options.run);
  else {
    const action = { type: command };
    for (const name of ['issue', 'reason', 'condition', 'evidence']) if (Object.hasOwn(options, name)) action[name] = options[name];
    if (command === 'resume') action.snapshot = await snapshotFile(options.snapshot);
    state = await store.update(options.run, options.revision, action);
  }
  process.stdout.write(`${JSON.stringify(describeState(state), null, 2)}\n`);
}

try { await main(); } catch (error) {
  process.stderr.write(`${JSON.stringify({ error: { code: error.code ?? 'ERROR', message: error.message } })}\n`);
  process.exitCode = 1;
}
