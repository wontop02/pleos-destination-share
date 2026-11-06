import json, os, subprocess, sys, time
from pathlib import Path
root=Path(__file__).resolve().parent
cfg=json.loads(Path('/Users/wontop/Documents/ChatGPT/현대 모비스 해커톤/pleos-destination-share/.harness/worktrees/product-run-artifacts/pleos-vehicle-20261105/toolchain/build-env.json').read_text())
env=os.environ.copy(); env.update(cfg['variables'])
env['PATH']=str(Path(env['JAVA_HOME'])/'bin')+os.pathsep+env['PATH']
name=sys.argv[1]; args=[cfg['gradle'],'--no-daemon','--offline',*sys.argv[2:]]
start=time.time()
with (root/(name+'.log')).open('w') as log:
 result=subprocess.run(args,cwd='/Users/wontop/Documents/ChatGPT/현대 모비스 해커톤/pleos-destination-share/.harness/worktrees/issue-21-poi-confirmation/prototype/vehicle',env=env,stdout=log,stderr=subprocess.STDOUT)
record={'command':args,'exit':result.returncode,'elapsedSeconds':round(time.time()-start,2)}
(root/(name+'.json')).write_text(json.dumps(record,ensure_ascii=False,indent=2)+'\n')
print(json.dumps(record,ensure_ascii=False))
sys.exit(result.returncode)
