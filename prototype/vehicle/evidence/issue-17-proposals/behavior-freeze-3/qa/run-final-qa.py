import qa, subprocess, json, time, hashlib, sys, datetime

W = qa.D.parents[2] / 'issue-17-proposals'
F = qa.D.parent / 'builder/freeze-3'
frozen = json.loads((F / 'source-manifest.json').read_text())
assert not (qa.D / 'final3-qa-result.json').exists()
steps = [
    ('negative-case.py', 'final3-reject', 'reject'),
    ('negative-case.py', 'final3-expire', 'expire'),
    ('accept-case.py', 'final3-accept-off', 'A', 'dream'),
    ('accept-case.py', 'final3-reverse-off', 'B', '42dot'),
    ('enable-automatic.py', 'final3'),
    ('accept-case.py', 'final3-accept-auto', 'A', 'dream'),
    ('stop-reconnect-case.py',),
]
results = []
tool_inputs = json.loads((qa.D / 'final3-tool-inputs.json').read_text())['files']
for index, step in enumerate(steps):
    assert all(hashlib.sha256((qa.D / p).read_bytes()).hexdigest() == h for p, h in tool_inputs.items())
    assert subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=W, text=True).strip() == frozen['head']
    assert all(hashlib.sha256((W / p).read_bytes()).hexdigest() == h
               for p, h in frozen['sourceInputsSha256'].items())
    argv = ['python3', str(qa.D / step[0]), *step[1:]]
    stem = step[1] if len(step) > 1 else 'final3-stop-reconnect'
    log = qa.D / (stem + '-command.log')
    start = time.time()
    data = {'step': index + 1, 'argv': argv, 'cwd': str(qa.D), 'head': frozen['head'],
            'startedAt': datetime.datetime.now(datetime.timezone.utc).isoformat(),
            'scriptSha256': hashlib.sha256((qa.D / step[0]).read_bytes()).hexdigest(),
            'log': log.name}
    with (qa.D / 'final3-scenarios-commands.jsonl').open('a') as out:
        out.write(json.dumps({'event': 'start', **data}) + '\n')
    with log.open('wb') as out:
        process = subprocess.run(argv, cwd=qa.D, stdout=out, stderr=subprocess.STDOUT, timeout=360)
    data |= {'exitCode': process.returncode, 'seconds': time.time() - start,
             'finishedAt': datetime.datetime.now(datetime.timezone.utc).isoformat()}
    results.append(data)
    with (qa.D / 'final3-scenarios-commands.jsonl').open('a') as out:
        out.write(json.dumps({'event': 'finish', **data}) + '\n')
    print(json.dumps({'step': index + 1, 'case': stem, 'exitCode': process.returncode}), flush=True)
    if process.returncode:
        (qa.D / 'final3-qa-stopped.json').write_text(json.dumps({'head': frozen['head'], 'steps': results}, indent=2))
        sys.exit(process.returncode)
(qa.D / 'final3-qa-result.json').write_text(json.dumps({'head': frozen['head'], 'allStepsPassed': True, 'steps': results}, indent=2))
