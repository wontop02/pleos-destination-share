import runpy,json,re,time
u=runpy.run_path('ui-tools.py');q=u['q'];A,B=u['A'],u['B']
def tap(s,n,scroll=False):u['tap'](s,n,scroll)
def visible(s,n):return any(x.get('resource-id')=='ai.pleos.destinations.vehicle:id/'+n for x in u['tree'](s).iter('node'))
for serial in (A,B):
 u['open_app'](serial)
 q.wait(lambda:u['state'](serial)['roomConnected'] and u['state'](serial)['connection']=='CONNECTED',25)
initial=u['checkpoint']();u['save']('room-setup-checkpoints',initial)
for s,name in ((A,'Vehicle-A'),(B,'Vehicle-B')):
 u['open_app'](s);tap(s,'nav_settings')
 if s==A:u['capture'](s,'settings-before-setup-A')
 tap(s,'forget_room',True);q.tap(s,'연결 지우기')
 q.wait(lambda:not u['state'](s)['roomConnected'])
 tap(s,'nav_home');q.wait(lambda:visible(s,'create_room_entry'))
 u['capture'](s,'welcome-'+s)
 tap(s,'create_room_entry' if s==A else 'join_room_entry')
 u['capture'](s,'room-form-'+s)
 u['field'](s,'vehicle_name_input',name)
 tap(s,'server_details',True);u['field'](s,'server_address_input','http://127.0.0.1:4190')
 tap(s,'server_details',True)
 print(name+' new simple room form ready',flush=True)
tap(A,'submit_room',True)
q.wait(lambda:u['state'](A)['roomConnected'] and u['state'](A)['role']=='host' and u['state'](A)['revision']==0,25)
tap(A,'nav_settings');tap(A,'invitation',True)
values=q.texts(A);text=next(v.replace('\\n','\n') for v in values if v.startswith('동행방 ID'))
m=re.match(r'^동행방 ID\n([^\n]+)\n\n초대 비밀\n([^\n]+)',text);assert m,'Unexpected invitation schema; no secret output'
room,secret=m.groups();assert re.fullmatch(r'[A-Za-z0-9_-]{8,64}',room) and re.fullmatch(r'[A-Za-z0-9_-]{16,128}',secret)
refused=False
try:u['capture'](A,'must-refuse-invitation')
except RuntimeError as e:refused='invitation' in str(e)
assert refused and not (q.D/'ui/must-refuse-invitation.xml.txt').exists()
q.tap(A,'닫기');tap(A,'nav_home')
u['field'](B,'room_id_input',room);u['field'](B,'invitation_input',secret)
tap(B,'submit_room',True)
values=text=m=secret=None
q.wait(lambda:all(u['state'](s)['roomConnected'] and u['state'](s)['connection']=='CONNECTED' and u['state'](s)['revision']==0 for s in (A,B)),30)
for s in (A,B):
 assert u['state'](s)['automatic']=='OFF'
 u['capture'](s,'fresh-empty-home-'+s)
 visibleTexts=q.texts(s)
 assert not any(re.search(r'SDK|POI|위도|경도|버전|로컬 서버',x) for x in visibleTexts)
traces=u['traces'](initial);assert u['counts'](traces)=={A:0,B:0}
u['save']('fresh-room',{'roomId':room,'port':4190,'sourceHead':json.loads((q.D/'installed.json').read_text())['sourceHead'],'invitationTransferredInMemoryOnly':True,'invitationCaptureRefused':refused,'states':{s:u['state'](s) for s in (A,B)},'routeCounts':u['counts'](traces),'trace':traces})
print('Simple create/join PASS; both same fresh room, autoOFF,0 routes',flush=True)
