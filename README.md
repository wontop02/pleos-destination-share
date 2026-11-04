# pleos-destination-share

Pleos 차량 간 목적지 공유와 짧은 원음 메시지 프로토타입을 준비하는 저장소입니다.

현재 단계는 **개발 하네스의 로컬 이슈 큐**입니다. 앱 개발은 사용자가 재개를 요청할 때까지 보류합니다.

## 작업 방식

이슈를 만든 뒤 `be/issue-<번호>-<설명>` 브랜치에서 작업합니다. PR에 검증 증거를 남기고 독립 검증을 거쳐 `main`에 squash 병합합니다.

- 에이전트 지침: [AGENTS.md](AGENTS.md)
- 역할, 실행 순서, 중지와 재개: [하네스 운영 규칙](docs/harness/README.md)
- 큐 실행 명령, 스냅샷과 복구: [로컬 큐 사용법](docs/harness/queue.md)
- 작업 요청: [이슈 양식](.github/ISSUE_TEMPLATE/task.md)
- 변경 검토: [PR 양식](.github/pull_request_template.md)

## 로컬 큐 확인

Node.js와 npm을 사용합니다. 외부 패키지 설치는 필요하지 않습니다.

```sh
npm test
node harness/cli.js init --repo-root . --run demo --snapshot harness/fixtures/demo.json
node harness/cli.js status --repo-root . --run demo
```

예제 이슈는 가상 자료입니다. 큐는 다음 작업과 보류 상태를 반환하고 `.harness/` 아래에 기록합니다. GitHub 조회·변경, 에이전트 실행, 앱 개발을 시작하지 않습니다. 기존 demo가 있으면 덮어쓰지 않으므로 [보관 후 새 묶음 시작](docs/harness/queue.md)을 따릅니다.

GitHub 실행기, 병합 증거 게이트, CI와 예약 실행은 후속 범위입니다.
