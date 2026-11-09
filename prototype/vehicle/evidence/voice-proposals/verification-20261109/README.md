# Voice proposal verification — 2026-11-09

제품 source `105cd7ef5b47e194be1fbfd2271ba6d96c0fd32f`와 설치 APK `5b9eec0cca7f645e25ef4615cd5096d8e31b9256141d35f8d5018bc2c398317e`의 후속 실제 검증이다. 기준 main은 `8b6321276f33923f24f15a8437205b6126bf92ca`, 검증 시작 head는 `b883042cbc9610a551b3329bb6effdd219eabd06`다. 이번 실행은 제품을 수정하지 않았으며 문서와 증거를 추가한다.

사용자 승인 범위와 별도 03:43:58–05:43:58 UTC 한도는 [execution.json](execution.json), 정확한 AC는 [issue-25-frozen.md](issue-25-frozen.md)에 있다. 11/8 구현 묶음의 상한·실패·당시 BLOCKED 기록은 유지했다. 실행 상태 갱신은 별도 [qa-state.json](qa-state.json)에 둔다.

[QA 보고서](qa-report.md)와 [사례 표](scenario-matrix.json)가 결과와 한계를 설명한다. 실제 collector는 03:53–05:00 UTC 양쪽 PID/전체 journal occurrence를 연속 보존했고, 전체에서 route 요청은 만장일치 Q08b의 차량별 1회뿐이다. [최종 원시 감사](final-runtime-audit.json), [수집 종료](collector-result.json), [원본 관찰](observations.jsonl)과 양쪽 `emulator-5570-journal.jsonl`/`emulator-5572-journal.jsonl`을 함께 읽는다. 이전 accepted 상태는 수집 시작 baseline으로 제외했다.

B의 실제 사람 발화 성공은 [11/8 사람 발화 관측](../human-recognition-B-20261108/README.md)이다. 이번 후보·이유·긴 문장 편집은 실제 UI 고정 fixture이며 새 음성 인식으로 세지 않는다. 현재 짧은 실마이크 시험은 명시 중지 뒤 TIMEOUT을 확인한 실패 표시 시험이다. 서로의 출처를 대체하지 않는다.

`verifier/`에는 별도 문맥 builtin default agent가 verifier.toml 계약에 따라 직접 실행한 회귀·빌드 및 원시/시각 감사가 있다. custom role 실행이나 GitHub 사람 승인이 아니다. 기존 부분 감사의 BLOCKED는 당시 단계 기록이며, 최종 exact-head 독립 보고서와 PR 최종 재결합 기록만 병합 판정으로 사용한다. 제품/테스트 입력 변경이 없으므로 문서·증거 head를 검증 당시 컴파일 입력과 결합한다.

기본 방 생성 주소 문제의 전후 증거와 재부팅 후 운영 reverse helper는 `environment/default-room-path-20261109/`에 보존한다. 앱 기본 4182를 이 작업 전용 서버4192로 연결하며 기존4192/4190·인증·방은 보존한다. helper는 이 Mac의 승인된 전용 toolchain 경로를 사용한다. 새 환경의 범용 배포 도구가 아니다. 방 생성 검사는 양쪽 실제 기본 주소 API의201/격리 방 정리200이며 전체 수동 UI 증거로 확대하지 않는다.

원본 외부 디렉터리는 `.harness/worktrees/product-run-artifacts/voice-proposals-verify-20261109/`에 보존한다. 이 묶음은 root의 공개 진단·UI·원시 collector와 verifier의 테스트 보고서를 담고, 재빌드 archive/APK/JAR/원음·인증값은 포함하지 않는다. [포장 manifest](artifact-manifest.json)가 파일 해시와 원본 위치를 기록한다. A/B 글꼴1.0과 비활성 마이크, A홈/B깨끗한 작성 화면을 확인한 뒤 collector를 중지했다. 기존 두 AVD/서버/방은 사용자 이용을 위해 유지했다.

Subsequent operation uses [environment/ensure-default-route.py](environment/ensure-default-route.py), which refuses an absent ADB server/conflicting mapping, preserves4192 and prints its result without overwriting archived evidence. The current idempotent execution exited0 and changed no mapping; [helper check](environment/helper-idempotency-check.json) records it.
