# Issue #14 실제 에뮬레이터 검증

총괄이 공식 Pleos Connect v2.1.0/API 34/arm64 에뮬레이터 두 대에서 수행한 기록이다. 독립 verifier의 최종 판정은 PR에 기록한다. 실차, 휴대폰 내비, 배포 심사 승인을 입증하는 자료는 아니다.

## 검증 대상

- Base: `7d9dcc053c8dfe2b8ce1f22fef3c17b9c8c54856`
- Branch: `be/issue-14-vehicle-auto-follow`
- Runtime source SHA-256: `76c4350628c272f0c04d6405b2819c732954ab9a6ef2db405ed36d925034e0cb`
- 전체 40개 build input SHA-256: `72af78492463b80978ad5882ea229114c94ce4db5bd7e0eb2de0fda9dc93a5a9`
- 설치 앱 SHA-256: `f96663e4da4ab428e9656841b0d2e4bbce592008e712eb0e79a0e9af8ee57fe4`
- 별도 self-target driver SHA-256: `d71239e388a415489aa163f7d7a809eb9d0cfbf92ed94d6f786f66eb1a5a406f`
- 전용 ADB: `5041`; 대표 `emulator-5570`, 참여 `emulator-5572`; Android user `10`; backend `127.0.0.1:4184`.

## 연속 실행 결과

[원본 결과](continuous-freeze2/result.json)의 연속 구간은 `2026-11-05T06:25:58.594303Z`부터 907.743초다. 두 기기의 `/proc/uptime` 증가도 각각 907.75초, 907.74초로 확인했다. 마지막 결과 파일의 작성 시각에는 종료 후 스크린샷 저장 시간이 추가되어 있다.

57쌍의 [상태 관측](continuous-freeze2/samples.jsonl), 전체 SDK/세션 journal과 시작 이전 journal prefix를 함께 보존했다. 모든 관측에서 순정 내비가 전경이고 우리 Activity는 STOPPED, `uiCount=0`이었다. 구간 내 Activity 재진입은 두 차량 모두 0회다. 앱 PID는 대표 `20878`, 참여 `4948`, 서비스 epoch는 `12836184`, `12421167`로 유지됐다. foreground service와 진행 알림도 유지됐다. 중간 APK 재설치, 프로세스 재시작, 우리 Activity 실행, 앱 대상 instrumentation은 수행하지 않았다.

| 구간 시작 후 실제 변경 시각 | 대표 순정 목적지 | 공유 revision | 참여 새 requestRoute | 실제 후속 조회 |
| --- | --- | --- | --- | --- |
| 24.436초 | 소프트웨어드림센터 | 3 | 1 | 이름·좌표·POI ID·nullable sub-ID·provider 일치 |
| 302.358초 | 42dot | 5 | 1 | 동일 strict match |
| 633.147초 | 소프트웨어드림센터 | 7 | 1 | 동일 strict match |

[변경별 기록](continuous-freeze2/changes.json)과 `ui/20-continuous-change-*`는 A의 실제 SDK 변경, 자동 공유 revision, B의 자동 확인, 별도 SDK 프로세스의 실제 목적지 조회를 연결한다. 별도 driver는 production 앱과 다른 패키지 및 프로세스이며 실제 NaviHelper 3.0.5를 호출한다. production PID가 호출 전후 바뀌지 않았는지도 기록했다. production의 숨은 테스트 명령이나 모의 내비를 사용하지 않았다.

대표의 실제 목적지 전환 과정에는 완전한 빈 목적지 관측과 새 목적지가 있어 자동 발행은 6회다. 참여자는 발행 0회, 경로 요청 3회, strict match 3회였다. 공유 목적지가 비어 있는 동안 참여 차량의 순정 길안내를 취소하지 않는다. 탐색 완료 `onRoutingCompleted` 수신은 관측하지 못했으므로 목적지 일치만 입증한다.

## 알림 중지와 이후 입력

[참여 차량 알림 화면](ui/22-notification-panel.png)에 `순정 목적지 일치 확인`과 `동행 중지`가 표시된다. 실제 SystemUI의 중지 동작을 눌렀으며, [전후 기록](notification-stop-receipt.json)에서 앱 복귀 없이 serviceDestroy, automatic OFF, SDK DISCONNECTED, foreground service 종료를 확인했다.

그 뒤 대표의 실제 목적지를 42dot으로 바꾸어 공유 revision이 7에서 9로 진행했다. [중지 후 결과](stop-negative-freeze2/result.json)는 source 변경 완료 후 약 77.97초 동안 참여 새 요청 0회, Activity 재진입 0회, 기존 소프트웨어드림센터 목적지 유지, foreground service 재생성 없음으로 PASS다. 별도 native 조회로 중지 전후 목적지 일치도 확인했다. 양성 구간 결과의 `stopNegativeStatus: PENDING`은 중지 검사를 시작하기 전에 기록한 값이며, 원본을 고치지 않고 별도 최종 결과를 보존했다.

대표도 마지막에 실제 알림으로 중지했다. [대표 중지 기록](leader-notification-stop-receipt.json)에 종료 상태를 남겼다. 공유방 인증 저장은 유지하되 자동 동행 동의는 저장하지 않는다. 다음 시작에는 다시 명시적 선택이 필요하다.

## 증거 무결성과 범위

[manifest.json](manifest.json)은 원본 96개 파일의 경로, 크기와 SHA-256을 기록한다. 스크린샷, XML, journal과 결과 JSON은 무편집 복사다. APK는 Git에 넣지 않았으며 설치 해시, manifest, 서명 검증 결과 및 원본 보관 경로로 연결한다. `builder/freeze-2/`의 JUnit·lint·권한·서명 결과와 npm/Gradle 로그도 함께 보존했다.

원본 `builder/gradle-freeze-2.log` 2행에는 Gradle이 출력한 후행 공백 한 개가 있다. 전체 `git diff --check`는 이 원본 공백을 보고한다. 증거 해시를 유지하기 위해 수정하지 않았으며, 원본 runtime 증거를 제외한 코드·문서의 공백 검사는 별도로 수행했다.

`root-*.py`는 이 전용 환경에서 실제 실행한 검증 코드의 기록이다. 경로와 전용 toolchain을 명시적으로 참조하며, 범용 실행 하네스로 배포하는 파일이 아니다. 실제 명령은 전용 `adb -P 5041 -s <위 두 serial>`과 `am instrument --user 10 -w -r -e mode <공개 fixture 또는 query> ai.pleos.destinations.driver/.NativeDestinationDriver`만 사용했다. 비밀·키 파일을 읽지 않고, allowlist의 두 진단 파일만 journal과 상태 증거로 수집했다.

이전 freeze-1에서 발견한 늦은 취소 후 확인 상태 손실은 `ui/15-*`, `ui/16-*`에 실패 전후 기록을 보존했다. 해당 구간을 15분 성공으로 세지 않는다. 현재 통과 기록은 새 APK 설치와 새 opt-in 이후의 freeze-2 실행이다. 실제 계정·실차, 네트워크 단절의 모든 조합, 무기한 상주, Google Play/Pleos의 specialUse 배포 승인은 이 에뮬레이터 검증으로 주장하지 않는다.
