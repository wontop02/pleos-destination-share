from pathlib import Path
import hashlib,json,shutil,subprocess,xml.etree.ElementTree as ET,datetime,os
root=Path.cwd();raw=Path('/Users/wontop/Documents/ChatGPT/현대 모비스 해커톤/pleos-destination-share/.harness/worktrees/product-run-artifacts/pleos-auto-follow-20261105/builder/freeze-3')
evidence=root/'prototype/vehicle/evidence/automatic-session'
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
prior=json.loads((raw/'prior-source-manifest-freeze-2.json').read_text())
assert (evidence/'source-manifest-freeze-2.json').read_bytes()==(raw/'prior-source-manifest-freeze-2.json').read_bytes()
entries=[{'path':x['path'],'sha256':sha(root/x['path']),'bytes':(root/x['path']).stat().st_size} for x in sorted(prior['runtimeFiles']+prior['testFiles'],key=lambda x:x['path'])]
runtime=[x for x in entries if '/src/test/' not in x['path'] and '/src/androidTest/' not in x['path']]
tests=[x for x in entries if x not in runtime]
assert len(runtime)==30 and len(tests)==10
changes=[x['path'] for x in entries if not any(y['path']==x['path'] and y['sha256']==x['sha256'] for y in prior['runtimeFiles']+prior['testFiles'])]
assert changes==['prototype/vehicle/app/src/main/kotlin/ai/pleos/destinations/vehicle/ConvoySessionService.kt'],changes
rollup=lambda x:hashlib.sha256(json.dumps(x,sort_keys=True,separators=(',',':')).encode()).hexdigest()
apks=[];commands=[]
for module,rel,name in [('app','app/build/outputs/apk/debug/app-debug.apk','app-debug.apk'),('probe','app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk','app-debug-androidTest.apk'),('nativeDriver','nativeDriver/build/outputs/apk/debug/nativeDriver-debug.apk','nativeDriver-debug.apk')]:
 p=root/'prototype/vehicle'/rel;dest=raw/name
 assert not dest.exists(),dest
 shutil.copy2(p,dest)
 apks.append({'module':module,'path':str(dest),'sha256':sha(dest),'bytes':dest.stat().st_size})
 env={**os.environ,'JAVA_HOME':'/private/tmp/pleos-vehicle-tools-20261105/jdk/jdk-21.0.13+9/Contents/Home'}
 for kind in ['permissions','badging','xmltree','signer']:
  cmd=([ '/private/tmp/pleos-vehicle-tools-20261105/sdk/build-tools/35.0.0/apksigner','verify','--print-certs',str(dest)] if kind=='signer' else ['/private/tmp/pleos-vehicle-tools-20261105/sdk/build-tools/35.0.0/aapt','dump',kind,str(dest)]+(['AndroidManifest.xml'] if kind=='xmltree' else []))
  r=subprocess.run(cmd,text=True,capture_output=True,env=env)
  output=raw/f'{module}-{kind}.txt';output.write_text(r.stdout+r.stderr)
  commands.append({'module':module,'check':kind,'command':cmd,'exit':r.returncode,'output':str(output),'sha256':sha(output)})
  assert r.returncode==0,(module,kind,r.returncode)
reports=[]
for m in ['app','core']:
 for p in (root/f'prototype/vehicle/{m}/build/test-results').rglob('TEST-*.xml'):
  t=ET.parse(p).getroot();dest=raw/(m+'-'+p.name);shutil.copy2(p,dest)
  reports.append({'suite':t.attrib['name'],'tests':int(t.attrib['tests']),'failures':int(t.attrib['failures']),'errors':int(t.attrib['errors']),'time':float(t.attrib['time']),'sha256':sha(p)})
assert sum(x['tests'] for x in reports)==97
assert sum(x['failures']+x['errors'] for x in reports)==0
lint={}
for m in ['app','nativeDriver']:
 for ext in ['txt','xml']:
  p=root/f'prototype/vehicle/{m}/build/reports/lint-results-debug.{ext}'
  shutil.copy2(p,raw/f'{m}-lint-results-debug.{ext}')
 issues=ET.parse(raw/f'{m}-lint-results-debug.xml').getroot().findall('issue')
 lint[m]={'errors':sum(i.get('severity') in ['Error','Fatal'] for i in issues),'warnings':sum(i.get('severity')=='Warning' for i in issues)}
 assert lint[m]['errors']==0
assert json.loads((raw/'gradle-command.json').read_text())['exit']==0
npm=(raw/'npm.log').read_text();assert 'ℹ tests 76' in npm and 'ℹ pass 76' in npm and 'ℹ fail 0' in npm
(raw/'npm-command.json').write_text(json.dumps({'command':['npm','test'],'exit':0,'tests':76,'pass':76,'fail':0},indent=2)+'\n')
(raw/'inspection-commands.json').write_text(json.dumps(commands,ensure_ascii=False,indent=2)+'\n')
manifest={'issue':14,'base':'7d9dcc053c8dfe2b8ce1f22fef3c17b9c8c54856','baselineHead':'0b92a6b4df1fb97c811842d0f0b455ccc9e5590b','branch':'be/issue-14-vehicle-auto-follow','status':'uncommitted-runtime-freeze-3','frozenAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'runtimeSourceSha256':rollup(runtime),'allBuildInputsSha256':rollup(entries),'runtimeFiles':runtime,'testFiles':tests,'apks':apks,'testReports':reports,'nodeTests':{'tests':76,'pass':76,'exit':0,'logSha256':sha(raw/'npm.log')},'gradle':{'exit':0,'logSha256':sha(raw/'gradle.log')},'lint':lint,'apkInspection':{'checks':len(commands),'exit':0,'commandsSha256':sha(raw/'inspection-commands.json')},'serverChanged':False,'supersedes':{'freeze':2,'sourceManifestSha256':sha(raw/'prior-source-manifest-freeze-2.json'),'runtimeSourceSha256':prior['runtimeSourceSha256'],'changedBuildInputs':changes,'reason':'Independent F1: actual Pleos notification body status had 1.138967:1 contrast. Keep the existing status calculation, place it first in the standard title with role, and remove the duplicate body. Actual new screenshot/continuity verification remains required.'},'runtimeEvidenceStatus':'PENDING at freeze-3 builder handoff: actual notification contrast, fresh 15-minute official two-emulator execution, stop-negative check, and independent exact-head verdict','finalStatusReference':'https://github.com/wontop02/pleos-destination-share/pull/15','scope':'One runtime notification builder-line change; no new tests, SDK policy, permissions, server changes, ADB/UI, or Git mutations by builder. Freeze-1/2 and prior #12 evidence preserved.'}
path=raw/'source-manifest.json';assert not path.exists();path.write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
(evidence/'source-manifest.json').write_bytes(path.read_bytes())
print(json.dumps({'runtimeSourceSha256':manifest['runtimeSourceSha256'],'allBuildInputsSha256':manifest['allBuildInputsSha256'],'runtimeFiles':len(runtime),'testFiles':len(tests),'changedBuildInputs':changes,'testTotal':76+sum(x['tests'] for x in reports),'failed':0,'lint':lint,'apks':apks,'manifest':str(path)},ensure_ascii=False,indent=2))
