# 목적지 중심 차량 UI 검증 (#23)

홈을 목적지·반영 상태와 `목적지 바꾸기` 중심으로 바꾸고, 받은 제안에는 `수락`·`거절`을 나란히 배치했다. 작성·설정·동행 만들기/참여는 별도 화면이다. 본문20sp, 터치56dp와 공통 색·간격·포커스 token을 사용한다. 서비스·core·서버·프로토콜·SDK 라우팅·권한은 변경하지 않았다.

| 화면 | 이전 | 새 화면 |
|---|---|---|
| 홈 | [이전 홈](before/before-home-emulator-5570.png) | [최종 홈](ui/final-home-emulator-5570.png) |
| 제안 | — | [글꼴1.3 수락·거절](attempts/cafe-first-native-timeout/ui/cafe-pending-responder.png) |
| 작성 | — | [선택 장소·이유](ui/cafe-composer.png) |
| 긴 장소 | — | [글꼴1.3 긴 이름·주소](ui/font-130-long-place-composer-A.png) |
| 설정 | — | [접힌 상세와 하위 행동](ui/settings-normal-emulator-5570.png) |

## 고정 버전과 실행

- 기준 main: `d03c6f0bd952fd2a0a0ebc376a6e8091e1c77a35`.
- 최종 제품 source: `3502929d569262253440fa3241ff90cffe57c859`; 이 뒤 커밋은 이 새 증거 폴더만 추가한다. 독립 최종 판정의 정확한 HEAD는 PR에 기록한다.
- 두 기기에 설치한 APK SHA-256: `623d103c6768762451f2113ffde35fa99351c5458b2de711f5d9a7d857664195`.
- ADB5041의 기존 Pleos12Leader/emulator-5570, Pleos12Follower/emulator-5572, user10만 사용했다. 외부 실제 기기·다른 AVD·SDK/계정/계약/배포 변경은 없다. 루프백 서버4190은 기존 서버 구현과 같으며 새 시험 동행방만 만들었다.
- 총괄이 실제 UI를 조작했다. 사용자 정의 역할이 현재 도구에 노출되지 않아 builder는 builtin worker, verifier는 별도 문맥 builtin default로 역할 TOML 계약을 전달받았다. verifier는 기기를 직접 조작했다고 주장하지 않으며 소스·독립 빌드·원시 실행 기록을 검사한다.

## 확인 결과

`runtime/runtime-audit.json`은 저장된 원시 기록을 다시 검사한 결과다. 각 case의 checkpoint는 연속 journal의 줄 offset이며 `requestRoute`의 모든 occurrence를 센다. 집합으로 중복을 제거하지 않는다. Native driver는 읽기 전용 query/bookmarks만 사용하고 각 실행의 routeRequestCount0과 제품 PID 유지를 확인했다.

- 새 시작 화면에서 실제 만들기·초대 참여 후 양쪽 연결을 확인했다. 비밀은 메모리에서만 전달했으며 초대 모달/비어 있지 않은 password의 캡처를 거부했다.
- 설정 왕복, 장소 선택 취소·다시 불러오기, 이유 입력 유지, 나가기 확인 취소, 동행 중지·재시작, cold start에서 암묵적 경로 요청은 양쪽0회였다. Cold start는 새 service epoch와 자동 동행OFF, 이전 확인 소실을 확인했다.
- 가든서비스(A 제안/B 응답), 청계산담백미(B 제안/A 응답)는 각각 대기·거절 요청0회, 거절 전후 실제 목적지 유지, 새 제안 수락 후 각 차량 요청1회를 확인했다. 실제 후속 조회의 이름·POI ID·nullable sub-ID·명시 provider가 선택 원본과 일치했다. 원본 좌표와 안내 좌표는 별도로 보존하며 좌표/입구/경로 동일성을 주장하지 않는다.
- 두 수락 case는 앱 UI count0인 순정 내비 화면을 각각45초보다 길게 유지했다. 연속 관찰의 PID·service epoch·자동 역할·accepted revision을 검사했으며 해당 구간의 수집 오류와 중복 요청은 없다. 단순 경로 시작/서버 수락을 성공 증거로 쓰지 않는다.
- 글꼴1.3에서 수락/거절은 첫 화면에 있고 터치 높이는56dp 이상이다. 실제 긴 저장 장소·주소, 입력 유지, disabled 상태와 키보드 포커스를 캡처했다. 중지 전 원래 설정 null로 복원했으며, 재개 시 OS가 기본값1.0을 기록한 것을 확인했다. 추가 확대 시험 후에는 재개 시 관찰한 두 user10의1.0으로 복원했고 시험 이유 입력을 비웠다. 최종 홈은 연결·동행ON 상태로 남겼다.
- 독립 archive 빌드: core93 + app76 =169 tests, 실패/오류/skip0; assemble/lint exit0, lint12 warnings/0errors. Gradle55tasks를 실제 새 archive에서 실행했다. Builder의 C3 core 결과는 UP-TO-DATE였으며 이를 새 실행으로 포장하지 않는다.
- 독립 APK의 전체 SHA는 ZIP 순서 차이로 다르다. 54 ZIP entry의 바이트와 entry 집합은 모두 같고 같은 유효 서명 인증서다. 전체 APK 재현성은 주장하지 않는다. 자세한 비교는 `build/independent/apk-comparison.json`이다.

