import qa, time, json, subprocess, hashlib

case = 'final3-stop-reconnect'
A, B = 'emulator-5570', 'emulator-5572'
assert not (qa.D / (case + '-result.json')).exists()
assert (qa.D / 'final3-accept-auto-result.json').exists()
started = int(time.time() * 1000)
qa.mark('caseStart', case=case, hostEpochMs=started,
        scriptSha256=hashlib.sha256(qa.Path(__file__).read_bytes()).hexdigest())
before = {label: qa.snapshot(serial, f'{case}-before-{label}') for label, serial in [('A', A), ('B', B)]}
base = before['B']['revision']
assert before['A']['revision'] == base and before['B']['automatic'] == 'FOLLOWER'
assert before['B']['proposal']['status'] == 'accepted' and not before['B']['proposalLease']
qa.show(B)
for _ in range(6): qa.U['scroll_top'](B)
qa.U['scroll_bottom'](B)
qa.findtap(B, '동행·제안 중지')
assert qa.state(B)['automatic'] == 'OFF'
qa.snapshot(B, case + '-stopped-B')
qa.findtap(B, '공유방 다시 연결')
deadline = time.monotonic() + 15
while time.monotonic() < deadline and not qa.state(B)['roomConnected']: time.sleep(.3)
qa.snapshot(B, case + '-reconnected-B')
for _ in range(6): qa.U['scroll_top'](B)
qa.U['scroll_bottom'](B)
qa.findtap(B, '자동 따르기 시작')
assert qa.state(B)['automatic'] == 'FOLLOWER'
qa.mark('explicitNativeEntry', serial=B, case=case)
qa.U['shell'](B, 'am', 'start', '--user', '10', '-n',
            'ai.umos.maps.android.navigation.app/.MainActivity')
time.sleep(10)
stem = case + '-B-native'
with (qa.D / (stem + '-command.log')).open('wb') as out:
    subprocess.run(['python3', str(qa.D / 'driver.py'), B, 'query', stem], cwd=qa.D,
                   stdout=out, stderr=subprocess.STDOUT, check=True, timeout=115)
oracle = json.loads((qa.D / 'ui' / (stem + '.json')).read_text())
expected = {'name': '소프트웨어드림센터', 'latitude': 37.41275833, 'longitude': 127.09481944,
            'poiId': '36khp2hdj2cfcm5nqhe3', 'poiProvider': '', 'poiSubId': None}
assert oracle['result']['status'] == 'OBSERVED' and oracle['productionProcessUnchanged']
assert all(oracle['result']['initialDestination'].get(k) == v for k, v in expected.items())
vehicles = {}
for label, serial in [('A', A), ('B', B)]:
    after = qa.snapshot(serial, f'{case}-after-{label}')
    assert after['revision'] == base and after['proposal']['id'] == before[label]['proposal']['id']
    assert after['proposal']['status'] == 'accepted'
    requests = [e for e in qa.trace_since(serial, started) if e['event'] == 'requestRoute']
    assert not requests
    vehicles[label] = {'serial': serial, 'revisionBefore': base, 'revisionAfter': base,
                       'requestRouteCount': 0, 'before': before[label], 'after': after}
assert vehicles['B']['after']['uiCount'] == 0 and vehicles['B']['after']['automatic'] == 'FOLLOWER'
qa.U['capture'](B, case + '-native-after-ui')
result = {'case': case, 'pass': True, 'startedAtEpochMs': started,
          'endedAtEpochMs': int(time.time() * 1000), 'vehicles': vehicles,
          'actualNativeB': oracle, 'extraHistoricalRequestCount': 0}
(qa.D / (case + '-result.json')).write_text(json.dumps(result, ensure_ascii=False, indent=2))
qa.mark('casePassed', case=case, revision=base, extraHistoricalRequests=0)
print(json.dumps({'case': case, 'pass': True, 'extraRequestCountEach': 0, 'revisionUnchanged': base}), flush=True)
