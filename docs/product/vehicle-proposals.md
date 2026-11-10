# 차량 목적지 변경 제안 (#17)

Legacy 호환 계약이다. #29의 [목적지 변경 요청](destination-change-requests.md)은 각 수신 차량의 수락 후 그 차량만 반영하며 이 문서의 전원 합의를 사용하지 않는다. 기존 API와 아래 실행 증거는 보존한다.

현재 회원이 실제 순정 POI를 제안하고, 제안자를 포함한 현재 회원 전원이 수락하면 공유 목적지를 한 번 확정한다. 제안 대기·거절·120초 만료는 기존 공유 목적지와 순정 안내를 바꾸지 않는다. 두 차량에서는 A 제안 → B 수락 → A/B 실제 반영이다. 참여 차량의 제안 → 대표의 수락도 같은 계약이다.

기준은 #14/PR #15의 main `875c353f9918981571908bda7e47b800ceff7897`이다. #16 지도 복구, AI/STT, 음성, 휴대폰, 일반 장소 검색은 포함하지 않는다. 지도 표시와 `onRoutingCompleted`를 성공 근거로 사용하지 않는다. [DESIGN.md](../../DESIGN.md)의 제안 상태 흐름과 기존 차량 토큰을 따른다.

## 버튼과 후보

화면 위쪽 `목적지 변경 제안` 카드에서 `프로토타입 공개 장소`를 선택한다. 이는 두 공개 장소만 있는 선택기이며 일반 검색이 아니다. 이유는 기본 문구가 있는 편집 입력으로 최대 200자다. 기존 20sp 본문·56dp 버튼·색상과 세로 스크롤을 사용한다.

| 공개 장소 | 실제 canonical 좌표 | POI ID |
|---|---|---|
| 42dot | 37.41289678, 127.09383464 | `36khp37hh8gqcm5nqhe5` |
| 소프트웨어드림센터 | 37.41275833, 127.09481944 | `36khp2hdj2cfcm5nqhe3` |

두 후보 모두 #12의 실제 공개 POI 관측과 기존 native driver를 따르며 provider는 빈 문자열, sub-ID는 null이다. 기존 SDK adapter 경계에서만 null을 SDK 요청의 빈 문자열로 전달한다. 주소는 공개 관측 `수정구 시흥동`이며 가짜 POI/일치 값을 만들지 않는다.

1. `이 장소로 변경 제안`으로 제안한다. 카드에 후보·이유·제안자와 고정된 회원별 수락/거절/대기가 표시된다. 제안자는 이미 수락한 것으로 본다.
2. 다른 회원은 `제안 수락 · 전원 동의 시 반영` 또는 `제안 거절 · 현재 안내 유지`를 선택한다. 전원 수락 전에는 새 경로를 요청하지 않는다. 오래된 카드·중복 탭은 다시 요청하지 않는다.
3. 수락 확정은 서버 상태이며 반영 완료가 아니다. 서비스가 새 SDK listener에서 한 번 요청하고 실제 후속 조회의 이름·좌표·POI ID/provider/nullable sub-ID 일치를 확인한다. 자동 동행을 켤 필요가 없으며 순정 내비 전경 뒤 앱 복귀도 요구하지 않는다.
4. 실패 또는 과거 확정은 자동 재요청하지 않는다. 현재 공유값과 같은 확정에만 `수락한 목적지 다시 적용`으로 새 45초 작업을 명시적으로 시작할 수 있다. 대표의 `자동 공유 다시 시작`은 실패 뒤 보류한 자동 공유를 풀어 이후 실제 순정 변경을 다시 공유한다.
5. `동행·제안 중지` 또는 알림의 `동행 중지`는 동의·대기 I/O·listener·새 요청 권한을 즉시 끝낸다. 탈퇴·방 종료·작업 제거·프로세스 소실도 동의를 복구하지 않는다. 순정 안내 자체는 취소하지 않는다.

## 인증된 HTTP/SSE 계약

기존 v1 직접 목적지 API와 이름·좌표 응답은 유지한다. 아래 API는 같은 방의 bearer 인증을 `Authorization` 헤더로만 받는다. 초대 비밀이나 다른 방 토큰은 읽기·제안·응답 권한이 없다. 응답 본문·URL·SSE ID에는 인증값을 넣지 않는다.

