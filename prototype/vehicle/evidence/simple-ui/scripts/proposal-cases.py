import runpy,json,sys,subprocess,time,datetime,re
u=runpy.run_path('ui-tools.py');q=u['q'];A,B=u['A'],u['B'];S=(A,B)
def save(n,v):u['save'](n,v)
def tap(s,n,scroll=False):u['tap'](s,n,scroll)
def probe(s,mode,stem,*extra):
 p=subprocess.run([sys.executable,'probe.py',s,mode,stem,*extra],capture_output=True,text=True,timeout=120)
 assert p.returncode==0,p.stderr
 v=json.loads((q.D/'ui'/(stem+'.json')).read_text())
 assert v['productionProcessUnchanged'] and v['result']['routeRequestCount']==0
 print(p.stdout.strip(),flush=True);return v
def enabled(s,n):return u['by_id'](s,n,True).get('enabled')=='true'
def select(s,name,tag,longReason=False):
 u['open_app'](s);q.wait(lambda:enabled(s,'change_destination'),25);tap(s,'change_destination')
 if longReason:u['field'](s,'proposal_reason_input','W'*200)
 before=u['by_id'](s,'proposal_reason_input',True).get('text','')
 q.wait(lambda:enabled(s,'select_saved_place'),25)
 label=u['by_id'](s,'select_saved_place',True).get('text')
 tap(s,'select_saved_place',True);q.wait(lambda:'저장한 장소 선택' in q.texts(s),35)
 _,t=q.U['dump'](s)
 hits=[n for n in t.iter('node') if n.get('resource-id')=='ai.pleos.destinations.vehicle:id/saved_place_choice' and n.get('text','').replace('\\n','\n').split('\n')[0]==name]
 assert len(hits)==1
 q.U['tap_node'](s,hits[0]);q.mark('explicitPublicPlaceSelection',serial=s,name=name,case=tag)
 assert u['by_id'](s,'selected_place_name',True).get('text')==name
 after=u['by_id'](s,'proposal_reason_input',True).get('text','');assert before==after
 u['capture'](s,tag+'-composer')
 return {'place':name,'entryButton':label,'reasonReadbackLength':len(after),'inputRetainedAcrossActualCallbacks':True}
def proposal(status,name=None):
 q.wait(lambda:all(u['state'](s)['proposal'] and u['state'](s)['proposal']['status']==status and (name is None or u['state'](s)['proposal']['candidateName']==name) for s in S),30)
 p=u['state'](A)['proposal'];assert p['id']==u['state'](B)['proposal']['id'];return p
def send(s,name):tap(s,'send_proposal',True);return proposal('pending',name)
def native_identity(p):
 d=p['result']['initialDestination']
 return None if d is None else {k:d[k] for k in ('name','latitude','longitude','displayLatitude','displayLongitude','address','poiId','poiSubId','poiProvider')}
def no_debug(s):assert not any(re.search(r'SDK|POI|위도|경도|버전|로컬 서버',x) for x in q.texts(s))
def accept_case(tag,proposer,responder,name,seconds=60):
 source=probe(proposer,'bookmarks',tag+'-source','publicNamesJson',json.dumps([name],ensure_ascii=False))
 assert len(source['result']['bookmarkObservation']['entries'])==1
 cp=u['checkpoint']();selection=select(proposer,name,tag,longReason=tag=='cafe' and not (q.D/'cafe-first-native-timeout/cafe-negative.json').exists())
 pending=send(proposer,name);u['open_app'](responder)
 for action in ('accept_proposal','reject_proposal'):
  n=u['by_id'](responder,action);assert n.get('enabled')=='true'
  x1,y1,x2,y2=map(int,re.findall(r'\d+',n.get('bounds')));assert y2-y1>=56 and y2<=1300
 no_debug(responder);u['capture'](responder,tag+'-pending-responder');u['capture'](proposer,tag+'-waiting-proposer')
 rows=u['traces'](cp);assert u['counts'](rows)=={A:0,B:0}
 before={s:probe(s,'query',tag+'-pending-native-'+s) for s in S}
 tap(responder,'reject_proposal');rejected=proposal('rejected')
 after={s:probe(s,'query',tag+'-rejected-native-'+s) for s in S}
 assert all(native_identity(before[s])==native_identity(after[s]) for s in S)
 rows=u['traces'](cp);assert u['counts'](rows)=={A:0,B:0}
 for s in S:u['open_app'](s);u['capture'](s,tag+'-rejected-'+s)
 save(tag+'-negative',{'checkpoints':cp,'selection':selection,'pending':pending,'rejected':rejected,'routeCounts':u['counts'](rows),'trace':rows,'source':'ui/'+tag+'-source.json','states':{s:u['state'](s) for s in S}})
 print(tag+' pending/reject0 native requests, unchanged actual guidance',flush=True)
 cp=u['checkpoint']();selection=select(proposer,name,tag+'-retry',longReason=tag=='cafe' and not (q.D/'cafe-first-native-timeout/cafe-negative.json').exists())
 assert '다시 불러오기' in selection['entryButton'],'Rejected proposal should offer direct retry'
 send(proposer,name);u['open_app'](responder);tap(responder,'accept_proposal');accepted=proposal('accepted')
 for s in S:u['native'](s)
 q.wait(lambda:all(u['state'](s)['applying']=='CONFIRMED' and u['state'](s)['confirmationKind']=='pleos_place' and u['state'](s)['uiCount']==0 and u['state'](s)['revision']==accepted['acceptedRevision'] for s in S),50)
 start=q.mark('confirmedNativeWindowStart',case=tag,proposalId=accepted['id']);begin=time.monotonic()
 while time.monotonic()-begin<seconds:
  for s in S:
   v=u['state'](s);assert v['uiCount']==0 and v['revision']==accepted['acceptedRevision'] and v['automatic']==('LEADER' if s==A else 'FOLLOWER')
  time.sleep(2)
 end=q.mark('confirmedNativeWindowEnd',case=tag,proposalId=accepted['id'])
 actual={s:probe(s,'query',tag+'-accepted-native-'+s) for s in S}
 expected=source['result']['bookmarkObservation']['entries'][0]
 for s in S:
  d=actual[s]['result']['initialDestination'];assert all(d[k]==expected[k] for k in ('name','poiId','poiSubId','poiProvider'))
  assert any('ai.umos.maps.android.navigation.app/.MainActivity' in x for x in q.foreground(s))
  u['capture'](s,tag+'-native-'+s)
 rows=u['traces'](cp);assert u['counts'](rows)=={A:1,B:1},u['counts'](rows)
 save(tag+'-accepted',{'checkpoints':cp,'selection':selection,'accepted':accepted,'nativeWindowStart':start,'nativeWindowEnd':end,'timedSeconds':time.monotonic()-begin,'routeCounts':u['counts'](rows),'trace':rows,'source':'ui/'+tag+'-source.json','actual':{s:'ui/'+tag+'-accepted-native-'+s+'.json' for s in S},'states':{s:u['state'](s) for s in S}})
 for s in S:
  u['open_app'](s);q.wait(lambda:'확인했어요' in u['by_id'](s,'destination_status').get('text'),15)
  no_debug(s);u['capture'](s,tag+'-confirmed-home-'+s)
 print(tag+' actual place confirmed once per car, native window>45s stable, clear home success',flush=True)
if __name__=='__main__':
 if sys.argv[1]=='cafe':accept_case('cafe',A,B,'가든서비스')
 elif sys.argv[1]=='restaurant':accept_case('restaurant',B,A,'청계산담백미',50)
