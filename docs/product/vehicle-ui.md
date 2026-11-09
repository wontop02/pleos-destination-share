# 차량 UI — 목적지 중심 화면 (#23)

차량 홈은 지금 함께 가는 장소와 다음 행동을 먼저 보여 준다. 정차 중이거나 동승자가 조작하는 프로토타입이며, 실제 순정 지도와 차량 좌측 패널은 시스템 화면을 그대로 사용한다. 디자인 기준은 저장소의 [DESIGN.md](../../DESIGN.md)다.

## 홈

상단의 `동행`과 `설정` 아래에 동행방·내비 연결과 자동 동행 상태를 한 줄로 표시한다. `함께 가는 곳`에는 공유 목적지의 이름·주소, 실제 반영 상태와 주 행동 `목적지 바꾸기`가 있다. 공유 목적지가 없을 때 현재 내비의 장소를 함께 정한 곳으로 추측하지 않는다.

`동행 시작` 옆의 문구는 역할별 동의를 설명한다. 대표 차량은 내비 목적지를 자동 공유하고, 참여 차량은 함께 가는 곳을 내비에 자동 반영한다. 시작은 사용자의 명시적인 버튼 조작을 필요로 하며, cold start와 화면 이동은 자동 동의를 만들지 않는다. 동행 또는 제안 lease가 활성화되면 모든 화면의 상단에서 `동행 중지`에 접근할 수 있다. 이 버튼은 기존 `ConvoySessionService.stopAutomatic()`을 호출한다. 중지는 기존 안내를 유지하며 동의와 적용 권한을 끝낸다.

새 제안은 홈으로 이동시켜 이름·이유·제안 차량·진행 상태와 나란한 `수락` / `거절`을 먼저 보여 준다. 제안자와 이미 수락한 차량은 대기 상태를 본다. 응답은 화면에 표시한 제안 ID·버전으로 기존 `canRespond()`를 다시 확인하며, 권한 대화상자 뒤에도 같은 ID·버전을 전달한다. 새 제안은 기존 작성 화면의 장소 선택을 해제한다. 거절·만료·무효화는 쉬운 문구로 표시하고 기존 내비 안내는 유지한다.

## 목적지 바꾸기

`목적지 바꾸기 → 저장한 장소 선택 → 선택 장소 확인과 이유(선택) → 제안 보내기` 순서다. 선택 단계의 `내비에 ☆ 저장한 장소만 보여요.`가 지원 범위를 설명한다. 이름과 주소를 함께 보여 주는 기존 immutable saved-place snapshot에서 장소를 명시적으로 선택한다. `제안 보내기` 전에는 제안이나 경로 요청을 보내지 않는다.

이유는 선택 사항이며 빈 입력은 `이곳으로 함께 가요.`라는 기본 제안 문구를 사용한다. 제안은 기존 controller의 현재 snapshot·선택 객체를 재검사한 뒤 `proposeSelectedPlace()`로 보낸다. 알림 권한을 기다리는 동안 snapshot이나 선택이 바뀌면 전송하지 않는다. 예제는 하단의 `예제 장소`에서 별도로 선택하며 기존 공개 exact-point 장소만 사용한다.

불러오기 중·빈 목록·지원 정보 없음·실패·취소 상태는 작성 화면에서 다음 행동을 설명한다. 취소하거나 한 번 사용한 연결에서 다시 읽어야 할 때 주 행동은 `다시 불러오기`다. 이미 읽은 목록은 `저장한 장소 다시 선택 · 불러온 목록`으로 열며, 새로 저장한 장소를 읽는 별도 행동은 `목록 새로 불러오기`다. 사용자가 이 행동을 누른 경우에만 기존 coordinator를 다시 연결하고 새로 연결된 내비의 첫 목적지 조회가 끝난 뒤 장소를 한 번 읽는다. 화면 진입·목록 재열기만으로 native reconnect나 새 읽기를 만들지 않는다. 한 연결당 한 번 읽는 기존 제한과 8초 timeout을 유지한다. 취소, 작성 화면 이탈, Activity 중지와 방/회원/내비 epoch 변경은 기존 controller 규칙에 따라 선택을 무효화한다.

`PlacesLoadIntent`는 이 명시적 불러오기 의도만 Activity 메모리에 보관한다. 방/회원/room epoch/작성 화면 generation과 첫 eligible native snapshot에 묶고, 새 연결의 destination read 중에는 기다린다. 경로 적용·현재 조회·장소 읽기·공유·제안 lease·자동 시작 대기 중에는 새 갱신을 시작하지 않는다. 대기 중 다른 작업, 페이지 이탈, 중지, 방/회원/epoch 변경, 새 native snapshot, 연결 실패 또는 25초 상한은 의도를 폐기한다. 이 helper는 경로 요청·제안 전송·자동 동의·FGS lease를 소유하거나 생성하지 않는다. cold start에서 복원하지 않으며 실패 후 늦은 연결도 장소를 읽지 않는다. 홈이나 설정으로 이동해도 이미 명시적으로 요청한 내비 연결 자체를 강제로 중지하지는 않지만, 그 뒤 장소 읽기를 이어가지 않는다.

