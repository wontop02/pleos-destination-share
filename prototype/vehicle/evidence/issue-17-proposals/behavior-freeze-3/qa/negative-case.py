import qa, json, time, sys, subprocess, hashlib

case, action = sys.argv[1:]
assert case.replace('-', '').isalnum() and action in ('reject', 'expire')
assert not (qa.D / (case + '-result.json')).exists()
serials = {'A': 'emulator-5570', 'B': 'emulator-5572'}
baseline = {'name': '42dot', 'latitude': 37.41289678, 'longitude': 127.09383464,
            'poiId': '36khp37hh8gqcm5nqhe5', 'poiProvider': '', 'poiSubId': None}

def query(label, suffix):
    stem = f'{case}-{suffix}-{label}-native'
    with (qa.D / (stem + '-command.log')).open('wb') as out:
        subprocess.run(['python3', str(qa.D / 'driver.py'), serials[label], 'query', stem],
                       cwd=qa.D, stdout=out, stderr=subprocess.STDOUT, check=True, timeout=115)
    value = json.loads((qa.D / 'ui' / (stem + '.json')).read_text())
    assert value['result']['status'] == 'OBSERVED'
    assert all(value['result']['initialDestination'].get(k) == v for k, v in baseline.items())
    return value

for serial in serials.values():
    qa.show(serial)
    for _ in range(6): qa.U['scroll_top'](serial)
started = int(time.time() * 1000)
qa.mark('caseStart', case=case, action=action, hostEpochMs=started,
        scriptSha256=hashlib.sha256(qa.Path(__file__).read_bytes()).hexdigest())
before = {label: qa.snapshot(serial, f'{case}-before-{label}') for label, serial in serials.items()}
base = before['A']['revision']
assert base == before['B']['revision']
assert all(v['automatic'] == 'OFF' and v['roomConnected'] for v in before.values())
_, tree = qa.U['dump'](serials['A'])
buttons = [n for n in tree.iter('node') if n.get('text', '').startswith('프로토타입 공개 장소 · ')
           and n.get('clickable') == 'true']
assert len(buttons) == 1
qa.U['tap_node'](serials['A'], buttons[0]); qa.tap(serials['A'], '소프트웨어드림센터')
qa.findtap(serials['A'], '이 장소로 변경 제안')
pending = {}
for label, serial in serials.items():
    pending[label] = qa.snapshot(serial, f'{case}-pending-{label}')
    assert pending[label]['proposal']['status'] == 'pending' and pending[label]['revision'] == base
    qa.U['capture'](serial, f'{case}-pending-{label}-ui')
    query(label, 'pending')
proposal = pending['A']['proposal']
assert proposal['id'] == pending['B']['proposal']['id']
assert proposal['expiresAt'] - proposal['createdAt'] == 120_000
if action == 'reject':
    qa.findtap(serials['B'], '제안 거절 · 현재 안내 유지')
else:
    for serial in serials.values():
        qa.mark('explicitNativeEntry', serial=serial, case=case)
        qa.U['shell'](serial, 'am', 'start', '--user', '10', '-n',
                    'ai.umos.maps.android.navigation.app/.MainActivity')
    print(json.dumps({'case': case, 'waitingRealExpiryAt': proposal['expiresAt']}), flush=True)
    while int(time.time() * 1000) < proposal['expiresAt'] + 1500: time.sleep(2)
    for label, serial in serials.items(): qa.snapshot(serial, f'{case}-expired-background-{label}')

oracles = {label: query(label, 'after') for label in serials}
results = {}
for label, serial in serials.items():
    if action == 'expire': qa.show(serial)
    value = qa.snapshot(serial, f'{case}-after-{label}')
    expected = 'rejected' if action == 'reject' else 'expired'
    assert value['revision'] == base and value['proposal']['id'] == proposal['id']
    assert value['proposal']['status'] == expected
    requests = [e for e in qa.trace_since(serial, started) if e['event'] == 'requestRoute']
    assert not requests
    qa.U['capture'](serial, f'{case}-after-{label}-ui')
    results[label] = {'serial': serial, 'revisionBefore': base, 'revisionAfter': value['revision'],
                      'requestRouteCount': 0, 'proposalStatus': expected, 'actualNative': oracles[label]}
result = {'case': case, 'pass': True, 'proposalId': proposal['id'], 'createdAt': proposal['createdAt'],
          'expiresAt': proposal['expiresAt'], 'startedAtEpochMs': started,
          'endedAtEpochMs': int(time.time() * 1000), 'vehicles': results}
(qa.D / (case + '-result.json')).write_text(json.dumps(result, ensure_ascii=False, indent=2))
qa.mark('casePassed', case=case, proposalId=proposal['id'])
print(json.dumps({'case': case, 'pass': True, 'revisionUnchanged': base,
                  'requestRouteCountEach': 0, 'actualNativeEach': '42dot'}), flush=True)
