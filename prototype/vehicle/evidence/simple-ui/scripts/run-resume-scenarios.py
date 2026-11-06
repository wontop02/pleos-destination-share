"""Sequential final-source UI scenarios; stop at the first failure."""
from pathlib import Path
import subprocess,sys,json,datetime
D=Path(__file__).resolve().parent
assert (D/'post-native-recovery-ready.json').exists()
completed=[]
for script,args,log in [('proposal-cases.py',['cafe'],'cafe.log'),('proposal-cases.py',['restaurant'],'restaurant.log'),('finish-ui-qa.py',[],'finish-ui-qa.log'),('audit-final-runtime.py',[],'runtime-audit.log')]:
    item={'script':script,'args':args,'log':log,'startedAt':datetime.datetime.now(datetime.timezone.utc).isoformat()}
    (D/'resume-qa-progress.json').write_text(json.dumps({'phase':'RUNNING','current':item,'completed':completed},indent=2)+'\n')
    with (D/log).open('w') as out:
        try: result=subprocess.run([sys.executable,'-u',str(D/script),*args],cwd=D,stdout=out,stderr=subprocess.STDOUT,timeout=600);code=result.returncode
        except subprocess.TimeoutExpired:code=124
    item.update({'endedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'exitCode':code});completed.append(item)
    (D/'resume-qa-progress.json').write_text(json.dumps({'phase':'PASS' if not code else 'STOPPED','completed':completed},indent=2)+'\n')
    print(script,args,'exit',code,flush=True)
    if code:sys.exit(code)
