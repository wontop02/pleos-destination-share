import runpy,json,re,time,datetime,hashlib
from pathlib import Path
RAW=Path(__file__).resolve().parent
U=runpy.run_path(str(RAW/'root-ui-tools.py'))
PKG='ai.pleos.destinations.vehicle'
NAV='ai.umos.maps.android.navigation.app'
COMPONENT='ai.pleos.destinations.driver/.NativeDestinationDriver'
def snapshot(serial):
    activity=U['shell'](serial,'dumpsys','activity','activities').decode()
    lines=activity.splitlines()
    resumed=[line.strip() for line in lines if 'ResumedActivity' in line or 'topResumedActivity' in line]
    own=[]
    for i,line in enumerate(lines):
        if '* Hist' in line and PKG+'/.MainActivity' in line:
            own.extend(x.strip() for x in lines[i:i+25] if 'state=' in x)
    service=U['shell'](serial,'dumpsys','activity','services',PKG).decode()
    details=[x.strip() for x in service.splitlines() if any(s in x for s in ['ServiceRecord{','isForeground=','foregroundServiceType=','startRequested=','app=ProcessRecord'])]
    state=None
    for attempt in range(5):
        try: state=U['session_state'](serial);break
        except json.JSONDecodeError:
            if attempt==4:raise
            time.sleep(0.05)
    return {'hostAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'hostMonotonic':time.monotonic(),'serial':serial,'pid':U['shell'](serial,'pidof',PKG).decode().strip(),'topResumed':resumed,'ownActivityStates':own,'nativeForeground':any(NAV in x for x in resumed),'ownResumed':any(PKG in x for x in resumed),'serviceForeground':'isForeground=true' in service,'serviceDetails':details,'session':state}
def driver(serial,mode,stem):
    if mode not in {'query','route-42dot','route-dream-center'}:raise RuntimeError('mode outside public fixture allowlist')
    beforePid=U['shell'](serial,'pidof',PKG).decode().strip()
    start=time.monotonic()
    text=U['adb'](serial,'shell','am','instrument','--user','10','-w','-r','-e','mode',mode,COMPONENT,timeout=110).decode()
    m=re.search(r'INSTRUMENTATION_RESULT: probe_json=(\{.*\})',text)
    if not m:raise RuntimeError('actual driver report absent')
    result=json.loads(m.group(1))
    afterPid=U['shell'](serial,'pidof',PKG).decode().strip()
    record={'hostAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'serial':serial,'mode':mode,'seconds':round(time.monotonic()-start,3),'productionPidBefore':beforePid,'productionPidAfter':afterPid,'productionProcessUnchanged':bool(beforePid) and beforePid==afterPid,'result':result}
    (RAW/f'ui/{stem}.log').write_text(text)
    (RAW/f'ui/{stem}.json').write_text(json.dumps(record,ensure_ascii=False,indent=2)+'\n')
    return record
def native_identity(value):
    return tuple(value.get(k) for k in ['name','latitude','longitude','poiId','poiSubId','poiProvider'])
