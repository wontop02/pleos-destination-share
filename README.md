# pleos-destination-share

차량 간 목적지와 최대 15초 원음을 공유하는 프로토타입입니다. 2026-11-04 앱 개발을 재개했으며 공유방·목적지·원음 첫 실행본(#6)부터 Android(#7), iPhone(#8) 순서로 진행합니다.

## 제품 실행

Node.js 22 이상과 npm을 사용합니다. 첫 실행본은 외부 패키지 설치가 필요하지 않습니다.

```sh
npm test
npm start
```

기본 주소는 `http://127.0.0.1:4180`입니다. 공유방을 만든 뒤 초대 링크를 다른 탭에서 열어 목적지와 원음을 확인합니다. API와 실행 방법은 [프로토타입 문서](prototype/README.md)를 따릅니다.

웹은 열린 화면에서의 기능 검증용입니다. iPhone·Android에서 내비 앱 사용 또는 화면 잠금 중 원음 자동 재생은 네이티브 앱과 실기기로 따로 검증합니다. 모든 기종에서 검증됐다고 표시하지 않습니다.

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
