"""Install only a clean committed UI build on the two approved virtual cars."""
from pathlib import Path
import qa as q
import subprocess,json,hashlib,sys,datetime
D=q.D;W=D.parents[2]/'issue-23-simple-vehicle-ui'
apk=Path(sys.argv[1]).resolve();head=sys.argv[2]
assert len(head)==40 and apk.is_file()
def git(*args):return subprocess.check_output(['git','-C',str(W),*args],text=True).strip()
assert git('rev-parse','HEAD')==head and not git('status','--porcelain')
base='d03c6f0bd952fd2a0a0ebc376a6e8091e1c77a35'
unchanged=['prototype/vehicle/core','prototype/vehicle/nativeDriver','prototype/server.mjs','prototype/lib','prototype/vehicle/app/src/main/AndroidManifest.xml','prototype/vehicle/app/src/main/kotlin/ai/pleos/destinations/vehicle/VehicleSession.kt','prototype/vehicle/app/src/main/kotlin/ai/pleos/destinations/vehicle/PleosNavigationGateway.kt','prototype/vehicle/app/src/main/kotlin/ai/pleos/destinations/vehicle/RoomProtocol.kt','prototype/vehicle/app/src/main/kotlin/ai/pleos/destinations/vehicle/SecureSessionStore.kt']
assert not git('diff',base,head,'--',*unchanged),'Non-UI production delta; investigate before device install'
digest=hashlib.sha256(apk.read_bytes()).hexdigest()
archive=D/'preinstall';archive.mkdir(exist_ok=False)
records=[]
for s in ('emulator-5570','emulator-5572'):
 assert q.U['shell'](s,'am','get-current-user').decode().strip()=='10'
 avd=q.U['adb'](s,'emu','avd','name').decode().splitlines()[0]
 assert avd==('Pleos12Leader' if s.endswith('5570') else 'Pleos12Follower')
 fingerprint=q.U['shell'](s,'getprop','ro.build.fingerprint').decode().strip();assert fingerprint.startswith('Pleos/crp_avd_arm64/crp_avd_arm64:14/')
 q.U['shell'](s,'am','force-stop','--user','10','ai.pleos.destinations.vehicle')
 raw=q.U['private_text'](s,'no_backup/navigation-events.jsonl');before=hashlib.sha256(raw.encode()).hexdigest()
 (archive/(s+'-journal.jsonl')).write_text(raw)
 oldMalformed=[]
 for i,line in enumerate(raw.splitlines()):
  if not line.strip():continue
  try:q.valid(json.loads(line))
  except (json.JSONDecodeError,AssertionError) as error:
   oldMalformed.append({'line':i,'sha256':hashlib.sha256(line.encode()).hexdigest(),'length':len(line),'errorClass':type(error).__name__})
 target='no_backup/navigation-events.before-simple-ui-'+before[:12]+'.jsonl'
 q.U['shell'](s,'run-as','ai.pleos.destinations.vehicle','--user','10','mv','no_backup/navigation-events.jsonl',target)
 q.U['adb'](s,'reverse','tcp:4190','tcp:4190')
 argv=[q.U['ADB'],'-P','5041','-s',s,'install','--user','10','-t','-r',str(apk)]
 p=subprocess.run(argv,env=q.U['ENV'],capture_output=True,text=True,timeout=60)
 assert p.returncode==0 and 'Success' in p.stdout,'Owned APK installation failed'
 path=q.U['shell'](s,'pm','path','--user','10','ai.pleos.destinations.vehicle').decode().strip().removeprefix('package:')
 actual=q.U['shell'](s,'sha256sum',path).decode().split()[0];assert actual==digest
 driver=q.U['shell'](s,'pm','path','--user','10','ai.pleos.destinations.driver').decode().strip().removeprefix('package:')
 driverHash=q.U['shell'](s,'sha256sum',driver).decode().split()[0]
 assert driverHash=='470c696a36d17c04089073a4ed4b6c33cd03e0630608601ee9d8085c29be7ad7'
 records.append({'serial':s,'avd':avd,'user':10,'fingerprint':fingerprint,'installArgv':argv,'installExit':p.returncode,'installedApkSha256':actual,'driverApkSha256':driverHash,'oldJournalSha256':before,'oldJournalPreservedAt':target,'oldJournalMalformedRows':oldMalformed,'oldJournalExcludedFromNewCaseCounts':True})
 print(s+' frozen APK installed and device hash verified',flush=True)
record={'at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'base':base,'sourceHead':head,'apk':str(apk),'apkSha256':digest,'nonUiProductionUnchanged':unchanged,'devices':records}
(D/'installed.json').write_text(json.dumps(record,ensure_ascii=False,indent=2)+'\n')