## 실패와 한계

초기 C1의 긴 입력 검사는 ADB가 넣을 의도였던200자와 실제 입력 readback을 혼동해 실패했다. 실제 읽은 입력을 callback 전후 비교하도록 고쳤다. C2에서는 새 폼 만들기/참여까지만 검증했고 C3 결과로 대신 주장하지 않는다. C3 준비 중 A의 제품 프로세스가 없어졌고 UI XML 읽기가 실패해 중단했다. 새 제안 전이었다. 자체 앱을 다시 열고 새 방을 만들었으며 원인은 확정하지 않았다. 제품 crash를 입증하거나 숨긴 결과가 아니다.

C3 자동 시작 첫 검사는 초기 native 목적지가 있을 것으로 가정했으나 실제로는 없어서 CONFIRMED 대기가 실패했다. 없음·공유 revision0을 확인하고 검사 조건을 바로잡았다. 글꼴 확대 후에는 Pleos 표면 재구성 때문에 UI resource 조회가 실패했고, 제품을 명시적으로 다시 열고 실제 보이는 버튼을 확인한 뒤 자동 동행을 켰다. 실패 로그와 원래 스크립트는 `attempts/` 및 `runtime/`에 남겼다. 이들은 통과 실행으로 세지 않는다.

첫 수락 시도는 순정 내비가 로딩/빈 화면에 머물렀고 양쪽 실제 일치 확인이45초 내 완료되지 않아 실패했다. 해당 실패와 요청은 `attempts/cafe-first-native-timeout/` 및 연속 journal에 보존한다. 원래 글꼴 복원과 두 가상 native 프로세스 재시작 후 기본 화면이 회복됐다. 글꼴 재구성과의 인과관계는 확정하지 않았다. 최종 양성 라우팅은 기본 글꼴에서 다시 검증하고, 글꼴1.3의 긴 장소·이유·응답 조작은 별도 실제 UI 기록으로 검증했다. 추가 확대 UI 검사 뒤 A의 초기 native 조회가 응답하지 않아 해당 시도도 중단·보존했다. B의 읽기 전용 조회는 정상 응답했다. 기존 가상 A만 데이터 삭제 없이 재부팅하고 user10과 cold 자동OFF를 다시 확인했다. Native를 명시적으로 연 뒤 A의 실제 조회도 정상 응답했다. 재부팅으로 사라진 A의 adb reverse tcp4190만 원래 루프백 서버4190으로 복원하고 동행방 다시 연결 버튼을 눌러 회복한 뒤 최종 검사했다. 사용자의 중지 동안 관찰을 계속했다고 주장하지 않는다. 재개 시 이전 수집 occurrence와 현재 journal prefix가 정확히 같은지 확인해 이어 붙였으며, 이전 request를 재수집해 중복으로 세지 않았다.

원시 전체 작업 기록은 로컬 `.harness/worktrees/product-run-artifacts/simple-ui-20261106/`에 보존했다. 이 폴더는 필요한 최종 C3 기록과 전후 화면만 묶었다. 환경 변수·인증 저장소·실제 비밀·SDK·APK는 포함하지 않는다. 지도 배경이 비는 기존 Pleos 에뮬레이터 한계와 실차/휴대폰 미검증은 이번 UI 변경으로 해결됐다고 주장하지 않는다. 새 검색·음성·AI 기능도 추가하지 않았다.
