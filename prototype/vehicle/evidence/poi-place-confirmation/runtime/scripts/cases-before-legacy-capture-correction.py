import qa as q
import json,sys,subprocess,time
A,B='emulator-5570','emulator-5572'
S=(A,B)
def save(name,data):
 p=q.D/(name+'.json');assert not p.exists();p.write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n')
def probe(s,mode,stem,*extra):
 p=subprocess.run([sys.executable,str(q.D/'probe.py'),s,mode,stem,*extra],capture_output=True,text=True,timeout=120)
 assert p.returncode==0,p.stdout+p.stderr
 print(p.stdout.strip(),flush=True)
 return json.loads((q.D/'ui'/(stem+'.json')).read_text())
def checks():return {s:q.checkpoint(s) for s in S}
def traces(c):return {s:q.since(c[s]) for s in S}
def counts(t):return {s:sum(r['event']=='requestRoute' for r in t[s]) for s in S}
def target(result):return result['initialDestination']
def identity(d):return None if d is None else {k:d[k] for k in ('name','latitude','longitude','displayLatitude','displayLongitude','address','poiId','poiSubId','poiProvider')}
def visible_contains(s,label):
 for _ in range(8):
  if any(label in x for x in q.texts(s)):return
  q.U['scroll_bottom'](s)
 for _ in range(8):
  q.U['scroll_top'](s)
  if any(label in x for x in q.texts(s)):return
 raise RuntimeError('Expected visible confirmation missing')
def waitproposal(status,candidate=None):
 q.wait(lambda:all(q.state(s)['proposal'] is not None and q.state(s)['proposal']['status']==status and (candidate is None or q.state(s)['proposal']['candidateName']==candidate) for s in S),30)
 x,y=(q.state(s)['proposal'] for s in S);assert x['id']==y['id']
 return x
def confirmed(kind,revision=None):
 q.wait(lambda:all(q.state(s)['applying']=='CONFIRMED' and q.state(s)['confirmationKind']==kind and (revision is None or q.state(s)['revision']==revision) for s in S),55)
def submit(s,candidate):
 q.show(s);q.findtap(s,'이 장소로 변경 제안');return waitproposal('pending',candidate)
def accept(s):
 q.show(s);q.findtap(s,'제안 수락 · 전원 동의 시 반영');return waitproposal('accepted')
def reject(s):
 q.show(s);q.findtap(s,'제안 거절 · 현재 안내 유지');return waitproposal('rejected')
def native(s):
 q.U['shell'](s,'am','start','-W','--user','10','-n','ai.umos.maps.android.navigation.app/.MainActivity')
 q.mark('nativeNavigationForeground',serial=s)
def picker(s,name,stem):
 q.show(s)
 try:q.findtap(s,'저장한 장소 불러오기')
 except RuntimeError:q.findtap(s,'저장한 장소 다시 선택 · 불러온 목록')
 q.wait(lambda:'저장한 장소 선택' in q.texts(s),15)
 _,tree=q.U['dump'](s)
 hits=[n for n in tree.iter('node') if n.get('clickable')=='true' and n.get('text','').replace('\\n','\n').split('\n')[0]==name]
 assert len(hits)==1,'Public saved place not present in picker'
 label=hits[0].get('text').replace('\\n','\n')
 q.U['tap_node'](s,hits[0]);q.mark('explicitSavedPlaceSelection',serial=s,name=name,label=label)
 return {'name':name,'label':label,'actualSdkListSource':stem}
def baseline():
 c=checks()
 q.show(A);q.findtap(A,'프로토타입 공개 장소 · 42dot');q.tap(A,'42dot')
 p=submit(A,'42dot');pre=traces(c);assert counts(pre)=={A:0,B:0}
 accepted=accept(B);confirmed('exact_point',accepted['acceptedRevision'])
 actual={s:probe(s,'query','legacy-exact-actual-'+s) for s in S}
 t=traces(c);assert counts(t)=={A:1,B:1},counts(t)
 for v in actual.values():
  d=target(v['result']);assert d['name']=='42dot' and d['poiId']=='36khp37hh8gqcm5nqhe5'
 for s in S:
  q.show(s);visible_contains(s,'순정 목적지 좌표 일치 확인');q.U['capture'](s,'legacy-exact-confirmed-'+s)
 save('legacy-exact-case',{'checkpoints':c,'pendingProposal':p,'pendingRouteCounts':counts(pre),'accepted':accepted,'routeCounts':counts(t),'trace':t,'actual':{s:'ui/legacy-exact-actual-'+s+'.json' for s in S},'states':{s:q.state(s) for s in S}})
 for s,label in ((A,'동행 시작 · 자동 공유'),(B,'자동 따르기 시작')):q.findtap(s,label)
 q.wait(lambda:q.state(A)['automatic']=='LEADER' and q.state(B)['automatic']=='FOLLOWER')
 save('automatic-started',{'states':{s:q.state(s) for s in S}})
 print('Legacy exact route confirmed once each; automatic modes ON',flush=True)
