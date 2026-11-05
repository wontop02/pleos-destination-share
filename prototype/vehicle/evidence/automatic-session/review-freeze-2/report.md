# Issue #14 / Draft PR #15 독립 검증: FAIL

현재 정확한 head에서 필수 진행 알림 상태를 읽기 어려운 결함 F1을 실제 공식 Pleos 이미지에서 재현했다. AC1/AC3은 FAIL이다. 자동 공유·적용·실제 목적지 일치·중지 차단과 15분 원본 무결성은 직접 확인했다. 병합 가능한 PASS 판정이 아니다.

## 고정 입력과 독립성

- Base `7d9dcc053c8dfe2b8ce1f22fef3c17b9c8c54856` / Head `0b92a6b4df1fb97c811842d0f0b455ccc9e5590b` / `be/issue-14-vehicle-auto-follow`. 시작·종료 GitHub 조회에서 이슈 원문, main base, PR head/open/Draft가 같았고 WT는 clean이었다. `remote-inputs-start.json`, `remote-inputs-end.json`, `commands-end.json` 참조.
- RAW 이슈 원문 SHA-256 `1ca59db75b55dd39a958c2bc9237b76b3bec7dd7b90b46f41257356bb17268b0`. GitHub body SHA-256 `a622def5a306558146e88f61f48cb1f5f42dac41e9d6cf107762d82544ce0f5a`와 다른 것은 RAW 끝의 개행 1개다. 문자열 본문은 완전히 같음을 직접 assert했다. 계약 SHA-256 `2c6f6693d831c91effe5d5964178fbc2a5fc9c286abb4c5d26029c1d5ca6baf1`.
- 사용자 정의 verifier 역할이 도구 목록에 노출되지 않아 총괄이 builtin default에 동일 행동 계약을 전달했다. 구현·총괄과 별도 문맥으로 `AGENTS.md`, `docs/harness/README.md`, `.codex/agents/verifier.toml`을 직접 읽고 검사했다. 자동 발견/사용자 정의 역할 실행이나 별도 보안 sandbox를 주장하지 않는다.
- 검사 대상 소스·테스트·문서·GitHub를 수정하지 않았다. `noCommit=true`, `noSourceChange=true`, `noGHMutation=true`. 생성한 검사 코드와 보고서는 이 `verifier/`에만 있다. 빌드 산출물은 기존 ignored build 디렉터리에서 재생성했다.

## 발견 결함 F1 — 필수 알림 상태 가독성

`prototype/vehicle/app/src/main/kotlin/ai/pleos/destinations/vehicle/ConvoySessionService.kt:132`는 역할만 밝은 제목에 넣고, 요청/확인 중/일치/실패/대기 상태를 `setContentText(status)`와 BigText 본문에만 넣는다. 실제 공식 B SystemUI에서 `순정 목적지 일치 확인` 본문은 RGB(51,51,51), 배경은 RGB(40,42,45)이며 실제 본문 영역의 가장 강한 대비도 **1.138967:1**이다. 제목과 중지 버튼은 밝게 표시되어 이 본문만 읽기 어렵다.

