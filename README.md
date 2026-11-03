# pleos-destination-share

Pleos 차량 간 목적지 공유와 짧은 원음 메시지 프로토타입을 준비하는 저장소입니다.

현재 단계는 **개발 하네스 기본 설정**입니다. 앱 개발은 사용자가 재개를 요청할 때까지 보류합니다.

## 작업 방식

이슈를 만든 뒤 `codex/issue-<번호>-<설명>` 브랜치에서 작업합니다. PR에 검증 증거를 남기고 독립 검증을 거쳐 `main`에 squash 병합합니다.

- 에이전트 지침: [AGENTS.md](AGENTS.md)
- 역할, 실행 순서, 중지와 재개: [하네스 운영 규칙](docs/harness/README.md)
- 작업 요청: [이슈 양식](.github/ISSUE_TEMPLATE/task.md)
- 변경 검토: [PR 양식](.github/pull_request_template.md)

이 저장소에는 에이전트 정의와 작업 양식이 있습니다. 이슈 큐 처리, 증거 검사 프로그램, CI, 예약 실행은 아직 구현하지 않았습니다. 설정 파일을 추가하는 것만으로 에이전트나 자동 작업이 시작되지는 않습니다.
