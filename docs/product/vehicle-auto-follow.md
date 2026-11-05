# Pleos 자동 동행 — issue #14

기준은 `main`의 #12/PR #13 병합 커밋 `7d9dcc053c8dfe2b8ce1f22fef3c17b9c8c54856`이다. #14는 앱 복귀 없이 실제 순정 목적지 변경 공유·자동 적용·실제 조회 확인을 수행한다. 기존 수동 기능과 v1 API를 보존한다. 구현·검증 브랜치는 `be/issue-14-vehicle-auto-follow`이며 Git/GitHub 작업과 두 공식 에뮬레이터 실증은 총괄, 별도 문맥의 검증은 verifier가 담당한다. 사용자 정의 builder 역할이 세션에 노출되지 않아 기본 worker에 `.codex/agents/builder.toml`의 행동 계약을 전달했다. 사용자 정의 역할 실행 성공으로 기록하지 않는다.

## 사용자 동의와 화면

자동 동행은 기본 꺼짐이다. 대표는 `동행 시작 · 자동 공유`로 자기 순정 목적지의 자동 공유를 시작한다. 참여자는 `자동 따르기 시작`으로 최신 공유 목적지와 이후 변경의 자동 적용을 시작한다. 역할별 동의는 서로 대신할 수 없다. 비대표는 어떤 native 변경에서도 목적지를 발행하지 않는다. 기존 View·색상·글꼴·56dp 버튼·스크롤 패턴은 [DESIGN.md](../../DESIGN.md)의 Vehicle sections를 따른다.

자동 세션은 화면을 벗어나도 유지하며 45초 앱 복귀를 요구하지 않는다. 진행 알림은 기존 계산된 공유/요청 전송/실제 조회 확인/일치/대기/재연결/실패 상태를 표준 제목의 맨 앞에 표시하고 동행·역할을 뒤에 둔다. 대비가 낮은 중복 본문은 제거한다. 앱의 `자동 동행 중지`와 알림의 `동행 중지`가 즉시 동의를 끝낸다. 알림 중지는 Service PendingIntent이며 Activity를 열지 않는다. 공유 null은 다음 목적지 대기 상태이며 각 차량의 순정 안내 취소 명령이 아니다. 자동 꺼짐에서는 직접 적용 전 순정 내비를 변경하지 않는다.

## 실행 소유권과 정리

`ConvoySessionService`가 하나의 `VehicleSession`, `VehicleCoordinator`, 실제 NaviHelper adapter, 인증 HTTP/SSE worker, monotonic 기한 tick과 알림을 소유한다. Activity는 bound observer와 사용자 명령만 전달한다. `onStop`은 observer detach·unbind이며 활성 FGS의 통신·SDK·확인을 중단하지 않는다. 같은 observer의 재부착과 `VehicleSession.start()` 반복은 새로운 runtime이나 경로 요청을 만들지 않는다.

사용자가 보이는 화면에서 시작 버튼을 누르면 메모리의 일회성 시작 허가를 소비해 `startForegroundService()`와 `startForeground()`를 수행한다. 서비스는 외부에 export하지 않는다. `START_NOT_STICKY`, `stopWithTask=true`, 저장하지 않는 자동 동의를 사용한다. 기동이나 Activity 재바인딩이 자동 동의를 복원하지 않는다. 프로세스가 사라지면 인증 정보만 암호화 저장에서 불러오며 자동 기능은 꺼짐이다. OS가 프로세스 종료 때 `onDestroy`를 보장한다고 주장하지 않는다. 종료된 프로세스의 작업은 소실되고 재생성된 서비스는 자동 요청을 복원하지 않는다.

