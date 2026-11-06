# Resume after observed null native destination invalidated the prior QA baseline assumption.
import runpy,json,time
u=runpy.run_path('ui-tools.py');q=u['q'];A,B=u['A'],u['B'];S=(A,B)
def tap(s,n,scroll=False):u['tap'](s,n,scroll)
cp=json.loads((q.D/'navigation-no-action.json').read_text())['checkpoints']
q.wait(lambda:u['state'](A)['automatic']=='LEADER' and u['state'](B)['automatic']=='FOLLOWER' and all(u['state'](s)['connection']=='CONNECTED' for s in S),15)
assert all(u['state'](s)['revision']==0 and u['state'](s)['sharedKind'] is None for s in S)
q.mark('resumeAfterAbsentBaseline',reason='No current host native destination; follower cannot confirm a nonexistent shared target. No product mutation or route injection.')
auto={s:u['state'](s) for s in S};rows=u['traces'](cp)
u['save']('explicit-auto-baseline',{'checkpoints':cp,'states':auto,'routeCounts':u['counts'](rows),'trace':rows,'claim':'Host actual destination absent, shared revision0; explicit automatic modes enabled without a route. Prior confirmed-baseline assumption failed and is preserved.'})
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
q.wait(lambda:u['state'](A)['automatic']=='LEADER' and u['state'](B)['automatic']=='FOLLOWER' and all(u['state'](s)['connection']=='CONNECTED' for s in S),45)
print('Both fontScale1.3; explicit automatic modes ready for proposal UI cases',flush=True)
