# Issue #25 구현측 검사

기준은 main `8b6321276f33923f24f15a8437205b6126bf92ca`, 브랜치는 `be/issue-25-voice-proposals`다. builtin worker가 `.codex/agents/builder.toml` 계약으로 구현했다. 사용자 정의 builder 역할 실행은 아니다. GitHub 변경·commit·기기 조작은 총괄이 담당한다. 이 보고서는 독립 verifier PASS나 실제 두 차량 검증을 대신하지 않는다.

제품 작성 화면에 명시적인 ON_DEVICE 음성 입력, 인식문 수정, 보수적인 로컬 초안 정리, 현재 저장 snapshot의 실제 후보 확인을 연결했다. 생성형 AI나 자유 장소 검색을 사용하지 않는다. 기존 명시 제안 보내기·전원 수락·fresh native 확인 경로를 유지한다. 서버·core 라우팅·인증·순정 gateway·기존 evidence는 변경하지 않았다. 새 `SavedPlacesController.clearSelection`은 선택만 비우고 현재 불변 목록/조회 횟수를 유지한다.

## 동결 4

`source-sha256.json`의 입력 68개를 빌드 전후 비교했고 모두 동일했다. 전체 소스 사본·APK·전체 중간 시도는 저장소 밖 `product-run-artifacts/voice-proposals-20261108/builder/`에 보존했다. Git에는 바이너리 SDK·APK나 자격 정보를 넣지 않는다. 원본 toolchain 설정은 해당 프로세스의 환경에만 재사용했다.

| 산출물 | SHA-256 |
|---|---|
| 제품 APK | `259c8ac483c753679c7f8ea56d1e966a813dc55ae50c6ce64bfd8b7891575a64` |
| testOnly driver APK | `7982111520e733472203cc4adc52132494b9a2705660edb0e109544142c9cccc` |
| 공개 SpeechToText 3.3.1 AAR | `35109ad13400a7fa103cca9fabbf9b785e63a6a98b17a0698a1aef51458d37bf` |

`freeze-4-result.json`과 원본 `gradle-freeze-4.log`에 명령·결과를 기록했다. `--offline --no-daemon --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx1024m -XX:MaxMetaspaceSize=384m' -Pkotlin.compiler.execution.strategy=in-process :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :nativeDriver:assembleDebug :nativeDriver:lintDebug`는 exit 0, 210.212948917초다. Gradle은 104 tasks 중 31개 실행/73개 up-to-date로 기록했다. core는 변경하지 않아 앞선 새 worktree 실행 결과를 재사용하며, 새 독립 빌드가 모든 검사를 다시 실행했다고 주장하지 않는다.

| 검사 | 결과 |
|---|---|
| core JVM | 93 tests, failures/errors/skips 0 |
| app JVM | 94 tests, failures/errors/skips 0 |
| 전체 Node `npm test` | 94 tests, pass 94, fail/cancel/skip 0, exit 0 |
| app lint | errors 0, warnings 13 |
| driver lint | errors 0, warnings 8 |
| 두 APK apksigner/aapt permissions/badging | 6개 명령 각각 exit 0 |
| 기존 evidence 보존 | 기준 tracked 846개 bytes 동일 |

제품 병합 manifest는 기존 권한과 정상 `STT_SERVICE`, AndroidX의 자기 패키지 `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` 정의/사용을 포함한다. 별도 위험/특권 grant, RECORD_AUDIO 또는 microphone FGS 유형을 추가하지 않았다. 실제 플랫폼 권한 정의/부여와 CAAS 버전은 총괄의 실행 증거로 따로 확인한다.

## 실제 결함과 수정 이력

최초 probe 빌드는 SDK transitive `ai.pleos.playground.security:universal-access:0.6.0`가 기존 Maven group allowlist에 없어 실패했다. 공식 저장소의 정확한 security group을 추가한 뒤 빌드했다. 실패 원본을 `first-probe-build-failure.*`로 보존한다.

동결 1은 request 반환 직후 듣는 중으로 표시했다. 실제 준비 신호가 늦게 관찰되어 동결 3에서 onReady/onStartedRecognition 전에는 준비 중을 유지하도록 수정했다. 준비 전 text/ended는 무시하며 중복 ready는 듣기 기한을 늘리지 않는다.

동결 1–3의 close는 stop/remove/release를 바로 연속 호출했다. 총괄은 실제 CAAS 마이크 녹음이 계속되는 결함을 발견했다. 동결 4는 UI generation/초안 권한을 즉시 폐기하되, 비동기 stop이 실행될 수 있도록 helper/listener를 onEnded 또는 최대 3초까지 유지하고 release한다. 새 인식은 이전 해제 뒤에만 시작한다. 회귀는 즉시 stop·지연 release, 중복 close의 기한, 옛 ended와 다른 자원 분리, 실제 준비 전 결과·타임아웃·scope 변경과 늦은 callback을 검사한다. 이 3초 기한 자체는 물리 녹음 종료의 증거가 아니다. 취소/화면 이탈/말하기 끝/timeout/재시도의 실제 appops/audio 종료는 총괄이 동결 4에서 확인해야 한다.

