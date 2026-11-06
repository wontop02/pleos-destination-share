# 저장한 장소 선택과 목적지 설정 확인 (#21)

## 의미와 지원 범위

기존 `exact_point`(모드 누락 포함)는 이름·좌표 `1e-5`·POI ID·nullable sub-ID·provider의 `Destination.matchesNative` 기준을 그대로 사용한다. 순정 목적지 직접 공유와 기존 공개 예제도 이 방식이다. 같은 ID라도 다른 좌표는 불일치다.

`pleos_place`는 사용자가 공식 `NaviHelper.getBookmarkInfo` / `onBookmarkInfo`에서 읽은 장소를 명시 선택했을 때만 앱에서 만들 수 있다. 출처는 `pleos_bookmark`, 현재 지원 provider는 SDK가 실제 제공한 정확한 빈 문자열이다. provider 필드 누락과 빈 문자열은 다르다. POI ID와 이름은 비어 있지 않아야 하며, sub-ID의 null은 원형대로 유지한다. 빈 문자열 sub-ID·다른 provider·외부 ID·다른 출처·알 수 없는 모드는 지원하지 않는다. 원본 주소는 비어 있지 않아야 하며, 주소/표시 좌표를 포함한 전체 필드는 타입·길이·유효 좌표 검사를 통과해야 한다.

선택한 장소 원본(`Destination`, `kind`, `source`)은 제안·원자적 수락·공유 snapshot까지 불변으로 보존한다. 실제 안내 응답은 별도의 `DestinationConfirmation.actual`에 보관한다. `DestinationConfirmation.source`는 원래 선택이고 `kind`는 무엇을 확인했는지 기록한다. 화면에도 선택 원본과 현재 실제 안내 지점을 각각 표시한다. `equals`는 전체 구조 비교를 유지한다.

새 모드의 완료 의미는 **선택한 장소로 목적지가 설정됨**이다. 현재 작업이 요청한 이후 시작된 실제 목적지 조회에서 이름·provider·POI ID·nullable sub-ID가 모두 같고, 기대 출처가 지원하는 북마크이며, 관측값이 유효한 `exact_point` native 응답일 때만 확인한다. 선택한/서버 DTO가 실제 관측값으로 들어오면 실패한다. 주소와 좌표는 선택 지점과 안내 지점의 별도 사실이다. 같은 좌표·입구·진입 경로·경로 탐색 완료까지 증명하지 않는다. 거리 오차 확대, ID 단독 판정, 빈 ID 요청, 후속 관측을 기대값으로 복사하는 self-match는 사용하지 않는다.

## 공개 근거와 남은 불확실성

