import qa, time, json, hashlib, sys
prefix = sys.argv[1] if len(sys.argv)>1 else "final"
assert prefix in ("final", "final3", "final4")

serials = {'A': 'emulator-5570', 'B': 'emulator-5572'}
qa.mark('enableAutomaticBegin', scriptSha256=hashlib.sha256(qa.Path(__file__).read_bytes()).hexdigest())
before = {}
for label, serial in serials.items():
    qa.show(serial)
    for _ in range(6): qa.U['scroll_top'](serial)
    before[label] = qa.snapshot(serial, prefix + '-automatic-before-' + label)
    qa.U['scroll_bottom'](serial)
    qa.findtap(serial, '동행 시작 · 자동 공유' if label == 'A' else '자동 따르기 시작')
time.sleep(2)
states = {}
for label, serial in serials.items():
    states[label] = qa.snapshot(serial, prefix + '-automatic-enabled-' + label)
    assert states[label]['automatic'] == ('LEADER' if label == 'A' else 'FOLLOWER')
    assert states[label]['roomConnected']
    assert states[label]['revision'] == before[label]['revision']
    qa.U['capture'](serial, prefix + '-automatic-enabled-' + label + '-ui')
(qa.D / (prefix + '-automatic-enabled.json')).write_text(json.dumps({'before': before, 'after': states}, ensure_ascii=False, indent=2))
qa.mark('enableAutomaticComplete', revision=states['A']['revision'])
print('A LEADER and B FOLLOWER enabled; shared revision unchanged', flush=True)