직접 육안 확인한 무편집 PNG: `ui/06-follower-notification.png`. 새 XML의 본문 bounds `[272,182][479,212]`, 문자열과 연결 상태: `06-notification-inspection.json`. 픽셀 분석: `notification-contrast.json`. 총괄의 기존 `../ui/22-notification-panel.png`에도 같은 현상이 있지만 독립 재현 증거를 판정에 사용했다. `DESIGN.md:50`의 상태 표시, `DESIGN.md:55`의 고대비 본문/상태 요구와 AC1/AC3의 화면 밖 상태 확인을 충족하지 못한다. [W3C 대비 지침](https://www.w3.org/WAI/WCAG22/Understanding/contrast-minimum.html)은 읽히는 텍스트 대비의 참고 기준이며 전체 접근성 인증을 주장하지 않는다.

수정은 실제 알림에서 현재 상태를 읽을 수 있게 해야 한다. 표준 Notification API 사용만으로 실제 렌더링이 적절하다고 간주하지 않았다. 소스 수정은 총괄에 반환하며 새 APK/source 동결 및 정확한 새 head 재검증이 필요하다.

## AC별 판정

| AC | 판정 | 직접 근거 |
|---|---|---|
| AC1 | FAIL | 기본 OFF·역할별 새 UI opt-in·onStop 독립 FGS·specialUse·최소6권한·실제 알림 중지는 확인. 상태 본문 F1로 실패. `02-default-off.json`, `03-independent-opt-in.json`, `07-follower-stop-receipt.json`, `09-leader-stop-receipt.json`, `apk-inspection-commands.json`. 소스 `MainActivity.kt:73`, `ConvoySessionService.kt:73`, `AndroidManifest.xml:18`. |
| AC2 | PASS | 대표 실제 native 변경→공유 rev9→11, null/새목적지 발행2회, B발행0. 원본15분 A발행6(null/목적지 각3), B발행0. `05-independent-change.json`, `05-source-dream.json`, `evidence-recalculation.json`. 소스 `VehicleCoordinator.kt:304`, `:396`, `:428`, normalizer exact empty; 기존 server/v1 diff없음. |
| AC3 | FAIL | 기존 최신42dot 자동 적용과 새Dream 자동 적용을 실제 후속 query로 strict match. B새요청1·일치1·화면재진입0. 원본3요청/3일치와 원래45초 작업기한·stale listener/version/room 취소 회귀 확인. 알림 확인결과 가독성 F1로 실패. `04-latest-confirmed.json`, `05-independent-change.json`, `ui/06-follower-notification.png`. 소스 `VehicleCoordinator.kt:199`, `:259`, `:345`, `:408`. |
| AC4 | PASS | 새 실제 SystemUI 중지 뒤 A rev11→13에도 B새요청0·native Dream유지·OFF/FGS없음. 비밀 패턴 검출0. `08-stop-negative-result.json`, `08-stop-negative-final-events.jsonl`, `09-resource-final.json`, `secret-scan.json`. 소스 `VehicleSession.kt:198`, `:232`, `RoomHttpClient.kt:102`, `SecureSessionStore.kt:25`; 실제 HTTP/SSE 및 의미있는 경합 회귀97개. |
| AC5 | PASS | 40입력/설치APK/96원본복사·57쌍을 재계산. 907.743초·동일PID/FGSepoch·재진입0·세변경·10분뒤변경·후속실제조회3일치·중지후77.9675초 요청0. `input-hashes.json`, `evidence-recalculation.json`. AC1/3의 F1은 별도이며 전체는 FAIL. |

## 원본 실증 재계산

`python3 verifier/verify_evidence.py` **exit0**. 각 파일을 직접 읽고 Git head blob·RAW·커밋된 복사본·manifest SHA를 대조했다. 40개 build input, 원본 복사96개, frozen APK3개가 일치했다. 원본 재계산 코드는 `verify_evidence.py`; 개별 직접해시는 `input-hashes.json`; 실행한 Git 명령과 exit는 `commands-static.json`이다. runtime source SHA `76c4350628c272f0c04d6405b2819c732954ab9a6ef2db405ed36d925034e0cb`, 전체40입력 SHA `72af78492463b80978ad5882ea229114c94ce4db5bd7e0eb2de0fda9dc93a5a9`.

원본 `continuous-freeze2/result.json:5`의907.743초는 최종 실제 host monotonic 관측에서도 최소 `906.327509`초, 기기 uptime 증가907.75/907.74초와 맞았다. 57쌍 모두 native 전경·우리 Activity STOPPED/uiCount0·FGS type40000000을 유지했다. A PID/epoch `20878/12836184`, B `4948/12421167` 유지. baseline journal prefix가 손실 없이 이어졌으며 이후 재진입·serviceCreate/Destroy·autoStop은 없었다. 전체 journal에 이전 freeze1 이력이 있지만 baseline 이전으로 분리했고 성공 횟수에 넣지 않았다.

원본 `changes.json:5`, `:545`, `:1118`에서 시작 +24.436/+302.358/+633.147초의 Dream/42dot/Dream 변경을 직접 확인했다. source driver 실제 후속 값과 B의 독립 query 값의 이름·위경도0.00001·POI ID·nullable sub-ID·provider를 다시 비교했다. B requestRoute의 같은 operation에 이후 실제 read와 match가 있고45초 안에 확인됐다. A/B driver target은 별도 자기 패키지이고 productionPID는 전후 같았다. B shared revision3/5/7과 요청 각1, match각1이었다. source의 전체 empty 관측3개와 대표null발행·다음목적지발행이 총6개로 연결된다.

원본 `continuous-freeze2/result.json:194`의 stopNegativeStatus PENDING은 수정하지 않았다. 최종 `stop-negative-freeze2/result.json:5`의77.967521208초, `:7`의새요청0, `:439`/`:440`의rev7→9와 journal prefix/후속 driver값을 별도로 연결해 재확인했다.

## 독립 실제 재현과 관측 오류의 구분

전용ADB5041·`emulator-5570/5572`·user10만 사용했다. `01-preflight.json`은 공식 `Pleos/crp_avd_arm64...release_connect_v2.1.0` fingerprint, Pleos12Leader/Follower AVD, 설치 app SHA `f96663e4da4ab428e9656841b0d2e4bbce592008e712eb0e79a0e9af8ee57fe4`와 driver SHA `d71239e388a415489aa163f7d7a809eb9d0cfbf92ed94d6f786f66eb1a5a406f`를 직접 기기에서 읽어 원본과 대조했다. 시작 시 두autoOFF/FGS없음을 확인하고 저장된 암호화 credentials를 앱 UI에서 재사용했다. credential/key 파일은 읽지 않았다.

실제 역할별 시작 버튼을 새로 눌렀다. A를 native로 전환한 직후 첫 검사 스크립트의 uiCount==0 즉시 assert가 비동기 onStop보다 앞서 실패했다. `03-opt-in-observation-failure.json`/`03-opt-in-observation-events-*`를 그대로 보존했다. 후속 관측에서 동일PID/동일새epoch의 A LEADER·FGStrue·STOPPED/uiCount0를 확인했고 당시 B는 아직 opt-in 전 OFF였다. 재설치/재시작/두번째 A opt-in 없이 B 실제 시작을 수행해 제품 결함과 관측 경합을 구분했다. 새 연속15분 성공으로 세지 않았다.

`04-latest-confirmed.json`에서 B는 최신42dot을 별도적용버튼 없이 반영했다. `05-independent-change.json`에서 A를 실제Dream으로 바꿔 rev9→11/B새요청1·strictmatch1·B발행0·A/B재진입0을 직접 재현했다. 모든 driver 호출은 `ai.pleos.destinations.driver/.NativeDestinationDriver`의 자기 target 계측이며 productionPID20878/4948과 새 FGSepoch15268336/14849405가 유지됐다. 이 native query는 공식 NaviHelper3.0.5를 직접 호출하며 단위테스트·root의 PASS 문자열만으로 인정하지 않았다.

`07-follower-stop-receipt.json`의 실제 SystemUI 자기역할 알림 중지 뒤 A를42dot으로 바꿨다. source 완료 이후 **86.621545초** 독립 관찰에서 B새요청0·Activity재진입0·Dream유지·OFF/FGS없음, A rev11→13을 확인했다. `08-stop-negative-result.json`과 전후실제query·journal을 보존했다. `09-leader-stop-receipt.json`으로 A도 실제 알림 중지했다. 명령·exit는 `commands-adb.jsonl`, 제어 코드는 `vehicle_probe.py`다.

## 독립 빌드·회귀·권한 검사

`commands-build.json`: `npm test` **exit0**,76통과/실패0. 전용 build-env의 변수/JDK/SDK/Gradle을 해당 subprocess에만 합쳤다. `gradle --no-daemon --rerun-tasks :core:test :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :nativeDriver:assembleDebug :app:lintDebug :nativeDriver:lintDebug` **exit0**. core71/app26, 합계173통과. `independent-junit-summary.json`, 실제JUnit8개·`gradle-independent.log`·`npm-independent.log` 참조. fake SDK로 대체하지 않았다.

`apk-inspection-commands.json`: frozen app/probe/driver에 aapt permissions/badging/xmltree와 apksigner 검증12개 **exit0**. app권한은 internet·NAVI2·FGS·specialUse·POST_NOTIFICATIONS의6개. 서비스는 nonexported specialUse/subtype, driver는testOnly·자기target·Activity없음이다. lint오류0, app경고5(미사용리소스2/아이콘1/문자열2), driver경고2(backup설명/아이콘). 기준을 완화하지 않았다.

독립 재빌드 app/driver의 전체APK hash는 패키징 entry순서 차이로 frozen과 달랐다. `rebuilt-apk-entry-comparison.json`에서 **모든 ZIP entry의 실제 바이트와 timestamp는 동일**하고 entry순서만 달랐음을 직접 확인했다. probeAPK는 전체hash도 동일하다. 설치본은 frozen APK그대로이며 재설치하지 않았다.

`commands-static.json`: 전체 `git diff --check` **exit2**, runtime원본 Gradle로그2행의 후행공백1개뿐이다. runtime원본 제외 코드/문서 검사 **exit0**. 원본SHA유지를 위해 공백을 수정하지 않았다. `prototype/server.mjs`, 기존웹/휴대폰/하네스/.codex/.github는 diff없음. 커밋runtime96파일 credential/URL/privatekey 패턴 검사0건(`secret-scan.json`); AES-GCM/AndroidKeyStore·헤더인증·redirect거절 소스도 직접 읽었다.

공식 근거도 직접 조회했다: [Pleos 수명 모델](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/app-structure-screen-model), [NaviHelper 초기화·해제](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/initialization-and-release), [목적지 요청·조회·이벤트](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/destinations), [Android specialUse 유형](https://developer.android.com/develop/background-work/services/fgs/service-types), [FGS timeout](https://developer.android.com/develop/background-work/services/fgs/timeout). subtype설명·정상FGS경로는 확인했고 media/location/개발자면제 작업을 쓰지 않았다. specialUse 배포심사 승인을 뜻하지 않는다.

## 미실행·한계·반환

실차·휴대폰내비·무기한상주·공개배포/스토어심사·가입/결제/새법적동의는 미실행이다. production앱을 target한 instrumentation, midrun재설치/재시작/앱강제종료, 다른앱·ADB·전역설정·license변경은 하지 않았다. onRoutingCompleted수신은 관측하지 못했으며 목적지strictmatch와 구분한다. 독립verifier가 새15분을 실행했다고 주장하지 않고 총괄의 무편집15분원본을 직접 검증한 뒤 실제추가동작을 재현했다. 주기스냅샷 사이의 lifecycle은 보존journal로 보완했으며 formal 접근성 인증은 수행하지 않았다.

`09-resource-final.json`에서 양쪽autoOFF·FGS없음·SDKDISCONNECTED·uiCount0·우리ActivitySTOPPED·순정내비전경, 기존PID유지를 확인했다. A는42dot, B는Dream을 유지한다. 전용ADB5041·두AVD·backend127.0.0.1:4184(PID55961)·기존암호화공유방저장을 유지했다. `commands-end.json`은 backendLISTEN과최종HEAD/clean상태를직접기록한다. **ADB/UI소유권을총괄에반환한다.**

다음작업은F1수정, 새runtime/APK동결 및15분전체실증후 정확한새head 재검증이다. 현재head의FAIL과실패/성공원본은보존해야한다.