View는 Activity 생성 시 한 번 만든다. 관찰 상태가 바뀔 때 텍스트와 enabled/visibility만 갱신하며 이유·차량 이름·방 입력을 tick마다 다시 설정하지 않는다. 페이지 이동은 서비스에 재연결하거나 세션을 재생성하지 않는다.

## 말로 초안 작성 (#25)

작성 화면 상단의 `말로 작성`은 명시적인 전경 음성 입력이다. `말하기 끝`은 최종 결과를 잠시 기다리고, `음성 초안 지우기`는 즉시 취소한다. `들은 말 (고칠 수 있어요)`에서 오인식을 고친 뒤 `초안 정리`로 변경할 곳과 이유의 초안을 만든다. 현재 저장 목록의 실제 이름/주소와 일치하는 후보만 `후보 확인`으로 보여 주고, 맞는 장소가 없으면 기존 수동 선택을 안내한다. `가든서비스`의 업종을 카페로 추측하거나 새 검색 결과를 만들지 않는다. 한 장소라도 직접 눌러 확인해야 하며 기존 `제안 보내기` 전에는 전송이나 경로 요청이 없다.

문장을 고치거나 다시 정리하면 음성으로 고른 선택을 해제하고 현재 불변 목록은 보존한다. 장소 snapshot, 방/회원/room/native epoch, 작성 화면 generation을 다시 검사하며, 페이지 이탈/Activity 중지/새 제안/동행 중지는 음성 UI 권한을 즉시 무효화하고 마이크 stop을 요청한다. 비동기 stop을 보존하도록 helper/listener를 종료 callback 또는 최대 3초까지 유지한 뒤 해제하며 새 시작은 이전 해제를 기다린다. 물리 종료는 별도 기기 관찰로 검증한다. raw 발화나 후보 목록을 기록하지 않고 UI observation tick이 입력을 덮지 않는다. 기존 View helper·20sp/56dp와 세로 스크롤을 사용한다. 실패/권한/미지원/시간 초과는 직접 입력·다시 말하기·수동 장소 선택을 안내한다. 처리 위치, 제한된 규칙과 실제 STT/사람 발화 구분은 [음성 초안 계약](vehicle-voice-proposals.md)을 따른다.

추가 View ID는 `voice_start`, `voice_stop`, `voice_cancel`, `voice_status`, `voice_text`, `voice_analyze`, `voice_summary`, `voice_candidates`, `voice_candidate_choices`, 반복하는 `voice_candidate_choice`다. 자기 testOnly driver의 `VehicleUiTextProbe`는 실제 보이는 음성문/이유 입력 View에만 고정 공개 문장을 입력할 수 있다. STT 결과 주입이나 운영 앱 대상 instrumentation은 아니다.

## 상태의 의미

홈에는 raw `state.message`, `proposalMessage`, 좌표, ID, 버전, SDK 용어를 표시하지 않는다. `VehiclePresentation`은 구조화된 기존 상태로 짧은 제품 문구를 만든다. 제한된 기존 제안 오류 문구는 실패 여부를 식별하는 데만 사용하고, 그 원문을 홈에 노출하지 않는다.

`내비에 반영 중`은 요청/연결/탐색 단계이며 성공이 아니다. `내비에서 목적지를 확인했어요`는 현재 방·현재 공유 대상과 실제 `DestinationConfirmation`의 source/kind/actual, 현재 실제 조회 및 연결 상태가 모두 맞을 때만 표시한다. 수락한 제안이 있으면 그 목적지와 accepted revision도 현재 공유 대상과 일치해야 한다. 수락 이력, 같은 이름, 내비 연결, 요청 반환, 경로 시작/완료 이벤트만으로 성공을 만들지 않는다. 끊김·대상 변경·확인 소실은 성공 표시를 없앤다.

이미 확인한 목적지도 주기적인 실제 조회 동안은 `내비 목적지 확인 중`으로 표시한다. 조회/반영 중에는 홈의 재반영 행동을 숨기며, 조회가 끝나 실제로 확인되지 않은 대상이나 연결 문제에 대해서만 필요한 복구 행동을 보여 준다. 이전 확인 기록을 조회 중 성공으로 추측하거나 단순 조회를 반영 실패로 표시하지 않는다.

저장한 장소의 성공은 기존 place identity 확인을 뜻하며 좌표·입구·경로 또는 계산 완료의 동일성을 뜻하지 않는다. 예제 exact-point는 기존 엄격한 좌표·POI 확인을 유지한다. 자세한 원본과 실제 native 조회는 설정의 접힌 `개발 상세 보기`에서 별도로 볼 수 있다. 명시적인 중지로 생긴 `CANCELLED`는 실패 재시도를 유도하지 않는 중립 상태다.

