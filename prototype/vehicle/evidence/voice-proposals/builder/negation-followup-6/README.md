# 부정·불확실 문장 회귀 수정 — 동결 6

수정 기준은 Draft PR #26의 head `1b78acf9b29282d9d5b103467fde3400dce5580f`다. 독립 verifier가 `카페에 들르면 안 돼`와 `카페에 가고 싶지 않아`에서 반대 방향의 긍정 이유와 후보를 만드는 결함을 발견했다. 이 폴더는 builder의 수정/재현 증거이며 독립 최종 PASS나 기기 실행 증거가 아니다.

기존 compiled analyzer를 직접 호출한 `VoiceNegationProbe.java`는 두 부정 입력에서 모두 `CHANGE`, `카페에 들러요.`, 후보 1개를 반환해 RED(exit 1, failures 2)였다. 같은 probe는 수정 후 두 부정 입력에서 `NEGATED`, 빈 이유, 후보 0개로 GREEN(exit 0)이다. `카페에 들르고 싶어`와 `카페에 가고 싶어`의 대조군은 수정 전후 모두 CHANGE와 원본 후보 1개를 유지한다. synthetic unit 입력이며 실제 발화/STT/실제 POI를 재현한 증거는 아니다. Java 실행은 `-Xmx128m`이고 Gradle을 추가로 기동하지 않았다.

수정은 공통 부정·불가·원하지 않음 형태를 먼저 차단하고, 불확실한 표현이나 미완성/인용 안의 동사 조각을 긍정으로 추측하지 않도록 한다. 긍정은 지원하는 명시적인 제안/희망 문장 끝에 한정한다. 특정 카페/fixture를 코드에서 예외 처리하지 않았다. 기존 테스트는 변경하거나 완화하지 않고 부정·불가·희망 대조·불확실/인용/미완성과 `안동카페`/`연못카페` 같은 일반 이름 음절의 대조를 추가했다. 한국어 전체의 부정 범위나 이중 부정/방언/모든 문장 끝을 이해한다고 주장하지 않는다. 지원하지 않거나 보류한 표현은 인식문 수정·수동 실제 장소 선택으로 처리한다.

첫 전체 검사에서는 `없`을 너무 넓게 보류하여 기존 `없는장소로 가자`의 명시적인 CHANGE/후보 0개 회귀가 실패했다. 기존 테스트를 유지하고 ‘갈 수 없음/생각·마음·의향·계획·필요 없음’ 형태로 범위를 좁힌 뒤 전체 검사를 통과했다. 첫 실패의 원본 로그·JUnit·결과를 `first-attempt-*`로 보존한다. 이 실패한 명령의 APK 필드는 앞선 성공 빌드의 남은 출력이며 새 소스의 APK가 아니다. 첫 실패 디렉터리의 NOTICE와 원래 결과를 함께 읽어야 한다. 설치/최종 검증에는 아래 성공한 동결 6 APK만 사용한다.

| 항목 | 최종 결과 |
|---|---|
| 제품 APK SHA-256 | `4868babf1e3f438d43ba51f808f7971259c75a919c4f89c15080ffe6e266264c` |
| core JVM | 93 tests, failures/errors/skips 0 |
| app JVM | 98 tests, failures/errors/skips 0 |
| assemble/lint | exit 0, 112.989831042초, app errors 0/warnings 13 |
| APK 서명/permissions/badging | 각각 exit 0 |
| build 전후 입력 | manifest 69개 모두 동일 |
| SDK/Main/controller/cleanup 등 비분석 main 입력 | 동결 4의 23개와 SHA-256 모두 동일 |
| driver | 입력/바이너리 변경 없음, 기존 `5f99cb5355e12f9af818e1b6740aa3a4f7a9974b387de93a3f8e504639e5a4ea` 유지 |

실행 명령은 `--offline --no-daemon --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx1024m -XX:MaxMetaspaceSize=384m' -Pkotlin.compiler.execution.strategy=in-process :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`다. Gradle 57 tasks 중 17개 실행/40개 up-to-date이며, core는 동일 소스의 기존 결과를 재사용한다. 이 작업의 Gradle 프로세스는 한 번에 하나만 실행했고 다른 프로젝트의 기존 daemon은 조작하지 않았다. Node/driver는 코드가 같아 이번에 재실행하지 않았다.

제품/계약 변경은 `VoiceDraft.kt`, `VoiceDraftTest.kt`, `docs/product/vehicle-voice-proposals.md` 세 경로뿐이다. 실제 마이크 수명을 소유하는 SDK gateway, Activity, controller, cleanup, 세션, 서비스와 선택/실제 지점 확인은 수정하지 않았다. `lifecycle-preservation.json`은 운영 중이던 동결 4 APK `259c8ac4…`의 source manifest와 직접 대조한 23개 입력의 해시다. 이는 물리 마이크 종료나 새 APK의 실제 두 차량 양성 경로 검증을 대신하지 않는다. 총괄이 새 APK 설치/실행·commit/PR 갱신을 마친 뒤 별도 verifier가 정확한 새 head를 재검증해야 한다.
