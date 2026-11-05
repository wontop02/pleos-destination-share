import qa, json, time, sys, subprocess, hashlib

case, proposer, target = sys.argv[1:]
assert case.replace('-', '').isalnum()
assert proposer in ('A', 'B') and target in ('42dot', 'dream')
serials = {'A': 'emulator-5570', 'B': 'emulator-5572'}
sender = serials[proposer]
receiver = serials['B' if proposer == 'A' else 'A']
name = '42dot' if target == '42dot' else '소프트웨어드림센터'
fixture = ({'name': '42dot', 'latitude': 37.41289678, 'longitude': 127.09383464,
            'poiId': '36khp37hh8gqcm5nqhe5'} if target == '42dot' else
           {'name': '소프트웨어드림센터', 'latitude': 37.41275833, 'longitude': 127.09481944,
            'poiId': '36khp2hdj2cfcm5nqhe3'})
fixture |= {'poiProvider': '', 'poiSubId': None}
previous = ({'name': '소프트웨어드림센터', 'latitude': 37.41275833, 'longitude': 127.09481944,
             'poiId': '36khp2hdj2cfcm5nqhe3'} if target == '42dot' else
            {'name': '42dot', 'latitude': 37.41289678, 'longitude': 127.09383464,
             'poiId': '36khp37hh8gqcm5nqhe5'})
previous |= {'poiProvider': '', 'poiSubId': None}
assert not (qa.D / (case + '-result.json')).exists()

def query(label, suffix):
    stem = f'{case}-{suffix}-{label}-native'
    with (qa.D / (stem + '-command.log')).open('wb') as out:
        subprocess.run(['python3', str(qa.D / 'driver.py'), serials[label], 'query', stem],
                       cwd=qa.D, stdout=out, stderr=subprocess.STDOUT, check=True, timeout=115)
    data = json.loads((qa.D / 'ui' / (stem + '.json')).read_text())
    assert data['result']['status'] == 'OBSERVED'
    assert data['productionProcessUnchanged']
    return data

for label, serial in serials.items():
    qa.show(serial)
    for _ in range(6): qa.U['scroll_top'](serial)
    assert qa.state(serial)['roomConnected']

started = int(time.time() * 1000)
qa.mark('caseStart', case=case, proposer=proposer, target=name, hostEpochMs=started,
        scriptSha256=hashlib.sha256(qa.Path(__file__).read_bytes()).hexdigest())
before = {label: qa.snapshot(serial, f'{case}-before-{label}') for label, serial in serials.items()}
base = before['A']['revision']
assert base == before['B']['revision']
_, tree = qa.U['dump'](sender)
buttons = [n for n in tree.iter('node') if n.get('text', '').startswith('프로토타입 공개 장소 · ')
           and n.get('clickable') == 'true']
assert len(buttons) == 1
qa.U['tap_node'](sender, buttons[0]); qa.mark('placeSelector', serial=sender, case=case)
qa.tap(sender, name)
qa.findtap(sender, '이 장소로 변경 제안')
background_receiver = before['B']['automatic'] == 'FOLLOWER' and receiver == serials['B']
if background_receiver:
    qa.mark('explicitNativeEntry', serial=receiver, case=case, phase='pending-auto-follow')
    qa.U['shell'](receiver, 'am', 'start', '--user', '10', '-n',
                'ai.umos.maps.android.navigation.app/.MainActivity')
pending = {}
for label, serial in serials.items():
    pending[label] = qa.snapshot(serial, f'{case}-pending-{label}')
    assert pending[label]['proposal']['status'] == 'pending'
    assert pending[label]['revision'] == base
    qa.U['capture'](serial, f'{case}-pending-{label}-ui')
    oracle = query(label, 'pending')
    assert all(oracle['result']['initialDestination'].get(key) == expected
               for key, expected in previous.items())
    assert not [e for e in qa.trace_since(serial, started) if e['event'] == 'requestRoute']
    if background_receiver and serial == receiver:
        assert pending[label]['uiCount'] == 0 and pending[label]['automatic'] == 'FOLLOWER'
proposal_id = pending['A']['proposal']['id']
assert pending['B']['proposal']['id'] == proposal_id
qa.mark('explicitNativeEntry', serial=sender, case=case)
qa.U['shell'](sender, 'am', 'start', '--user', '10', '-n',
            'ai.umos.maps.android.navigation.app/.MainActivity')
