from pathlib import Path
import runpy, json, time, datetime, hashlib, traceback

RAW = Path(__file__).resolve().parent
WT = RAW.parent.parent / 'issue-14-auto-follow'
R = runpy.run_path(str(RAW / 'root-runtime-tools.py'))
U = R['U']
OUT = RAW / 'continuous-freeze2'
OUT.mkdir(exist_ok=False)
SERIALS = [('emulator-5570', 'leader', 'LEADER'), ('emulator-5572', 'follower', 'FOLLOWER')]
manifest = json.loads((RAW / 'builder/freeze-2/source-manifest.json').read_text())

def now(): return datetime.datetime.now(datetime.timezone.utc).isoformat()
def save(name, value): (OUT / name).write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n')
def check_sources():
    rows = manifest['runtimeFiles'] + manifest['testFiles']
    mismatch = [r['path'] for r in rows if hashlib.sha256((WT / r['path']).read_bytes()).hexdigest() != r['sha256']]
    if mismatch: raise RuntimeError('frozen source mismatch: ' + str(mismatch))
    return {'at': now(), 'fileCount': len(rows), 'mismatch': mismatch,
            'runtimeSourceSha256': manifest['runtimeSourceSha256'], 'allBuildInputsSha256': manifest['allBuildInputsSha256']}

start = None
baseline = {}
prefix = {}
changes = []
samples = 0

def elapsed(): return round(time.monotonic() - start, 3) if start is not None else None
def journal(role, serial):
    rows = U['read_events'](serial)
    initial = prefix.get(role, [])
    if rows[:len(initial)] != initial: raise RuntimeError(role + ': journal prefix lost or rotated')
    after = rows[len(initial):]
    if any(x['event'] == 'activityStartAttach' for x in after): raise RuntimeError(role + ': own Activity reentry')
    if any('uiCount' in x and x['uiCount'] != 0 for x in after): raise RuntimeError(role + ': own UI attached')
    (OUT / (role + '-events.jsonl')).write_text(''.join(json.dumps(x, ensure_ascii=False) + '\n' for x in rows))
    return after

def sample(tag):
    global samples
    pair = {'at': now(), 'elapsedSeconds': elapsed(), 'tag': tag, 'vehicles': {}}
    for serial, role, mode in SERIALS:
        value = R['snapshot'](serial)
        if not value['nativeForeground'] or value['ownResumed'] or value['session']['uiCount'] != 0:
            raise RuntimeError(role + ': native/Activity continuity failed')
        if not value['serviceForeground'] or value['session']['automatic'] != mode:
            raise RuntimeError(role + ': foreground automatic session lost')
        if baseline:
            old = baseline[role]
            if value['pid'] != old['pid'] or value['session']['serviceEpoch'] != old['session']['serviceEpoch']:
                raise RuntimeError(role + ': production process/service replaced')
        pair['vehicles'][role] = value
        if role in prefix: journal(role, serial)
    with (OUT / 'samples.jsonl').open('a') as f: f.write(json.dumps(pair, ensure_ascii=False) + '\n')
    samples += 1
    save('progress.json', {'status': 'RUNNING', 'at': now(), 'elapsedSeconds': elapsed(), 'samples': samples,
                          'completedChanges': len(changes), 'latest': pair})
    return pair

def equal_destination(a, b):
    return all(a.get(k) == b.get(k) for k in ['name', 'poiId', 'poiSubId', 'poiProvider']) and all(
        abs(a[k] - b[k]) <= 0.00001 for k in ['latitude', 'longitude'])

