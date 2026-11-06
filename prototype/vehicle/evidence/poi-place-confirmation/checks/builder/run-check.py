import json, subprocess, sys, time
from pathlib import Path
root=Path(__file__).resolve().parent
name=sys.argv[1]; args=sys.argv[2:]; start=time.time()
with (root/(name+'.log')).open('w') as log:
 result=subprocess.run(args,cwd='/Users/wontop/Documents/ChatGPT/현대 모비스 해커톤/pleos-destination-share/.harness/worktrees/issue-21-poi-confirmation',stdout=log,stderr=subprocess.STDOUT)
record={'command':args,'exit':result.returncode,'elapsedSeconds':round(time.time()-start,2)}
(root/(name+'.json')).write_text(json.dumps(record,ensure_ascii=False,indent=2)+'\n'); print(json.dumps(record));sys.exit(result.returncode)
