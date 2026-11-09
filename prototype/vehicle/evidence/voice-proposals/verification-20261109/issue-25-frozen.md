## 목표

Pleos 차량 앱에서 짧게 말한 내용을 목적지 변경 제안 초안으로 정리하고, 실제 저장 장소 확인·수정 뒤 기존 제안/전원 수락 흐름으로 보낸다. 사용자 2026-11-08 새 채팅 실행 지시에 따른 단일 승인 작업이다.

## 작업 종류

prototype

## 수정 범위

- prototype/vehicle/ (제품 코드, JVM/기기 진단, 새 evidence/voice-proposals/만 추가; 기존 evidence 보존)
- docs/product/vehicle-voice-proposals.md, docs/product/vehicle-ui.md, docs/product/README.md
- DESIGN.md
- 필요할 때만 prototype/server.mjs 및 관련 prototype/test/ (새 외부 서비스는 승인 없이 활성화하지 않음)

## 완료 조건

- AC1: 기존 목적지 작성 화면에서 명시적으로 음성 입력을 시작·중지·취소한다. 현재 공식 SDK·실제 아티팩트와 전용 차량 환경을 확인하여 지원 경로를 사용한다. 인식 중/실패/미지원/시간 초과를 구분하며 화면 이탈·중지·방/회원/내비 세대 변경 후 늦은 결과로 초안이나 제안을 만들지 않는다. 몰래 계속 듣기·원음 재생/전송·통화는 없다.
- AC2: 확정 인식문 또는 사용자가 고친 문장에서 변경 의도·장소 단서·이유를 초안으로 정리한다. 카페·식당·주유소 등 일반 장소를 다루고 순정 ☆ 저장 장소의 현재 불변 snapshot만 실제 후보로 사용한다. 좌표·POI ID를 만들거나 자유 검색을 가장하지 않는다. 후보가 없거나 모호하면 수동 선택·수정·재시도·취소가 가능하다. 문장/이유는 메모리 수명이며 journal/server에 전체 후보 목록이나 원음을 쓰지 않는다.
- AC3: 사용자가 실제 이름/주소와 편집 가능한 이유를 확인한 뒤 기존 제안 보내기를 눌러야 전송한다. 인식·초안·후보 추천만으로 동의·제안·경로 요청을 만들지 않는다. 기존 제안/수락/거절/만료/중복/room scope·수락 후 native fresh-query 확인·선택/실제 지점 분리 계약을 보존한다. 전원 수락 전 경로 요청은 0회다.
- AC4: DESIGN.md의 기존 20sp/56dp 토큰과 단순한 한국어 문구를 사용한다. 실제 Pleos 화면에서 인식/수정/후보/전송 및 실패·취소, 긴 문장과 fontScale 1.3을 확인한다. 관련 JVM·HTTP 회귀, APK 빌드·lint를 실행한다.
- AC5: 전용 ADB5041의 기존 emulator-5570/Pleos12Leader 및 emulator-5572/Pleos12Follower, user10만 사용하여 새 APK/source를 고정한다. 실제 STT 초기화/인식과 입력 출처, 마이크 가능 여부, 초안 정리, 상대 수신, 거절/대기0회·전원 수락 후 실제 native 확인을 각각 증거로 남긴다. 합성/녹음/주입 입력은 실제 사람 발화 성공과 구분하며, 실마이크·사람 발화 미검증은 명시한다. 실제 두 차량 제안/수락/native 확인 및 별도 verifier PASS 없이는 병합하지 않는다.

## 의존 이슈

#17/PR #18 제안 계약, #21/PR #22 저장 장소 확인, #23/PR #24 UI 병합. 기준 main 8b6321276f33923f24f15a8437205b6126bf92ca. #7/#8 미병합 휴대폰·#16 지도 복구는 제외한다.

## 검증

- 명령: 저장소의 npm test, 전용 toolchain의 :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug 및 필요한 nativeDriver 검사.
- 실제 자원: 승인된 두 공식 AVD/전용 ADB5041/user10, 기존 toolchain. 기존 서버/방/데이터를 덮지 않고 새 loopback 실행을 분리한다.
- 최신 공식 Pleos/Android SDK 문서와 공개 아티팩트 조사, 공급자·음성 처리 위치/키/비용 조건 기록. 신규 가입·키·결제·약관·보안 권한 확대는 자동 승인으로 가정하지 않는다.
- 증거: 정확한 base/head, 입력·APK 해시, 명령/종료 코드, 실제 PNG/XML, 음성 이벤트와 경로 횟수/후속 실제 조회. 인증값/초대/원음·사적 발화를 출력하지 않는다.
- builder 구현 후 PR, 별도 문맥 verifier의 AC별 PASS, 예상 head로 main squash 병합, 실제 merged/main/issue closure 확인.
## 실행 상태 — 2026-11-08 BLOCKED

PR #26은 Draft/미병합이다. 제품 source105cd7e의 독립 JVM198/Node94 및 assemble/lint가 통과했다. 앞선 합성음 마이크 시험은 timeout했으나, 이후 대기 중이던 B의 실제 사람 발화 재시도는 인식 성공을 사용자와 finalText 로그로 확인했다. 두 차량 제안·전원 수락 후 native 및 확대 화면 증거는 미완료다. 전체 독립 판정은 BLOCKED이며 완료 조건을 낮추지 않았다. [최종 증거](https://github.com/wontop02/pleos-destination-share/blob/98ab831/prototype/vehicle/evidence/voice-proposals/closeout-20261108/README.md).

현재b883042는 증거만 추가한 커밋으로 exact-head PASS가 아니다. 저장소의 묶음당120분 상한(10:13:20–12:13:20UTC)에 도달해 제품 수정·새 시험을 중지했다. 시작 시각/한도는 체크포인트에 보존하며 재개로 초기화하지 않는다. 다음 실행은 새로 승인된 범위를 고정하고 최신 실제 음성 인식·두 차량/화면 시나리오와 정확한 최종 head의 별도 PASS를 확보해야 한다. 실제 merged/main 포함/이슈 닫힘은 수행하지 않았다.

## 후속 사용자 관측 — B 실제 발화 인식 성공

사용자가 “여기서 주유하고 가자”가 표시됐다고 확인했다. 전용 B/ADB5041/user10에서 새 시험을 시작하지 않고 로그·설치 APK만 읽어 finalText 수신과 freeze7 해시 일치, 활성 녹음 없음을 확인했다. 말하기 끝에서 최종 결과까지8026ms였다. [실제 사람 발화 성공 증거](https://github.com/wontop02/pleos-destination-share/blob/b883042/prototype/vehicle/evidence/voice-proposals/human-recognition-B-20261108/README.md). 이전 실패는 보존하며, A와 나머지 필수 시나리오·별도 최종 PASS는 여전히 미완료다. 기존 실행 상한과 BLOCKED/Draft 상태는 유지한다.
