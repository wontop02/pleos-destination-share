import qa,json,time,subprocess,hashlib

def own_surface(s):
 qa.show(s)
 deadline=time.monotonic()+20
 while time.monotonic()<deadline:
  fg=qa.foreground(s)
  if any('ai.pleos.destinations.vehicle/.MainActivity' in x for x in fg) and '동행 목적지' in qa.texts(s):return
  time.sleep(.5)
 raise AssertionError('Own app surface was not foreground; no controls operated')

def primary(s):
 assert any('ai.pleos.destinations.vehicle/.MainActivity' in x for x in qa.foreground(s))
 text=qa.texts(s)
 # The dump can encode line breaks as literal slash-n. Compare visible wording,
 # not the dump's newline representation; preserve raw XML and screenshots.
 values=[t.replace('\\n','\n') for t in text]
 matches=[t for t in values if t.startswith('순정 목적지 일치 확인 완료') and '마지막 확인 시각 ' in t]
 assert len(matches)==1 and not any('실제 순정 목적지 일치 확인 전' in t for t in values)
 return matches[0],values

observations=[]
for label,s in [('A','emulator-5570'),('B','emulator-5572')]:
 own_surface(s)
 for _ in range(2):qa.U['scroll_top'](s)
 value=qa.snapshot(s,'ui4-completed-'+label);assert value['applying']=='CONFIRMED' and value['confirmedAtElapsed'] is not None
 text,values=primary(s);qa.U['capture'](s,'ui4-completed-'+label+'-ui')
 observations.append({'vehicle':label,'phase':'confirmedForeground','primary':text,'state':value})
 # Find a freshly enabled refresh button before tapping it.
 deadline=time.monotonic()+25
 while time.monotonic()<deadline:
  node=qa.find(s,'현재 목적지 다시 조회',True)
  if node.get('enabled')=='true':
   qa.U['tap_node'](s,node);qa.mark('button',serial=s,label='현재 목적지 다시 조회');break
  time.sleep(.4)
 else:raise AssertionError('Refresh did not become enabled')
 for _ in range(4):qa.U['scroll_top'](s)
 for i in range(3):
  text,values=primary(s);value=qa.state(s)
  assert value['applying']=='CONFIRMED' and value['confirmedAtElapsed'] is not None
  observations.append({'vehicle':label,'phase':'afterManualAndPeriodicQuery-'+str(i),
                       'primary':text,'nativeLoadingTextAlsoObserved':any('현재 목적지 조회 중' in t for t in values),'state':value})
  time.sleep(1)
 qa.U['capture'](s,'ui4-after-refresh-'+label+'-ui');qa.snapshot(s,'ui4-after-refresh-'+label)
result={'pass':True,'head':json.loads((qa.D/'deployment.json').read_text())['head'],
        'acceptedCase':'ui4-accept-result.json','observations':observations,
        'completedCardPersistsAfterManualAndPeriodicQueries':True,
        'initialHostAttempt':'verify-ui-command.log','scriptSha256':hashlib.sha256(qa.Path(__file__).read_bytes()).hexdigest()}
(qa.D/'ui-verdict.json').write_text(json.dumps(result,ensure_ascii=False,indent=2));print('Both completed cards and last-confirmation times persist after refresh',flush=True)
