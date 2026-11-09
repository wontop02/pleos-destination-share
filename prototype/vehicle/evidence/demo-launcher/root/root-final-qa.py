from pathlib import Path
import runpy,json,subprocess,time,datetime,hashlib,re,sys,os
P=Path('/Users/wontop/Documents/ChatGPT/현대 모비스 해커톤/pleos-destination-share')
R=P/'.harness/worktrees/product-run-artifacts/demo-launcher-20261109'
W=P/'.harness/worktrees/issue-27-demo-launcher'
H=P/'.harness/demo'; ENTRY=P.parent/'Pleos 데모 실행.command'
Q=runpy.run_path(str(P/'.harness/worktrees/product-run-artifacts/voice-proposals-verify-20261109/qa-live.py'));U=Q['U'];SER=[Q['A'],Q['B']];PKG=Q['PKG'];PANE=[853,90,2535,1300]
os.umask(0o077)
def now():return datetime.datetime.now(datetime.timezone.utc).isoformat()
def sha(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()
def run(*a,**kw):return subprocess.run(a,capture_output=True,text=True,timeout=20,**kw)
def listeners():
 out={}
 for port in [4190,4192,5041,5570,5572]:
  p=run('/usr/sbin/lsof','-nP','-iTCP:'+str(port),'-sTCP:LISTEN','-t');out[str(port)]=sorted(set(map(int,p.stdout.split())))
 return out
def save(name,value):
 p=R/(name+'.json');assert not p.exists(),name
 p.write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n')
def journal(s,name):
 raw=U['private_text'](s,'no_backup/navigation-events.jsonl');assert raw.endswith('\n')
 for line in raw.splitlines():Q['safe'](json.loads(line))
 (R/(s+'-'+name+'.jsonl')).write_text(raw);return raw
def obs(s):
 return {'state':Q['state'](s),'appPid':Q['pid'](s),'bootId':U['shell'](s,'cat','/proc/sys/kernel/random/boot_id').decode().strip()}
def screen(s,name,l):
 raw,tree=U['dump'](s)
 assert not any(n.get('password')=='true' and n.get('text') or n.get('text','') in ['초대 정보','공유방 초대 정보'] for n in tree.iter('node'))
 hints={PKG+':id/voice_text':'예: 카페 들르는 거 어때?',PKG+':id/proposal_reason_input':'예: 여기서 함께 쉬어요'}
 assert not any(n.get('class')=='android.widget.EditText' and n.get('text') and n.get('text')!=hints.get(n.get('resource-id')) for n in tree.iter('node')),'Populated form privacy guard'
 (R/(s+'-'+name+'.xml')).write_text(raw);(R/(s+'-'+name+'.png')).write_bytes(U['adb'](s,'exec-out','screencap','-p'))
 a=l['activity_observation'](U['shell'](s,'dumpsys','activity','activities').decode());ui=l['ui_observation'](raw,PANE)
 assert a=={'count':1,'user_ok':True,'resumed':True},a
 assert ui,ui
 return {'activity':a,'publicUi':ui}
def valid(s,before,raw,name,l,cold):
 x=obs(s);x.update(screen(s,name,l));x['appop']=Q['appop'](s);assert 'running=true' not in x['appop'] and 'duration=-1' not in x['appop']
 st=x['state'];assert st['roomConnected'] and st['revision']==before['state']['revision'] and st['role']==before['state']['role'] and st['automatic']=='OFF'
 assert not cold or x['bootId']!=before['bootId']
 after=journal(s,name+'-after');assert after.startswith(raw),'journal prefix lost'
 new=[json.loads(z) for z in after[len(raw):].splitlines()];x['newRecords']=new;x['requestRouteOccurrences']=sum(z['event']=='requestRoute' for z in new);assert x['requestRouteOccurrences']==0
 reverse=U['adb'](s,'reverse','--list').decode();x['reverse']=reverse
 assert re.search(r'\btcp:4182 tcp:4192\b',reverse) and re.search(r'\btcp:4192 tcp:4192\b',reverse)
 return x
def execute(mode,name):
 l=runpy.run_path(str(W/'prototype/vehicle/demo/launcher.py'));beforelisten=listeners();v={'atUtc':now(),'scenario':mode,'head':run('git','-C',str(W),'rev-parse','HEAD').stdout.strip(),'sourceSha256':sha(W/'prototype/vehicle/demo/launcher.py'),'entrySha256':sha(ENTRY),'listenersBefore':beforelisten,'before':{},'after':{}}
 raw={};cl=False
 try:
  assert beforelisten['4190']==[40320] and beforelisten['4192']==[69453] and beforelisten['5041']
  for s in SER:
   if beforelisten[s.split('-')[1]]:
    v['before'][s]=obs(s);raw[s]=journal(s,name+'-before')
   else:
    # An earlier failed attempt may never have started B. Use its last cold fixture journal and preserved state.
    assert mode=='cold' and s==SER[1]
    old=json.loads((R/'cold3-entry-result.json').read_text())['after'][s]
    v['before'][s]={'state':old['state'],'appPid':old['appPid'],'bootId':old['bootId'],'observationSource':'Last recorded cold3 while live; B is already off before this attempt. This is a preserved data prefix, not a fresh stopped-device read.'};raw[s]=(R/(s+'-cold3-after.jsonl')).read_text()
  if mode=='cold':
   for s in SER:
    if beforelisten[s.split('-')[1]]:v.setdefault('stopAcks',{})[s]=U['adb'](s,'emu','kill').decode().strip()
   for _ in range(120):
    if all(not listeners()[p] for p in ['5570','5572']):break
    time.sleep(.25)
   assert all(not listeners()[p] for p in ['5570','5572'])
   p=subprocess.run([U['ADB'],'-P','5041','kill-server'],env=U['ENV'],capture_output=True,timeout=10);assert p.returncode==0
   assert not listeners()['5041'];v['coldAtUtc']=now();cl=True
   logs=set((H/'logs').glob('*.jsonl'));t=time.monotonic();p=run('/usr/bin/open',str(ENTRY));v['openExit']=p.returncode;assert p.returncode==0
   events=[];end=t+650
   while time.monotonic()<end:
    newlogs=set((H/'logs').glob('*.jsonl'))-logs
    if newlogs:
     assert len(newlogs)==1;log=newlogs.pop();events=[json.loads(z) for z in log.read_text().splitlines()];
     if events and events[-1]['event'] in ['ready','failed']:break
    time.sleep(.5)
   v['launcherLog']=log.name;v['launcherEvents']=events;v['secondsToTerminal']=time.monotonic()-t
   assert events[-1]['event']=='ready','launcher failed '+str(events[-1])
  elif mode=='concurrent':
   page=screen(SER[1],name+'-page-before',l)['publicUi']['page']
   if page!='compose':
    assert page=='home';Q['press'](SER[1],Q['node'](SER[1],'change_destination'));time.sleep(.4)
   assert screen(SER[1],name+'-compose-before',l)['publicUi']['page']=='compose'
   # Remove the approved missing mapping deliberately; launcher must repair, never manual repair disguised as success.
   for s in SER:U['adb'](s,'reverse','--remove','tcp:4182')
   procs=[];files=[];t=time.monotonic()
   for i,cwd in enumerate(['/tmp',str(P.parent)]):
    out=(R/(name+'-'+str(i)+'-stdout.txt')).open('w');err=(R/(name+'-'+str(i)+'-stderr.txt')).open('w');files.extend([out,err]);procs.append(subprocess.Popen([str(ENTRY)],cwd=cwd,stdout=out,stderr=err))
   exits=[p.wait(timeout=180) for p in procs]
   for f in files:f.close()
   v['exitCodes']=exits;v['secondsToTerminal']=time.monotonic()-t;assert sorted(exits)==[0,3],exits
  else:raise ValueError(mode)
  for s in SER:v['after'][s]=valid(s,v['before'][s],raw[s],name,l,cl)
  v['listenersAfter']=listeners();assert v['listenersAfter']['4190']==[40320] and v['listenersAfter']['4192']==[69453]
  if mode=='concurrent':
   assert v['listenersAfter']==beforelisten
   for s in SER:assert v['after'][s]['appPid']==v['before'][s]['appPid']
   assert v['after'][SER[1]]['publicUi']['page']=='compose'
  else:
   assert v['listenersAfter']['5041']!=beforelisten['5041']
   for port in ['5570','5572']:assert v['listenersAfter'][port] and v['listenersAfter'][port]!=beforelisten[port]
  assert sha(W/'prototype/vehicle/demo/launcher.py')==v['sourceSha256']
  v['pass']=True
 except Exception as e:
  v['pass']=False;v['errorClass']=type(e).__name__;v['error']=str(e)[:300];raise
 finally:
  v['finishedAtUtc']=now();save(name+'-result',v);print(json.dumps({k:v.get(k) for k in ['scenario','head','sourceSha256','pass','error','secondsToTerminal','listenersAfter']},ensure_ascii=False))
if __name__=='__main__':execute(sys.argv[1],sys.argv[2])
