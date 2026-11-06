"""Check a real long bookmark at font1.3 before native routing scenarios."""
import runpy,json,time
u=runpy.run_path('ui-tools.py');q=u['q'];A,B=u['A'],u['B'];S=(A,B)
assert all(u['state'](s)['automatic']=='OFF' for s in S)
original={s:q.U['shell'](s,'settings','--user','10','get','system','font_scale').decode().strip() for s in S}
u['save']('font-scale-resume-original',{'user':10,'original':original,'note':'OS currently materializes the default as1.0; preserve the values observed on explicit resume.'})
cp=u['checkpoint']()
q.U['shell'](A,'settings','--user','10','put','system','font_scale','1.3');q.mark('ownedFontScaleResume',serial=A,value=1.3)
def visible(s,name):
 try:return u['by_id'](s,name,True).get('enabled')=='true'
 except (RuntimeError,ValueError):return False
u['open_app'](A);q.wait(lambda:visible(A,'change_destination'),25)
u['tap'](A,'change_destination')
q.wait(lambda:visible(A,'select_saved_place'),30);u['tap'](A,'select_saved_place',True)
q.wait(lambda:'저장한 장소 선택' in q.texts(A),35)
_,t=q.U['dump'](A);name='HD현대오일뱅크 세종주유소'
hits=[n for n in t.iter('node') if n.get('resource-id')=='ai.pleos.destinations.vehicle:id/saved_place_choice' and n.get('text','').replace('\\n','\n').split('\n')[0]==name]
assert len(hits)==1;q.U['tap_node'](A,hits[0]);q.mark('explicitLongPublicPlaceSelection',serial=A,name=name)
assert u['by_id'](A,'selected_place_name',True).get('text')==name
assert q.U['shell'](A,'settings','--user','10','get','system','font_scale').decode().strip()=='1.3'
u['capture'](A,'font-130-long-place-composer-A')
u['tap'](A,'nav_home')
rows=u['traces'](cp);assert u['counts'](rows)=={A:0,B:0}
u['save']('long-place-no-send',{'checkpoints':cp,'place':name,'fontScale':1.3,'routeCounts':u['counts'](rows),'trace':rows})
q.U['shell'](A,'am','force-stop','--user','10','ai.pleos.destinations.vehicle');q.mark('explicitOwnAppStopAfterFontProbe',serial=A)
old=original[A]
if old=='null':q.U['shell'](A,'settings','--user','10','delete','system','font_scale')
else:q.U['shell'](A,'settings','--user','10','put','system','font_scale',old)
assert q.U['shell'](A,'settings','--user','10','get','system','font_scale').decode().strip()==old
q.U['shell'](A,'am','force-stop','--user','10','ai.umos.maps.android.navigation.app');u['native'](A)
q.mark('restoreOwnedFontAfterLongPlace',serial=A,original=old,nativeDataCleared=False)
u['open_app'](A);q.wait(lambda:visible(A,'change_destination') and u['state'](A)['connection']=='CONNECTED',35)
assert all(u['state'](s)['automatic']=='OFF' for s in S)
u['save']('long-font-restored',{'original':original,'actual':{s:q.U['shell'](s,'settings','--user','10','get','system','font_scale').decode().strip() for s in S},'states':{s:u['state'](s) for s in S}})
print('Actual long public bookmark at font1.3 PASS,0 routes; original resume fonts restored and autoOFF',flush=True)
