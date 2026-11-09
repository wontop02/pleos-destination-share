from pathlib import Path
import runpy,json,subprocess,shlex,re,time,datetime,hashlib
D=Path(__file__).resolve().parent
OLD=D.parent/'voice-proposals-20261108/root'
U=runpy.run_path(str(OLD/'root-ui-tools.py'))
PKG='ai.pleos.destinations.vehicle'
A,B='emulator-5570','emulator-5572'
CORE=U['CORE_KEYS']
BASE={'at','event','serviceEpoch','roomEpoch','revision','role','automatic','applying','connection','roomConnected','uiCount','confirmedAtElapsed','routingCompletedObserved'}
EXT=BASE|{'proposalLease','proposalBusy','proposalMessage','proposalPublicationHeld','proposal'}
NEW=EXT|{'acceptedProposalId','acceptedProposalVersion','acceptedProposalRevision'}
POI=NEW|{'confirmationKind','sharedKind'}
PKEYS={'id','version','status','candidateName','candidatePoiId','proposerId','baseRevision','createdAt','expiresAt','acceptedRevision','responses'}
PUBLIC={'','42dot','소프트웨어드림센터','가든서비스','청계산담백미','HD현대오일뱅크 세종주유소'}
def now():return datetime.datetime.now(datetime.timezone.utc).isoformat()
def safe(value):
 assert set(value) in [CORE,BASE,EXT,NEW,POI],'Unexpected journal schema'
 assert not re.search(r'\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\b',json.dumps(value))
 if value.get('proposal'):
  assert set(value['proposal'])==PKEYS
  assert all(set(v)=={'memberId','response'} for v in value['proposal']['responses'])
  if value['proposal']['candidateName'] not in PUBLIC:
   value=json.loads(json.dumps(value));value['proposal']['candidateName']='<outside public test allowlist>';value['proposal']['candidatePoiId']='<withheld>'
 return value
def state(s):return safe(json.loads(U['private_text'](s,'no_backup/session-state.json')))
def pid(s):
 p=subprocess.run([U['ADB'],'-P','5041','-s',s,'shell','pidof',PKG],env=U['ENV'],capture_output=True,text=True,timeout=5)
 return p.stdout.strip() if p.returncode==0 else None
def mark(event,**data):
 value={'hostAt':now(),'event':event,**data}
 with (D/'actions.jsonl').open('a') as f:f.write(json.dumps(value,ensure_ascii=False)+'\n')
 return value
def save(name,value):
 p=D/(name+'.json');assert not p.exists(),'Evidence file exists'
 p.write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n');return str(p)
def wait(predicate,seconds=20):
 end=time.monotonic()+seconds
 while time.monotonic()<end:
  if predicate():return
  time.sleep(.25)
 raise RuntimeError('Expected state not reached within'+str(seconds)+'seconds')
def tree(s):return U['dump'](s)[1]
def node(s,ident,scroll=False):
 target=PKG+':id/'+ident
 for direction in ([None,'down','up'] if scroll else [None]):
  for attempt in range(6 if direction else 1):
   nodes=list(tree(s).iter('node'));hits=[n for n in nodes if n.get('resource-id')==target]
   if len(hits)==1:return hits[0]
   if direction:U['scroll_bottom' if direction=='down' else 'scroll_top'](s)
 raise RuntimeError('Visible public product control unavailable:'+ident)
