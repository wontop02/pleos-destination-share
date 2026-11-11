import json,sys,time
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parent))
import runtime_tools as t
import test_room_api as api
A='emulator-5570';B='emulator-5572';NAME='프레소커피랩';P='R11-final-'
r={'app_sha256':sys.argv[1],'head':sys.argv[2],'started_at_ms':int(time.time()*1000),'status':'RUNNING'}
def save(): (t.ROOT/(P+'result.json')).write_text(json.dumps(r,ensure_ascii=False,indent=2)+'\n')
def action(serial,resource):
    p=t.instrument(serial,'VehicleUiActionProbe',resource=resource,operation='click')
    assert p.get('actionAccepted')=='true',resource+' requires actual visible enabled UI control'
    return p
try:
    t.capture(B,P+'01-B-native-before-send')
    t.launch(A)
    if not any(n.get('resource-id')==t.PACKAGE+':id/search_keyword' for n in t.nodes(A)):action(A,'change_destination')
    initial=api.safe_snapshot(P+'before-frame');old=initial['requestRevision'];r['previous_revision']=old;r['baseline']={}
    for s in (A,B):
        size=int(t.shell(s,'run-as',t.PACKAGE,'--user','10','wc','-c','no_backup/navigation-events.jsonl').decode().split()[0]);assert size<400000,'Bounded trace must not approach rollover'
        e=t.events(s);(t.ROOT/(P+'before-'+s+'-events.json')).write_text(json.dumps(e,indent=2)+'\n')
        r['baseline'][s]={'last_at':max(x['at'] for x in e),'bytes':size,'rows':len(e)}
    save();r['preview_action']=action(A,'preview_changed_destination');time.sleep(1.2)
    preview=t.capture(A,P+'02-A-fresh-preview');assert any(x['id']==t.PACKAGE+':id/changed_destination_name' and x['text']==NAME for x in preview)
    r['preview_texts']=preview;r['send_action']=action(A,'send_destination_change');r['send_return_at_ms']=int(time.time()*1000)
    for _ in range(40):
        _,s=api.call();q=s.get('changeRequest')
        if q and q['revision']>old:break
        time.sleep(.2)
    else:raise AssertionError('Explicit Send did not produce a fresh request')
    assert q['destination']['name']==NAME and all(x['response']=='pending' for x in q['recipients'])
    r.update(request_id=q['id'],request_revision=q['revision'],server_created_at=q['createdAt'],expected_destination=q['destination']);save();api.safe_snapshot(P+'after-send-frame')
    (t.ROOT/'ui'/(P+'03-B-native-notification-immediate.png')).write_bytes(t.adb(B,'exec-out','screencap','-p'))
    # Expand the real OS notification surface, then tap its actual rendered card.
    t.shell(B,'cmd','statusbar','expand-notifications');t.capture(B,P+'04-B-Android-notification-shade')
    t.tap_nearest_text(B,'목적지 변경 요청');t.record('actual_android_notification_tap',serial=B,request_revision=q['revision'])
    review=t.capture(B,P+'05-B-current-request-review');assert any(x['id']==t.PACKAGE+':id/change_request_name' and x['text']==NAME for x in review)
    r['notification_tapped']=True;r['review_texts']=review;r['preaccept_routes']={}
    for device in (A,B):
        new=[x for x in t.events(device) if x['at']>r['baseline'][device]['last_at']]
        (t.ROOT/(P+'preaccept-'+device+'-events.json')).write_text(json.dumps(new,indent=2)+'\n')
        r['preaccept_routes'][device]=sum(x['event'].endswith('requestRoute') for x in new);assert r['preaccept_routes'][device]==0
    before=api.safe_snapshot(P+'before-accept-frame');assert before['changeRequest']['id']==q['id'] and before['changeRequest']['status']=='active'
    assert all(x['response']=='pending' for x in before['changeRequest']['recipients']);save()
    r['accept_action']=action(B,'accept_change_request');r['accept_return_at_ms']=int(time.time()*1000);save()
    folder=t.ROOT/(P+'live-events');folder.mkdir(exist_ok=False)
    for index in range(100):
        new=[x for x in t.events(B) if x['at']>r['baseline'][B]['last_at']]
        (folder/(str(index)+'.json')).write_text(json.dumps(new,indent=2)+'\n')
        routes=[x for x in new if x['event']=='request:requestRoute']
        match=[x for x in new if x['event']=='request:destinationMatch' and x.get('matched') is True]
        if match:break
        time.sleep(.4)
    else:raise AssertionError('No fresh native destination confirmation within bounded operation')
    assert len(routes)==1,'Exactly one product route request required; complete fresh journal retained'
    m=match[-1];assert routes[0]['operation']==m['operation']
    reads=[x for x in new if x['event']=='request:getDestinationInfo' and x.get('operation')==m['operation'] and x.get('nativeEpoch')==m['nativeEpoch'] and x['at']<=m['at']];assert reads
    assert any(x['event']=='request:onDestinationInfo' and x.get('operation')==m['operation'] and x.get('nativeEpoch')==m['nativeEpoch'] and reads[-1]['at']<=x['at']<=m['at'] for x in new)
    (t.ROOT/(P+'after-accept-events.json')).write_text(json.dumps(new,indent=2)+'\n')
    f=api.safe_snapshot(P+'match-while-C-pending');assert any(x['member']['name']=='Observer-C' and x['response']=='pending' for x in f['changeRequest']['recipients'])
    r.update(route_request_count=1,fresh_match=m,C_pending_at_B_match=True);save();t.capture(B,P+'06-B-native-confirmed')
    pid=t.shell(B,'pidof',t.PACKAGE).decode().strip();p=t.instrument(B,'NativeDestinationDriver',mode='query');native=json.loads(p['probe_json']);assert native['status']=='OBSERVED' and native['routeRequestCount']==0 and native['initialDestination']['name']==NAME
    assert pid==t.shell(B,'pidof',t.PACKAGE).decode().strip();(t.ROOT/(P+'independent-B-native-query.json')).write_text(json.dumps(native,ensure_ascii=False,indent=2)+'\n')
    code,_=api.call('POST','/destination-requests/'+q['id']+'/responses',{'revision':q['revision'],'response':'rejected'});assert code==200
    f=api.safe_snapshot(P+'C-rejected-B-still-accepted');assert f['destination'] is None and f['revision']==0
    assert {x['member']['name']:x['response'] for x in f['changeRequest']['recipients']}=={'Car-B':'accepted','Observer-C':'rejected'}
    anew=[x for x in t.events(A) if x['at']>r['baseline'][A]['last_at']];assert not any(x['event'].endswith('requestRoute') for x in anew)
    (t.ROOT/(P+'sender-after-events.json')).write_text(json.dumps(anew,indent=2)+'\n');r.update(status='PASS',sender_route_request_count=0,C_rejected_without_reverting_B=True,independent_native_name=native['initialDestination']['name']);save()
    print(json.dumps({'status':r['status'],'request_revision':r['request_revision'],'sender_route_count':0,'receiver_preaccept_route_count':0,'receiver_after_accept_route_count':1,'fresh_native_match':True,'C_pending_during_match':True,'C_rejected_later':True},ensure_ascii=False),flush=True)
except Exception as error:
    r.update(status='FAIL',error_type=type(error).__name__,error=str(error));save();raise