중지·탈퇴·방 종료·인증 실패는 owner 세대를 먼저 갱신해 queued HTTP/SSE 결과를 거절하고, feed 재시도·진행 작업·SDK listener·timer·foreground 수명을 정리한다. 실제 HTTP 구현에 따라 `disconnect()`가 SSE read를 기다릴 수 있으므로 transport의 닫힘 표시는 동기적으로 설정하고 유한 read/connect timeout의 일회성 연결 정리만 별도 daemon thread로 보낸다. 이 정리 스레드는 재접속·재시도·SDK 요청을 하지 않는다. foreground 중지 후 화면이 남아 있으면 수동 연결 또는 새 자동 시작은 사용자의 새 명시적 조작으로 수행한다. 서버 나가기 확인 실패와 기기 인증 지우기는 구분한다.

## 자동 상태와 경합

대표는 실제 `onDestinationChanged`를 300ms로 모으고 단일 조회로 최신 목적지를 얻는다. 이벤트가 누락되면 15초 간격의 단일 보완 조회를 수행한다. 실제 전체 empty pattern만 null로 공유한다. 부분 누락·invalid·timeout·전역 취소 이벤트는 없음으로 추측하지 않는다. 실제 nullable sub-ID·display 좌표·provider·주소를 보존한다. 공유는 인증된 대표의 기존 CAS API 한 작업씩 수행하며 같은 값의 반복 발행과 공유/적용 피드백을 억제한다. HTTP 응답 전 새 native 관찰은 마지막 값으로 모아 이전 저장 완료 뒤 전송한다. 실패 시 실제 보완 조회 또는 인증 snapshot으로 최신 상태를 다시 확인한다.

참여자는 최신 유효 revision을 300ms로 모아 한 번 요청한다. 낮은 revision·이전 방 epoch·이전 회원·이전 SDK listener 결과는 무시한다. 같은 대상의 높은 revision은 진행 작업에 채택해 경로를 재요청하지 않는다. 새 대상은 이전 작업과 listener를 무효화한다. 네트워크 재인증 때 같은 요청이 남아 있으면 원래 기한으로 query-only 확인을 재개한다. 실패한 같은 revision을 무한 재요청하지 않는다. off→on은 새 명시적 동의다.

연결 15초·조회 8초·작업별 목적지 확인 45초 기한을 monotonic clock으로 유지한다. `requestRoute` 전송·`onRouteStarted`·`onRoutingCompleted`·실제 `getDestinationInfo` strict match는 구분한다. 이름·위경도(0.00001 이내)·POI ID·nullable sub-ID·provider가 모두 맞아야 목적지 일치를 확인한다. 완료 이벤트만으로 성공시키지 않는다. 전역 `onRouteCancelled`는 이전 안내 교체에서도 발생하므로 목적지 삭제나 새 요청 실패로 단정하지 않는다. listener 세션을 교체하고 경로 재요청 없이 새 조회로 확인하며 원래 기한을 연장하지 않는다. 엄격 일치 직후 늦게 도착한 취소·변경 이벤트도 같은 최신 대상과 원래 기한의 최근 확인을 새 조회로 다시 검증한다. 이전 성공을 그대로 복원하지 않는다.

## 서비스 유형과 최소 권한

실제 merged app manifest의 권한은 다음 여섯 개다.

- `android.permission.INTERNET`
- `pleos.car.permission.NAVI_ROUTE`
- `pleos.car.permission.NAVI_ROUTE_SEARCH`
- `android.permission.FOREGROUND_SERVICE`
- `android.permission.FOREGROUND_SERVICE_SPECIAL_USE`
- `android.permission.POST_NOTIFICATIONS`

