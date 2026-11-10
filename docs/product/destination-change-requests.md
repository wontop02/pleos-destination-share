# 길맞춤 목적지 변경 요청 (#29)

2026-11-10 사용자 지시에 따라 차량의 기본 흐름을 실제 목적지를 정한 뒤 다른 차량에 알리는 방식으로 바꾼다. 장소 목록은 “장소명·업종으로 검색한 실제 장소”이며, 수신은 “각 차량이 수락하면 변경”이다. 기존 전원 합의 제안과 자동 따르기는 새 요청의 수락 조건이 아니다.

## 기본 조작

1. 공유방에 연결하고 `목적지 바꾸기`에서 장소명 또는 업종을 입력해 `장소 검색`을 누른다.
2. 실제 순정 검색 결과에서 사용자가 장소를 선택하고 목적지를 설정한다. 길맞춤으로 돌아와 `내비에서 정한 목적지 확인`을 누른다.
3. 새로 읽은 이름·주소를 확인하고 `다른 차량에 변경 요청 보내기`를 누른다. 발신 직전 다시 읽은 대상이 달라졌으면 새 대상을 보여 주고 다시 확인하도록 한다. 발신 차량에 경로를 다시 요청하지 않는다.
4. 다른 차량은 `변경 요청 알림 받기`를 시작하면 순정 내비 전경에서도 Android 알림을 받는다. 알림을 열어 발신 차량·이름·주소를 확인하고 `수락하고 내 목적지 변경` 또는 `거절`을 누른다.
5. 수락한 차량만 경로를 한 번 요청하고 실제 후속 목적지를 확인한다. 다른 차량의 무응답·거절을 기다리지 않는다. `알림 수신 중지`와 진행 알림의 중지는 이전 요청 권한을 끝내며 현재 순정 안내를 취소하지 않는다.

☆ 저장, 음성 입력, LLM, 외부 검색 계정·키는 이 흐름의 선행 조건이 아니다. 기존 저장 장소·음성 초안은 설정의 보조 기능으로 남긴다. 정차 또는 동승자가 조작하는 프로토타입이다.

## 실제 검색과 목적지 원본

NaviHelper 3.0.5의 공개 `NavigationScreenRequest.SearchPlace(keyword)`를 전경 `Activity` context로 호출한다. 공식 필수 `NAVI_CUSTOM_MAP`의 최소 normal 권한 선언을 추가한다. #19가 실제 `protectionLevel=0`을 확인했으며 특권 부여나 lint 억제로 우회하지 않는다. 초기화·요청·해제는 그 화면의 수명과 연결한다. 화면 이탈·방/회원 변경·연결 종료는 미완료 검색 권한과 발신 미리보기를 취소한다. `applicationContext`로 검색 화면을 열지 않는다.

