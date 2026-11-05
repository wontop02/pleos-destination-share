from pathlib import Path
import json, runpy, hashlib, time, datetime

RAW = Path(__file__).resolve().parent
PARENT = RAW.parent
WT = PARENT.parent.parent / 'issue-14-auto-follow'
R = runpy.run_path(str(RAW/'root-runtime-tools.py'))
U = R['U']
manifest = json.loads((PARENT/'builder/freeze-3/source-manifest.json').read_text())
assert all(hashlib.sha256((WT/x['path']).read_bytes()).hexdigest()==x['sha256'] for x in manifest['runtimeFiles']+manifest['testFiles'])
modules = {x['module']: x for x in manifest['apks']}
app = PARENT/'builder/freeze-3/app-debug.apk'
assert hashlib.sha256(app.read_bytes()).hexdigest()==modules['app']['sha256']
driver_apk = PARENT/'builder/freeze-3/nativeDriver-debug.apk'
assert hashlib.sha256(driver_apk.read_bytes()).hexdigest()==modules['nativeDriver']['sha256']

def installed_hash(serial, package):
    text=U['shell'](serial,'pm','path','--user','10',package).decode().strip()
    paths=[x.removeprefix('package:') for x in text.splitlines() if x.startswith('package:')]
    assert len(paths)==1 and paths[0].endswith('/base.apk')
    return hashlib.sha256(U['adb'](serial,'exec-out','cat',paths[0])).hexdigest()

records=[]
for serial,role,label in [('emulator-5570','leader','동행 시작 · 자동 공유'),('emulator-5572','follower','자동 따르기 시작')]:
    assert U['shell'](serial,'am','get-current-user').decode().strip()=='10'
    old=R['snapshot'](serial)
    assert not old['serviceForeground'] and old['session']['automatic']=='OFF'
    result=U['adb'](serial,'install','--user','10','-r',str(app),timeout=120).decode()
    assert 'Success' in result
    driver_result=U['adb'](serial,'install','--user','10','-r','-t',str(driver_apk),timeout=120).decode()
    assert 'Success' in driver_result
    actual=installed_hash(serial,'ai.pleos.destinations.vehicle')
    driver=installed_hash(serial,'ai.pleos.destinations.driver')
    assert actual==modules['app']['sha256'] and driver==modules['nativeDriver']['sha256']
    launch=U['shell'](serial,'am','start','--user','10','-W','-n','ai.pleos.destinations.vehicle/.MainActivity').decode()
    deadline=time.monotonic()+45
    while True:
        _,tree=U['dump'](serial)
        hits=[n for n in tree.iter('node') if n.get('text')==label and n.get('enabled')=='true']
        if len(hits)==1:break
        if time.monotonic()>=deadline:raise RuntimeError('authenticated default-off start control unavailable')
        time.sleep(1)
    state=R['snapshot'](serial)
    assert state['session']['automatic']=='OFF' and state['session']['uiCount']==1 and not state['serviceForeground']
    U['capture'](serial,'17-freeze3-'+role+'-off')
    records.append({'serial':serial,'appSHA256':actual,'driverSHA256':driver,'launch':launch,'defaultOff':state})
receipt={'at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'manifest':'../builder/freeze-3/source-manifest.json','sourceHashMismatch':0,'receipts':records}
(RAW/'root-freeze3-install.json').write_text(json.dumps(receipt,ensure_ascii=False,indent=2)+'\n')
print(json.dumps(receipt,ensure_ascii=False),flush=True)
