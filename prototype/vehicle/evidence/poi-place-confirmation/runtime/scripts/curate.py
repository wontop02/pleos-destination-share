from pathlib import Path
import json,hashlib,shutil,datetime,re,subprocess
D=Path(__file__).resolve().parent;RAW=D.parent;W=D.parents[2]/'issue-21-poi-confirmation'
OUT=W/'prototype/vehicle/evidence/poi-place-confirmation'
assert not OUT.exists()
OUT.mkdir(parents=True)
copied=[]
def cp(source,target):
 p=OUT/target;p.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(source,p);copied.append({'path':target,'source':str(source),'sha256':hashlib.sha256(p.read_bytes()).hexdigest(),'bytes':p.stat().st_size})
cp(RAW/'issue.md','inputs/issue.md');cp(RAW/'run.json','inputs/run.json')
for n in ('manifest.json','source-sha256.json','baseline-preservation.json','lint-results-debug.xml','lint-results-debug.txt'):
 cp(RAW/'builder/freeze-2'/n,'checks/builder/'+n)
for f in (RAW/'builder/freeze-2/junit').rglob('*.xml'):cp(f,'checks/builder/junit/'+str(f.relative_to(RAW/'builder/freeze-2/junit')))
for n in ('handoff.md','handoff-2.md','full-node.json','full-node.log','provenance-node.json','provenance-node.log','provenance-vehicle.json','provenance-vehicle.log','baseline-red.json','baseline-red.log','baseline-red-source.json','baseline-red-source.kt','baseline-red.junit.xml','publication-lifetime-red.json','publication-lifetime-red.log','publication-lifetime-red.junit.xml','node-first-attempt.json','run-check.py','run-build.py'):
 f=RAW/'builder'/n
 if f.is_file():cp(f,'checks/builder/'+n)
for n in ('source-review.md','source-correction-review.md','independent-build-review.md','source-integrity.json','correction-integrity.json','correction-node.json','correction-node.log'):
 cp(RAW/'verifier'/n,'checks/verifier/'+n)
for n in ('inputs.json','result.json','gradle.log','preparation-failure.json','apk-comparison.json','apk-layout-comparison.json','apk-signing-container-comparison.json'):
 cp(RAW/'verifier/independent-build-a26b941'/n,'checks/verifier/independent-build/'+n)
for kind,folder in (('core','core/build/test-results/test'),('app','app/build/test-results/testDebugUnitTest')):
 for f in (RAW/'verifier/independent-build-a26b941/source/prototype/vehicle'/folder).glob('*.xml'):
  cp(f,'checks/verifier/independent-build/junit/'+kind+'/'+f.name)
names=['actions.jsonl','observations.jsonl','emulator-5570-journal.jsonl','emulator-5572-journal.jsonl','collector-result.json','installed.json','runtime-source-binding.json','emulators-launched.json','owned-server.json','fresh-room.json','automatic-started.json','legacy-exact-case.json','legacy-exact-case.log','cafe-negative-case.json','cafe-accept-case.json','cafe-case.log','restaurant-negative-case.json','restaurant-case.log','restaurant-retry-negative-case.json','restaurant-retry-accept-case.json','restaurant-retry-case.log','restaurant-retry-inputs.json','lifetime-checkpoints.json','lifetime-inputs.json','stop-reconnect-case.json','cold-resume-checkpoints.json','cold-resume-case.json','lifetime-case.log','runtime-audit.json','qa-inputs-frozen.json','final-demo-ready.json','environment-after-restaurant-interrupt-emulator-5570.json','environment-after-restaurant-interrupt-emulator-5572.json']
for n in names:cp(D/n,'runtime/'+n)
for n in ('qa.py','root-ui-tools.py','cases.py','lifetime-case.py','restaurant-retry.py','probe.py','collect-runtime.py','create-room.py','finish-room.py','rebind-source.py','audit-runtime.py','cases-before-legacy-capture-correction.py','curate.py','prepare-final-demo.py'):
 cp(D/n,'runtime/scripts/'+n)
for n in ('manifest.json','create-room.log','collector.log'):
 cp(D/'preqa-attempt-1'/n,'runtime/interruptions/preqa-attempt-1/'+n)
for n in ('create-room-attempt-2.log','create-room.log','finish-room.log'):
 cp(D/n,'runtime/interruptions/'+n)
for f in (D/'lifetime-interrupted').iterdir():
 if f.is_file():cp(f,'runtime/interruptions/lifetime-ui-wait/'+f.name)
