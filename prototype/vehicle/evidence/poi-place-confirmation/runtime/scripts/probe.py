import qa,subprocess,shlex,json,re,time,datetime,sys

serial,mode,stem,*extra=sys.argv[1:]
assert serial in ('emulator-5570','emulator-5572')
assert mode in ('query','permission-info','observe','search-screen','bookmarks','route-coordinate-only','route-public-poi','trip-public-poi')
assert re.fullmatch('[A-Za-z0-9-]+',stem) and len(extra)%2==0
assert not (qa.D/'ui'/(stem+'.json')).exists()
allowed={'keyword','durationMs','latitude','longitude','name','address','publicNamesJson','publicDestinationJson'}
assert all(extra[i] in allowed for i in range(0,len(extra),2))
assert qa.U['shell'](serial,'am','get-current-user').decode().strip()=='10'
def pid():
 p=subprocess.run([qa.U['ADB'],'-P','5041','-s',serial,'shell','pidof','ai.pleos.destinations.vehicle'],env=qa.U['ENV'],capture_output=True,text=True)
 return p.stdout.strip() if p.returncode==0 else None
before=pid();args=['am','instrument','--user','10','-w','-r','-e','mode',mode]
for i in range(0,len(extra),2):args+=['-e',extra[i],extra[i+1]]
args+=['ai.pleos.destinations.driver/.NativeDestinationDriver']
argv=[qa.U['ADB'],'-P','5041','-s',serial,'shell',shlex.join(args)]
start=time.monotonic();p=subprocess.run(argv,env=qa.U['ENV'],capture_output=True,text=True,timeout=120)
assert p.returncode==0
m=re.search(r'INSTRUMENTATION_RESULT: probe_json=(\{.*\})',p.stdout);assert m,'Self-target public SDK result missing'
value=json.loads(m.group(1));assert not re.search(r'\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\b',json.dumps(value))
def check_names(v):
 if isinstance(v,dict):
  for k,x in v.items():
   if k in ('name','poiName') and isinstance(x,str) and x not in ('','42dot','소프트웨어드림센터','가든서비스','HD현대오일뱅크 세종주유소','청계산담백미'):
    raise RuntimeError('Native observation outside public test-name allowlist; not persisted')
   check_names(x)
 elif isinstance(v,list):
  for x in v:check_names(x)
check_names(value)
after=pid();record={'hostAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'serial':serial,'user':10,
 'command':{'argv':argv,'exitCode':p.returncode},'seconds':time.monotonic()-start,
 'productionPidBefore':before,'productionPidAfter':after,'productionProcessUnchanged':bool(before) and before==after,'result':value}
(qa.D/'ui'/(stem+'.json')).write_text(json.dumps(record,ensure_ascii=False,indent=2))
qa.mark('publicSdkProbe',serial=serial,mode=mode,stem=stem,status=value.get('status'))
print(json.dumps({'serial':serial,'mode':mode,'status':value.get('status'),'reason':value.get('reason'),
 'seconds':record['seconds'],'routeRequests':value.get('routeRequestCount'),'saved':stem+'.json'},ensure_ascii=False),flush=True)
