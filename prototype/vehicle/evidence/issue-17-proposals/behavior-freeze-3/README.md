# 목적지 변경 제안 실제 차량 검증 (#17)

검증한 제품 커밋은 `7c8e616d6c274f0aad0b0048cc2a30af52223788`, 기준 main은 `875c353f9918981571908bda7e47b800ceff7897`다. 아래 증거 추가 커밋은 제품 소스·설치 APK를 변경하지 않는다. 최종 별도 verifier 판정은 PR #18의 정확한 최종 head 검증 기록을 따른다.

## 실제 결과

| 시나리오 | 공유 revision | 실제 순정 목적지 | 새 SDK 경로 요청 |
|---|---|---|---|
| A 제안 → B 거절 | 1 → 1 | 양쪽 42dot 유지 | 양쪽 0회 |
| A 제안 → 120초 무응답 만료 | 1 → 1 | 양쪽 42dot 유지 | 양쪽 0회 |
| 자동 OFF: A 제안 → B 수락 | 1 → 2 | 양쪽 소프트웨어드림센터 | 각 1회 |
| 자동 OFF: B 제안 → A 수락 | 2 → 3 | 양쪽 42dot | 각 1회 |
| 자동 ON: 내비 전경 대기 → 수락 | 3 → 4 | 대기는 42dot, 수락 후 양쪽 드림센터 | 대기 0회, 수락 각 1회 |
| 성공 45초 창 종료 → B 중지·재연결·자동 시작 | 4 → 4 | 기존 드림센터 유지 | 양쪽 0회 |

세 수락 시험 모두 양쪽 `uiCount=0`과 순정 내비 전경에서 실제 목적지 일치를 확인했다. 수락부터 작업의 원래 45초 창이 끝난 뒤까지 자체 앱 재진입은 0회다. 이름·위도·경도·POI ID·provider·nullable sub-ID를 실제 SDK 조회 콜백과 비교했고, 독립 self-target driver의 조회만 사용했다. 수락 시험 중 driver의 경로 설정 모드는 사용하지 않았다. 운영 앱 PID는 각 driver 조회 전후에 유지됐다.

## 실행 환경과 증거

전용 ADB 서버 127.0.0.1:5041의 기존 `emulator-5570`/`emulator-5572`, user10만 조작했다. 공식 Pleos Connect2.1.0/API34/arm64 이미지, Native Navigation3.3.2-rc5-hotfix.3, NaviHelper3.0.5를 사용했다. 서버는 새 loopback4186 프로세스와 새 공유방이다. 실제 휴대폰·실차·다른 AVD는 조작하지 않았다.

- `deployment.json`: 정확한 제품 head·서버 PID/source SHA·16개 설치/해시 확인 ADB argv와 exit0. 두 설치 app APK SHA는 `f4316376ea901a00fb8f147797c6ba5e8bb74f22e0882d821266c126a1fef9c3`다.
- `case-results.json`: 버튼·응답·revision·서비스 상태·요청 횟수와 실제 driver JSON의 연결.
- `runtime-journal.jsonl`: 지속 수집기에서 시나리오 구간의 모든 완전한 행을 원래 순서로 보존했다. 동일한 중복 행을 set으로 제거하지 않는다. 앱의 약512KiB 파일 회전도 수집 세대에 포함한다.
- `scenario-commands.jsonl`, `scenario-results.json`, `qa-inputs.json`, `qa/`: 실제 시나리오 명령·종료 코드와 동결한 총괄 QA 코드. `qa/`는 이 Mac의 별도 runtime artifact 경로에서 실행한 원본이며 제품 앱 코드가 아니다. 이 폴더를 그대로 다른 환경에 실행하는 설치 스크립트로 제공하지 않는다.
- `checks.json`: Node90/core83/app47, 전체220개 통과. 앱/driver 빌드·lint 오류0, 기존 경고 앱7/driver2. 별도 verifier는 외부4회귀를 더해224개를 직접 통과시켰다.

초대 비밀은 실제 UI에서 메모리로만 전달하고 저장·캡처하지 않았다. vendor 일반 logcat·계정 자격증명은 수집하지 않았다. 이전 실행의 malformed 진단 행은 이번 구간보다 이전이며 원본·정확한 해시로 별도 보존/감사했다. 이 파일의 신규 시나리오 구간에는 malformed 행을 제외하지 않았다. 과거 동결1/2의 실패·중단 증거와 원본 전체는 `.harness/worktrees/product-run-artifacts/vehicle-proposals-20261105/`에 보존했다.

## 범위와 한계

이 프로토타입의 장소 선택기는 실제 관측한 공개 POI 두 곳이다. 일반 검색·AI·음성·휴대폰 연동을 구현했다고 주장하지 않는다. 지도 배경 공백은 사용자 결정에 따라 이번 이슈에서 유지한다. 성공 기준은 실제 순정 목적지 일치이며 도로 지도 복구·`onRoutingCompleted`·실차 주행 성공을 주장하지 않는다.
