import qa,json,subprocess,hashlib,datetime
W=qa.D.parents[2]/'issue-21-poi-confirmation'
freeze=qa.D.parent/'builder/freeze-2'
m=json.loads((freeze/'manifest.json').read_text())
def git(*args):return subprocess.check_output(['git','-C',str(W),*args],text=True).strip()
assert git('rev-parse','HEAD')==m['head']
production=['prototype/vehicle/app/src/main','prototype/vehicle/core/src/main','prototype/vehicle/nativeDriver/src/main','prototype/server.mjs','prototype/lib']
assert not git('diff',m['previousHead'],m['head'],'--',*production)
assert hashlib.sha256((freeze/'app-debug.apk').read_bytes()).hexdigest()==m['apkSha256']
installed=json.loads((qa.D/'installed.json').read_text())
record={'at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'sourceHead':m['head'],'runtimeStartupHead':m['previousHead'],
 'reason':'Only tests/docs changed after installation; production inputs and APK are byte-identical. Original startup/install records retained.',
 'productionDelta':[],'apkSha256':m['apkSha256'],'freeze':'../builder/freeze-2/manifest.json','originalInstalledRecord':'installed.json','devices':{}}
for s in ('emulator-5570','emulator-5572'):
 assert qa.U['shell'](s,'am','get-current-user').decode().strip()=='10'
 path=qa.U['shell'](s,'pm','path','--user','10','ai.pleos.destinations.vehicle').decode().strip().removeprefix('package:')
 digest=qa.U['shell'](s,'sha256sum',path).decode().split()[0]
 assert digest==m['apkSha256']
 record['devices'][s]={'installedApkSha256':digest,'user':10}
(qa.D/'runtime-source-binding.json').write_text(json.dumps(record,ensure_ascii=False,indent=2)+'\n')
print('Corrected frozen source bound to identical installed APK on both cars')
