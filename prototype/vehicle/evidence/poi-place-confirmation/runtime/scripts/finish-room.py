import qa as q
import json,re
A,B='emulator-5570','emulator-5572'
q.findtap(A,'대표 차량으로 공유방 만들기')
q.wait(lambda:q.state(A)['roomConnected'] and q.state(A)['role']=='host')
q.findtap(A,'초대 정보 보기')
values=q.texts(A)
invitation=next(v.replace('\\n','\n') for v in values if v.startswith('공유방 ID'))
m=re.match(r'^공유방 ID\n([^\n]+)\n\n초대 비밀\n([^\n]+)',invitation)
assert m,'Unexpected invitation schema; no credential output'
room,secret=m.groups()
assert re.fullmatch(r'[A-Za-z0-9_-]{8,64}',room) and re.fullmatch(r'[A-Za-z0-9_-]{16,128}',secret)
q.tap(A,'닫기')
q.find(B,'참여할 공유방 ID');q.U['field'](B,'참여할 공유방 ID',room)
q.find(B,'초대 비밀');q.U['field'](B,'초대 비밀',secret)
q.findtap(B,'공유방 참여')
invitation=values=m=secret=None
q.wait(lambda:all(q.state(s)['roomConnected'] for s in (A,B)))
q.mark('freshRoomJoined',roomId=room,port=4188,invitationTransferredInMemoryOnly=True)
for s in (A,B):
 if q.state(s)['connection']!='CONNECTED':q.findtap(s,'순정 내비 연결 / 다시 연결')
q.wait(lambda:all(q.state(s)['connection']=='CONNECTED' for s in (A,B)))
result={'roomId':room,'port':4188,'invitationTransferredInMemoryOnly':True,'states':{s:q.snapshot(s,'fresh-room-'+s) for s in (A,B)}}
(q.D/'fresh-room.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
print('Fresh issue21 room joined and SDK connected on both cars',flush=True)