testOnly probe의 옛 `rawAudioPersisted=false`는 공급 PCM fixture의 사설 저장과 혼동할 수 있어 동결 2부터 `microphoneAudioPersisted=false`로 범위를 좁히고 공급 fixture 파일명/바이트 수를 별도로 기록했다. 옛 원본은 바꾸지 않았다. probe는 한 초기화당 입력 한 번이며 새 초기화도 이전 cleanup 뒤에만 수행한다. UI 수정 helper는 고정 공개 문장을 실제 보이는 EditText에 입력하며 STT 결과 주입이 아니다.

## 완료 조건별 구현측 근거

| 조건 | 구현측 근거와 아직 필요한 증거 |
|---|---|
| AC1 | SDK 공개 AAR/API, ON_DEVICE adapter, 전경 controller/cleanup 회귀. 실제 물리 마이크 종료와 실제 발화는 총괄 검사 필요. |
| AC2 | `VoiceDraftTest`: 일반 카페/식당/주유, 부정·대안·복수·미지원/없는 후보, 원본 객체 보존. 분류는 저장 이름의 실제 단어만 사용한다. |
| AC3 | 음성 controller에 제안/경로 기능이 없다. 기존 snapshot select와 `proposeSelectedPlace`, 동의·fresh native 경로를 호출한다. 기존 HTTP/JVM 회귀 통과. 실제 전원 수락 전 0회/후속 native 확인은 총괄 검사 필요. |
| AC4 | 기존 View/token을 사용하고 JVM/build/lint를 통과했다. 실제 fontScale 1.3·긴 문장·수정/취소 화면은 총괄 검사 필요. |
| AC5 | builder는 ADB/UI/실마이크/차량을 조작하지 않았다. 실제 두 차량 입력 출처·오인식 수정·상대 수신·거절/수락·native 결과와 별도 verifier가 필요하다. |

실제 사람 발화·실마이크 인식·생성형 AI 이해 성공을 이 검사로 주장하지 않는다. 합성 PCM, 음향 합성, UI 수정과 실제 사람 발화를 구분해야 한다. 기존 휴대폰/지도 복구/원음 통화/외부 배포 범위를 추가하지 않았다.

복사한 로그와 SDK 출력은 원본 bytes를 보존한다. `raw-whitespace.json`은 Git diff-check에서 예외가 필요한 원문 공백 위치만 기록하며 제품 소스에는 공백 오류가 없다. `SHA256SUMS.json`은 자신을 제외한 이 builder 증거 파일의 해시다.

## Driver 전용 후속 동결 5

실제 마이크 취소 시험에서 전체 UI dump가 늦어 15초 자동 종료가 먼저 발생한 시도는 취소 성공 근거로 사용할 수 없었다. 총괄 요청에 따라 `VehicleUiActionProbe`를 testOnly 자기 package 대상 instrumentation으로 추가했다. 운영 소스/APK는 동결 4를 유지한다. 변경은 driver manifest와 새 helper Kotlin 파일 두 경로뿐이며 `driver-followup-5/preservation-and-package.json`에서 비driver 입력 변경 0개와 운영 APK 바이트 동일을 확인한다. 동결 4의 source manifest는 보존하고 새 전체 입력 69개는 `driver-followup-5/all-input-sha256.json`에 기록했다.

새 driver APK SHA-256은 `5f99cb5355e12f9af818e1b6740aa3a4f7a9974b387de93a3f8e504639e5a4ea`다. `:nativeDriver:assembleDebug :nativeDriver:lintDebug`만 새로 실행했고 exit 0, 97.027783375초, lint 오류 0/경고 8이다. 47 tasks 중 17개 실행/30개 up-to-date다. 제품 JVM/Node 검사와 운영 APK `259c8ac4…`는 입력이 같아 동결 4의 결과를 재사용하며 이번에 새로 실행했다고 주장하지 않는다. 새 driver 서명·manifest·permissions 확인도 각각 exit 0이다.

`am instrument --user 10 -w -r -e operation click -e resource voice_cancel ai.pleos.destinations.driver/.VehicleUiActionProbe`는 실제 활성 `ai.pleos.destinations.vehicle` root의 `voice_start|voice_stop|voice_cancel|nav_home` 중 하나만 처리한다. 정확한 Button 클래스·현재 활성/보임/clickable·비어 있지 않은 경계를 검사한 뒤 공개 `ACTION_CLICK`을 사용한다. `operation=inspect`는 조작 없이 상태/경계만 읽고 `actionAccepted=false`를 반환한다. 출력은 `inputSource=public_ui_button`, canonical resource, actionAccepted, enabled/visible, bounds, elapsedMs뿐이다. 텍스트/인식문을 읽거나 출력하지 않고, SDK나 제품 내부 메서드를 호출하지 않는다. idle 대기를 추가하지 않았다. 실제 클릭 성립과 전체 처리 시간은 총괄이 기기에서 확인한다.

공개 API 근거는 [UiAutomation 활성 창](https://developer.android.com/reference/android/app/UiAutomation#getRootInActiveWindow())과 [AccessibilityNodeInfo 동작/상태](https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo#performAction(int))다. UI 버튼 조작은 실제 음성 인식이나 물리 마이크 종료의 대체 증거가 아니다.