API 34 이상에서 `specialUse` 유형과 `FOREGROUND_SERVICE_TYPE_SPECIAL_USE`를 사용한다. subtype property는 사용자가 시작한 지속 동행 조율, 순정 화면에서 실제 대표 목적지 변경 공유, 명시 동의한 참여자의 최신 목적지 반영·확인, 알림 중지를 구체적으로 설명한다. Android 공식 문서에서 이 유형의 runtime prerequisite는 별도로 없으며 사용 사례 설명은 배포 심사 대상이다. 이 프로토타입의 에뮬레이터 실행은 향후 Google Play/Pleos 배포 승인이 아니다. [Android 서비스 유형](https://developer.android.com/develop/background-work/services/fgs/service-types)

통신만 필요하다고 `dataSync`로 장시간 보장을 주장하지 않는다. Android 15의 dataSync/mediaProcessing은 24시간 중 6시간 한도, shortService는 더 짧은 한도가 있으므로 이 사용 사례의 지속 동행 수명 설명을 대체하지 않는다. media/location/connectedDevice, 센서·미디어 가짜 작업, 배터리 최적화 면제, exact alarm, boot receiver, 특권 API는 사용하지 않는다. [Android FGS timeout](https://developer.android.com/develop/background-work/services/fgs/timeout)

Pleos 공식 모델은 화면에서 사라진 앱과 프로세스 종료를 구분하며, 지속 통신의 실행 조건·정리·재개 정책을 정의하고 불필요한 timer·polling을 줄이도록 안내한다. 이 구현의 timer는 기한 관리와 활성 대표의 bounded 보완 조회에만 사용한다. SDK initialize/listener/release와 실제 목적지 API는 공식 NaviHelper 3.0.5 경로를 사용한다. [Pleos 실행 모델](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/app-structure-screen-model), [초기화·해제](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/initialization-and-release), [목적지 API](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/destinations)

## 비밀과 관찰

기존 `RoomHttpClient`의 bearer/초대 인증 헤더, loopback-only URL 검증, redirect 거절, AndroidKeyStore AES-GCM 사설 저장을 유지한다. 서버·v1 세 필드 API·기존 SSE 형식은 변경하지 않았다. 비밀·실제 목적지 필드는 URL·로그·알림·저장 view state에 넣지 않는다. 초대 UI의 FLAG_SECURE를 유지한다.

앱의 `noBackupFilesDir/navigation-events.jsonl`은 약 512KiB 제한이다. 서비스 상태 행은 `at,event,serviceEpoch,roomEpoch,revision,role,automatic,applying,connection,roomConnected,uiCount,confirmedAtElapsed,routingCompletedObserved`만 포함한다. SDK 행은 `at,event,nativeEpoch,operation,matched`다. 최신 비밀 없는 서비스 상태는 `session-state.json`이다. 실제 공개 POI 값은 별도 test-only driver의 query 원본으로 대조한다.

## 빌드·회귀와 정확한 동결

[차량 빌드 방법](../../prototype/vehicle/README.md#재현-빌드)을 따른다. 전용 toolchain 환경은 해당 프로세스에만 병합했고 사용자 HOME·전역 설정·vendor SDK/AAR·기존 ADB/AVD를 변경하지 않았다. builder는 ADB/UI를 실행하지 않았다.

최신 동결 3의 source/APK·빌드 결과는 [source manifest](../../prototype/vehicle/evidence/automatic-session/source-manifest.json)와 [검증 기록](../../prototype/vehicle/evidence/automatic-session/verification.md)에 기록한다. 원본 APK·Gradle/Node 로그·JUnit XML·merged manifest·권한·서명·lint는 총괄의 외부 RAW 디렉터리에 보관한다. 기존 #12 증거 디렉터리는 변경하지 않는다. 문서 변경은 APK/runtime 소스 동결에 영향을 주지 않는다. 런타임 소스가 바뀌면 새 APK/hash와 새 연속 실증이 필요하다. 동결 1은 실제 확인 직후의 늦은 취소가 표시를 지운 경합으로 교체했으며, 동결 2는 같은 최신 대상·원래 기한의 최근 작업을 fresh query-only로 재검증한다. 동결 3은 알림 가독성 F1의 표시만 수정한다. 이전 동결의 원본과 source manifest는 역사 증거로 보존한다.

## 실제 두 차량 15분 실증 계약

1. 최종 APK/source 해시를 고정하고 두 공식 Pleos Emulator의 각각의 역할을 최초 설정한다. A 자동 공유·B 자동 따르기 opt-in 후 두 순정 Navigation 화면을 유지하는 시각을 시작점으로 잡는다.
2. 최소 15분 동안 우리 Activity 재진입 0회, 운영 PID와 같은 FGS epoch 연속 수명, 실제 진행 알림을 확인한다. 공개 두 POI의 서로 다른 변경을 두 번 이상 만들며 한 번은 10분 이후 수행한다.
3. 각 변경의 A 실제 native query → 서버 revision/메타데이터 → B 요청 횟수·시각 → B 실제 native query strict match → 서비스 일치 상태/알림을 대조한다. 요청·탐색 완료 이벤트·실제 일치를 분리한다.
4. native 화면에서 B 알림의 `동행 중지`를 누른 뒤 A를 새 공개 목적지로 바꾸고 B 새 자동 SDK 요청이 0회인지 확인한다. PID·서비스·Activity·알림·journal의 전후 원본을 보존한다.
5. 별도 verifier가 정확한 base/head와 실제 diff·원본 해시·조건별 증거를 직접 확인한다. 실제 SDK·플랫폼 제한으로 불가능하면 Draft/BLOCKED이며 실행 기준을 낮추지 않는다.

A seed와 최종 query는 자기 패키지를 target하는 별도 `nativeDriver` 또는 실제 native UI로 수행한다. 운영 앱을 target하는 기존 #12 instrumentation은 운영 프로세스를 죽일 수 있으므로 연속 실증에 사용하지 않는다. driver APK의 두 NAVI 권한·자기 target·Activity 없음·정확한 source/APK 해시도 기록한다. 자세한 명령은 [native driver](../../prototype/vehicle/README.md#별도-프로세스의-실제-native-driver)를 따른다.

## 동결 2 실제 실행 결과와 F1 독립 판정

총괄은 `2026-11-05T06:25:58.594303Z`에 시작한 두 공식 Pleos Emulator 실행을 monotonic 기준 **907.743초(15분 7.743초)** 유지했다. 57쌍의 상태 관찰에서 두 순정 Navigation 전경, 우리 Activity STOPPED·`uiCount=0`, 같은 운영 PID와 foreground service epoch가 유지됐고 lifecycle journal의 Activity 재진입은 A/B 모두 0회였다. A PID/서비스 epoch는 `20878/12836184`, B는 `4948/12421167`이다.

A를 시작 후 24.436초 소프트웨어드림센터, 302.358초 42dot, 633.147초 소프트웨어드림센터로 바꿨다. B는 공유 revision 3·5·7 각각에서 새 경로 요청 한 번과 실제 native strict match를 기록했다. 10분 이후 변경을 포함한 총 세 번의 실제 변경이다. 순정 경로 교체 중 A의 실제 전체 빈 조회가 먼저 관찰되어 자동 발행은 null 세 번·목적지 세 번, 총 여섯 번이며 B 발행은 0회다. null을 받을 때 B는 순정 안내를 유지하고 다음 목적지를 기다렸다.

실제 SystemUI 진행 알림의 `동행 중지`를 누른 뒤 B serviceDestroy·자동 OFF·FGS 종료·native 전경·`uiCount=0`을 확인했다. 중지 뒤 A를 실제 42dot으로 바꾸고 공유 revision이 7에서 9로 진행한 뒤, source 작업 완료 후 **77.967521208초**를 관찰했다. B 새 자동 요청과 Activity 재진입은 모두 0회였고 자동 OFF·FGS 종료 상태와 실제 소프트웨어드림센터 목적지를 유지했다. 별도 중지 검사 전체 시간은 98.255966초다. 총괄의 선별 원본과 해시는 [실행 증거](../../prototype/vehicle/evidence/automatic-session/verification.md)를 따른다. 런타임·테스트·빌드 입력 40개는 실행 전후 동결 2 해시와 일치했다.

동결 2의 정확한 head `0b92a6b4df1fb97c811842d0f0b455ccc9e5590b`에 대한 독립 판정은 **전체 FAIL**이다. 기능과 원본 재계산으로 AC2/AC4/AC5는 확인했지만, 실제 SystemUI의 상태 본문이 배경 RGB(40,42,45) 위 RGB(51,51,51), 대비 1.138967:1로 읽기 어려워 AC1/AC3은 F1로 실패했다. [동결 2 독립 검증 원본](../../prototype/vehicle/evidence/automatic-session/review-freeze-2/)과 기능 실증을 함께 보존한다. 실차, `onRoutingCompleted` 수신, specialUse 배포 심사·스토어 승인은 검증하지 않았다. 자동 모드에서는 45초 안에 앱 복귀할 필요가 없으며 작업별 실제 목적지 확인의 원래 45초 기한은 유지한다.

## 동결 3 F1 수정과 후속 판정

수정은 `ConvoySessionService`의 표준 알림 구성 한 곳이다. 기존 상태 계산을 그대로 `상태 · 동행 중 · 역할` 순서의 제목에 표시하고 `setContentText`·BigText 본문의 중복 상태를 제거한다. 제목이 잘려도 상태가 앞에 남도록 하며 역할과 `동행 중지`를 유지한다. 별도 RemoteViews·가짜 미디어·새 권한·SDK 호출 정책 변경은 없다.

동결 3 최초 builder hand-off(07:19:25Z) 뒤 총괄이 새 APK에서 알림·연속 실행을 관찰했다. 실제 18b PNG/XML에서 `순정 목적지 일치 확인 · 동행 중 · 참여 · 자동 따르기` 제목이 완전히 표시되고, 흰색 RGB(255,255,255)와 배경 RGB(40,42,45)의 실제 대비는 **14.390448297873283:1**이다. 읽기 어려운 중복 본문은 없고 역할·중지는 유지됐다. 최초 18 캡처는 순정 내비 마지막 전환이 알림 패널을 닫아 native 화면이 찍힌 관측 실패 원본이다. 재진입·재시작·재opt-in 없이 패널 관측만 다시 한 18b를 근거로 삼았다.

새 실행은 `2026-11-05T07:27:30.544477Z`부터 **908.721초(15분 8.721초)** 유지됐다. 55쌍의 관찰과 journal에서 native 전경, 우리 Activity STOPPED·`uiCount=0`, A/B 재진입 0/0, 같은 PID `24562/10889`와 서비스 epoch `16526086/16132415`를 확인했다. 시작 후 23.099초 Dream·304.198초 42dot·633.089초 Dream 변경은 공유 revision 15·17·19에서 각각 B 요청 한 번과 실제 strict match로 이어졌다. A는 실제 empty/target 관측으로 여섯 번 발행했고 B는 발행하지 않았다. 런타임·테스트·빌드 40개 입력은 실행 전후 동결 3과 일치했다.

실제 SystemUI `동행 중지`를 누른 뒤 B의 serviceDestroy·자동 OFF·SDK 연결 종료·FGS 없음·`uiCount=0`·순정 내비 전경을 확인했다. A를 실제 42dot으로 바꿔 공유 revision 19→21이 된 뒤 source 작업 완료 후 **81.347894375초** 동안 B 새 요청·재진입은 0회였고 실제 소프트웨어드림센터 목적지를 유지했다. 별도 검사 전체 시간은 107.922877333초다. 이후 A도 실제 알림으로 중지했고, 최종 자원 기록에서 양쪽 serviceDestroy·자동 OFF·SDK 연결 종료·방 연결 종료·FGS 없음·native 전경·`uiCount=0`을 확인했다. 이 수치는 총괄 실행 관찰이며 새 head의 독립 판정을 대신하지 않는다. [동결 3 실행 증거](../../prototype/vehicle/evidence/automatic-session/runtime-freeze-3/)는 원본 106개(4,621,432 bytes)이며, 동결 2 원본 96개·F1 판정과 구분해 보존한다. 정확한 새 head를 재검증하며, 최종 독립 판정과 병합 상태는 [PR #15](https://github.com/wontop02/pleos-destination-share/pull/15)를 참조한다.
