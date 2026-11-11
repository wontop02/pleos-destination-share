import hashlib,json,sys
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parent));import runtime_tools as t
args=sys.argv[1:];assert len(args)==4
app,appsha,driver,driversha=args
for file,expected in [(app,appsha),(driver,driversha)]: assert hashlib.sha256(Path(file).read_bytes()).hexdigest()==expected
records={}
for serial in sorted(t.SERIALS):
    assert t.shell(serial,'am','get-current-user').decode().strip()=='10'
    t.shell(serial,'am','force-stop','--user','10',t.PACKAGE)
    for file,flags in [(app,['-r']),(driver,['-t','-r'])]:
        output=t.adb(serial,'install',*flags,file,timeout=55).decode();assert 'Success' in output
    mapped=t.adb(serial,'reverse','--list').decode().splitlines()
    for port in (4182,4192):
        present=[line for line in mapped if 'tcp:'+str(port)+' ' in line]
        if present: assert all('tcp:'+str(port)+' tcp:4192' in line for line in present)
        else: t.adb(serial,'reverse','--no-rebind','tcp:'+str(port),'tcp:4192')
    own=[line for line in mapped if 'tcp:4196' in line]
    if own: assert all('tcp:4196 tcp:4196' in line for line in own)
    else: t.adb(serial,'reverse','--no-rebind','tcp:4196','tcp:4196')
    location=t.shell(serial,'pm','path','--user','10',t.PACKAGE).decode().strip().removeprefix('package:')
    installed=hashlib.sha256(t.adb(serial,'exec-out','cat',location)).hexdigest();assert installed==appsha
    package=t.shell(serial,'dumpsys','package',t.PACKAGE).decode()
    records[serial]={'installed_app_sha256':installed,'versionCode_2':'versionCode=2' in package,'versionName_0_4':'versionName=0.4.0-issue29' in package,'navi_custom_map_granted':'pleos.car.permission.NAVI_CUSTOM_MAP: granted=true' in package,'no_uninstall':True,'reverse4196_added':not bool(own)}
    (t.ROOT/'installed-candidate-final-resume.json').write_text(json.dumps(records,indent=2)+'\n')
    print({'serial':serial,'installed_verified':True},flush=True)
    t.shell(serial,'am','start','--user','10','-a','android.intent.action.MAIN','-c','android.intent.category.LAUNCHER','-f','0x10200000','--windowingMode','6','--display','0','-n',t.PACKAGE+'/.MainActivity',timeout=30)
    t.record('normal_launcher_entry_nonblocking',serial=serial,surface_verification_pending=True)
(t.ROOT/'installed-candidate-final.json').write_text(json.dumps(records,indent=2)+'\n');print(records)
