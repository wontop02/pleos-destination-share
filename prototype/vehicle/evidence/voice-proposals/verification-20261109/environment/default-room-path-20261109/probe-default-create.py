from pathlib import Path
import runpy,json,subprocess,time,select,os,io,http.client,re,datetime,sys
D=Path(__file__).resolve().parent
U=runpy.run_path(str(D.parent/'root-ui-tools.py'))
class MemorySocket:
 def __init__(self,data):self.data=data
 def makefile(self,*args,**kwargs):return io.BytesIO(self.data)
def request(serial,method,path,body=None,token=None):
 data=b'' if body is None else json.dumps(body,ensure_ascii=False).encode()
 headers=[method+' '+path+' HTTP/1.1','Host: localhost','Connection: close','Accept: application/json','Content-Length: '+str(len(data))]
 if body is not None:headers.append('Content-Type: application/json')
 if token is not None:
  assert re.fullmatch(r'[A-Za-z0-9_-]{16,256}',token)
  headers.append('Authorization: Bearer '+token)
 payload=('\r\n'.join(headers)+'\r\n\r\n').encode()+data
 process=subprocess.Popen([U['ADB'],'-P','5041','-s',serial,'shell','toybox','nc','-w','2','127.0.0.1','4182'],env=U['ENV'],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
 raw=b''
 try:
  process.stdin.write(payload);process.stdin.flush()
  end=time.monotonic()+5
  while time.monotonic()<end:
   ready,_,_=select.select([process.stdout],[],[],max(0,end-time.monotonic()))
   if not ready:break
   chunk=os.read(process.stdout.fileno(),65536)
   if not chunk:break
   raw+=chunk
   assert len(raw)<=262144
  process.stdin.close();process.stdin=None
  tail,err=process.communicate(timeout=2);raw+=tail
 except Exception:
  process.kill();process.communicate();raise
 if not raw:return {'status':None,'connectRefused':b'Connection refused' in err,'exit':process.returncode},None
 response=http.client.HTTPResponse(MemorySocket(raw));response.begin();content=response.read()
 return {'status':response.status,'connectRefused':False,'exit':process.returncode},content
results=[]
for serial in ['emulator-5570','emulator-5572']:
 result={'serial':serial,'endpoint':'http://127.0.0.1:4182','inputMatchesAppDefault':True,'nameMatchesAppDefault':True}
 created=None
 try:
  created,body=request(serial,'POST','/api/rooms',{'name':'차량 A'})
  result['create']=created
  if created['status'] in [200,201]:
   identity=json.loads(body)
   assert identity['role']=='host' and re.fullmatch(r'[A-Za-z0-9_-]{8,64}',identity['roomId'])
   result['createdIsolatedProbeRoom']=True
   try:
    deleted,_=request(serial,'DELETE','/api/rooms/'+identity['roomId'],token=identity['token'])
    result['cleanupStatus']=deleted['status']
   finally:identity=body=None
  result['pass']=created['status'] in [200,201] and result.get('cleanupStatus') in [200,204]
 except Exception as error:result.update({'pass':False,'errorClass':type(error).__name__})
 results.append(result)
report={'atUtc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'overall':'PASS' if all(r['pass'] for r in results) else 'FAIL','scope':'Actual default-endpoint room POST from both approved cars, isolated probes only; current app rooms/credentials are untouched. Response credentials remain in memory and are never output. This is endpoint/API verification, not a claim of full manual UI verification.','results':results}
name=sys.argv[1] if len(sys.argv)>1 else 'probe-result'
assert re.fullmatch(r'[A-Za-z0-9_-]+',name)
(D/(name+'.json')).write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
print(json.dumps(report,ensure_ascii=False,indent=2))
raise SystemExit(0 if report['overall']=='PASS' else 1)
