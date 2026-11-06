# 저장한 장소 선택과 안내 지점 확인: #21 실행 증거

실행일: 2026-11-06.

저장한 장소를 명시 선택하고 전원이 수락했을 때만 순정 내비에 반영하는 실행 증거다. `pleos_place`는 선택한 장소 설정을 확인한다. 기존 `exact_point`의 좌표 비교는 그대로이며 입구·좌표·경로 탐색 완료가 같다고 주장하지 않는다.

- 기준 `ff3e6a15067ee8285c83f318ec715899ed3f4364`, 검증 source `a26b9419804874052329001ad86e60ff1bccb42c`. 이후 Git 커밋은 이 증거 디렉터리만 추가하며 독립 최종 검토는 해당 정확한 head에 대해 별도로 한다.
- 설치 APK SHA-256 `5845c358d2a63c4ead083c699d94446d0353f3fcedc92f6afb8c0521832cc35a`. C1 설치 뒤 주소 원문을 바로잡은 테스트·문서만 변경되어 C2와 production/APK는 같다. 원래 설치 기록과 [버전 연결 증거](runtime/runtime-source-binding.json)를 모두 보존한다.
- 승인된 ADB5041, user10의 Pleos12Leader/emulator-5570와 Pleos12Follower/emulator-5572, loopback4188 서버만 사용했다. Connect2.1.0/NaviHelper3.0.5/navi-data3.0.4, 내비3.3.2-rc5-hotfix.3 경로다.

| 실제 시나리오 | 결과 | 근거 |
|---|---|---|
| 기존 exact42dot A→B | 수락 전0회, 수락 후 각각1회 요청, 실제 SDK 목적지와 좌표 일치 | [legacy](runtime/legacy-exact-case.json) |
| 카페 A→B | 대기·거절0회, 기존 실제 안내 유지; 수락 후 각각1회 요청, 양쪽 장소 확인 | [거절](runtime/cafe-negative-case.json), [수락](runtime/cafe-accept-case.json) |
| 식당 B→A | 복구 후 새 선택·제안·동의로 대기·거절0회, 수락 후 각각1회; 실제 양쪽 장소 확인 | [거절](runtime/restaurant-retry-negative-case.json), [수락](runtime/restaurant-retry-accept-case.json) |
| 중지→SDK/방 재연결→다시 시작 | 동일 선택 원본·revision3, 추가 경로 요청0회, 내비 화면55초 이상 | [연결 수명](runtime/stop-reconnect-case.json) |
| 양쪽 프로세스 cold 재시작→직접 자동 동행 시작 | 저장값으로 확인 복원 없음, 과거 수락 replay 없음, revision3·선택 원본 유지, 추가 요청0회 | [cold](runtime/cold-resume-case.json) |

카페 원본은 `37.41255277,127.09591666`, 주소 `경기도 성남시 수정구 창업로40번길 6`이다. 양쪽 실제 안내는 `37.412475,127.09534722`, 주소 `수정구 시흥동`이다. 약51.031m 차이를 허용 오차로 숨기지 않았다. 명시한 source/provider/이름/ID/nullable sub-ID가 같음을 실제 후속 SDK 조회로 확인하고 원본/actual을 분리했다. UI의 “선택한 장소로 목적지 설정 확인”과 실제 안내 지점 표시도 캡처했다. 식당은 `청계산담백미`다.

카페는 앱 복귀 없이70초, 식당은50초 이상 직접 유지했다. 연속 수집 자료를 다시 검사한 공통 native 화면 구간은 각각 89.521초 / 67.082초다. 각 구간의 모든 표본에서 app PID/service epoch, uiCount0, 자동 동행, 공유 version/kind를 확인했다. 자기 대상 SDK oracle의 경로 요청 수0과 제품 앱의 `requestRoute` 횟수를 구분한다. 횟수는 연속 occurrence journal의 line offset으로 세며 집합으로 중복을 지우지 않는다. [재검사 결과](runtime/runtime-audit.json), [검사 스크립트](runtime/scripts/audit-runtime.py)

독립 검토자가 정확한 source archive에서 Node94 + core93 + app59 =246개 검사를 실행해 모두 통과했다. `:app:assembleDebug`, `:app:lintDebug`도 통과했다(오류0, 경고6). [독립 명령과 결과](checks/verifier/independent-build/result.json), [Node](checks/verifier/correction-node.json), [빌드 검토](checks/verifier/independent-build-review.md). 새 archive APK는 컨테이너 gap/offset/signing 차이로 전체 hash가 다르며,54개 ZIP entry와 압축 payload는 모두 같다. 실제 기기 검증은 설치 APK5845에만 연결한다.

실패·중단도 보존했다. 원래 RED의 주소 공백 누락은 후속 정정으로 고쳤으며 원본 RAW/freeze-1은 덮어쓰지 않았다. 시연 전 A Android의 activity 서비스가 사라지고 user10이 중지된 구간, 잘못된 기존 진단 row, 재부팅 뒤 reverse 소실과 방 생성 실패는 시나리오 통과 증거로 사용하지 않았다. 카페 성공 뒤 식당 재제안 중 A 서비스가 다시 끊겨 새 프로세스로 복구했다. 중단된 pending 제안은 만료됐고 경로 요청이나 수락을 복원하지 않았다. 해당 실패 로그와 환경 진단을 보존하고 새 선택·동의로 식당 전체 흐름을 다시 검증했다. 저장 공간/메모리는 충분했으며 시스템 중단의 원인은 확정하지 않았다.

기존 exact 수동 lease는45초다. B를 그 뒤 다시 연 캡처에서는 확인이 남아 있지 않았고 검사 스크립트의 늦은 UI 기대가 실패했다. 이를 완료 UI로 세지 않았다. 그 이전 양쪽 fresh확인, 실제 조회, 정확히1회 요청은 원시 journal로 증명한다. 수명 검사 첫 실행도 UI onStop 비동기 반영을 기다리지 않아 멈췄다. 원본을 보존한 뒤 실제 uiCount0을 기다리도록 검증 스크립트만 고쳐 전체 수명 검사를 다시 했다. 독립 archive 준비의 tarfile API 오류도 실제 Gradle 시작 이전의 실패로 구분했다.

구현은 내비에서 ☆로 저장한 공개 장소를 불러오는 범위다. 불러온 목록은 epoch별 snapshot이며 새 저장 항목은 명시 재연결 후 읽는다. 지원하지 않는 provider/빈 ID 등은 거절한다. 휴대폰, 음성·AI, 실차, 다른 SDK/provider와 지도 배경 문제는 이 증거의 검증 범위가 아니다.

실행 주체는 총괄의 실제 ADB/UIAutomator와 별도 문맥의 builtin worker/default 검토자다. 사용자 정의 builder/verifier 역할 파일의 계약을 전달했으며 해당 custom role을 직접 실행했다고 주장하지 않는다. 마지막 독립 최종 판정과 병합 gate는 PR#22에 exact head와 함께 기록한다.