| 요청 | 본문 | 결과 |
|---|---|---|
| `POST /api/rooms/:roomId/proposals` | `{id, expectedRevision, destination, reason}` | 현재 회원이 제안. 24자리 소문자 hex ID, 현재 목적지 revision, 적용 가능한 기존 Pleos 메타데이터가 필요하다. |
| `POST /api/rooms/:roomId/proposals/:id/responses` | `{version, expectedRevision, response}` | 표시한 제안 버전, 제안의 base revision과 `accepted`/`rejected`. 호출자 자신의 응답만 변경한다. |
| 기존 방 GET / events | 기존 경로·인증 | snapshot과 새 `proposal`/기존 `destination`/`members` 이벤트에 optional `proposal` 및 `acceptedProposal` 메타데이터를 포함한다. |

공개 제안은 `id`, `version`, `status`, `destination`, `reason`, `proposer`, `createdAt`, `expiresAt`, `baseRevision`, `responses`, nullable `acceptedRevision`/`endedAt`/`invalidatedBy`를 가진다. 응답 목록은 제안 시점의 현재 회원 ID·이름과 응답/응답 시각을 고정한다. 이후 회원 가입·탈퇴는 대기 제안을 무효화한다. 한 명뿐인 방은 제안자의 동의로 즉시 전원 수락을 충족한다.

대기 제안은 방당 하나다. 보관 제안은 방당 최대 64개이며 상한 뒤에는 `429 proposal_limit`로 새 방을 요구한다. 4KiB JSON, 이유 200자와 기존 목적지 필드 상한을 적용한다. 이력을 지워 ID 재사용을 허용하지 않으므로 같은 ID·같은 내용 재전송은 기존 결과, 다른 내용은 `409 proposal_id_conflict`다. 응답의 동일 버전·동일 선택 재전송은 현재 결과를 반환하고 revision을 늘리지 않는다. 중복 요청의 top-level destination/revision/sequence/proposal/acceptedProposal은 항상 현재 방의 한 프레임이다. 과거 요청 결과는 별도 `result: {proposalId, version, status, acceptedRevision}`에 넣는다. 과거 제안을 현재 sequence의 proposal로 반환하지 않는다. 반대 선택·낡은 버전·낡은 base revision·다른 방 ID·무효/만료 제안은 확정하지 못한다. 여러 회원이 같은 버전에 동시에 응답하면 먼저 처리된 응답 뒤의 요청은 409일 수 있다. 최신 카드를 확인한 명시 응답이 필요하다.

서버는 JSON 본문을 기다린 뒤 회원 인증과 방/제안 만료를 다시 검사한다. mutation과 sequence 증가는 await 없는 한 구간에서 처리한다. 만료는 제안별 타이머·정기 정리·요청 진입에서 검사하며 정확히 `now >= expiresAt`이면 수락할 수 없다. 방 종료·만료는 인증과 저장 상태를 함께 제거한다. 더 최신 직접 목적지는 대기 제안을 무효화한다.

전원 수락 시 proposal을 accepted로 만들고 `room.destination`을 바꾸며 revision을 정확히 한 번 증가시킨다. **확정 제안과 목적지/revision은 같은 sequence의 하나의 `destination` 프레임**으로 전송한다. 따로 도착하는 두 프레임을 가정하지 않는다. HTTP 결과도 같은 프레임이며 클라이언트는 한 monotonic reducer로 SSE/HTTP 중복과 오래된 결과를 처리한다. 재연결 snapshot은 최신 상태를 포함하며 이벤트 로그를 재실행하지 않는다. `acceptedProposal`은 현재 공유 목적지를 확정한 제안을 별도로 보존한다. 다음 pending/rejected/expired/invalidated 제안이 latest proposal을 대체해도 이 출처는 유지한다. 명시적인 직접 목적지 변경/삭제에서 null로 바꾸고, 다음 수락 확정에서 새 출처로 교체한다. 같은 sequence의 mutation 응답과 snapshot은 두 필드 모두 일치해야 한다.

## 서비스 동의와 자동 공유 경합

제안/수락 버튼은 방·회원·제안 ID/버전·base revision·후보·120초 기한에 묶인 메모리 동의를 만든다. 서버 확정과 현재 shared revision/대상이 일치하고 해당 동의가 살아 있을 때만 기존 `applyShared()`를 한 번 호출한다. 이때 먼저 권한을 소비하고 별도의 원래 45초 확인 기한을 만든다. 120초 직전 확정도 작업의 45초 확인 기한을 가진다. 대기/거절/만료 후보를 shared로 승격시키지 않는다.

