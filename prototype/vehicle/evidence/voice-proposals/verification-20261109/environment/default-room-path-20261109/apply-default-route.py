from pathlib import Path
import runpy,json,datetime
D=Path(__file__).resolve().parent
U=runpy.run_path(str(D.parent/'root-ui-tools.py'))
results=[]
for serial in ['emulator-5570','emulator-5572']:
 assert U['shell'](serial,'am','get-current-user').decode().strip()=='10'
 old=U['adb'](serial,'reverse','--list').decode().splitlines()
 targets=[]
 for line in old:
  fields=line.split()
  if len(fields)==3 and fields[1]=='tcp:4182':targets.append(fields[2])
 assert not targets or targets==['tcp:4192'],'Existing different default mapping preserved; refusing override'
 if not targets:U['adb'](serial,'reverse','--no-rebind','tcp:4182','tcp:4192')
 after=U['adb'](serial,'reverse','--list').decode().splitlines()
 assert any(line.split()[1:]==['tcp:4182','tcp:4192'] for line in after if len(line.split())==3)
 assert any(line.split()[1:]==['tcp:4192','tcp:4192'] for line in after if len(line.split())==3)
 results.append({'serial':serial,'default4182TargetsOwned4192':True,'existing4192Preserved':True,'newMapping':not targets})
report={'atUtc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'results':results,'note':'Run after each emulator boot; adb reverse mappings are per live transport. No app data or room identity changes.'}
(D/'routing-fix.json').write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps(report,indent=2))
