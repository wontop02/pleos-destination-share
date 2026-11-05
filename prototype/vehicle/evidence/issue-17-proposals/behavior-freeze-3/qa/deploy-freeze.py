from pathlib import Path
import qa, json, hashlib, subprocess, os, signal, time, socket, sys, datetime

freeze = sys.argv[1]
assert freeze in ('freeze-2', 'freeze-3')
W = qa.D.parents[2] / 'issue-17-proposals'
# root -> vehicle-proposals-20261105 -> product-run-artifacts -> worktrees
assert (W / 'prototype/server.mjs').is_file()
F = qa.D.parent / 'builder' / freeze
source = json.loads((F / 'source-manifest.json').read_text())
head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=W, text=True).strip()
assert head == source['head']
assert all(hashlib.sha256((W / p).read_bytes()).hexdigest() == h
           for p, h in source['sourceInputsSha256'].items())
assert not (qa.D / ('deployment-' + freeze + '.json')).exists()
records = []

def command(serial, *args, timeout=90):
    assert serial in qa.U['SERIALS']
    argv = [qa.U['ADB'], '-P', '5041', '-s', serial, *args]
    p = subprocess.run(argv, env=qa.U['ENV'], capture_output=True, text=True, timeout=timeout)
    item = {'at': datetime.datetime.now(datetime.timezone.utc).isoformat(), 'argv': argv,
            'exitCode': p.returncode, 'stdout': p.stdout.strip(), 'stderr': p.stderr.strip()}
    records.append(item)
    (qa.D / ('deployment-' + freeze + '-commands.json')).write_text(json.dumps(records, indent=2))
    assert p.returncode == 0, 'Scoped deployment command failed; see own command artifact'
    return p.stdout.strip()

for serial in ('emulator-5570', 'emulator-5572'):
    value = qa.state(serial)
    assert value['automatic'] == 'OFF' and not value['proposalLease'] and not value['proposalBusy']

previous_file = 'backend-freeze-2.json' if freeze == 'freeze-3' else 'backend.json'
previous = json.loads((qa.D / previous_file).read_text())
pid = previous['pid']
proc = subprocess.check_output(['ps', '-p', str(pid), '-o', 'command='], text=True).strip()
assert proc == 'node prototype/server.mjs'
cwd = subprocess.check_output(['lsof', '-a', '-p', str(pid), '-d', 'cwd', '-Fn'], text=True)
assert '\nn' + str(W) + '\n' in cwd
listeners = subprocess.check_output(['lsof', '-nP', '-iTCP:4186', '-sTCP:LISTEN', '-Fp'], text=True)
assert {line for line in listeners.splitlines() if line.startswith('p')} == {'p' + str(pid)}
os.kill(pid, signal.SIGTERM)
for _ in range(40):
    with socket.socket() as s:
        if s.connect_ex(('127.0.0.1', 4186)) != 0: break
    time.sleep(.1)
else: raise AssertionError('Owned old backend did not release 4186')
with (qa.D / ('backend-' + freeze + '.log')).open('ab') as log:
    server = subprocess.Popen(['node', 'prototype/server.mjs'], cwd=W,
                              env=os.environ | {'PORT': '4186'}, stdout=log,
                              stderr=subprocess.STDOUT, start_new_session=True)
time.sleep(.7)
assert server.poll() is None
backend = {'argv': ['node', 'prototype/server.mjs'], 'cwd': str(W), 'host': '127.0.0.1',
           'port': 4186, 'pid': server.pid, 'replacedOwnedPid': pid, 'head': head,
           'serverSha256': hashlib.sha256((W / 'prototype/server.mjs').read_bytes()).hexdigest()}
(qa.D / ('backend-' + freeze + '.json')).write_text(json.dumps(backend, indent=2))
installed = []
for serial in ('emulator-5570', 'emulator-5572'):
    assert command(serial, 'shell', 'am', 'get-current-user') == '10'
    for filename, package, test in [('app-debug.apk', 'ai.pleos.destinations.vehicle', False),
                                    ('nativeDriver-debug.apk', 'ai.pleos.destinations.driver', True)]:
        apk = F / filename
        sha = hashlib.sha256(apk.read_bytes()).hexdigest()
        output = command(serial, 'install', '--user', '10', '-r', *(['-t'] if test else []), str(apk))
        assert 'Success' in output
        path = command(serial, 'shell', 'pm', 'path', '--user', '10', package)
        assert path.startswith('package:/data/app/') and path.endswith('/base.apk')
        device_sha = command(serial, 'shell', 'sha256sum', path.removeprefix('package:')).split()[0]
        assert device_sha == sha
        installed.append({'serial': serial, 'user': 10, 'package': package, 'file': filename,
                          'inputSha256': sha, 'installedBaseSha256': device_sha})
    command(serial, 'reverse', 'tcp:4186', 'tcp:4186')
result = {'freeze': freeze, 'base': source['base'], 'head': head,
          'sourceInputsVerified': source['sourceInputsCount'], 'backend': backend,
          'installAndHashCommands': records, 'installed': installed}
(qa.D / ('deployment-' + freeze + '.json')).write_text(json.dumps(result, indent=2))
qa.mark('deploymentComplete', freeze=freeze, head=head, backendPid=server.pid)
print(json.dumps({'freeze': freeze, 'head': head, 'backendPid': server.pid, 'installed': installed}), flush=True)