기존 specialUse FGS는 자동 동행과 별도로 이 제한된 제안·확인 lease를 소유한다. 알림 허용과 화면의 직접 조작으로만 시작하며 타입·권한 범주를 추가하지 않는다. automatic OFF여도 제안 대기와 발행한 작업 확인 동안 Activity detach/unbind 뒤 계속 실행한다. 확인 성공 이후에도 원래 기한 안의 늦은 취소/변경은 새 listener의 실제 조회로 재검증하고 경로를 다시 요청하지 않는다. 실패한 같은 확정을 자동 반복하지 않는다. 재연결은 이미 발행한 작업만 원래 기한의 query-only로 재개한다. 과거 accepted snapshot·보안 인증 저장·UI 재부착은 동의나 새 경로를 만들지 않는다. 이후 다른 제안이 latest를 대체한 뒤 cold resume해도 acceptedProposal 출처가 일반 자동 따르기의 과거 경로 재생과 대표의 이전 목적지 재공유를 막는다. 현재 확정에 대한 명시 재시도는 최신 제안이 종료된 뒤에도 가능하다.

대기/확정 프레임은 coordinator에 목적지를 전달하거나 native 조회를 시작하기 **전에** 대표 publication을 보류한다. 이전 관측값과 공유 중 플래그를 지우고, publication generation으로 아직 실행되지 않은 이전 전송과 늦은 결과를 차단한다. 이미 HTTP 바이트를 보낸 작업은 서버 revision CAS로 보호한다. 그 작업이 수락보다 먼저 저장되면 제안이 무효화되고, 수락보다 늦으면 409로 확정을 덮어쓰지 못한다. accepted revision은 일반 자동 따르기의 적용 경로에서도 차단해 버튼 권한 경로와 중복되지 않는다.

대기 제안 보류와 수락한 목적지 보호는 별도 수명이다. 새 제안의 거절·만료·회원 변경 무효화는 대기 보류만 풀고 이전 수락 실패의 보호는 유지한다. 성공은 원래 45초 창 뒤 해당 accepted revision의 보호만 풀며, 그 사이 시작한 새 pending 보류는 유지한다. 반영/확인 실패는 이전 목적지 재공유를 계속 막으며 명시 재시도의 성공 정책 또는 `자동 공유 다시 시작`을 기다린다. 같은 프로세스의 완료·명시 해제는 후속 snapshot의 같은 출처로 자동 공유를 다시 잠그지 않는다. 이 publication 수명과 별도로, accepted revision은 일반 자동 따르기의 새 권한이 되지 않도록 차단한다. 성공한 45초 창 종료·중지·동일 방 재연결·automatic OFF 재연결 뒤에도 같은 출처는 새 경로를 만들지 않는다. 명시적인 현재 목적지 재적용/재시도와 더 최신 목적지 의도만 새 작업을 시작할 수 있다. 방·회원 인증을 지울 때 replay 기록을 함께 버리므로 다른 방의 같은 숫자 revision을 차단하지 않는다. 기존 직접 공유/공유 지우기는 별도 명시 변경이며 보류를 해제한다.

## 검증과 증거

`prototype/test/vehicle-proposals.test.mjs`는 실제 HTTP/SSE, fake clock, chunked 지연 본문으로 권한·전원 동의·거절·TTL·회원/방 수명·용량·중복·동시 선택·대표 CAS 경합을 검사한다. core의 `VehicleProposalProtectionTest`와 app의 `RoomProtocolTest`/`VehicleProposalSessionTest`는 기존 전체 회귀와 함께 실행한다. 실제 Node 서버 + 두/세 회원 + fake native 경계에서 auto OFF 양쪽 적용, 역방향, 부분 동의, 늦은 이벤트, 재연결, cold resume, 중지, 실패와 대기 중인 이전 publication을 검사한다. 이 fake native 결과는 실제 Pleos 실증의 대체가 아니다.

```sh
npm test
cd prototype/vehicle
./gradlew --no-daemon :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug \
  :app:assembleDebugAndroidTest :nativeDriver:assembleDebug :nativeDriver:lintDebug
```

비밀 없는 앱 journal `navigation-events.jsonl`, `session-state.json`과 `proposal-state.json`은 공개 후보 ID/이름, proposal ID/version/status, base/accepted revision, 회원별 응답, lease·busy·publication 보류를 기록한다. 이유·회원 이름·인증값·초대 비밀·개인 키·일반 순정 목적지 이력은 기록하지 않는다. root nullable keys `acceptedProposalId`, `acceptedProposalVersion`, `acceptedProposalRevision`으로 최신 제안과 현재 공유 목적지의 수락 출처를 구분한다. 기존 실제 `requestRoute`/`destinationMatch`와 source epoch는 유지한다. journal은 약 512KiB로 제한한다. vendor logcat은 인증값을 포함할 수 있어 수집하지 않는다.

