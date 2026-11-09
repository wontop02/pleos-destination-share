from pathlib import Path
import json,datetime,re
P=Path('/Users/wontop/Documents/ChatGPT/현대 모비스 해커톤/pleos-destination-share');R=P/'.harness/worktrees/product-run-artifacts/demo-launcher-20261109'
source=P/'.harness/worktrees/product-run-artifacts/voice-proposals-20261108/root/default-room-path-20261109/probe-default-create.py'
namespace={'__file__':str(source)};exec(compile(source.read_text().split('\nresults=[]')[0].replace("'4182'",'str(PROBE_PORT)'),str(source),'exec'),namespace);request=namespace['request']
results=[]
for serial,port in [(serial,port) for serial in ['emulator-5570','emulator-5572'] for port in [4182,4192]]:
 namespace['PROBE_PORT']=port
 value={'serial':serial,'endpoint':'http://127.0.0.1:'+str(port),'nameMatchesAppDefault':True};identity=None;body=None
 try:
  create,body=request(serial,'POST','/api/rooms',{'name':'차량 A'});value['create']=create;assert create['status']==201
  identity=json.loads(body);assert identity['role']=='host' and re.fullmatch(r'[A-Za-z0-9_-]{8,64}',identity['roomId'])
  deleted,_=request(serial,'DELETE','/api/rooms/'+identity['roomId'],token=identity['token']);value['delete']=deleted;assert deleted['status']==200
  value['pass']=True
 except Exception as error:value['pass']=False;value['errorClass']=type(error).__name__
 finally:identity=body=None
 results.append(value)
report={'atUtc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'pass':all(x['pass'] for x in results),'scope':'Operator QA isolated room create201/delete200 from both actual guests at4182 and4192. Existing app room is not mutated. Response roomId/token exist only in memory/stdin, never printed or saved. This is actual API verification, not a claim of complete new-room manualUI. Launcher never performs room management.','results':results}
path=R/'final-default-api-result.json';assert not path.exists();path.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n');print(json.dumps(report,ensure_ascii=False,indent=2));raise SystemExit(0 if report['pass'] else 1)
