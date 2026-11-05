import qa,json,time

case=json.loads((qa.D/'ui4-accept-result.json').read_text());assert case['pass']
observations=[]
for label,s in [('A','emulator-5570'),('B','emulator-5572')]:
 qa.show(s)
 for _ in range(3):qa.U['scroll_top'](s)
 v=qa.snapshot(s,'ui4-completed-'+label)
 assert v['applying']=='CONFIRMED' and v['confirmedAtElapsed'] is not None and v['roomConnected']
 texts=qa.texts(s)
 primary=[t for t in texts if t.startswith('순정 목적지 일치 확인 완료\n마지막 확인 시각 ')]
 assert len(primary)==1 and '실제 순정 목적지 일치 확인 전' not in '\n'.join(texts)
 qa.U['capture'](s,'ui4-completed-'+label+'-ui')
 observations.append({'vehicle':label,'phase':'afterBackgroundConfirmation','primary':primary[0],'state':v})
 qa.findtap(s,'현재 목적지 다시 조회')
 for _ in range(4):qa.U['scroll_top'](s)
 for i in range(3):
  texts=qa.texts(s);v=qa.state(s)
  primary=[t for t in texts if t.startswith('순정 목적지 일치 확인 완료\n마지막 확인 시각 ')]
  assert len(primary)==1 and v['applying']=='CONFIRMED' and v['confirmedAtElapsed'] is not None
  observations.append({'vehicle':label,'phase':'manualAndAutomaticRefresh-'+str(i),'primary':primary[0],
                       'nativeLoadingTextAlsoObserved':any('현재 목적지 조회 중' in t for t in texts),'state':v})
  time.sleep(1)
 qa.U['capture'](s,'ui4-after-refresh-'+label+'-ui');qa.snapshot(s,'ui4-after-refresh-'+label)
result={'pass':True,'head':json.loads((qa.D/'deployment.json').read_text())['head'],
        'acceptedCase':'ui4-accept-result.json','observations':observations,
        'completedCardPersistsAfterManualAndPeriodicQueries':True}
(qa.D/'ui-verdict.json').write_text(json.dumps(result,ensure_ascii=False,indent=2));print('Both completed cards and last-confirmation times persist after refresh',flush=True)
