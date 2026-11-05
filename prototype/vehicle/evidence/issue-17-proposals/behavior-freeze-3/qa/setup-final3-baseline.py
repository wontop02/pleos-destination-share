import qa, json, subprocess, concurrent.futures, hashlib

assert (qa.D / 'deployment-freeze-3.json').exists()
expected = {'name': '42dot', 'latitude': 37.41289678, 'longitude': 127.09383464,
            'poiId': '36khp37hh8gqcm5nqhe5', 'poiProvider': '', 'poiSubId': None}

def run(label, serial):
    def driver(mode, phase):
        stem = f'final3-baseline-{phase}-{label}-native'
        with (qa.D / (stem + '-command.log')).open('wb') as out:
            p = subprocess.run(['python3', str(qa.D / 'driver.py'), serial, mode, stem],
                               cwd=qa.D, stdout=out, stderr=subprocess.STDOUT, timeout=115)
        assert p.returncode == 0
        return json.loads((qa.D / 'ui' / (stem + '.json')).read_text())
    initial = driver('query', 'initial')
    assert initial['result']['status'] == 'OBSERVED'
    actual = initial['result']['initialDestination']
    route = None
    if not all(actual.get(k) == v for k, v in expected.items()):
        route = driver('route-42dot', 'setup-route')
        assert route['result']['status'] == 'VERIFIED'
    final = driver('query', 'confirmed')
    assert final['result']['status'] == 'OBSERVED'
    assert all(final['result']['initialDestination'].get(k) == v for k, v in expected.items())
    return {'serial': serial, 'initial': initial, 'setupRoute': route, 'confirmed': final,
            'baselineOnlyNotAnAcceptedProposalOperation': True}

with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
    futures = {label: pool.submit(run, label, serial) for label, serial in
               [('A', 'emulator-5570'), ('B', 'emulator-5572')]}
    values = {label: future.result() for label, future in futures.items()}
(qa.D / 'final3-native-baseline.json').write_text(json.dumps(values, ensure_ascii=False, indent=2))
qa.mark('actualNativeBaselineConfirmed', target='42dot', freeze='freeze-3')
print('Both actual native baselines strictly match 42dot; no production UI was entered during setup routes', flush=True)
