import qa as q
import cases as c
import subprocess,json,time,hashlib
original=q.state
def live_state(s):
 p=subprocess.run([q.U['ADB'],'-P','5041','-s',s,'shell','pidof ai.pleos.destinations.vehicle'],env=q.U['ENV'],capture_output=True,text=True,timeout=10)
 if p.returncode or not p.stdout.strip():raise RuntimeError('Owned app process absent; stale state refused')
 return original(s)
q.state=live_state
q.show(c.A)
if q.state(c.A)['automatic']=='OFF':q.findtap(c.A,'동행 시작 · 자동 공유')
q.wait(lambda:q.state(c.A)['automatic']=='LEADER' and q.state(c.B)['automatic']=='FOLLOWER')
time.sleep(3)
q.mark('restaurantRetryAfterEnvironmentRecovery',sourceHead='a26b9419804874052329001ad86e60ff1bccb42c',originalAttemptPreserved=True)
(q.D/'restaurant-retry-inputs.json').write_text(json.dumps({'sha256':{n:hashlib.sha256((q.D/n).read_bytes()).hexdigest() for n in ('restaurant-retry.py','cases.py','qa.py','root-ui-tools.py','probe.py')},'case':'Fresh explicit selection and proposal after interrupted pending proposal expired; actual source re-read; no old consent reused.'},indent=2)+'\n')
c.place('restaurant-retry',c.B,c.A,'청계산담백미',50)
