import qa,json,hashlib,subprocess,time
from pathlib import Path

R=qa.D.parents[1]
F=R/'builder/freeze-4'
W=R.parents[1]/'issue-17-proposals'
new=json.loads((F/'source-manifest.json').read_text())
old=json.loads((R/'builder/freeze-3/source-manifest.json').read_text())
assert subprocess.check_output(['git','rev-parse','HEAD'],cwd=W,text=True).strip()==new['head']
changed=[p for p,h in new['sourceInputsSha256'].items() if old['sourceInputsSha256'].get(p)!=h]
assert changed==['prototype/vehicle/app/src/main/kotlin/ai/pleos/destinations/vehicle/MainActivity.kt']
assert all(hashlib.sha256((W/p).read_bytes()).hexdigest()==h for p,h in new['sourceInputsSha256'].items())
server=json.loads((qa.D.parent/'backend-freeze-3.json').read_text())
assert server['serverSha256']==new['sourceInputsSha256']['prototype/server.mjs']
assert subprocess.check_output(['ps','-p',str(server['pid']),'-o','command='],text=True).strip()=='node prototype/server.mjs'
records=[]
def command(s,*args):
 argv=[qa.U['ADB'],'-P','5041','-s',s,*args]
 p=subprocess.run(argv,env=qa.U['ENV'],capture_output=True,text=True,timeout=90)
 records.append({'argv':argv,'exitCode':p.returncode,'stdout':p.stdout.strip(),'stderr':p.stderr.strip()})
 (qa.D/'deployment-commands.json').write_text(json.dumps(records,indent=2));assert p.returncode==0
 return p.stdout.strip()
apk=F/'app-debug.apk';sha=hashlib.sha256(apk.read_bytes()).hexdigest();assert sha=='e855437d1c7a44eb5612d56e564d44da3c16b95cd92f5ff7284e34c62e1e4db9'
installed=[]
for s in ['emulator-5570','emulator-5572']:
 qa.show(s)
 for _ in range(6):qa.U['scroll_top'](s)
 qa.U['scroll_bottom'](s);qa.findtap(s,'동행·제안 중지')
 assert qa.state(s)['automatic']=='OFF' and not qa.state(s)['proposalLease']
 assert command(s,'shell','am','get-current-user')=='10'
 assert 'Success' in command(s,'install','--user','10','-r',str(apk))
 for package,expected in [('ai.pleos.destinations.vehicle',sha),('ai.pleos.destinations.driver','8e534886360623b27aede4c49f8fdc1c33b8d89bd2e0b6d2b4949fa59e397ea9')]:
  path=command(s,'shell','pm','path','--user','10',package);assert path.startswith('package:/data/app/')
  actual=command(s,'shell','sha256sum',path.removeprefix('package:')).split()[0];assert actual==expected
  installed.append({'serial':s,'package':package,'installedSha256':actual})
result={'head':new['head'],'reusedRuntimeHead':old['head'],'changedSourceInputs':changed,'unchangedSourceInputs':59,
 'unchangedBackend':server,'serverRestarted':False,'reason':'Only MainActivity render changed; running server source SHA is identical',
 'appSha256':sha,'installed':installed,'commands':records}
(qa.D/'deployment.json').write_text(json.dumps(result,indent=2));print('F4 installed; unchanged server source explicitly reused',flush=True)
