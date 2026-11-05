import qa, json, datetime

case = json.loads((qa.D / 'final3-stop-reconnect-result.json').read_text())
assert case['pass']
serial = 'emulator-5572'
stopped = json.loads((qa.D / 'ui/final3-stop-reconnect-stopped-B-state.json').read_text())
reconnected = json.loads((qa.D / 'ui/final3-stop-reconnect-reconnected-B-state.json').read_text())
before = case['vehicles']['B']['before']
after = case['vehicles']['B']['after']
epochs = [v['serviceEpoch'] for v in [before, stopped, reconnected, after]]
assert len(set(epochs)) == 1
assert stopped['uiCount'] == reconnected['uiCount'] == 1 and after['uiCount'] == 0
assert before['acceptedProposalId'] == after['acceptedProposalId']
assert before['acceptedProposalRevision'] == after['acceptedProposalRevision']
rows = [json.loads(x) for x in (qa.D / (serial + '-journal-freeze3.jsonl')).read_text().splitlines()]
interval = [r for r in rows if case['startedAtEpochMs'] <= r['record']['at'] <= case['endedAtEpochMs']]
pids = {r['processPidAtRead'] for r in interval}
assert pids == {case['actualNativeB']['productionPidBefore']}
assert not [r for r in interval if r['record']['event'] == 'requestRoute']
actions = [json.loads(x) for x in (qa.D / 'actions.jsonl').read_text().splitlines()]
reverse = json.loads((qa.D / 'final3-reverse-off-result.json').read_text())
auto = json.loads((qa.D / 'final3-accept-auto-result.json').read_text())
earliest = datetime.datetime.fromtimestamp(reverse['endedAtEpochMs'] / 1000, datetime.timezone.utc).isoformat()
enable = next(a for a in actions if a['event'] == 'enableAutomaticBegin' and a['hostAt'] >= earliest)
begin = int(datetime.datetime.fromisoformat(enable['hostAt']).timestamp() * 1000)
counts = {}
for label, device in [('A', 'emulator-5570'), ('B', 'emulator-5572')]:
    data = [json.loads(x) for x in (qa.D / (device + '-journal-freeze3.jsonl')).read_text().splitlines()]
    count = sum(1 for r in data if begin <= r['record']['at'] < auto['startedAtEpochMs']
                and r['record']['event'] == 'requestRoute')
    assert count == 0
    counts[label] = count
result = {'pass': True, 'sameLiveServiceEpochThroughoutStopReconnect': epochs[0],
          'sameProductionPidThroughout': next(iter(pids)), 'foregroundUiCountDuringStopReconnect': 1,
          'backgroundUiCountAfterRestart': 0, 'extraRouteRequestsAfterStopReconnect': 0,
          'initialGenericAutomaticStartHistoricalRequests': counts,
          'acceptedProposalId': before['acceptedProposalId'], 'revision': before['revision']}
(qa.D / 'final3-historical-boundary-audit.json').write_text(json.dumps(result, indent=2))
print(json.dumps(result), flush=True)
