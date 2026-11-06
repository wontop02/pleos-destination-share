import runpy,json,time
u=runpy.run_path('ui-tools.py');q=u['q'];A,B=u['A'],u['B'];S=(A,B)
def tap(s,n,scroll=False):u['tap'](s,n,scroll)
cp=u['checkpoint']();before={s:u['state'](s) for s in S}
for s in S:
 tap(s,'nav_settings');u['capture'](s,'settings-normal-'+s)
 assert not any(x in ' '.join(q.texts(s)) for x in ('위도','경도','POI','SDK'))
 if s==A:
  tap(s,'developer_details',True);u['capture'](s,'settings-details-A');tap(s,'developer_details',True)
 tap(s,'nav_home')
 assert u['state'](s)['serviceEpoch']==before[s]['serviceEpoch']
tap(A,'change_destination');u['field'](A,'proposal_reason_input','W'*200)
reasonBefore=u['by_id'](A,'proposal_reason_input',True).get('text','');assert len(reasonBefore)>40
u['save']('reason-input-readback',{'intendedLength':200,'observedLength':len(reasonBefore),'scope':'Read back before bookmark callbacks; retention compares observed bytes, not assumed shell injection.'})
tap(A,'select_saved_place',True)
q.wait(lambda:'저장한 장소 선택' in q.texts(A),15)
u['capture'](A,'saved-place-picker-A')
tap(A,'saved_places_cancel')
assert u['by_id'](A,'proposal_reason_input',True).get('text')==reasonBefore
u['capture'](A,'composer-cancel-reason-retained-A')
assert u['by_id'](A,'select_saved_place',True).get('enabled')=='true'
tap(A,'select_saved_place',True)
q.wait(lambda:'저장한 장소 선택' in q.texts(A),30)
u['capture'](A,'cancel-then-reload-picker-A');tap(A,'saved_places_cancel')
assert u['by_id'](A,'proposal_reason_input',True).get('text')==reasonBefore
tap(A,'nav_home')
rows=u['traces'](cp);assert u['counts'](rows)=={A:0,B:0}
u['save']('navigation-no-action',{'checkpoints':cp,'states':{s:u['state'](s) for s in S},'routeCounts':u['counts'](rows),'trace':rows,'reasonRetainedAcrossActualBookmarkCallbacks':True})
print('Navigation/settings/cancel/re-render PASS,0 routes',flush=True)
cp=u['checkpoint']()
for s in S:tap(s,'start_convoy')
q.wait(lambda:u['state'](A)['automatic']=='LEADER' and u['state'](B)['automatic']=='FOLLOWER' and u['state'](B)['applying']=='CONFIRMED',45)
auto={s:u['state'](s) for s in S};rows=u['traces'](cp)
u['save']('explicit-auto-baseline',{'checkpoints':cp,'states':auto,'routeCounts':u['counts'](rows),'trace':rows,'claim':'Explicit opt-in may apply existing host native destination; this setup is outside proposal request windows.'})
cp=u['checkpoint']()
for s in S:
 u['open_app'](s);tap(s,'nav_settings');tap(s,'leave_room',True);q.tap(s,'취소');tap(s,'nav_home')
assert u['state'](A)['automatic']=='LEADER' and u['state'](B)['automatic']=='FOLLOWER'
rows=u['traces'](cp);assert u['counts'](rows)=={A:0,B:0}
u['save']('settings-active-cancel',{'checkpoints':cp,'states':{s:u['state'](s) for s in S},'routeCounts':u['counts'](rows),'trace':rows})
cp=u['checkpoint']();tap(A,'stop_convoy')
q.wait(lambda:u['state'](A)['automatic']=='OFF')
u['capture'](A,'intentional-stop-neutral-A')
text=u['by_id'](A,'destination_status').get('text');assert '멈췄어요' in text and '못했어요' not in text
tap(A,'start_convoy');q.wait(lambda:u['state'](A)['automatic']=='LEADER' and u['state'](A)['connection']=='CONNECTED',25)
rows=u['traces'](cp);assert u['counts'](rows)=={A:0,B:0}
u['save']('explicit-stop-restart',{'checkpoints':cp,'neutralCopy':text,'states':{s:u['state'](s) for s in S},'routeCounts':u['counts'](rows),'trace':rows})
cp=u['checkpoint']();old={s:u['state'](s) for s in S}
for s in S:q.U['shell'](s,'am','force-stop','--user','10','ai.pleos.destinations.vehicle');q.mark('explicitOwnAppColdStop',serial=s)
for s in S:u['open_app'](s)
q.wait(lambda:all(u['state'](s)['serviceEpoch']!=old[s]['serviceEpoch'] and u['state'](s)['roomConnected'] and u['state'](s)['connection']=='CONNECTED' for s in S),30)
for s in S:
 assert u['state'](s)['automatic']=='OFF' and u['state'](s)['confirmationKind'] is None
 assert '확인했어요' not in u['by_id'](s,'destination_status').get('text')
 u['capture'](s,'cold-no-consent-'+s)
rows=u['traces'](cp);assert u['counts'](rows)=={A:0,B:0}
u['save']('cold-no-implicit-action',{'checkpoints':cp,'oldStates':old,'newStates':{s:u['state'](s) for s in S},'routeCounts':u['counts'](rows),'trace':rows})
print('Active settings/danger-cancel/stop/cold resume PASS,0 implicit routes',flush=True)
scales={}
for s in S:
 scales[s]=q.U['shell'](s,'settings','--user','10','get','system','font_scale').decode().strip()
 q.U['shell'](s,'settings','--user','10','put','system','font_scale','1.3');q.mark('ownedFontScale',serial=s,value=1.3)
 u['open_app'](s)
 q.wait(lambda:u['state'](s)['roomConnected'] and u['state'](s)['connection']=='CONNECTED',25)
 u['capture'](s,'font-130-home-'+s)
u['save']('font-scale-original',{'user':10,'original':scales,'activeTestScale':1.3,'restoreRequired':True})
for s in S:tap(s,'start_convoy')
q.wait(lambda:u['state'](A)['automatic']=='LEADER' and u['state'](B)['automatic']=='FOLLOWER' and u['state'](B)['applying']=='CONFIRMED',45)
print('Both fontScale1.3; explicit automatic modes ready for proposal UI cases',flush=True)
