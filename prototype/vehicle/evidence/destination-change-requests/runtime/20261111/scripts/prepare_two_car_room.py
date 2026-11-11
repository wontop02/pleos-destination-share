import json,re,sys,time,urllib.request
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parent));import runtime_tools as t
a='emulator-5570';b='emulator-5572'
t.tap_id(a,'create_room_entry')
t.field_ascii(a,'vehicle_name_input','Car-A')
t.tap(a,t.reveal_id(a,'server_details'));t.reveal_id(a,'server_address_input');t.field_ascii(a,'server_address_input','http://127.0.0.1:4196');t.scroll_top(a)
t.tap_id(a,'submit_room');time.sleep(1)
assert any(n.get('resource-id')==t.PACKAGE+':id/connection_status' and '연결됨' in n.get('text','') for n in t.nodes(a))
t.capture(a,'01-A-room-ready')
t.tap_id(a,'nav_settings');t.tap(a,t.reveal_id(a,'invitation'))
content=[n.get('text','') for n in t.nodes(a) if n.get('text','').startswith('동행방 ID\n')]
assert len(content)==1
parts=content[0].split('\n');room=parts[1];secret=parts[4]
assert re.fullmatch('[a-f0-9]{16}',room) or re.fullmatch('[a-zA-Z0-9_-]{8,80}',room)
assert re.fullmatch('[a-zA-Z0-9_-]{16,200}',secret)
t.tap_text(a,'닫기');t.tap_id(a,'nav_home')
t.tap_id(b,'join_room_entry');t.field_ascii(b,'vehicle_name_input','Car-B')
t.field_ascii(b,'room_id_input',room,confidential=True)
t.field_ascii(b,'invitation_input',secret,confidential=True)
t.tap(b,t.reveal_id(b,'server_details'));t.reveal_id(b,'server_address_input');t.field_ascii(b,'server_address_input','http://127.0.0.1:4196');t.scroll_top(b)
t.tap_id(b,'submit_room');time.sleep(1)
assert any(n.get('resource-id')==t.PACKAGE+':id/connection_status' and '연결됨' in n.get('text','') for n in t.nodes(b))
t.capture(b,'02-B-room-ready')
request=urllib.request.Request('http://127.0.0.1:4196/api/rooms/'+room+'/members',data=json.dumps({'name':'Observer-C'}).encode(),headers={'Content-Type':'application/json','Authorization':'Bearer '+secret},method='POST')
with urllib.request.urlopen(request,timeout=10) as r: observer=json.load(r)
private=t.ROOT/'runtime-test-room.private.json';private.write_text(json.dumps({'roomId':room,'inviteSecret':secret,'observer':observer},ensure_ascii=False));private.chmod(0o600)
print(json.dumps({'two_actual_cars_joined':True,'third_observer_joined':True,'credential_output':False}))
