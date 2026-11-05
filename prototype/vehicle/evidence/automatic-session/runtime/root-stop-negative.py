from pathlib import Path
import runpy, json, time, datetime

RAW = Path(__file__).resolve().parent
R = runpy.run_path(str(RAW / 'root-runtime-tools.py'))
U = R['U']
OUT = RAW / 'stop-negative-freeze2'
OUT.mkdir(exist_ok=False)

def now(): return datetime.datetime.now(datetime.timezone.utc).isoformat()
def save(name, value): (OUT / name).write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n')
def request_count(rows): return sum(x['event'] == 'requestRoute' for x in rows)
def no_follow(snapshot):
    return not snapshot['serviceForeground'] and snapshot['session']['automatic'] == 'OFF' and snapshot['session']['uiCount'] == 0
def match(a, b):
    return all(a.get(k) == b.get(k) for k in ['name', 'poiId', 'poiSubId', 'poiProvider']) and all(abs(a[k]-b[k]) <= 0.00001 for k in ['latitude', 'longitude'])

assert json.loads((RAW / 'continuous-freeze2/result.json').read_text())['status'] == 'PASS'
start = time.monotonic()
before = R['snapshot']('emulator-5572')
save('before.json', before)
assert no_follow(before), 'follower must first be stopped via the actual notification action'
assert before['nativeForeground'] and not before['ownResumed']
events_before = U['read_events']('emulator-5572')
save('before-events.json', events_before)
native_before = R['driver']('emulator-5572', 'query', '24-stop-negative-follower-before')
assert native_before['result']['status'] == 'OBSERVED'
assert native_before['result']['initialDestination']['name'] == '소프트웨어드림센터'
leader_before = R['snapshot']('emulator-5570')
assert leader_before['session']['automatic'] == 'LEADER' and leader_before['serviceForeground']
source = R['driver']('emulator-5570', 'route-42dot', '24-stop-negative-leader-change')
assert source['result']['status'] == 'VERIFIED' and source['productionProcessUnchanged']
source_done = time.monotonic()
samples = []
while time.monotonic() - source_done < 65:
    follower = R['snapshot']('emulator-5572')
    leader = R['snapshot']('emulator-5570')
    samples.append({'at': now(), 'elapsedSeconds': time.monotonic()-start, 'leader': leader, 'follower': follower})
    save('samples.json', samples)
    assert no_follow(follower), 'stopped foreground session restarted'
    assert follower['nativeForeground'] and not follower['ownResumed']
    assert request_count(U['read_events']('emulator-5572')) == request_count(events_before), 'stopped follower made a new route request'
    print(json.dumps({'event': 'stopped_follower_sample', 'secondsAfterSource': round(time.monotonic()-source_done, 2)}, ensure_ascii=False), flush=True)
    time.sleep(10)
after_native = R['driver']('emulator-5572', 'query', '24-stop-negative-follower-after')
assert after_native['result']['status'] == 'OBSERVED' and after_native['productionProcessUnchanged']
assert match(native_before['result']['initialDestination'], after_native['result']['initialDestination'])
leader_final = R['snapshot']('emulator-5570')
assert leader_final['session']['revision'] > leader_before['session']['revision'], 'leader automatic publication absent'
follower_final = R['snapshot']('emulator-5572')
events_after = U['read_events']('emulator-5572', '24-stop-negative-follower-events')
assert events_after[:len(events_before)] == events_before
assert request_count(events_after) == request_count(events_before)
assert no_follow(follower_final)
assert not any(x['event']=='activityStartAttach' for x in events_after[len(events_before):])
result = {'status': 'PASS', 'at': now(), 'durationSeconds': time.monotonic()-start,
          'observationAfterSourceSeconds': time.monotonic()-source_done,
          'stopMethod': 'actual notification stop action; see notification-stop receipt',
          'newFollowerRequests': 0, 'followerActivityReentries': 0, 'followerDestinationUnchanged': True,
          'source': source, 'followerNativeBefore': native_before, 'followerNativeAfter': after_native,
          'leaderRevisionBefore': leader_before['session']['revision'], 'leaderRevisionAfter': leader_final['session']['revision'],
          'followerBefore': before, 'followerAfter': follower_final}
save('result.json', result)
U['capture']('emulator-5572', '25-stop-negative-follower-native')
checkpoint = json.loads((RAW/'run-state.json').read_text())
checkpoint.update(at=now(), status='freeze2-continuity-and-stop-negative-pass-docs-pending', stopNegativePass=True)
(RAW/'run-state.json').write_text(json.dumps(checkpoint, ensure_ascii=False, indent=2)+'\n')
print(json.dumps({'event':'stop_negative_complete','status':'PASS','newFollowerRequests':0,'observationAfterSourceSeconds':result['observationAfterSourceSeconds']}), flush=True)
