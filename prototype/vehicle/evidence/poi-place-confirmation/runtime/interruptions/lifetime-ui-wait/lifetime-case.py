import cases as c
import qa as q
import json,time,hashlib,subprocess
A,B=c.A,c.B;S=c.S
original=q.state
def live_state(s):
 p=subprocess.run([q.U['ADB'],'-P','5041','-s',s,'shell','pidof ai.pleos.destinations.vehicle'],env=q.U['ENV'],capture_output=True,text=True,timeout=10)
 if p.returncode or not p.stdout.strip():raise RuntimeError('Owned app process absent; stale state refused')
 return original(s)
q.state=live_state
(q.D/'lifetime-inputs.json').write_text(json.dumps({'sha256':{n:hashlib.sha256((q.D/n).read_bytes()).hexdigest() for n in ('lifetime-case.py','cases.py','qa.py','root-ui-tools.py','probe.py')}},indent=2)+'\n')
assert all(q.state(s)['sharedKind']=='pleos_place' for s in S)
revision=q.state(A)['revision'];assert q.state(B)['revision']==revision
checkpoint=c.checks();c.save('lifetime-checkpoints',checkpoint)
for s in S:
 q.show(s);q.findtap(s,'동행·제안 중지')
q.wait(lambda:all(q.state(s)['automatic']=='OFF' and q.state(s)['confirmationKind'] is None for s in S))
stopped={s:q.state(s) for s in S}
for s in S:
 q.findtap(s,'순정 내비 연결 / 다시 연결')
 q.findtap(s,'공유방 다시 연결')
q.wait(lambda:all(q.state(s)['connection']=='CONNECTED' and q.state(s)['roomConnected'] and q.state(s)['revision']==revision for s in S),30)
for s,label in ((A,'동행 시작 · 자동 공유'),(B,'자동 따르기 시작')):q.findtap(s,label)
q.wait(lambda:q.state(A)['automatic']=='LEADER' and q.state(B)['automatic']=='FOLLOWER')
for s in S:c.native(s)
start=time.monotonic()
while time.monotonic()-start<55:
 assert all(q.state(s)['uiCount']==0 and q.state(s)['revision']==revision for s in S)
 time.sleep(2)
trace=c.traces(checkpoint);assert c.counts(trace)=={A:0,B:0},c.counts(trace)
reconnectEnd={s:q.state(s) for s in S}
c.save('stop-reconnect-case',{'checkpoints':checkpoint,'stopped':stopped,'endStates':reconnectEnd,'nativeScreenSeconds':time.monotonic()-start,'routeCounts':c.counts(trace),'trace':trace})
print('Stop/reconnect/restart automatic: same selected source revision,0 routes,55s native screen',flush=True)
checkpoint=c.checks();c.save('cold-resume-checkpoints',checkpoint)
oldEpochs={s:q.state(s)['serviceEpoch'] for s in S}
for s in S:
 q.U['shell'](s,'am','force-stop','--user','10','ai.pleos.destinations.vehicle')
 q.mark('explicitOwnAppColdStop',serial=s)
for s in S:q.show(s)
q.wait(lambda:all(q.state(s)['serviceEpoch']!=oldEpochs[s] and q.state(s)['roomConnected'] and q.state(s)['connection']=='CONNECTED' for s in S),30)
cold={s:q.state(s) for s in S}
assert all(v['automatic']=='OFF' and v['confirmationKind'] is None and v['revision']==revision for v in cold.values())
for s,label in ((A,'동행 시작 · 자동 공유'),(B,'자동 따르기 시작')):q.findtap(s,label)
q.wait(lambda:q.state(A)['automatic']=='LEADER' and q.state(B)['automatic']=='FOLLOWER')
for s in S:c.native(s)
start=time.monotonic()
while time.monotonic()-start<50:
 assert all(q.state(s)['uiCount']==0 and q.state(s)['revision']==revision and q.state(s)['sharedKind']=='pleos_place' for s in S)
 time.sleep(2)
actual={s:c.probe(s,'query','cold-resume-actual-'+s) for s in S}
trace=c.traces(checkpoint);assert c.counts(trace)=={A:0,B:0},c.counts(trace)
for v in actual.values():
 d=c.target(v['result']);assert d['name']=='청계산담백미' and d['poiId']=='36khp2h5fjcfcm5nqhe3'
c.save('cold-resume-case',{'checkpoints':checkpoint,'oldServiceEpochs':oldEpochs,'coldStates':cold,'endStates':{s:q.state(s) for s in S},'routeCounts':c.counts(trace),'trace':trace,'nativeScreenSeconds':time.monotonic()-start,'actual':{s:'ui/cold-resume-actual-'+s+'.json' for s in S},'claim':'Fresh opt-in and observation do not replay historical acceptance or rewrite selected source; no confirmation reconstructed from storage.'})
print('Cold process resume: no stale confirmation, no acceptance replay, selected source stable,0 routes',flush=True)