for f in (D/'ui').iterdir():
 if not f.is_file() or f.name.startswith(('fresh-room-','user0-')):continue
 if f.suffix in ('.json','.png','.txt','.jsonl'):cp(f,'runtime/ui/'+f.name)
audit=json.loads((D/'runtime-audit.json').read_text());assert audit['verdict']=='PASS'
node=json.loads((RAW/'verifier/correction-node.json').read_text());build=json.loads((RAW/'verifier/independent-build-a26b941/result.json').read_text())
assert build['exitCode']==0 and build['junit']['core']['tests']==93 and build['junit']['app']['tests']==59
manifest={'base':'ff3e6a15067ee8285c83f318ec715899ed3f4364','sourceHead':audit['sourceHead'],'branch':'be/issue-21-poi-confirmation','issue':21,'pullRequest':22,'apkSha256':audit['apkSha256'],'createdAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'runtimeAudit':'runtime/runtime-audit.json','scope':'Approved two Pleos virtual cars only; no phones or real vehicle, vendor private logs, tokens, accounts, payment, mail or deployment.','sourceVersusEvidence':'Final Git head adds this evidence directory only after sourceHead; final verifier must audit that exact head separately.','files':copied,'generatedFiles':['README.md','manifest.json'],'oldArtifactsPreserved':True,'exclusions':'APK/SDK binaries, environment files, invitation secrets, credentials and full bookmark lists are not copied. Precase corrupted journal bytes stay in original RAW quarantine; their hashes and failure provenance are included.'}
(OUT/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
cafe=audit['cases']['cafe'];restaurant=audit['cases']['restaurant-retry']
text=f'''# 저장한 장소 선택과 안내 지점 확인: #21 실행 증거

실행일: 2026-11-06.

저장한 장소를 명시 선택하고 전원이 수락했을 때만 순정 내비에 반영하는 실행 증거다. `pleos_place`는 선택한 장소 설정을 확인한다. 기존 `exact_point`의 좌표 비교는 그대로이며 입구·좌표·경로 탐색 완료가 같다고 주장하지 않는다.

- 기준 `ff3e6a15067ee8285c83f318ec715899ed3f4364`, 검증 source `{audit['sourceHead']}`. 이후 Git 커밋은 이 증거 디렉터리만 추가하며 독립 최종 검토는 해당 정확한 head에 대해 별도로 한다.
- 설치 APK SHA-256 `{audit['apkSha256']}`. C1 설치 뒤 주소 원문을 바로잡은 테스트·문서만 변경되어 C2와 production/APK는 같다. 원래 설치 기록과 [버전 연결 증거](runtime/runtime-source-binding.json)를 모두 보존한다.
- 승인된 ADB5041, user10의 Pleos12Leader/emulator-5570와 Pleos12Follower/emulator-5572, loopback4188 서버만 사용했다. Connect2.1.0/NaviHelper3.0.5/navi-data3.0.4, 내비3.3.2-rc5-hotfix.3 경로다.

| 실제 시나리오 | 결과 | 근거 |
|---|---|---|
| 기존 exact42dot A→B | 수락 전0회, 수락 후 각각1회 요청, 실제 SDK 목적지와 좌표 일치 | [legacy](runtime/legacy-exact-case.json) |
| 카페 A→B | 대기·거절0회, 기존 실제 안내 유지; 수락 후 각각1회 요청, 양쪽 장소 확인 | [거절](runtime/cafe-negative-case.json), [수락](runtime/cafe-accept-case.json) |
| 식당 B→A | 복구 후 새 선택·제안·동의로 대기·거절0회, 수락 후 각각1회; 실제 양쪽 장소 확인 | [거절](runtime/restaurant-retry-negative-case.json), [수락](runtime/restaurant-retry-accept-case.json) |
| 중지→SDK/방 재연결→다시 시작 | 동일 선택 원본·revision3, 추가 경로 요청0회, 내비 화면55초 이상 | [연결 수명](runtime/stop-reconnect-case.json) |
| 양쪽 프로세스 cold 재시작→직접 자동 동행 시작 | 저장값으로 확인 복원 없음, 과거 수락 replay 없음, revision3·선택 원본 유지, 추가 요청0회 | [cold](runtime/cold-resume-case.json) |

카페 원본은 `37.41255277,127.09591666`, 주소 `경기도 성남시 수정구 창업로40번길 6`이다. 양쪽 실제 안내는 `37.412475,127.09534722`, 주소 `수정구 시흥동`이다. 약51.031m 차이를 허용 오차로 숨기지 않았다. 명시한 source/provider/이름/ID/nullable sub-ID가 같음을 실제 후속 SDK 조회로 확인하고 원본/actual을 분리했다. UI의 “선택한 장소로 목적지 설정 확인”과 실제 안내 지점 표시도 캡처했다. 식당은 `청계산담백미`다.

카페는 앱 복귀 없이70초, 식당은50초 이상 직접 유지했다. 연속 수집 자료를 다시 검사한 공통 native 화면 구간은 각각 {cafe['auditedWindowSeconds']:.3f}초 / {restaurant['auditedWindowSeconds']:.3f}초다. 각 구간의 모든 표본에서 app PID/service epoch, uiCount0, 자동 동행, 공유 version/kind를 확인했다. 자기 대상 SDK oracle의 경로 요청 수0과 제품 앱의 `requestRoute` 횟수를 구분한다. 횟수는 연속 occurrence journal의 line offset으로 세며 집합으로 중복을 지우지 않는다. [재검사 결과](runtime/runtime-audit.json), [검사 스크립트](runtime/scripts/audit-runtime.py)

독립 검토자가 정확한 source archive에서 Node94 + core93 + app59 =246개 검사를 실행해 모두 통과했다. `:app:assembleDebug`, `:app:lintDebug`도 통과했다(오류0, 경고6). [독립 명령과 결과](checks/verifier/independent-build/result.json), [Node](checks/verifier/correction-node.json), [빌드 검토](checks/verifier/independent-build-review.md). 새 archive APK는 컨테이너 gap/offset/signing 차이로 전체 hash가 다르며,54개 ZIP entry와 압축 payload는 모두 같다. 실제 기기 검증은 설치 APK5845에만 연결한다.

실패·중단도 보존했다. 원래 RED의 주소 공백 누락은 후속 정정으로 고쳤으며 원본 RAW/freeze-1은 덮어쓰지 않았다. 시연 전 A Android의 activity 서비스가 사라지고 user10이 중지된 구간, 잘못된 기존 진단 row, 재부팅 뒤 reverse 소실과 방 생성 실패는 시나리오 통과 증거로 사용하지 않았다. 카페 성공 뒤 식당 재제안 중 A 서비스가 다시 끊겨 새 프로세스로 복구했다. 중단된 pending 제안은 만료됐고 경로 요청이나 수락을 복원하지 않았다. 해당 실패 로그와 환경 진단을 보존하고 새 선택·동의로 식당 전체 흐름을 다시 검증했다. 저장 공간/메모리는 충분했으며 시스템 중단의 원인은 확정하지 않았다.

기존 exact 수동 lease는45초다. B를 그 뒤 다시 연 캡처에서는 확인이 남아 있지 않았고 검사 스크립트의 늦은 UI 기대가 실패했다. 이를 완료 UI로 세지 않았다. 그 이전 양쪽 fresh확인, 실제 조회, 정확히1회 요청은 원시 journal로 증명한다. 수명 검사 첫 실행도 UI onStop 비동기 반영을 기다리지 않아 멈췄다. 원본을 보존한 뒤 실제 uiCount0을 기다리도록 검증 스크립트만 고쳐 전체 수명 검사를 다시 했다. 독립 archive 준비의 tarfile API 오류도 실제 Gradle 시작 이전의 실패로 구분했다.

구현은 내비에서 ☆로 저장한 공개 장소를 불러오는 범위다. 불러온 목록은 epoch별 snapshot이며 새 저장 항목은 명시 재연결 후 읽는다. 지원하지 않는 provider/빈 ID 등은 거절한다. 휴대폰, 음성·AI, 실차, 다른 SDK/provider와 지도 배경 문제는 이 증거의 검증 범위가 아니다.

실행 주체는 총괄의 실제 ADB/UIAutomator와 별도 문맥의 builtin worker/default 검토자다. 사용자 정의 builder/verifier 역할 파일의 계약을 전달했으며 해당 custom role을 직접 실행했다고 주장하지 않는다. 마지막 독립 최종 판정과 병합 gate는 PR#22에 exact head와 함께 기록한다.
'''
(OUT/'README.md').write_text(text)
for f in OUT.rglob('*'):
 if f.is_file() and f.suffix!='.png':
  raw=f.read_bytes()
  assert not re.search(rb'\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\b',raw),'Credential-shaped data; do not stage'
print(json.dumps({'evidencePath':str(OUT),'copiedFiles':len(copied),'bytes':sum(x['bytes'] for x in copied),'runtimeAudit':'PASS'},ensure_ascii=False))
