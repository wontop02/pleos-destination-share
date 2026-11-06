from pathlib import Path
import json,datetime,math,hashlib,re
D=Path(__file__).resolve().parent
S=('emulator-5570','emulator-5572')
def read(p):return json.loads((D/p).read_text())
def jsonl(p):return [json.loads(x) for x in (D/p).read_text().splitlines() if x.strip()]
def when(x):return datetime.datetime.fromisoformat(x)
def routecount(rows):return sum(r['event']=='requestRoute' for r in rows)
def identity(d):return {k:d[k] for k in ('name','latitude','longitude','displayLatitude','displayLongitude','address','poiId','poiSubId','poiProvider')}
def haversine(a,b):
 x,y=map(math.radians,[a['latitude'],b['latitude']]);d=math.radians(b['longitude']-a['longitude']);h=math.sin((y-x)/2)**2+math.cos(x)*math.cos(y)*math.sin(d/2)**2
 return 6371000*2*math.asin(math.sqrt(h))
actions=jsonl('actions.jsonl');observations=jsonl('observations.jsonl')
out={'sourceHead':'a26b9419804874052329001ad86e60ff1bccb42c','apkSha256':'5845c358d2a63c4ead083c699d94446d0353f3fcedc92f6afb8c0521832cc35a','cases':{}}
legacy=read('legacy-exact-case.json')
for s,v in legacy['vehicles'].items():
 rows=[r['record'] for r in jsonl(s+'-journal.jsonl')]
 assert v['pendingRouteCount']==0 and v['acceptedRouteCount']==1
 assert rows[v['requestRouteLines'][0]]['event']=='requestRoute'
 assert v['acceptedFirstLine']<v['requestRouteLines'][0]<v['confirmedLastLine']
 p=read(v['actual']);assert p['productionProcessUnchanged'] and p['result']['routeRequestCount']==0
 d=p['result']['initialDestination'];assert d['name']=='42dot' and d['poiId']=='36khp37hh8gqcm5nqhe5'
out['cases']['legacyExact']={'routeCounts':{s:1 for s in S},'pendingRouteCounts':{s:0 for s in S},'lateCaptureLimitation':legacy['captureLimitation']}
for label,minimum in (('cafe',70),('restaurant-retry',50)):
 negative=read(label+'-negative-case.json');accepted=read(label+'-accept-case.json')
 source=read(accepted['source'])['result']['bookmarkObservation']['entries'][0]
 assert accepted['nativeScreenSeconds']>=minimum
 checkpoint=max(when(v['hostAt']) for v in accepted['checkpoints'].values())
 probes=[a for a in actions if a['event']=='publicSdkProbe' and a.get('stem','').startswith(label+'-accepted-')]
 end=min(when(x['hostAt']) for x in probes)-datetime.timedelta(seconds=1)
 native=[a for a in actions if a['event']=='nativeNavigationForeground' and checkpoint<when(a['hostAt'])<end]
 assert set(a['serial'] for a in native)==set(S)
 start=max(when(a['hostAt']) for a in native)+datetime.timedelta(seconds=3)
 assert (end-start).total_seconds()>=minimum
 result={}
 for s in S:
  assert routecount(negative['trace'][s])==0 and negative['pendingRouteCounts'][s]==0 and negative['rejectedRouteCounts'][s]==0
  assert routecount(accepted['trace'][s])==1 and accepted['routeCounts'][s]==1
  before=read('ui/'+label+'-before-'+s+'.json')['result']['initialDestination']
  for phase in ('pending','rejected'):
   p=read('ui/'+label+'-'+phase+'-'+s+'.json');assert p['productionProcessUnchanged'] and identity(p['result']['initialDestination'])==identity(before)
  p=read(accepted['actual'][s]);assert p['productionProcessUnchanged'] and p['result']['routeRequestCount']==0
  actual=p['result']['initialDestination'];assert all(source[k]==actual[k] for k in ('name','poiId','poiSubId','poiProvider'))
  if label=='cafe':assert actual['latitude']!=source['latitude'] or actual['longitude']!=source['longitude']
  samples=[v for v in observations if v['serial']==s and start<=when(v['hostAt'])<=end]
  assert len(samples)>=10
  pids={v['processPidAtRead'] for v in samples};assert len(pids)==1 and None not in pids
  epochs={v['state']['serviceEpoch'] for v in samples};assert len(epochs)==1
  for sample in samples:
   v=sample['state'];assert v['uiCount']==0 and v['sharedKind']=='pleos_place' and v['revision']==accepted['accepted']['acceptedRevision']
   assert v['acceptedProposalId']==accepted['accepted']['id'] and v['automatic']==('LEADER' if s==S[0] else 'FOLLOWER')
  gaps=[(when(b['hostAt'])-when(a['hostAt'])).total_seconds() for a,b in zip(samples,samples[1:])]
  assert max(gaps,default=0)<10
  v=accepted['endStates'][s];assert v['applying']=='CONFIRMED' and v['confirmationKind']=='pleos_place'
  result[s]={'sourceToGuidanceMeters':haversine(source,actual),'nativeIdentityMatched':True,'routeRequests':1,'observedProcessPid':next(iter(pids)),'serviceEpoch':next(iter(epochs)),'nativeScreenSamples':len(samples),'maxSampleGapSeconds':max(gaps,default=0)}
 out['cases'][label]={'source':source,'nativeWindowStart':start.isoformat(),'nativeWindowEnd':end.isoformat(),'auditedWindowSeconds':(end-start).total_seconds(),'timedHoldSeconds':accepted['nativeScreenSeconds'],'vehicles':result,'pendingAndRejectedRoutes':0}
for name in ('stop-reconnect','cold-resume'):
 case=read(name+'-case.json')
 assert case['nativeScreenSeconds']>=50
 for s in S:assert routecount(case['trace'][s])==0 and case['routeCounts'][s]==0 and case['endStates'][s]['sharedKind']=='pleos_place'
 if name=='cold-resume':
  for s in S:
   assert case['coldStates'][s]['automatic']=='OFF' and case['coldStates'][s]['confirmationKind'] is None
   assert case['oldServiceEpochs'][s]!=case['coldStates'][s]['serviceEpoch']
   p=read(case['actual'][s]);assert p['productionProcessUnchanged'] and p['result']['initialDestination']['name']=='청계산담백미'
 out['cases'][name]={'routeCounts':case['routeCounts'],'nativeScreenSeconds':case['nativeScreenSeconds'],'revision':case['endStates'][S[0]]['revision']}
assert read('runtime-source-binding.json')['sourceHead']==out['sourceHead']
out['verdict']='PASS'
(D/'runtime-audit.json').write_text(json.dumps(out,ensure_ascii=False,indent=2)+'\n')
print(json.dumps({'verdict':out['verdict'],'cases':list(out['cases'])},ensure_ascii=False))
