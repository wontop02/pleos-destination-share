# Issue #14 freeze-3 실제 검증

이 폴더는 알림 가독성 결함 F1을 수정한 최종 후보 APK의 총괄 실행 원본이다. 독립 verifier의 최종 정확한 base/head 판정과 병합 상태는 [PR #15](https://github.com/wontop02/pleos-destination-share/pull/15)를 따른다. 이전 [freeze-2 판정](../review-freeze-2/verdict.json)은 전체 FAIL 이력으로 보존한다.

## 동결과 환경

- Base: `7d9dcc053c8dfe2b8ce1f22fef3c17b9c8c54856`; branch `be/issue-14-vehicle-auto-follow`.
- Runtime source SHA-256: `f9215df3cff1ac036a3ce4d7a5ec44ff20064060bb30dc74001afa4142cf316b`.
- 40개 runtime/test/build input SHA-256: `7036d13a14dda56a79481282f77f211fb24f4ab1bba6b15c6304bc91f0ee707a`.
- 실제 설치 앱 SHA-256: `264605564a27d7cdc05578769d509b1c28220917cdf7dc605df43437e11cc216`.
- 실제 설치 별도 driver SHA-256: `8e534886360623b27aede4c49f8fdc1c33b8d89bd2e0b6d2b4949fa59e397ea9`.
- 공식 Pleos Connect v2.1.0/API 34/arm64 두 대, user 10, 전용 ADB 5041의 emulator-5570/5572, loopback backend 127.0.0.1:4184.

[설치 기록](root-freeze3-install.json)과 source start/end는 실제 설치 해시와 동결 입력을 대조한다. 자동 동행은 두 기기 모두 기본 OFF였고 역할별 시작을 각각 한 번 선택했다. 이후 우리 Activity를 다시 열거나 APK를 재설치하지 않았다.

## 알림 가독성

F1의 어두운 중복 본문을 제거하고 실제 계산한 상태를 표준 알림의 제목 맨 앞에 표시했다. [실제 화면](ui/18b-freeze3-confirmed-notification.png)의 `순정 목적지 일치 확인 · 동행 중 · 참여 · 자동 따르기`가 완전히 읽힌다. [픽셀 측정](notification-contrast-preflight.json)은 상태 제목 RGB [255, 255, 255], 배경 RGB [40, 42, 45], 대비 **14.390448:1**이다. 이는 해당 실제 PNG의 보조 측정이며 전체 접근성 인증 주장은 아니다. 이전 1.139:1 본문은 남아 있지 않다.

최초 `ui/18-*` 관측은 순정 내비의 마지막 화면 전환이 알림 열기를 닫아 native 화면이 찍힌 실패 기록이다. 앱 재진입·재동의·재시작 없이 안정된 화면에서 `18b`를 다시 관측했다. 실패 원본도 수정하지 않았다.

## 연속 실행

[양성 결과](continuous-freeze3/result.json)는 `2026-11-05T07:27:30.544477+00:00`부터 **908.721초**다. 두 기기 uptime 증가도 각각 908.770초, 908.720초로 900초 이상이다. 결과 작성 시각에는 종료 뒤 캡처 저장 시간이 추가된다.

55쌍의 [상태 관측](continuous-freeze3/samples.jsonl)에서 native navigation foreground, 우리 Activity STOPPED, uiCount=0, foreground service가 유지됐다. baseline journal prefix와 이후 전체 lifecycle journal에서 Activity 재진입은 A/B 모두 0회다. PID는 24562/10889, service epoch는 16526086/16132415로 유지됐다. 중간 설치·강제 종료·프로세스 재시작·production 앱 대상 instrumentation을 하지 않았다.

| 시작 후 변경 시각 | A 실제 목적지 | 공유 revision | B 새 경로 요청 | B 실제 후속 조회 |
| --- | --- | --- | --- | --- |
| 23.099초 | 소프트웨어드림센터 | 15 | 1 | strict match |
| 304.198초 | 42dot | 17 | 1 | strict match |
| 633.089초 | 소프트웨어드림센터 | 19 | 1 | strict match |

10분 이후 변경을 포함하며 각 [실제 변경](continuous-freeze3/changes.json)의 A driver 응답, 자동 서버 revision, B 자동 상태와 별도 실제 SDK query를 연결한다. 별도 self-target driver는 NaviHelper 3.0.5를 직접 호출하며 production과 다른 프로세스다. 호출 전후 production PID도 같다. 모의 내비나 production의 숨은 테스트 명령을 사용하지 않았다.

A의 실제 전환 중 전체 빈 목적지와 새 목적지가 각각 관찰되어 자동 발행은 6회다. B 발행은 0회, 새 경로 요청 3회, strict match 3회다. 빈 공유 목적지는 B 순정 안내를 취소하지 않는다. `onRoutingCompleted` 실제 수신은 미관측이며 목적지 일치만 입증한다.

## 실제 알림 중지와 이후 입력

[SystemUI 중지 기록](notification-stop-receipt.json)은 우리 Activity를 열지 않고 알림의 동행 중지를 눌러 OFF, SDK DISCONNECTED, serviceDestroy와 FGS 종료를 확인했다. 이어 A의 실제 목적지를 바꾸고 자동 공유 revision이 19에서 21로 진행했는데도 B의 새 경로 요청은 **0회**, Activity 재진입은 **0회**다. [중지 후 검사 결과](stop-negative-freeze3/result.json)는 source 완료 후 **81.347894초**를 관찰했고 실제 B 목적지는 기존 소프트웨어드림센터로 유지됐다.

양성 JSON의 stopNegativeStatus PENDING은 중지 검사 전에 작성한 원본 값이며, 별도 중지 후 최종 PASS 결과와 연결한다. 원본을 사후 수정하지 않았다. 대표도 [실제 알림 중지](leader-notification-stop-receipt.json)로 종료했으며 두 기기에 자동 동행 수명을 남기지 않았다.

## 보존과 한계

[manifest](manifest.json)의 106개 파일은 지정 RAW에서 무편집 복사했고 각 SHA-256을 대조했다. PNG/XML/journal/JSON, 173개 Node/core/app 회귀, SDK 3개 APK 빌드와 lint·권한·서명 결과를 포함한다. APK는 Git에 넣지 않고 설치 해시와 원본 보관 경로를 연결한다. driver payload와 공개 인증서가 같아도 ZIP entry 순서와 배치가 달라 전체 APK 해시가 달라질 수 있다는 [공개 아티팩트 비교](builder/freeze-3/apk-reproducibility.json)도 보존했다.

root-*.py는 이 전용 환경에서 수행한 검증 코드의 기록이며 범용 실행 하네스로 배포하지 않는다. 실제 ADB는 전용 두 serial/user/port만 사용했고 SDK API는 공개 두 POI fixture에 한정했다. 비밀 저장·키 파일은 읽지 않았으며 앱 진단은 allowlist의 navigation-events.jsonl과 session-state.json만 수집했다. 원본 Gradle 로그에 도구가 출력한 후행 공백이 있으면 해시 보존을 위해 유지하며, 소스·문서 공백 검사는 원본 runtime 증거와 구분한다.

실차·휴대폰 내비·무기한 상주·specialUse 배포 심사는 검증하지 않았다. 자동 모드는 45초 앱 복귀를 요구하지 않으며 개별 native 목적지 확인 작업의 원래 45초 기한은 유지한다. 총괄 실행 관찰과 독립 verifier 최종 판정은 구분한다.
