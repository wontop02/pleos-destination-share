"""Audit final source runtime records without operating any device."""
from pathlib import Path
import json, datetime, hashlib, re

D = Path(__file__).resolve().parent
S = ('emulator-5570', 'emulator-5572')
HEAD = '3502929d569262253440fa3241ff90cffe57c859'
APK = '623d103c6768762451f2113ffde35fa99351c5458b2de711f5d9a7d857664195'
def read(n): return json.loads((D / (n + '.json')).read_text())
def lines(n): return [json.loads(x) for x in (D / n).read_text().splitlines() if x.strip()]
def counts(trace): return {s: sum(r['event'] == 'requestRoute' for r in trace[s]) for s in S}
def timestamp(x): return datetime.datetime.fromisoformat(x)

installed = read('installed')
assert installed['sourceHead'] == HEAD and installed['apkSha256'] == APK
assert {d['serial'] for d in installed['devices']} == set(S)
assert all(d['user'] == 10 and d['installExit'] == 0 and d['installedApkSha256'] == APK for d in installed['devices'])
freeze = json.loads((D.parent / 'builder/freeze-3/freeze.json').read_text())
assert freeze['head'] == HEAD and freeze['apkSha256'] == APK
fresh = read('fresh-room')
assert fresh['sourceHead'] == HEAD and fresh['invitationTransferredInMemoryOnly'] and fresh['invitationCaptureRefused']
assert fresh['routeCounts'] == {s: 0 for s in S}
summary = {'sourceHead': HEAD, 'apkSha256': APK, 'initialRoomCreationAndJoin': True, 'cases': {}, 'noActionChecks': {}}
for name in ('navigation-no-action', 'settings-active-cancel', 'explicit-stop-restart', 'cold-no-implicit-action', 'long-place-no-send', 'final-demo-ready'):
    v = read(name)
    assert v['routeCounts'] == counts(v['trace']) == {s: 0 for s in S}
    summary['noActionChecks'][name] = v['routeCounts']

observations = lines('observations.jsonl')
errors = lines('collector-read-errors.jsonl')
for case in ('cafe', 'restaurant'):
    negative, accepted = read(case + '-negative'), read(case + '-accepted')
    assert negative['pending']['status'] == 'pending' and negative['rejected']['status'] == 'rejected'
    assert negative['routeCounts'] == counts(negative['trace']) == {s: 0 for s in S}
    assert accepted['accepted']['status'] == 'accepted'
    assert accepted['routeCounts'] == counts(accepted['trace']) == {s: 1 for s in S}
    start, end = accepted['nativeWindowStart']['hostAt'], accepted['nativeWindowEnd']['hostAt']
    duration = (timestamp(end) - timestamp(start)).total_seconds()
    assert duration > 45
    assert not [e for e in errors if start <= e['hostAt'] <= end]
    source = read('ui/' + case + '-source')['result']['bookmarkObservation']['entries'][0]
    actual = {}
    windows = {}
    for s in S:
        rows = [x for x in observations if x['serial'] == s and start <= x['hostAt'] <= end]
        assert len(rows) >= 15
        assert len({x['processPidAtRead'] for x in rows}) == 1 and rows[0]['processPidAtRead']
        assert len({x['state']['serviceEpoch'] for x in rows}) == 1
        for x in rows:
            v = x['state']
            assert v['uiCount'] == 0 and v['roomConnected']
            assert v['automatic'] == ('LEADER' if s == S[0] else 'FOLLOWER')
            assert v['revision'] == accepted['accepted']['acceptedRevision']
        result = read('ui/' + case + '-accepted-native-' + s)
        d = result['result']['initialDestination']
        assert result['productionProcessUnchanged'] and result['result']['routeRequestCount'] == 0
        assert all(d[k] == source[k] for k in ('name', 'poiId', 'poiSubId', 'poiProvider'))
        before = read('ui/' + case + '-pending-native-' + s)
        after = read('ui/' + case + '-rejected-native-' + s)
        assert before['result']['initialDestination'] == after['result']['initialDestination']
        actual[s] = d
        windows[s] = {'observations': len(rows), 'processPid': rows[0]['processPidAtRead'], 'serviceEpoch': rows[0]['state']['serviceEpoch']}
    summary['cases'][case] = {'place': source['name'], 'pendingAndRejectedRequests': negative['routeCounts'], 'acceptedRequests': accepted['routeCounts'], 'nativeWindowSeconds': duration, 'continuousNativeWindow': windows, 'actualReadOnlyNativeDestinations': actual}

cold = read('cold-no-implicit-action')
for s in S:
    assert cold['oldStates'][s]['automatic'] in ('LEADER', 'FOLLOWER')
    assert cold['newStates'][s]['automatic'] == 'OFF'
    assert cold['newStates'][s]['confirmationKind'] is None
    assert cold['oldStates'][s]['serviceEpoch'] != cold['newStates'][s]['serviceEpoch']
demo, fonts = read('final-demo-ready'), read('font-scale-resume-original')
assert demo['restoredFontScales'] == fonts['original']
assert demo['draftReasonsCleared']
for s in S:
    assert demo['states'][s]['uiCount'] > 0 and demo['states'][s]['roomConnected']
    assert demo['states'][s]['proposal']['status'] == 'accepted'
    assert demo['keyboardFocus'][s]
summary['fontScalesRestored'] = demo['restoredFontScales']
summary['coldConsentRevocation'] = True
summary['collectorReadErrorsOutsideConfirmedWindows'] = len(errors)
summary['verdict'] = 'PASS'
(D / 'runtime-audit.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2) + '\n')
print(json.dumps(summary, ensure_ascii=False, indent=2))
