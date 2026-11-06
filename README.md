# pleos-destination-share

차량 간 목적지와 최대 15초 원음을 공유하는 프로토타입입니다. 공유방 웹·인증 서버(#6)는 병합되었습니다. 2026-11-05 [Pleos 차량 목적지 앱 #12](prototype/vehicle/README.md)의 수동 실증을 병합했고, [앱 복귀 없는 자동 동행 #14](docs/product/vehicle-auto-follow.md)은 PR #15로 병합되었습니다. 현재 #17의 [버튼 기반 목적지 변경 제안](docs/product/vehicle-proposals.md)을 구현·검증합니다. 휴대폰 원음 앱의 [Android PR #10](https://github.com/wontop02/pleos-destination-share/pull/10)과 [iPhone PR #11](https://github.com/wontop02/pleos-destination-share/pull/11)은 실기기 필수 검증이 막힌 Draft이며 `main`에 병합되지 않았습니다.

## 제품 실행

Node.js 22 이상과 npm을 사용합니다. 첫 실행본은 외부 패키지 설치가 필요하지 않습니다.

```sh
npm test
npm start
```

기본 주소는 `http://127.0.0.1:4180`입니다. 공유방을 만든 뒤 초대 링크를 다른 탭에서 열어 목적지와 원음을 확인합니다. API와 실행 방법은 [프로토타입 문서](prototype/README.md)를 따릅니다.

웹은 열린 화면에서의 기능 검증용입니다. iPhone·Android에서 내비 앱 사용 또는 화면 잠금 중 원음 자동 재생은 네이티브 앱과 실기기로 따로 검증합니다. 모든 기종에서 검증됐다고 표시하지 않습니다.

차량 앱은 NaviHelper 3.0.5로 순정 목적지를 조회합니다. 기본 꺼짐인 자동 동행을 대표와 참여자가 각각 시작하면 순정 내비 화면에서 변경 공유·최신 목적지 적용·실제 조회 확인을 계속합니다. 진행 알림으로 중지할 수 있고 기존 직접 공유·적용도 유지합니다. 동결 3의 공식 Pleos 에뮬레이터 두 대에서 908.721초 동안 앱 재진입 없이 세 번 변경·실제 목적지 일치를 관찰했고, 상태 우선 알림 제목 대비는 14.390:1이었습니다. 알림 중지 후 새 A 변경 완료 뒤 81.348초 동안 B 새 요청은 0회였습니다. 동결 2의 F1 전체 FAIL과 새 실행은 [검증 이력](prototype/vehicle/evidence/automatic-session/verification.md)에서 구분하며, 최종 독립 판정과 병합 상태는 [PR #15](https://github.com/wontop02/pleos-destination-share/pull/15)를 참조합니다.

차량의 `목적지 변경 제안` 카드에서는 42dot·소프트웨어드림센터 공개 장소와 이유를 제안합니다. 전원이 수락하면 양쪽 차량이 한 번 요청하고 실제 순정 목적지를 확인합니다. 대기·거절·120초 만료는 기존 안내를 유지하며, 자동 동행이 꺼져 있어도 버튼 작업은 내비 화면에서 계속 확인합니다. 새 이슈의 실제 두 차량 실증과 독립 판정은 기존 #14 증거와 별도로 확인합니다.

## 작업 방식

이슈 → `be/issue-<번호>-<설명>` → PR → 별도 verifier 검증 → `main` squash 병합을 사용합니다. 승인 범위 안에서는 이슈가 끝날 때마다 사용자 재지시를 기다리지 않습니다.

- 현재 구현 범위: [제품 개발](docs/product/README.md)
- 화면 기준·용어: [DESIGN.md](DESIGN.md), [GLOSSARY.md](GLOSSARY.md)
- 에이전트 지침: [AGENTS.md](AGENTS.md)
- 역할·검토·병합: [하네스 운영 규칙](docs/harness/README.md)
- 작업 양식: [이슈](.github/ISSUE_TEMPLATE/task.md), [PR](.github/pull_request_template.md)

## 로컬 하네스 큐

```sh
node harness/cli.js init --repo-root . --run demo --snapshot harness/fixtures/demo.json
node harness/cli.js status --repo-root . --run demo
```

예제 이슈는 가상 자료입니다. 로컬 큐는 기존 harness/docs 정책과 `.harness/` 체크포인트를 유지합니다. 제품 이슈는 총괄이 직접 관리하며 추가 하네스 자동화가 앱 개발을 막지 않습니다. 명령과 복구는 [로컬 큐 사용법](docs/harness/queue.md)을 따릅니다.

저장한 일반 장소의 명시 선택·제안과 장소/좌표 확인 의미는 [일반 장소 확인 계약](docs/product/poi-place-confirmation.md)에 기록한다. 실제 차량 증거와 로컬 회귀 결과를 구분한다.
