from pathlib import Path
import qa, json, hashlib, datetime

cases = ['final3-reject', 'final3-expire', 'final3-accept-off', 'final3-reverse-off', 'final3-accept-auto', 'final3-stop-reconnect']
deployment = json.loads((qa.D / 'deployment-freeze-3.json').read_text())
qa_run = json.loads((qa.D / 'final3-qa-result.json').read_text())
assert qa_run['allStepsPassed'] and qa_run['head'] == deployment['head']
actions = [json.loads(x) for x in (qa.D / 'actions.jsonl').read_text().splitlines()]
out = qa.D / 'final3-summary'
out.mkdir(exist_ok=True)
results, trace = [], []
for case in cases:
    data = json.loads((qa.D / (case + '-result.json')).read_text())
    assert data['pass']
    for label, vehicle in data['vehicles'].items():
        serial = vehicle['serial']
        source = qa.D / (serial + '-journal-freeze3.jsonl')
        rows = [json.loads(x) for x in source.read_text().splitlines()]
        interval = [r for r in rows if data['startedAtEpochMs'] <= r['record']['at'] <= data['endedAtEpochMs']]
        assert interval
        requests = [r for r in interval if r['record']['event'] == 'requestRoute']
        assert len(requests) == vehicle['requestRouteCount']
        if 'accept' in case or 'reverse' in case:
            assert len(requests) == 1
            matches = [r for r in interval if r['record']['event'] == 'destinationMatch' and r['record']['matched'] is True]
            assert matches
            assert vehicle['confirmedBackground']['state']['uiCount'] == 0
            assert vehicle['ownAppReentriesAfterAccept'] == 0
            oracle = vehicle['actualNative']
            assert oracle['mode'] == 'query' and oracle['productionProcessUnchanged']
            assert oracle['command']['exitCode'] == 0
            accept_marker = next(a for a in actions if a.get('case') == case and a['event'] == 'acceptButtonBeforeTap')
            end = datetime.datetime.fromtimestamp(data['endedAtEpochMs'] / 1000, datetime.timezone.utc).isoformat()
            entries = [a for a in actions if a['event'] == 'explicitOwnAppEntry' and a.get('serial') == serial
                       and accept_marker['hostAt'] <= a['hostAt'] <= end]
            assert not entries
        else:
            assert not requests
        trace.extend({'case': case, 'vehicle': label, **row} for row in interval)
    results.append(data)
(out / 'case-results.json').write_text(json.dumps({'deploymentHead': deployment['head'], 'cases': results}, ensure_ascii=False, indent=2))
(out / 'runtime-journal.jsonl').write_text(''.join(json.dumps(row, ensure_ascii=False) + '\n' for row in trace))
summary = {'base': deployment['base'], 'head': deployment['head'],
           'serverSha256': deployment['backend']['serverSha256'],
           'serverPid': deployment['backend']['pid'], 'port': 4186,
           'installed': deployment['installed'], 'sourceInputsVerified': deployment['sourceInputsVerified'],
           'casesPassed': cases, 'caseCount': len(cases), 'runtimeTraceRows': len(trace),
           'claims': {'twoApprovedVirtualVehiclesOnly': True, 'eachAcceptedRequestCount': 1,
                      'eachNegativeRequestCount': 0, 'ownAppReentryAfterAccept': 0,
                      'strictActualDestinationConfirmed': True, 'mapRenderingFixed': False,
                      'routingCompletedClaimed': False, 'physicalVehicleClaimed': False}}
(out / 'runtime-report.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2))
paths = [qa.D / 'deployment-freeze-3.json', qa.D / 'deployment-freeze-3-commands.json',
         qa.D / 'final3-scenarios-commands.jsonl', qa.D / 'final3-qa-result.json',
         qa.D / 'final3-fresh-room.json', qa.D / 'final3-automatic-enabled.json',
         qa.D / 'actions.jsonl', *out.iterdir()]
manifest = {str(p.relative_to(qa.D)): hashlib.sha256(p.read_bytes()).hexdigest() for p in paths if p.is_file() and p.name != 'artifact-hashes.json'}
(out / 'artifact-hashes.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2))
print(json.dumps({'pass': True, 'cases': cases, 'traceRows': len(trace), 'head': deployment['head']}, ensure_ascii=False))