def press(s,n):
 assert n.get('enabled')=='true'
 x1,y1,x2,y2=map(int,re.findall(r'\d+',n.get('bounds')))
 assert 853<=x1<x2<=2535 and 90<=y1<y2<=1300,'Outside owned app pane'
 U['shell'](s,'input','tap',str((x1+x2)//2),str((y1+y2)//2))
def tap(s,ident,scroll=False):
 n=node(s,ident,scroll);assert n.get('clickable')=='true';press(s,n);mark('publicUiTap',serial=s,resource=ident);time.sleep(.25)
def label(s,text):
 hits=[n for n in tree(s).iter('node') if n.get('text')==text and n.get('clickable')=='true']
 assert len(hits)==1,'Expected dialog action unavailable';press(s,hits[0]);mark('publicUiDialog',serial=s,action=text)
def open_app(s):
 U['shell'](s,'am','start','--user','10','-W','--windowingMode','6','--display','0','-a','android.intent.action.MAIN','-c','android.intent.category.LAUNCHER','-f','0x10200000','-n',PKG+'/.MainActivity')
 wait(lambda:state(s)['uiCount']>0,20);mark('explicitOwnAppEntry',serial=s)
def compose(s):
 open_app(s)
 ids={n.get('resource-id') for n in tree(s).iter('node')}
 if PKG+':id/voice_start' in ids:
  U['scroll_top'](s);return
 if PKG+':id/nav_home' in ids:tap(s,'nav_home')
 tap(s,'change_destination');U['scroll_top'](s);U['scroll_top'](s);node(s,'voice_start',True)
def instrument(s,component,args,timeout=30):
 before=pid(s);argv=['am','instrument','--user','10','-w','-r']
 for k,v in args.items():argv+=['-e',k,v]
 argv+=['ai.pleos.destinations.driver/.'+component]
 command=[U['ADB'],'-P','5041','-s',s,'shell',shlex.join(argv)]
 started=time.monotonic();result=subprocess.run(command,env=U['ENV'],capture_output=True,text=True,timeout=timeout)
 values={}
 for line in result.stdout.splitlines():
  if line.startswith('INSTRUMENTATION_RESULT: '):
   k,_,v=line.removeprefix('INSTRUMENTATION_RESULT: ').partition('=');values[k]=v
 assert not re.search(r'\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\b',json.dumps(values))
 return {'atUtc':now(),'serial':s,'component':component,'arguments':args,'exit':result.returncode,'seconds':time.monotonic()-started,'result':values,'productionPidBefore':before,'productionPidAfter':pid(s)}
def edit(s,key,name,field='voice_text'):
 value=instrument(s,'VehicleUiTextProbe',{'field':field,'textKey':key})
 save(name,value)
 assert value['exit']==0 and value['result'].get('success')=='true','UI fixture edit failed (not speech)'
 assert value['productionPidBefore']==value['productionPidAfter']
 return value
def action(s,resource,name,click=True):
 value=instrument(s,'VehicleUiActionProbe',{'resource':resource,'operation':'click' if click else 'inspect'})
 save(name,value)
 assert value['exit']==0 and value['result'].get('inputSource')=='public_ui_button'
 if click:assert value['result'].get('actionAccepted')=='true'
 return value
def capture(s,name,publicFields=False):
 raw,root=U['dump'](s);nodes=list(root.iter('node'))
 assert not any(n.get('password')=='true' and n.get('text') for n in nodes)
 assert not any(n.get('text','')=='초대 정보' for n in nodes),'Invitation modal capture refused'
 if not publicFields:
  assert not any(n.get('class')=='android.widget.EditText' and n.get('text') for n in nodes),'Populated form capture requires explicit public-fixture attestation'
 (D/'ui'/(name+'.xml')).write_text(raw)
 (D/'ui'/(name+'.png')).write_bytes(U['adb'](s,'exec-out','screencap','-p'))
 return str(D/'ui'/(name+'.png'))
def appop(s):return U['shell'](s,'cmd','appops','get','--user','10',PKG,'RECORD_AUDIO').decode()
def case_checkpoint():
 barrier=now();wait(lambda:all(_observed(s,barrier) for s in [A,B]),10)
 value={}
 for s in [A,B]:
  raw=(D/(s+'-journal.jsonl')).read_text();assert not raw or raw.endswith('\n')
  value[s]={'serial':s,'lineOffset':len(raw.splitlines()),'hostAt':now(),'state':state(s)}
 return value
def _observed(s,barrier):
 p=D/'observations.jsonl'
 if not p.exists():return False
 for line in reversed(p.read_text().splitlines()[-20:]):
  v=json.loads(line)
  if v['serial']==s and v['hostAt']>=barrier:return True
 return False
def since(check):
 barrier=now();wait(lambda:_observed(check['serial'],barrier),10)
 raw=(D/(check['serial']+'-journal.jsonl')).read_text();lines=raw.splitlines()
 if raw and not raw.endswith('\n'):lines=lines[:-1]
 return [json.loads(x) for x in lines[check['lineOffset']:]]

def prepare_cafe(prefix):
 s=A;compose(s);tap(s,'select_saved_place',True)
 wait(lambda:any(n.get('resource-id')==PKG+':id/saved_place_choice' for n in tree(s).iter('node')),20)
 choices=[n for n in tree(s).iter('node') if n.get('resource-id')==PKG+':id/saved_place_choice' and n.get('text','').startswith('가든서비스\n')]
 assert len(choices)==1
 actual=choices[0].get('text');press(s,choices[0]);mark('actualSavedCafeSelected',serial=s,case=prefix)
 U['scroll_top'](s);node(s,'voice_text',True);edit(s,'saved-cafe',prefix+'-edit-text');tap(s,'voice_analyze')
 tap(s,'voice_candidates')
 candidates=[n for n in tree(s).iter('node') if n.get('resource-id')==PKG+':id/voice_candidate_choice']
 assert len(candidates)==1 and candidates[0].get('text')==actual
 capture(s,prefix+'-candidate',True);press(s,candidates[0]);mark('explicitVoiceCafeCandidateSelected',serial=s,case=prefix)
 node(s,'proposal_reason_input',True);edit(s,'cafe',prefix+'-edit-reason',field='proposal_reason_input')
 assert node(s,'send_proposal',True).get('enabled')=='true'
 capture(s,prefix+'-reviewed',True)
 return actual