공개 SDK에는 검색 결과를 돌려주는 callback이 없다. 길맞춤이 임의 후보 목록이나 ID를 만들지 않는다. 순정 화면에서 사용자가 실제 목적지를 설정하고 돌아온 뒤 `getDestinationInfo()`의 이름·주소·좌표·POI ID·nullable sub-ID·provider를 읽는다. 이 관측한 `EXACT_POINT`가 발신 원본이다. 앞선 [#19 공개 검색 실증](general-place-discovery.md)의 화면 요청과 검색 결과 관찰은 새 앱 흐름의 전체 실증을 대신하지 않는다.

미리보기는 15초이며 발신 때 새 SDK 세션으로 다시 읽는다. 실패·없음·불완전 메타데이터·오래된 미리보기·화면 이탈은 발신하지 않는다. 재조회 대상이 미리보기와 다르면 요청을 보내지 않고 새 이름·주소의 재확인을 요구한다. 이름만 같은 곳은 같은 대상으로 간주하지 않는다.

## 별도 요청 프로토콜

인증·방 수명·loopback 제한은 기존 API를 쓴다. 새 요청은 기존 `destination`/`revision`을 변경하지 않는다. 이벤트는 별도 `destination_request`이며 기존 `destination` 이벤트를 발행하지 않는다. 모든 snapshot/frame은 추가 필드 `requestRevision`, `changeRequest`를 포함하고 기존 필드도 유지한다.

| 경로 | 요청 본문 | 권한과 동작 |
|---|---|---|
| `POST /api/rooms/:room/destination-requests` | `{id, expectedRequestRevision, destination}` | 유효 회원 누구나 현재 요청 버전과 실제 native metadata를 보낸다. 발신자 이외의 현재 회원을 수신자로 고정한다. |
| `POST /api/rooms/:room/destination-requests/:id/responses` | `{revision, response:"accepted" 또는 "rejected"}` | 고정 수신자 본인의 pending 응답만 변경한다. 다른 회원 ID를 받지 않는다. |

요청은 `{id, revision, status, destination, sender, createdAt, expiresAt, recipients}`를 가진다. recipient는 `{member:{id,name}, response, respondedAt}`다. 대상과 revision은 불변이며, 다른 수신자가 응답해도 해당 요청의 revision은 바뀌지 않는다. 따라서 서로 다른 차량의 동시·역순 응답은 충돌하지 않는다. room `sequence`는 각 변경을 정렬한다.

같은 ID·같은 내용의 발신과 같은 응답은 idempotent다. 반대 응답, 다른 내용으로 같은 ID 사용, 오래된 생성 버전·요청 ID·revision, 다른 방·수신자 권한은 거절한다. 중복 발신 결과는 현재 room frame과 과거 요청 ID/revision을 별도로 반환하며 과거 요청을 부활시키지 않는다. 요청은 방당 최대 64개, 수신자는 기존 회원 상한 이내이며 입력은 기존 목적지 metadata 경계를 지킨다.

새 요청은 이전 요청을 `superseded`로 끝낸다. 최대 120초 또는 방 만료는 `expired`, 회원 변경·기존 직접 목적지 변경은 `invalidated`로 끝낸다. 종료한 요청은 새 응답을 받지 않는다. 새 요청 진행 중에는 legacy 제안 생성을 거절하고, 새 요청 생성은 진행 중 legacy 제안을 무효화한다. legacy 제안·목적지 HTTP/SSE 회귀는 유지한다.

## 개별 동의와 실제 확인

요청 controller는 legacy 자동 모드와 별도 native gateway를 소유한다. 수신·snapshot·알림 클릭은 경로 권한을 만들지 않는다. 현재 방/회원·요청에 대한 살아 있는 로컬 수락과 서버의 본인 accepted 응답이 함께 있어야 한 번 적용한다. 과거 accepted snapshot, 중복 SSE·HTTP 응답·버튼은 표시만 하고 새 경로를 만들지 않는다. 새 요청을 관찰하면 기존 자동 동행을 중지하고 generic follower 경로를 차단한다.

서버 수락, SDK 요청 전송, 경로 이벤트, 실제 목적지 일치를 구분한다. 실제 확인은 기존 single-flight 조회·listener epoch·엄격한 목적지 비교와 45초 원래 기한을 재사용한다. 전역 취소 이벤트는 경로 교체에서도 발생하므로 경로를 다시 요청하지 않고 새 listener로 조회만 재개한다.

응답 기한이 지나면 새 수락과 pending 알림은 끝난다. 기한 전에 명시 수락해 시작한 native 작업은 원래 45초 한도 안에서 후속 확인을 마칠 수 있다. 같은 요청의 만료 frame은 이미 발행한 작업을 다시 요청하거나 성공을 지우지 않는다. 새 요청·무효화·중지·방/회원 변경·인증 종료는 이전 작업 권한을 취소한다. 프로세스 소실은 동의를 복원하지 않는다.

## 알림과 보존

사용자가 시작한 수신은 기존 정당한 `specialUse` FGS와 실제 진행 알림을 사용한다. 별도 높은 중요도 요청 채널은 발신 차량·이름·주소와 내용을 확인하라는 안내를 표시한다. 알림 클릭은 앱의 최신 요청 검토를 연다. 직접 잠금 화면 경로 변경, 배경 마이크, 새 FGS 유형, 특권 예외는 없다. 알림·journal에는 bearer·초대 비밀·음성·마이크 원음을 넣지 않는다. request journal은 고정 이벤트와 숫자·일치 여부만 기록한다.

기존 4190/4192 서버·방·인증·증거와 AVD 상태는 보존한다. 새 검증은 별도 4196과 일반 제품 UI로 만든 테스트 방을 사용한다. APK는 기존 debug 서명과 일치하는 업데이트만 허용하며 uninstall/wipe로 우회하지 않는다.

## 검증 경계

Node 실제 인증 HTTP/SSE는 3명 이상 응답의 독립성·동시성·idempotency·권한·만료·기존 목적지 불변을 검사한다. JVM은 발신 재조회·대상 재확인·발신 route0·수신 route0·본인 수락 route1·중복/중지/cold snapshot·기한 경계를 검사한다. 이 검사는 실제 검색 결과·설정·순정 전경 Android 알림·SDK 후속 관찰의 대체 증거가 아니다.

현재 빌드와 실제 두 공식 Pleos 차량 증거는 [선별 검증 기록](../../prototype/vehicle/evidence/destination-change-requests/verification.md)에 정확한 소스/APK 해시·명령·실패·미실행을 구분해 기록한다. 전체 AC5와 완료는 같은 빌드의 실제 두 차량 검증 및 별도 verifier 판정 전까지 미확인이다. 공개 배포·release 제출 완료를 주장하지 않는다.
