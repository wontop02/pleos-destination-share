import qa as q
import cases as c
import json,time
A,B=c.A,c.B;S=c.S
checkpoint=c.checks();source=json.loads((q.D/'ui/restaurant-retry-source.json').read_text())['result']['bookmarkObservation']['entries'][0]
def visible(s,parts):
 for _ in range(3):
  values=q.texts(s)
  if any(all(part in text for part in parts) for text in values):return
  q.U['scroll_bottom'](s)
 raise RuntimeError('Expected source/actual coordinate body not visible')
for s in S:
 q.show(s)
 q.find(s,'받은 공유 목적지');visible(s,[source['name'],str(source['latitude']),str(source['longitude'])])
 q.U['capture'](s,'final-shared-source-'+s)
 actual=json.loads((q.D/'ui'/('cold-resume-actual-'+s+'.json')).read_text())['result']['initialDestination']
 q.find(s,'현재 순정 내비 · 실제 안내 지점');visible(s,[actual['name'],str(actual['latitude']),str(actual['longitude'])])
 q.U['capture'](s,'final-native-actual-'+s)
sourceRecord=c.probe(A,'bookmarks','final-demo-cafe-source','publicNamesJson',json.dumps(['가든서비스'],ensure_ascii=False))
selection=c.picker(A,'가든서비스','final-demo-cafe-source')
for s in S:
 for _ in range(3):q.U['scroll_top'](s)
 assert q.find(s,'이 장소로 변경 제안',True).get('enabled')=='true'
 q.U['capture'](s,'final-demo-ready-'+s)
trace=c.traces(checkpoint);assert c.counts(trace)=={A:0,B:0}
states={s:q.state(s) for s in S}
assert states[A]['automatic']=='LEADER' and states[B]['automatic']=='FOLLOWER'
assert all(v['revision']==3 and v['sharedKind']=='pleos_place' and v['proposal']['status']!='pending' for v in states.values())
c.save('final-demo-ready',{'sourceHead':'a26b9419804874052329001ad86e60ff1bccb42c','port':4188,'selectedOnA':selection,'proposalSubmitted':False,'acceptanceSubmitted':False,'states':states,'selectionRouteCounts':c.counts(trace),'trace':trace,'sharedSourceObservedInUi':source,'nextUserAction':'A의 이 장소로 변경 제안 → B의 제안 수락 또는 제안 거절','actualWindowNames':['Pleos12Leader:5570','Pleos12Follower:5572']})
print('Actual two-car UI ready: A cafe selected, no pending proposal, autoON,0 selection routes',flush=True)