try:
    save('source-start.json', check_sources())
    first = sample('baseline-before-window')
    baseline = first['vehicles']
    if baseline['follower']['session']['applying'] != 'CONFIRMED': raise RuntimeError('baseline follower unconfirmed')
    for serial, role, _ in SERIALS:
        prefix[role] = U['read_events'](serial)
        save(role + '-journal-baseline.json', prefix[role])
    uptime_start = {role: float(U['shell'](serial, 'cat', '/proc/uptime').decode().split()[0]) for serial, role, _ in SERIALS}
    wall_start = now()
    start = time.monotonic()
    save('window-start.json', {'at': wall_start, 'hostMonotonic': start, 'deviceUptimeSeconds': uptime_start,
                              'baseline': baseline, 'scheduledChangesSeconds': [20, 300, 630],
                              'minimumSeconds': 900, 'apkSHA256': 'f96663e4da4ab428e9656841b0d2e4bbce592008e712eb0e79a0e9af8ee57fe4',
                              'driverSHA256': 'd71239e388a415489aa163f7d7a809eb9d0cfbf92ed94d6f786f66eb1a5a406f'})
    checkpoint = json.loads((RAW / 'run-state.json').read_text())
    checkpoint.update(at=now(), status='freeze2-real-continuity-running', runtimeCandidate='freeze-2',
                      actualContinuousWindowStarted=True, longRunPass=False, runtimeProofDirectory=str(OUT))
    (RAW / 'run-state.json').write_text(json.dumps(checkpoint, ensure_ascii=False, indent=2) + '\n')
    schedule = [(20, 'route-dream-center'), (300, 'route-42dot'), (630, 'route-dream-center')]
    next_sample = 0
    print(json.dumps({'event': 'window_started', 'at': wall_start}, ensure_ascii=False), flush=True)
    while time.monotonic() - start < 905:
        current = time.monotonic() - start
        if len(changes) < len(schedule) and current >= schedule[len(changes)][0]:
            index = len(changes) + 1
            due, mode = schedule[index - 1]
            before = sample('before-change-' + str(index))
            previous_revision = before['vehicles']['follower']['session']['revision']
            request_before = sum(x['event'] == 'requestRoute' for x in U['read_events']('emulator-5572'))
            began = elapsed()
            source = R['driver']('emulator-5570', mode, '20-continuous-change-' + str(index) + '-source')
            if not source['productionProcessUnchanged'] or source['result']['status'] != 'VERIFIED':
                raise RuntimeError('actual source change failed')
            desired = source['result']['postRequestDestination']
            deadline = time.monotonic() + 65
            confirmed = None
            while time.monotonic() < deadline:
                pair = sample('waiting-change-' + str(index))
                a, b = pair['vehicles']['leader']['session'], pair['vehicles']['follower']['session']
                if b['revision'] > previous_revision and a['revision'] == b['revision'] and b['applying'] == 'CONFIRMED' and b['confirmedAtElapsed'] is not None:
                    confirmed = pair
                    break
                time.sleep(2)
            if confirmed is None: raise RuntimeError('new revision did not confirm in background')
            actual = R['driver']('emulator-5572', 'query', '20-continuous-change-' + str(index) + '-follower')
            if not actual['productionProcessUnchanged'] or actual['result']['status'] != 'OBSERVED' or not equal_destination(desired, actual['result']['initialDestination']):
                raise RuntimeError('actual follower destination mismatch')
            time.sleep(5)
            final = sample('after-change-' + str(index))
            if final['vehicles']['follower']['session']['applying'] != 'CONFIRMED':
                raise RuntimeError('late native event lost confirmation')
            request_after = sum(x['event'] == 'requestRoute' for x in U['read_events']('emulator-5572'))
            change = {'index': index, 'scheduledAfterSeconds': due, 'startedAfterSeconds': began,
                      'finishedAfterSeconds': elapsed(), 'mode': mode, 'source': source, 'follower': actual,
                      'previousRevision': previous_revision, 'confirmedRevision': final['vehicles']['follower']['session']['revision'],
                      'newFollowerRequests': request_after - request_before, 'actualDestinationsMatch': True,
                      'confirmation': confirmed, 'afterLateEvents': final}
            changes.append(change)
            save('changes.json', changes)
            print(json.dumps({'event': 'change_verified', 'index': index, 'elapsedSeconds': elapsed(), 'newFollowerRequests': request_after-request_before}, ensure_ascii=False), flush=True)
            next_sample = time.monotonic() - start + 20
        elif current >= next_sample:
            sample('periodic')
            print(json.dumps({'event': 'sample', 'elapsedSeconds': elapsed(), 'samples': samples, 'completedChanges': len(changes)}), flush=True)
            next_sample = time.monotonic() - start + 20
        else:
            time.sleep(min(1, max(0.01, next_sample-current)))
    end_pair = sample('end-window')
    uptime_end = {role: float(U['shell'](serial, 'cat', '/proc/uptime').decode().split()[0]) for serial, role, _ in SERIALS}
    total = elapsed()
    device_delta = {role: uptime_end[role] - uptime_start[role] for _, role, _ in SERIALS}
    if total < 900 or any(v < 900 for v in device_delta.values()) or len(changes) < 3:
        raise RuntimeError('duration or destination-change requirement incomplete')
    event_summary = {}
    for serial, role, _ in SERIALS:
        delta = journal(role, serial)
        event_summary[role] = {'eventsAfterBaseline': len(delta), 'activityReentries': sum(x['event']=='activityStartAttach' for x in delta),
                               'newRequestRoute': sum(x['event']=='requestRoute' for x in delta),
                               'strictMatches': sum(x['event']=='destinationMatch' and x.get('matched') is True for x in delta),
                               'automaticPublications': sum(x['event']=='automaticPublish' for x in delta)}
        U['capture'](serial, '21-continuous-final-' + role)
    save('source-end.json', check_sources())
    result = {'status': 'PASS', 'atStart': wall_start, 'atEnd': now(), 'durationSeconds': total,
              'deviceUptimeDeltaSeconds': device_delta, 'samples': samples, 'changes': len(changes),
              'changeAfterTenMinutes': any(c['startedAfterSeconds'] >= 600 for c in changes),
              'events': event_summary, 'baseline': baseline, 'final': end_pair,
              'stopNegativeStatus': 'PENDING', 'realCars': False, 'routingCompletedClaimed': False}
    save('result.json', result)
    checkpoint.update(at=now(), status='freeze2-15minute-continuity-pass-stop-negative-pending', longRunPass=True)
    (RAW / 'run-state.json').write_text(json.dumps(checkpoint, ensure_ascii=False, indent=2) + '\n')
    save('progress.json', result)
    print(json.dumps({'event': 'window_complete', 'result': result}, ensure_ascii=False), flush=True)
except Exception as error:
    failure = {'status': 'FAIL', 'at': now(), 'elapsedSeconds': elapsed(), 'failureType': type(error).__name__, 'reason': str(error), 'traceback': traceback.format_exc(), 'changes': changes, 'samples': samples}
    save('result.json', failure)
    save('progress.json', failure)
    print(json.dumps(failure, ensure_ascii=False), flush=True)
    raise
