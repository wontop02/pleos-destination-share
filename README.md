# pleos-destination-share

길맞춤은 여러 차량에 목적지 변경을 알리는 Pleos 프로토타입입니다. 현재 #29의 [실제 장소 검색·목적지 변경 요청](docs/product/destination-change-requests.md)을 구현·검증합니다. 장소명·업종으로 순정 검색을 열고, 사용자가 목적지를 설정한 뒤 이름·주소를 새로 확인해 다른 차량에 알립니다. 각 수신 차량이 수락하면 그 차량의 내비만 바뀝니다. 다른 차량의 응답을 기다리지 않습니다. 음성과 ☆ 저장은 기본 흐름의 선행 조건이 아닙니다.

기존 웹 목적지·원음 서버와 #12/#14/#17/#21/#23/#25/#27의 기록은 보존합니다. 휴대폰 [Android PR #10](https://github.com/wontop02/pleos-destination-share/pull/10)과 [iPhone PR #11](https://github.com/wontop02/pleos-destination-share/pull/11)은 실기기 필수 검증이 막힌 Draft이며 `main` 미병합입니다.

## 제품 실행

Node.js 22 이상과 npm을 사용합니다. 첫 실행본은 외부 패키지 설치가 필요하지 않습니다.

```sh
npm test
npm start
```

기본 주소는 `http://127.0.0.1:4180`입니다. 공유방을 만든 뒤 초대 링크를 다른 탭에서 열어 목적지와 원음을 확인합니다. API와 실행 방법은 [프로토타입 문서](prototype/README.md)를 따릅니다.

웹은 열린 화면에서의 기능 검증용입니다. iPhone·Android에서 내비 앱 사용 또는 화면 잠금 중 원음 자동 재생은 네이티브 앱과 실기기로 따로 검증합니다. 모든 기종에서 검증됐다고 표시하지 않습니다.

차량 앱은 NaviHelper 3.0.5의 실제 검색 화면과 현재 목적지 조회를 사용합니다. 사용자가 `변경 요청 알림 받기`를 시작하면 순정 내비가 전경이어도 Android 알림을 받습니다. 알림을 열어 이름·주소·발신 차량을 확인하고 개별 수락/거절합니다. 수신·알림 클릭만으로 경로를 요청하지 않으며, 발신 차량에는 설정한 경로를 다시 요청하지 않습니다. 실제 후속 조회가 요청과 일치해야 변경 확인을 표시합니다. [차량 실행·검증 문서](prototype/vehicle/README.md)를 따릅니다.

기존 자동 동행과 전원 합의 제안은 legacy 호환 경로입니다. 새 변경 요청과 별도 상태·이벤트를 쓰며 기존 자동 따르기가 개별 수락을 우회하지 못합니다. 이전 차량 증거는 #29의 실제 두 차량 실증과 독립 판정을 대신하지 않습니다.

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