if background_receiver:
    qa.show(receiver)
    for _ in range(6): qa.U['scroll_top'](receiver)
accept_at = int(time.time() * 1000)
qa.mark('acceptButtonBeforeTap', serial=receiver, case=case, hostEpochMs=accept_at, proposalId=proposal_id)
qa.findtap(receiver, '제안 수락 · 전원 동의 시 반영')

# From this point until the completed result, never enter the production Activity.
deadline = time.monotonic() + 52
confirmed = {}
while time.monotonic() < deadline and len(confirmed) < 2:
    for label, serial in serials.items():
        value = qa.state(serial)
        if (value['applying'] == 'CONFIRMED' and value['uiCount'] == 0 and value['revision'] == base + 1 and
                value.get('proposal', {}).get('id') == proposal_id):
            if label not in confirmed:
                confirmed[label] = {'hostEpochMs': int(time.time() * 1000), 'state': value,
                                    'foreground': qa.foreground(serial)}
                qa.snapshot(serial, f'{case}-confirmed-background-{label}')
                qa.mark('backgroundConfirmedObserved', case=case, serial=serial,
                        proposalId=proposal_id, uiCount=value['uiCount'])
    if len(confirmed) < 2: time.sleep(.75)
if len(confirmed) != 2:
    failure = {label: qa.snapshot(serial, f'{case}-failed-{label}') for label, serial in serials.items()}
    (qa.D / (case + '-failure.json')).write_text(json.dumps(failure, ensure_ascii=False, indent=2))
    raise AssertionError('Both native confirmations were not observed; no app reentry performed')

oracles = {}
for label, serial in serials.items():
    oracles[label] = query(label, 'accepted')
    value = oracles[label]['result']['initialDestination']
    assert all(value.get(key) == expected for key, expected in fixture.items())
    qa.U['capture'](serial, f'{case}-native-confirmed-{label}-ui')

# Retain the navigation foreground through the entire original confirmation lease.
while int(time.time() * 1000) < accept_at + 49_000: time.sleep(1)
results = {}
for label, serial in serials.items():
    value = qa.snapshot(serial, f'{case}-after-{label}')
    rows = qa.trace_since(serial, started)
    requests = [e for e in rows if e['event'] == 'requestRoute']
    matches = [e for e in rows if e['event'] == 'destinationMatch' and e['matched'] is True]
    assert value['revision'] == base + 1
    assert value['proposal']['id'] == proposal_id and value['proposal']['status'] == 'accepted'
    assert len(requests) == 1 and matches
    assert any('ai.umos.maps.android.navigation.app/.MainActivity' in line for line in qa.foreground(serial))
    own_entries = [json.loads(x) for x in (qa.D / 'actions.jsonl').read_text().splitlines()
                   if json.loads(x)['event'] == 'explicitOwnAppEntry' and
                   json.loads(x).get('serial') == serial and
                   json.loads(x)['hostAt'] >= next(json.loads(y)['hostAt'] for y in
                       (qa.D / 'actions.jsonl').read_text().splitlines()
                       if json.loads(y)['event'] == 'acceptButtonBeforeTap' and json.loads(y).get('case') == case)]
    assert not own_entries
    results[label] = {'serial': serial, 'revisionBefore': base, 'revisionAfter': value['revision'],
                      'automaticBefore': before[label]['automatic'], 'requestRouteCount': len(requests),
                      'requestEvents': requests, 'destinationMatches': matches,
                      'confirmedBackground': confirmed[label], 'actualNative': oracles[label],
                      'ownAppReentriesAfterAccept': len(own_entries), 'finalForeground': qa.foreground(serial)}

result = {'case': case, 'pass': True, 'proposalId': proposal_id, 'proposer': proposer,
          'target': name, 'startedAtEpochMs': started, 'acceptBeforeTapEpochMs': accept_at,
          'endedAtEpochMs': int(time.time() * 1000), 'vehicles': results}
(qa.D / (case + '-result.json')).write_text(json.dumps(result, ensure_ascii=False, indent=2))
qa.mark('casePassed', case=case, proposalId=proposal_id, target=name)
print(json.dumps({'case': case, 'pass': True, 'revision': base + 1,
                  'requestRouteCountEach': 1, 'backgroundConfirmedEach': True,
                  'ownAppReentriesAfterAcceptEach': 0}, ensure_ascii=False), flush=True)
