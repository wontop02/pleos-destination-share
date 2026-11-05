import qa, time, re, json, hashlib, sys
prefix = sys.argv[1] if len(sys.argv)>1 else "final"
assert prefix in ("final", "final3")

A, B = 'emulator-5570', 'emulator-5572'
for serial in (A, B):
    qa.show(serial)
    for _ in range(7): qa.U['scroll_bottom'](serial)
    if '이 기기 연결 지우기' in qa.texts(serial): qa.tap(serial, '이 기기 연결 지우기')
    for _ in range(3): qa.U['scroll_bottom'](serial)
    qa.find(serial, '로컬 서버 주소')
    qa.U['field'](serial, '로컬 서버 주소', 'http://127.0.0.1:4186')
    qa.U['field'](serial, '차량 이름', 'Vehicle-A' if serial == A else 'Vehicle-B')

qa.findtap(A, '대표 차량으로 공유방 만들기')
qa.findtap(A, '초대 정보 보기')
values = qa.texts(A)
invitation = next(v for v in values if '공유방 ID\n' in v)
match = re.fullmatch(r'공유방 ID\n([^\n]+)\n\n초대 비밀\n([^\n]+)\n\n신뢰하는 참여자에게만 전달해 주세요\.', invitation)
assert match
room, secret = match.groups()
qa.tap(A, '닫기')
qa.find(B, '참여할 공유방 ID'); qa.U['field'](B, '참여할 공유방 ID', room)
qa.find(B, '초대 비밀'); qa.U['field'](B, '초대 비밀', secret)
qa.findtap(B, '공유방 참여')
secret = invitation = values = match = None
qa.mark('joinedFreshRoom', roomId=room, port=4186, purpose=prefix+'-runtime')
for _ in range(3): qa.U['scroll_top'](A)
qa.findtap(A, '현재 순정 목적지 공유')
deadline = time.monotonic() + 15
while time.monotonic() < deadline:
    if all(qa.state(serial)['revision'] == 1 and qa.state(serial)['roomConnected'] for serial in (A, B)): break
    time.sleep(.5)
states = {}
for label, serial in [('A', A), ('B', B)]:
    value = qa.snapshot(serial, prefix+'-room-baseline-' + label)
    assert value['revision'] == 1 and value['roomConnected'] and value['automatic'] == 'OFF'
    assert value['proposal'] is None
    states[label] = value
result = {'roomId': room, 'port': 4186, 'sourceScriptSha256': hashlib.sha256(qa.Path(__file__).read_bytes()).hexdigest(),
          'invitationTransferredInMemoryOnly': True, 'states': states}
(qa.D / (prefix+'-fresh-room.json')).write_text(json.dumps(result, ensure_ascii=False, indent=2))
print(json.dumps({'freshRoom': room, 'bothConnected': True, 'revision': 1, 'automaticEach': 'OFF'}), flush=True)