공식 `BookmarkInfo`는 POI ID를 지도상의 장소 식별자로, sub-ID를 같은 POI의 순서를 구분하는 값으로 설명한다. `RouteInfo`와 `DestinationInfo`에도 이름·ID·sub-ID·provider가 있으며 일반 위경도와 표시용 위경도를 구분한다. 이 문서는 각 좌표의 동일성이나 요청 입력으로 실제 안내 좌표를 예측하는 규칙을 보장하지 않는다. 따라서 이 구현은 canonical 좌표를 해석했다고 주장하지 않는다. [공식 BookmarkInfo 문서 자산](https://document.pleos.ai/en/assets/js/2945b0f8.b47729f0.js), [공식 RouteInfo 문서 자산](https://document.pleos.ai/en/assets/js/b679c257.632d6fac.js), [공식 DestinationInfo 문서 자산](https://document.pleos.ai/en/assets/js/e23c1b13.4ca33874.js)

지원은 기존 Pleos Connect 2.1.0 / NaviHelper 3.0.5 / navi-data 3.0.4의 공개 북마크 경로와 명시된 native provider에 한정한다. 이 식별 조합의 실제품 적용성은 해당 버전의 두 차량 실행으로 추가 검증한다. 다른 SDK/provider, 임의의 검색 callback, Trip 결과의 canonical 의미를 확장 추정하지 않는다. #19 관측과 그 strict FAIL은 그대로 보존한다. [원본 조사](general-place-discovery.md)

## 고정한 실제 카페 회귀

| 필드 | 선택한 BookmarkInfo 원본 | 실제 후속 DestinationInfo |
|---|---|---|
| 이름 | 가든서비스 | 가든서비스 |
| 좌표 | 37.41255277, 127.09591666 | 37.412475, 127.09534722 |
| 주소 | 경기도 성남시 수정구 창업로40번길6 | 수정구시흥동 |
| ID / sub-ID / provider | 36khp2h36p68cm5nqhec / null / `""` | 동일 |

약 51m 차이는 기존 exact 검증이 올바르게 거부한다. 첫 RED는 실제 `VehicleCoordinator.applyShared → requestRoute → 후속 destinationInfo` seam에 이 입력을 고정하고, 기존에 유일한 exact 의미로 장소 확인을 기대하면 `VERIFYING`에 남는다는 기능 공백을 재현했다. exact matcher의 결함이 아니다. 같은 seam의 GREEN은 명시적인 `pleos_place` 요청만 장소 설정을 확인하며 원본과 실제 지점을 각각 보존한다. 기존 exact 모드는 계속 실패한다.

두 번째 RED는 선택 장소 확인 후 45초 보호가 끝나고 같은 방을 재연결했을 때, 잃은 로컬 확인 marker와 구조적으로 다른 좌표 때문에 자동 공유가 새 revision을 만드는 문제를 재현한다. 새 코드의 자동 공유 합치기는 지원하는 선택 기대와 **새 실제 조회**의 완전한 장소 식별에만 적용한다. 재연결·중지/재시작·cold 관측에서도 불필요한 원본 덮어쓰기와 재적용을 막는다. 이 합치기는 확인 권한이 아니며 `CONFIRMED`, 확인 시각, typed confirmation을 만들지 않는다. 실제 다른 장소 식별이나 기존 exact 좌표 변경은 기존 자동 공유로 처리한다.

## UI와 후보 수명

기존 제안 카드의 “저장한 장소 불러오기 → 이름·주소로 명시 선택 → 이 장소로 변경 제안”을 사용한다. 순정 내비의 ☆ 저장만 안내하며 새 키워드 callback이나 고정 카테고리를 추가하지 않는다. 기존 공개 예제 선택은 exact 회귀용으로 유지한다. 제안 전 목록/선택은 서버로 보내거나 journal에 쓰지 않는다. 명시 제안 때 선택한 하나만 전송한다.

서비스의 기존 helper/listener가 읽기 전용 controller를 소유한다. 새 foreground service/lease 또는 권한을 추가하지 않는다. 한 native 연결 epoch에서 북마크 읽기는 최대 한 번, 기한은 8초다. 성공한 목록은 불변 snapshot으로 다시 선택할 수 있고 “불러온 목록”이라고 표시한다. 새로 저장한 항목을 읽으려면 기존 “순정 내비 연결 / 다시 연결”을 명시 선택한다. 자동 경로 확인 중에는 장소 로딩 때문에 SDK를 재연결하지 않는다.

빈 목록, 전체 잘못된 metadata, 200개 초과 응답, 오류, 시간 초과를 구분한다. 혼합 목록에서는 지원하지 않는 항목만 개별 제외하고 유효한 항목을 보존한다. 제외 항목의 이름·주소는 로그에 쓰지 않는다. 중복 탭은 조회를 늘리지 않는다. 취소/시간 초과/오래된 callback은 같은 epoch의 새 읽기를 시작하지 못한다. 성공 후의 지연 중복도 새 요청과 섞이지 않는다. UI 종료·방/회원/room epoch 변경·SDK 재연결은 후보와 선택을 버린다. dialog는 표시한 snapshot을 캡처하며 오래된 선택/닫기 callback은 새 목록이나 새 읽기를 바꾸지 못한다.

## 동의·전송·확인 보호

서버는 새 모드를 인증된 제안에만 허용하고 직접 순정 공유 endpoint에서는 거절한다. 이름/식별 정보·`kind`·`source`는 제안 payload, SSE, snapshot, accepted provenance, 수락 commit을 통과해 유지된다. 모드 누락과 source 없음만 legacy exact로 해석한다. source 단독/알 수 없는 kind/source/null/잘못된 타입은 fail closed다. 선택 모드 공유 frame은 같은 revision/target의 수락 provenance가 있어야 한다.

기존 전원 수락·120초 만료·version/CAS·중복 및 replay fencing·room/member scope·cold resume·중지 보호를 유지한다. 앱의 새 모드 제안은 현재 유효한 선택 snapshot 원본만 사용한다. 대기·거절·만료 중에는 경로를 요청하지 않는다. 수락 후에도 SDK 반환이나 `onRouteStarted`는 완료가 아니다. 기존 작업의 45초 기한·fresh SDK epoch·요청 뒤 시작된 단일 조회를 유지한다. 잘못된 식별·invalid geo·오류·기한 초과·이전 epoch·다른 방·중지 이후 응답은 확인을 만들지 않는다. 취소 이벤트는 기존 교체 경로와 마찬가지로 새 SDK 조회만 재개하며 기한과 경로 요청 횟수를 늘리지 않는다.

## 검증 상태

로컬 검사는 `SelectedPlaceConfirmationTest`, `PlaceIntentSafetyTest`, `SavedPlacesTest`, `RoomProtocolTest`, `VehicleProposalSessionTest`와 실제 인증 HTTP/SSE의 `poi-place-confirmation.test.mjs`에 있다. JVM의 native seam은 결정적인 fake이므로 차량 적용의 증거와 구분한다. 전체 검사에서 `npm test`는 94개, `:core:test`는 93개, `:app:testDebugUnitTest`는 59개가 통과했다(실패·오류·skip 0). 승인된 기존 toolchain의 `--no-daemon --offline :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`도 exit 0이다. driver source는 바꾸지 않았다. 실제 전체 출력·lint 결과·정확한 source/APK 해시와 JUnit 결과는 이번 실행의 `builder` artifact에 고정한다. 첫 Node 대상 검사는 선행 shell 검색이 no-match로 종료해 파일 생성이 생략된 상태에서 “Could not find”로 실패했으며, 기록을 보존하고 파일 생성 후 통과했다. 의도한 두 RED와 이 실행 실패를 구분한다.

AC5의 실제 두 차량 양방향 제안·거절·수락, 독립 native 조회, 45초 이후 revision/요청 안정과 exact 회귀는 총괄이 별도로 실행한다. 이 문서는 그 미실행 조건이나 독립 verifier를 완료로 표현하지 않는다.
