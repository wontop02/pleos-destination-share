# Long-name/font1.3 proof was captured before final routing; do not reconfigure native fonts during positive cases.
import runpy,json,re
u=runpy.run_path('ui-tools.py');q=u['q'];A,B=u['A'],u['B'];serials=(A,B)
# The only modified setting is font_scale for the two approved virtual user-10s.
original = json.loads((q.D / 'font-scale-resume-original.json').read_text())['original']
cp = u['checkpoint']()
restored = {}
focus = {}
drafts = {}
for s in serials:
    old = original[s]
    current = q.U['shell'](s, 'settings', '--user', '10', 'get', 'system', 'font_scale').decode().strip()
    if current == old:
        pass
    elif old == 'null':
        q.U['shell'](s, 'settings', '--user', '10', 'delete', 'system', 'font_scale')
    else:
        q.U['shell'](s, 'settings', '--user', '10', 'put', 'system', 'font_scale', old)
    restored[s] = q.U['shell'](s, 'settings', '--user', '10', 'get', 'system', 'font_scale').decode().strip()
    assert restored[s] == old
    q.mark('restoreOwnedFontScale', serial=s, original=old)
    u['open_app'](s)
    u['tap'](s, 'change_destination')
    value = u['by_id'](s, 'proposal_reason_input', True).get('text', '')
    if value not in ('', '예: 여기서 함께 쉬어요'):
        u['field'](s, 'proposal_reason_input', '')
        value = u['by_id'](s, 'proposal_reason_input', True).get('text', '')
    assert value in ('', '예: 여기서 함께 쉬어요')
    drafts[s] = {'readback': value, 'emptyOrKnownHint': True, 'noTestReasonRemaining': True}
    u['tap'](s, 'nav_home')
    q.wait(lambda: u['state'](s)['connection'] == 'CONNECTED' and u['state'](s)['automatic'] == ('LEADER' if s == A else 'FOLLOWER'), 30)
    q.wait(lambda: '확인했어요' in u['by_id'](s, 'destination_status').get('text', ''), 20)
    u['capture'](s, 'final-home-' + s)
    q.U['shell'](s, 'input', 'keyevent', '61')
    _, tree = q.U['dump'](s)
    focused = [n for n in tree.iter('node') if n.get('focused') == 'true' and n.get('resource-id', '').startswith('ai.pleos.destinations.vehicle:id/')]
    assert focused
    focus[s] = [{'resource': n.get('resource-id'), 'label': n.get('text'), 'bounds': n.get('bounds')} for n in focused]
    u['capture'](s, 'keyboard-focus-' + s)
rows = u['traces'](cp)
assert u['counts'](rows) == {A: 0, B: 0}
u['save']('final-demo-ready', {'sourceHead': '3502929d569262253440fa3241ff90cffe57c859', 'checkpoints': cp, 'restoredFontScales': restored, 'keyboardFocus': focus, 'states': {s: u['state'](s) for s in serials}, 'routeCounts': u['counts'](rows), 'trace': rows, 'draftReasonsCleared': True, 'draftReasonReadbacks': drafts})
print('Long public place/fontScale1.3 and keyboard focus PASS; original fonts restored, homes ready,0 extra routes', flush=True)
