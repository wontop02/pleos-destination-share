import qa,json,time,subprocess,hashlib

d=json.loads((qa.D/'deployment.json').read_text())
c=subprocess.Popen(['python3',str(qa.D/'collect-runtime-freeze4.py')],cwd=qa.D,
                   stdout=(qa.D/'collector.log').open('ab'),stderr=subprocess.STDOUT,start_new_session=True)
(qa.D/'collector-process.json').write_text(json.dumps({'pid':c.pid}))
for label,s in [('A','emulator-5570'),('B','emulator-5572')]:
 qa.show(s)
 for _ in range(3):qa.U['scroll_top'](s)
 deadline=time.monotonic()+15
 while time.monotonic()<deadline and not qa.state(s)['roomConnected']:time.sleep(.5)
 v=qa.snapshot(s,'ui4-historical-'+label);assert v['revision']==4 and v['automatic']=='OFF'
 assert v['applying']!='CONFIRMED' and v['confirmedAtElapsed'] is None
 assert not any(t.startswith('순정 목적지 일치 확인 완료') for t in qa.texts(s))
 qa.U['capture'](s,'ui4-historical-'+label+'-ui')
names=['qa.py','root-ui-tools.py','driver.py','accept-case.py','enable-automatic.py','collect-runtime-freeze4.py','deploy.py','run.py','verify-ui.py']
hashes={p:hashlib.sha256((qa.D/p).read_bytes()).hexdigest() for p in names}
(qa.D/'qa-inputs.json').write_text(json.dumps(hashes,indent=2))
results=[]
for step in [('enable-automatic.py','final4'),('accept-case.py','ui4-accept','A','42dot'),('verify-ui.py',)]:
 assert all(hashlib.sha256((qa.D/p).read_bytes()).hexdigest()==h for p,h in hashes.items())
 argv=['python3',str(qa.D/step[0]),*step[1:]];name=step[0].removesuffix('.py')+'-command.log'
 with (qa.D/name).open('wb') as out:p=subprocess.run(argv,cwd=qa.D,stdout=out,stderr=subprocess.STDOUT,timeout=300)
 results.append({'argv':argv,'exitCode':p.returncode,'log':name})
 (qa.D/'commands.json').write_text(json.dumps(results,indent=2));assert p.returncode==0
 print(step[0],'PASS',flush=True)
(qa.D/'collector.stop').write_text('F4 UI and actual route validation complete\n');time.sleep(2)
assert json.loads((qa.D/'collector-freeze4-result.json').read_text())['readErrors']==0
(qa.D/'result.json').write_text(json.dumps({'pass':True,'head':d['head'],'commands':results,
 'reusedF3RuntimeHead':d['reusedRuntimeHead'],'unchangedProductInputs':59},indent=2))