## 설정과 시작

설정에는 연결 복구, 현재 내비 목적지 조회, 수락한 목적지 직접 재시도/대표 자동 공유 보호 해제, 기존 수동 공유·반영·공유값 삭제, 초대, 방 종료/나가기와 기기 연결 삭제가 있다. 위험한 방 관리와 공유값 삭제·보호 해제는 확인 대화상자를 거친다. 현재 반영 중 내비 재연결은 기존 applying guard로 비활성화한다. 재연결은 새 동의나 같은 목적지의 새 요청을 만들지 않는 기존 세션 동작을 그대로 호출한다.

초대 모달은 `FLAG_SECURE`를 유지하고 닫으면 비밀 텍스트를 지운다. 초대 비밀 입력은 password 형식, 저장/자동완성 금지이며 화면을 떠나거나 제출하면 지운다. 토큰·초대 비밀은 개발 상세나 로그에 넣지 않는다. 서버 주소는 방 만들기/참여의 접힌 개발 설정 안에만 있다.

연결 전 시작 화면은 `동행 만들기`와 `초대로 참여`를 제공한다. 같은 폼에서 역할에 필요한 차량 이름과 초대 정보를 입력한다. 페이지 이동은 단순 표시 전환이며 기존 인증·초대·저장 규칙을 바꾸지 않는다.

## 표시와 검증

`app/src/main/res/values/styles.xml`이 색·간격·글꼴·반경을 소유하고 `VehicleViews`가 카드·버튼·입력·포커스·disabled 상태를 공통으로 사용한다. 본문과 버튼은 20sp, 제목은 34–36sp, 버튼은 최소 56dp다. 밝은 회색 바탕과 흰색 둥근 카드, 짙은 본문, 파란 주 행동, 테두리 보조 행동, 빨간 위험 행동을 사용한다. 상태는 문구와 색을 함께 사용한다. 누름/포커스 상태의 배경·글자 우선순위를 일치시키고 DPAD 포커스에 3dp 테두리를 둔다.

콘텐츠는 최대 1120dp 폭으로 가운데 배치하고 세로 스크롤한다. 긴 이름·주소는 줄바꿈하며 font scale을 고정하거나 줄이지 않는다. 제안 응답은 주소보다 앞에 배치해 주요 동작의 접근을 우선한다. 실제 Pleos viewport, fontScale 1.3과 긴 텍스트의 가독성·응답 위치는 총괄의 실제 기기 캡처로 확인하며 JVM 테스트나 빌드 성공만으로 통과를 주장하지 않는다.

`VehiclePresentationTest`는 현재 대상/accepted revision/실제 identity guard, 연결 소실, route 이벤트의 false success, 실제 coordinator 중지 상태와 선택 이유의 protocol 제한을 검사한다. `PlacesLoadIntentTest`는 명시적 입력 없는 무동작, 캐시 재열기와 새 읽기 구분, 목적지 조회 종료 후 한 번 읽기, 방/회원/화면/native generation 및 중지·실패·충돌에 따른 취소를 검사한다. 기존 core/app 회귀는 세션·동의·stale/중복·정확한 place/exact 의미를 계속 검사한다. 두 차량 실제 요청 횟수와 45초 이후 연속 수명, 생성·참여·복구와 최종 화면의 증거는 고정 source/APK에서 별도로 확인한다.

주요 접근 가능한 View ID는 다음과 같다. 각 ID는 실제 사용자 조작과 표시 View에만 부여한다.

| 흐름 | ID |
|---|---|
| 홈 | `home_page`, `connection_status`, `shared_place_name`, `shared_place_address`, `destination_status` |
| 주요 행동 | `change_destination`, `start_convoy`, `stop_convoy`, `nav_settings`, `nav_home`, `home_recovery` |
| 제안 응답 | `proposal_card`, `proposal_name`, `proposal_reason`, `proposal_proposer`, `proposal_status`, `accept_proposal`, `reject_proposal` |
| 작성 | `compose_page`, `select_saved_place`, `reload_saved_places`, `saved_places_status`, `selected_place_name`, `selected_place_address`, `proposal_reason_input`, `send_proposal`, `example_places` |
| 실제 목록 | `saved_place_choices`, 반복하는 `saved_place_choice`, `saved_places_cancel` |
| 설정 | `settings_page`, `reconnect_room`, `reconnect_native`, `refresh_native`, `retry_accepted`, `reset_protection`, `developer_details` |
| 수동·방 관리 | `manual_share`, `manual_apply`, `clear_shared`, `invitation`, `leave_room`, `forget_room` |
| 생성·참여 | `welcome_page`, `create_room_entry`, `join_room_entry`, `room_form_page`, `vehicle_name_input`, `room_id_input`, `invitation_input`, `submit_room`, `server_details`, `server_address_input` |
