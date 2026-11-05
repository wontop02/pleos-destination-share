# Issue #12 차량 검증

2026-11-05. 시작 HEAD/base는 `821e3a387e99e4d3c87400fed3bd71173f0b9493`, 브랜치는 `be/issue-12-pleos-navigation`이며 builder 변경은 미커밋이다. 총괄이 공식 Pleos 두 대의 실제 SDK·UI를 실행했고 builder는 원본 JSON·trace·화면을 대조했다. builder의 ADB/UI 조작은 없다. 고정 commit과 별도 문맥 verifier는 총괄의 후속 단계다.

## 최종 빌드와 검사

| 항목 | 결과 |
|---|---|
| 앱 APK SHA-256 | `e2840dd5426fc0b3aac50c48604f8b2c137191aea72de64928959cc98f1be8b9` |
| 계측 APK SHA-256 | `2f90e2e79bc7a445df2931bac97ac521726fa28074cf688f8e24a6d89f663ab7` |
| `npm test` | exit 0, 기존 69 + 차량 계약 7 = 76/76 |
| `:core:test` | exit 0, 42/42. 상태·역순/중복·timeout·취소·방 변경·전경 복귀를 fake gateway로 검사 |
| `:app:testDebugUnitTest` | exit 0, 21/21. 실제 Node HTTP/SSE 3 + wire/normalizer/SDK DTO 변환/메모리 범위 18 |
| 앱·계측 APK 전체 빌드 | exit 0, 정상 Maven NaviHelper 3.0.5 사용 |
| `:app:lintDebug` | exit 0, 0 errors / 4 warnings. 미사용 리소스 2개·아이콘·문자열 리소스화. 기준 완화 없음 |

JDK 21.0.13+9, Gradle 8.11.1, AGP 8.9.1, Kotlin 1.9.25, compile/target SDK 35, build-tools 35.0.0, Node 25.8.1을 사용했다. [명령·exit](execution-summary.json), [runtime 25개/test 9개 입력 해시](source-manifest.json), [SDK 대조](sdk-api.md)를 따른다. APK·vendor 아카이브·캐시·자격 정보는 커밋하지 않는다.

## 실제 두 차량 실증

전용 ADB 5041, `emulator-5570`/`emulator-5572`, AAOS 사용자 10에서 같은 최종 APK를 실행했다. A의 실제 순정 목적지를 대표가 공유하고 B가 직접 적용했다. 공유값 삭제·재공유·새 revision 수신도 관찰했다. 원본 그대로 선별한 화면·설치/source receipt는 [runtime/](runtime/)에 있다.

| 시나리오 | 관찰 결과 |
|---|---|
| [25: 없음 → 소프트웨어드림센터](runtime/25-final-empty-to-dream-result.json) | PASS. native 안내 후 앱 복귀 30.22초에 UI 일치 확인. request 1/resume 1, 요청→실제 match 23,081ms |
| [27: 기존 드림센터 → 공유 42dot](runtime/27-final-existing-to-42dot-result.json) | PASS. 기존 안내 취소 이벤트 후 새 native 42dot, 앱 복귀 28.07초에 일치 확인. request 1/resume 1, 요청→match 21,255ms |

두 경우 모두 `onRoutingCompleted`는 미관찰이며 화면에 `확인 시점에 탐색 완료 이벤트 미수신`을 표시했다. 목적지 일치와 전체 탐색·주행·도착 성공을 구분한다. UI 시각은 기기 설정이며 trace의 Unix epoch ms로 시간 관계를 대조한다. 실제 물리 차량·실도로 검증은 수행하지 않았다.

## AC별 근거

| AC | 확보한 근거 | 남은 범위 |
|---|---|---|
| AC1 | 실제 POM/module/AAR·Kotlin 기본값 대조, 전체 빌드, 공식 Pleos 연결/조회·전경 복귀 후 새 SDK 조회 | 독립 verifier의 고정 commit 검사 대기 |
| AC2 | A 실제 목적지·nullable metadata, 대표 공유/삭제/변경, B 최신 revision 수신, 전체 빈 조회와 실패 구분 | 이벤트 전달 차이를 감추지 않고 명시적 공유 전 새 조회 사용 |
| AC3 | 25/27 실제 적용, 취소/전경 전환 후 원래 45초 안 query-only 재개, strict match, 재요청 없음 | 탐색 완료 이벤트 미관찰 유지 |
| AC4 | 기존 인증/대표 권한 69회귀 + metadata/CAS/만료중 변경 7검사, URL/redirect 거절, AndroidKeyStore/AES-GCM 저장·실제 복귀 인증 | 늦은 응답·탈퇴·방 변경·프로세스 소실의 부정 시나리오는 JVM/서버 검사 |
| AC5 | 같은 최종 APK/runtime 입력의 두 공식 Pleos 실제 화면·trace·receipt | 별도 verifier NOT RUN. builder가 병합 승인하지 않음 |

## 이전 결과와 판정 기준

[실제 SDK 기록](native-sdk-observations.json)은 전체 빈 목적지 응답과 source null sub-ID를 SDK 빈 문자열로 전달한 실제 호환성을 보존한다. source/room의 null은 유지한다. 최초 문서 좌표 seed의 BLOCKED, 두 번째 seed의 `error:route_cancelled` BLOCKED, 별도 후속 query의 OBSERVED를 합쳐 PASS로 바꾸지 않았다.

이전 `ad268…` APK의 18번 없음→목적지는 PASS였지만 22번 기존 안내 교체는 취소 이벤트로 확인이 사라져 FAIL이었다. 두 결과·해시는 [실행 요약](execution-summary.json)에 보존한다. 최종 `e284…`에서 취소는 삭제/새 요청 실패로 단정하지 않고 listener 교체 후 실제 조회로 판별한다.

목적지 없음은 정확한 전체 빈 조회에서만 판별한다. 부분 누락·공백·timeout·취소 이벤트만으로 삭제를 공유하지 않는다. 복귀는 같은 방/회원 인증과 같은 최신 목적지에서만 원래 기한을 이어가며, 45초 초과·방 변경·탈퇴·프로세스 소실은 이전 성공으로 복원하지 않는다.