2026-11-05 동결 1 구현측 검사 결과: 전체 Node 88개, core 79개, app JVM 38개가 실패/오류/skip 없이 통과했다. 앱 debug·androidTest·native driver APK 빌드와 두 모듈 lint의 종료 코드는 0이다. lint 오류는 0개이며 app 경고 7개(미사용 자원·아이콘·문자열 연결), driver 경고 2개(백업 규칙·아이콘)를 보존했다. raw 명령/로그·JUnit·lint·입력/APK 해시는 `.harness/worktrees/product-run-artifacts/vehicle-proposals-20261105/builder/`에 고정한다. 최초 컴파일의 journal context 참조와 검사 코드 nullable smart-cast 오류는 수정 후 전체 검사를 통과했으며 실패 로그도 보존한다.

동결 1 독립 검증에서는 이전 수락 실패의 보호를 다음 제안 종료가 풀어 버리는 결함과, 과거 중복 요청이 현재 sequence에 다른 proposal을 반환하는 결함이 재현됐다. 추가로 latest 제안이 과거 수락 출처를 가린 cold resume에서 새 경로가 생기는 경계도 재현했다. 동결 2는 pending/accepted 보호 수명과 accepted 출처를 분리하고 현재 프레임/별도 요청 결과를 구현했다. 실제 HTTP의 거절·만료·회원 변경·cold resume RED 검사를 GREEN으로 전환했으며, 독립 검증의 원본 2개 검사도 외부 source set 그대로 재실행해 통과했다. 이는 새 head의 독립 최종 판정을 대신하지 않는다.

동결 2 전체 검사 결과: Node 90개, core 81개, app JVM 43개(총 214개)가 실패/오류/skip 없이 통과했다. 원본 독립 재현 2개는 별도 targeted replay에서도 통과했다. 앱 debug·androidTest·driver 빌드와 app/driver lint는 모두 exit 0이며 lint 경고는 기존 app 7개·driver 2개, 오류 0개다. 동결 1 APK·입력·실패/정상 실행 원본을 보존하고 동결 2 산출물은 같은 builder 경로의 `freeze-2/`에 별도로 고정한다.

동결 2의 추가 독립 검사에서는 성공한 수락 작업의 45초 보호가 끝난 뒤 중지·재연결·일반 자동 따르기 시작이 같은 과거 경로를 다시 요청하는 결함이 재현됐다. 동결 3은 accepted replay revision을 publication 보호와 분리해 유지하고, 같은 ID의 snapshot에서도 이를 다시 확인한다. 같은 방의 중지/재연결은 기록을 보존하고 방·회원 인증을 지울 때만 버린다. 중지 경로와 automatic OFF 재연결 경로를 먼저 RED로 재현한 뒤 명시 재시도, 새 accepted ID/revision, 최신 직접 목적지와 다른 방의 같은 revision이 정상 동작하는 GREEN 회귀를 확인했다.

동결 3 전체 검사 결과: Node 90개·core 83개·app JVM 47개(총 220개)가 실패/오류/skip 없이 통과했다. 독립 VerifierStopReplayTest 원본도 외부 source set에서 별도로 통과했다. 앱·계측·driver 빌드와 lint exit 0, 오류 0, 경고 app 7개·driver 2개다. 동결 1/2 원본을 보존하며 최종 산출물은 `builder/freeze-3/`에 고정한다. Journal schema·버튼명·서버 API는 동결 2와 같다. 새 head의 실제 AC5와 최종 독립 판정은 총괄이 별도로 수행한다.

Builder는 구현/HTTP/JVM/build/lint만 담당한다. 총괄은 전용 ADB5041, 기존 emulator-5570/5572, user10과 새 loopback4186에서 새 APK의 실제 두 차량 AC5를 수행한다. 입력/설치 APK 해시, 정확한 base/head, 버튼·순정 화면, 원시 명령과 별도 verifier가 필요하다. 이번 구현 문서는 AC5 실행·지도 복구·실차·배포 심사 완료를 주장하지 않는다. 이전 `prototype/vehicle/evidence/**`는 byte-for-byte 보존하며 새 원시 산출물은 작업 전용 artifact 경로에 보관한다.
