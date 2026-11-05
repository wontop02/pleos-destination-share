from pathlib import Path
import runpy, subprocess, sys, re, json, datetime, time

D = Path(__file__).resolve().parent
U = runpy.run_path(str(D / 'root-ui-tools.py'))
serial, mode, stem = sys.argv[1:]
assert serial in ('emulator-5570', 'emulator-5572')
assert mode in ('query', 'route-42dot', 'route-dream-center')
assert re.fullmatch(r'[a-zA-Z0-9-]+', stem)
assert not (D / 'ui' / (stem + '.json')).exists()
assert U['shell'](serial, 'am', 'get-current-user').decode().strip() == '10'

def pid():
    p = subprocess.run([U['ADB'], '-P', '5041', '-s', serial, 'shell', 'pidof', 'ai.pleos.destinations.vehicle'], env=U['ENV'], capture_output=True, text=True)
    return p.stdout.strip() if p.returncode == 0 else None

before = pid()
start = time.monotonic()
arguments = ['shell', 'am', 'instrument', '--user', '10', '-w', '-r', '-e', 'mode', mode, 'ai.pleos.destinations.driver/.NativeDestinationDriver']
text = U['adb'](serial, *arguments, timeout=110).decode()
match = re.search(r'INSTRUMENTATION_RESULT: probe_json=(\{.*\})', text)
assert match, 'Actual self-target driver report unavailable'
result = json.loads(match.group(1))
assert not re.search(r'\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\b', json.dumps(result)), 'Credential-shaped result not persisted'

def public_only(value):
    if isinstance(value, dict):
        if 'poiId' in value and value.get('poiId') not in ('36khp37hh8gqcm5nqhe5', '36khp2hdj2cfcm5nqhe3', '', None):
            return {'redacted': 'Destination outside public fixture allowlist'}
        return {k: public_only(v) for k, v in value.items()}
    if isinstance(value, list):
        return [public_only(v) for v in value]
    return value

after = pid()
record = {'hostAt': datetime.datetime.now(datetime.timezone.utc).isoformat(), 'serial': serial, 'mode': mode, 'seconds': time.monotonic() - start, 'command': {'argv': [U['ADB'], '-P', '5041', '-s', serial, *arguments], 'exitCode': 0}, 'productionPidBefore': before, 'productionPidAfter': after, 'productionProcessUnchanged': bool(before) and before == after, 'result': public_only(result)}
(D / 'ui' / (stem + '.json')).write_text(json.dumps(record, ensure_ascii=False, indent=2) + '\n')
print(json.dumps(record, ensure_ascii=False))