def place(label,proposer,responder,name,seconds):
 source=probe(proposer,'bookmarks',label+'-source','publicNamesJson',json.dumps([name],ensure_ascii=False))
 entries=source['result']['bookmarkObservation']['entries'];assert len(entries)==1 and entries[0]['name']==name
 chosen=picker(proposer,name,label+'-source')
 c=checks();before={s:probe(s,'query',label+'-before-'+s) for s in S}
 pending=submit(proposer,name);time.sleep(3)
 pendingActual={s:probe(s,'query',label+'-pending-'+s) for s in S}
 pt=traces(c);assert counts(pt)=={A:0,B:0},counts(pt)
 rejected=reject(responder)
 rejectedActual={s:probe(s,'query',label+'-rejected-'+s) for s in S}
 rt=traces(c);assert counts(rt)=={A:0,B:0},counts(rt)
 for s in S:
  for data in (pendingActual,rejectedActual):assert identity(target(data[s]['result']))==identity(target(before[s]['result']))
 save(label+'-negative-case',{'selection':chosen,'source':'ui/'+label+'-source.json','checkpoints':c,'pending':pending,'pendingRouteCounts':counts(pt),'rejected':rejected,'rejectedRouteCounts':counts(rt),'trace':rt,'states':{s:q.state(s) for s in S}})
 print(label+' pending/reject0 routes and native target unchanged',flush=True)
 c=checks();submit(proposer,name);accepted=accept(responder)
 for s in S:native(s)
 confirmed('pleos_place',accepted['acceptedRevision'])
 start=time.monotonic();startStates={s:q.state(s) for s in S};print(label+' accepted and place-confirmed; leaving native screen '+str(seconds)+'s',flush=True)
 while time.monotonic()-start<seconds:
  assert all(q.state(s)['uiCount']==0 for s in S),'Own app returned during native-screen check'
  assert all(q.state(s)['revision']==accepted['acceptedRevision'] for s in S),'Unexpected automatic coordinate-only publication'
  time.sleep(2)
 elapsed=time.monotonic()-start
 actual={s:probe(s,'query',label+'-accepted-'+s) for s in S}
 t=traces(c);assert counts(t)=={A:1,B:1},counts(t)
 end={s:q.state(s) for s in S}
 e=entries[0]
 for s,v in actual.items():
  d=target(v['result'])
  assert all(d[k]==e[k] for k in ('name','poiId','poiSubId','poiProvider'))
  assert end[s]['confirmationKind']=='pleos_place' and end[s]['sharedKind']=='pleos_place'
  assert end[s]['revision']==accepted['acceptedRevision'] and end[s]['uiCount']==0
  assert any('ai.umos.maps.android.navigation.app/.MainActivity' in x for x in q.foreground(s))
  q.U['capture'](s,label+'-native-confirmed-'+s)
 save(label+'-accept-case',{'selection':chosen,'checkpoints':c,'accepted':accepted,'nativeScreenSeconds':elapsed,'startStates':startStates,'endStates':end,'routeCounts':counts(t),'trace':t,'source':'ui/'+label+'-source.json','actual':{s:'ui/'+label+'-accepted-'+s+'.json' for s in S}})
 for s in S:
  q.show(s);visible_contains(s,'선택한 장소로 목적지 설정 확인');q.U['capture'](s,label+'-place-confirmed-'+s)
 print(label+' full native identity confirmed, source retained, one route each, native screen stable',flush=True)
if __name__=='__main__':
 mode=sys.argv[1]
 if mode=='baseline':baseline()
 elif mode=='cafe':place('cafe',A,B,'가든서비스',70)
 elif mode=='restaurant':place('restaurant',B,A,'청계산담백미',50)
 else:raise ValueError('Unknown QA case')
