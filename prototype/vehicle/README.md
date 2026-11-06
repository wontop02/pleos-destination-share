# Pleos 차량 목적지 앱 — #12 수동·#14 자동 동행·#17 제안·#23 UI

공식 NaviHelper 3.0.5를 사용하는 Kotlin 차량 실행본이다. 대표 차량의 현재 순정 목적지를 조회해 공유방에 저장하고, 다른 회원이 받은 목적지를 직접 순정 내비에 요청한다. 경로 요청 반환이나 서버 저장을 순정 내비 변경 완료로 표시하지 않는다. 후속 `getDestinationInfo()` 응답의 이름·좌표·POI ID·sub-ID·provider가 요청과 일치해야 `순정 목적지 일치 확인`을 표시한다.

#23 화면은 홈에서 `함께 가는 곳`, `목적지 바꾸기`, `동행 시작/중지`를 먼저 보여 준다. 변경 흐름은 `목적지 바꾸기 → 저장한 장소 선택 → 이름·주소 확인과 이유(선택) → 제안 보내기`다. 새 제안은 홈에서 `수락` / `거절`, 제안자는 `기다리는 중`을 본다. `내비에서 목적지를 확인했어요`는 기존 실제 후속 확인과 현재 대상 guard를 통과했을 때만 표시하며, 저장한 장소는 좌표·입구·경로의 동일성을 주장하지 않는다. 수동 공유/반영·재연결·초대·방 관리는 `설정`, 좌표/POI/버전은 접힌 `개발 상세 보기`, 공개 fixture는 작성 화면의 `예제 장소`에 있다. [차량 UI 계약과 조작 ID](../../docs/product/vehicle-ui.md)를 따른다. 기존 실행 기록에 인용된 예전 버튼 문구는 그 기록의 동결 당시 화면이다.

장소 선택을 취소했거나 제안 뒤 다시 선택할 때는 작성 화면의 `다시 불러오기`를 누른다. 캐시 목록은 그대로 다시 열고, 새로 저장한 곳은 `목록 새로 불러오기`로 명시적으로 갱신한다. 이 입력만 기존 내비 연결과 한 번의 새 읽기를 요청하며, 작성 화면 진입·복귀는 연결이나 동의를 만들지 않는다. 적용·읽기 등 다른 작업 중에는 갱신하지 않는다.

정차 또는 동승자가 공유방을 만들고 역할별 자동 동행을 명시적으로 시작한다. 자동 동행은 기본 꺼짐이며 기존 직접 공유·적용도 유지한다. 대표의 자동 공유와 참여자의 자동 따르기를 시작하면 순정 내비 화면에서도 공유·적용·실제 목적지 확인을 계속한다. 원음·마이크·PTT·AI/STT/TTS·외부 내비 앱 연동은 제공하지 않는다. `main`에 없는 휴대폰 앱 소스를 의존하지 않는다. [화면 계약](../../DESIGN.md), [제품 범위](../../docs/product/README.md), [#12 검증](evidence/verification.md), [#14 자동 동행 계약·검증](../../docs/product/vehicle-auto-follow.md)을 따른다.

## 목적지 변경 제안

현재 회원은 화면 위쪽 카드에서 `프로토타입 공개 장소`(42dot·소프트웨어드림센터)와 기본값이 있는 이유를 선택해 제안한다. 제안자는 동의한 것으로 보고 나머지 회원 전원이 수락한 뒤 공통 목적지를 한 번 확정한다. 대기·거절·120초 만료와 회원/목적지 변경 무효화는 기존 순정 안내를 유지한다. 자동 동행이 꺼져 있어도 버튼 동의와 발행한 작업의 제한된 서비스 수명으로 실제 후속 조회를 앱 복귀 없이 계속한다. 전송/서버 수락과 실제 목적지 일치를 구분한다. 실패는 직접 다시 적용하며 대표의 이전 목적지 자동 공유로 확정을 덮어쓰지 않는다. API, 중복·재연결·동의 수명과 실제 두 차량 확인 조건은 [#17 계약](../../docs/product/vehicle-proposals.md)을 따른다. 이번 전용 서버는 `127.0.0.1:4186`이며 앞선 서버·증거를 변경하지 않는다.

## 재현 빌드

JDK 21, Android SDK API 35/build-tools 35.0.0, Node.js 22 이상이 필요하다. 저장소 안에 JDK·SDK·Gradle 캐시·라이선스·개인 설정을 복사하지 않는다. 해당 개발 환경의 `JAVA_HOME`, `ANDROID_HOME`을 지정하고 실행한다. Gradle 8.11.1 배포 SHA-256이 wrapper 설정에 고정되어 있다. AGP 8.9.1과 Kotlin 1.9.25를 사용한다.

```sh
# repository root: 전체 Node 회귀
npm test

cd prototype/vehicle
./gradlew --no-daemon :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug \
  :app:assembleDebugAndroidTest :nativeDriver:assembleDebug :nativeDriver:lintDebug
```

APK는 `app/build/outputs/apk/debug/app-debug.apk`에 생성된다. 애플리케이션 ID는 `ai.pleos.destinations.vehicle`, Activity는 `.MainActivity`다. minSdk 28/targetSdk 35이며 자동차 하드웨어를 선언한다. 정상 Maven 의존성을 사용하고 vendor AAR를 저장소에 포함하지 않는다. JVM HTTP 검사는 이 저장소의 실제 Node 서버를 임시 loopback 포트에 띄워 두 독립 회원과 인증 SSE를 사용한다. fake gateway는 상태 경합 검사에서만 사용한다.

## 자동 동행 사용

1. A가 대표 공유방을 만들고 B가 별도 참여자로 들어온다. 기존 암호화 사설 저장은 방 인증만 보관하며 자동 동의는 저장하지 않는다.
2. A는 `동행 시작 · 자동 공유`, B는 `자동 따르기 시작`을 한 번 선택한다. 진행 알림을 허용해야 시작할 수 있다. 대표는 실제 순정 목적지의 자동 공유, 참여자는 최신 공유값과 이후 변경의 자동 적용에 각각 동의한다.
3. 두 차량에서 순정 Navigation 화면을 유지한다. 앱 복귀·반복 적용 조작 없이 A의 실제 변경이 공유되고 B가 자동 요청 후 실제 목적지를 확인한다. 알림은 요청 전송·확인 중·목적지 일치·대기를 구분한다. 탐색 완료 이벤트 수신은 목적지 일치와 별개다.
4. 앱의 `동행·제안 중지` 또는 진행 알림의 `동행 중지`로 중지한다. 알림 중지는 Activity를 열지 않는다. 탈퇴·방 종료·인증 종료·작업 제거·프로세스 종료도 자동 동의를 끝낸다. 다시 시작하려면 명시적으로 선택한다.

자동 세션은 `ConvoySessionService`가 SDK·인증 HTTP/SSE·250ms 상태 기한 tick·진행 알림을 소유한다. Activity의 `onStop`은 observer detach와 unbind만 수행한다. 활성 대표는 실제 변경 이벤트를 300ms로 모으고 15초 간격의 단일 보완 조회로 누락을 복구한다. 참여자는 300ms 안의 빠른 변경을 최신 버전 하나로 모아 적용한다. 같은 대상의 높은 버전은 진행 중인 확인에 채택하며 경로를 다시 요청하지 않는다. 조회가 실제 빈 목적지 전체 패턴을 반환하면 대표는 공유 null을 발행한다. 참여자는 순정 안내를 취소하지 않고 다음 공유값을 기다린다.

기한은 연결 15초·조회 8초·각 목적지 확인 45초다. `onRouteCancelled`는 기존 안내 교체에서도 발생하므로 새 listener 세션의 조회만 재개하고 원래 45초 기한을 유지한다. 실패한 같은 버전을 무한 경로 요청하지 않는다. 네트워크 재연결은 최신 인증 snapshot을 받고 이미 전송한 같은 작업은 새 조회로 확인한다. 명시 중지는 즉시 epoch·SDK listener·timer·동의를 무효화한다. 일부 HTTP 구현에서 SSE `disconnect()`가 read를 기다리므로, 닫힌 transport의 유한 timeout 정리만 일회성 별도 스레드에서 수행한다.

FGS 유형·권한·정확한 수명과 검증 한계는 [자동 동행 문서](../../docs/product/vehicle-auto-follow.md)에 기록한다. 동결 3의 두 공식 에뮬레이터 실행은 908.721초 동안 55쌍의 관찰과 journal에서 Activity 재진입 A/B 0회, 순정 내비 전경과 같은 PID·FGS epoch를 유지했다. 공개 목적지 변경 세 번(시작 후 23.099·304.198·633.089초)은 공유 revision 15·17·19에서 각각 B 요청 한 번과 실제 native strict match로 이어졌다. 상태 우선 알림 제목의 실제 대비는 14.3904483:1이며 읽기 어려운 중복 본문은 없다. 실제 알림 중지 후 새 A 변경 작업이 끝난 뒤 81.347894375초 동안 B 새 요청·재진입은 0회이고 기존 실제 목적지를 유지했다. 상세 시각·버전·소스/APK 해시는 [실행 기록](evidence/automatic-session/verification.md)을 따른다.

동결 2의 독립 판정은 알림 상태 가독성 F1로 **전체 FAIL**이었다. 그 기능 실증 907.743초와 실패 원본은 별도로 보존한다. 동결 3의 위 수치는 총괄이 새 APK에서 관찰한 결과이며 새 head의 독립 판정을 대신하지 않는다. 최종 독립 판정과 병합 상태는 [PR #15](https://github.com/wontop02/pleos-destination-share/pull/15)를 참조한다. 실차, `onRoutingCompleted` 수신, specialUse 스토어/배포 심사는 검증하지 않았다. 자동 모드에는 45초 앱 복귀 요구가 없고, 작업별 실제 목적지 확인의 원래 45초 기한은 유지한다.

## 수동 동작: 전용 Pleos 두 대에서 실행

기존 사용자 기기·AVD·서버에 적용하지 않는다. 아래는 issue #12 전용 ADB 포트·에뮬레이터 serial 예시다. 실제 승인된 전용 serial을 먼저 확인한다. 로컬 서버는 저장소 루트에서 `PORT=4182 npm start`로 실행하며 `127.0.0.1`만 수신한다. `adb reverse`로 각 에뮬레이터의 loopback을 같은 서버에 연결한다. 주소나 명령에 bearer·초대 비밀을 넣지 않는다.

```sh
adb -P 5041 -s emulator-5570 reverse tcp:4182 tcp:4182
adb -P 5041 -s emulator-5572 reverse tcp:4182 tcp:4182
adb -P 5041 -s emulator-5570 install -r app/build/outputs/apk/debug/app-debug.apk
adb -P 5041 -s emulator-5572 install -r app/build/outputs/apk/debug/app-debug.apk
adb -P 5041 -s emulator-5570 shell am start -n ai.pleos.destinations.vehicle/.MainActivity
adb -P 5041 -s emulator-5572 shell am start -n ai.pleos.destinations.vehicle/.MainActivity
```

1. A의 순정 Navigation 화면에서 공개 장소를 설정·변경한다. 차량 앱의 `현재 목적지 다시 조회`에서 실제 이름·위도·경도와 조회 시각을 확인한다. SDK 초기 연결 실패·응답 없음은 별도 오류로 남긴다.
2. A에서 이름을 입력해 대표 공유방을 만든다. `초대 정보 보기`로 방 ID와 초대 비밀을 B에 전달한다. 초대 모달은 화면 캡처를 차단한다. B는 별도 차량 이름·방 ID·초대 비밀로 참여한다.
3. A가 `현재 순정 목적지 공유`를 누른다. 이 조작은 새 순정 조회를 먼저 수행한다. B에서 받은 장소와 공유 버전을 확인한다. 수신만으로 내비는 바뀌지 않는다.
4. B가 `받은 목적지를 순정 내비에 적용`을 누른다. 새 SDK 세션에서 한 번 요청하고 이벤트 또는 2초 간격의 단일 후속 조회로 목적지를 확인한다. 순정 Navigation 화면이 전면으로 열리면 45초 안에 이 앱으로 돌아온다. 복귀 시 같은 방 인증·같은 최신 목적지를 확인하고 경로 재요청 없이 새 SDK 조회만 재개한다. B 순정 화면과 일치·확인 시각을 함께 기록한다. A의 실제 목적지를 바꿔 다시 공유하고 두 번째 값도 검사한다.
5. 순정 경로 취소, 잘못된/지원되지 않는 목적지, 네트워크 해제·재연결, 방 종료/탈퇴를 검사한다. 새 값·방 변경은 이전 확인을 무효화한다. 자동 동행이 꺼진 수동 세션은 앱을 벗어나면 bound 서비스와 SSE·SDK를 해제한다. 요청한 대상·버전·원래 기한만 프로세스 메모리에 잠시 보관하며 방/회원이 달라지거나 프로세스가 사라지면 사용할 수 없다. 재접속 이력은 자동 적용하지 않는다.

관찰 시 공개 문서 장소나 합성 테스트 입력만 사용한다. 실제 내비 미확인은 소스·모의 응답·일반 AOSP 결과로 통과시키지 않는다. 순정 지도 서비스·계정 등의 문제로 재현할 수 없으면 AC5를 BLOCKED로 남긴다.

## 상태와 동시성

순정 연결은 15초, 조회는 8초, 적용 일치 확인은 45초로 제한한다. 기기 벽시계 변경이 제한 시간을 바꾸지 않도록 monotonic clock을 사용한다. 연결·조회·서버 저장·다른 회원 수신·요청 전송·탐색 이벤트·목적지 일치 확인은 각각 별도 상태다. 일치 확인은 목적지 관찰 결과이며 전체 경로 안내나 도착 성공의 보장이 아니다.

SDK 이벤트에는 앱 요청 ID나 원본 조회 시각이 없다. 조회는 한 번에 하나만 수행하고 변경 이벤트와 겹친 응답은 버린다. 적용 시작, 취소 이벤트, 전경 복귀 때 새 helper/listener를 사용한다. timeout·더 최신 공유값·방 변경·탈퇴는 기존 확인을 무효화한다. 이전 listener와 이전 방의 HTTP 결과는 새 상태를 갱신하지 못한다. 취소 이벤트는 기존 안내 교체 과정에서도 발생하므로 삭제나 새 요청 실패로 단정하지 않고 원래 45초 기한 안에서 query-only로 실제 값을 판별한다. 콜백이 없더라도 후속 조회의 이름·좌표·POI·nullable sub-ID·provider가 일치하면 목적지 일치만 확인하고, 탐색 완료 이벤트 미수신은 화면과 trace에 그대로 남긴다.

공개 SDK 문서는 빈 `DestinationInfo` 표식을 정의하지 않는다. 공식 전용 Pleos에서 관찰한 전체 패턴(name/address/poiId/provider가 정확한 빈 문자열, poiSubId/표시 좌표는 null, 위·경도/남은 거리/시간/요금은 모두 0)만 조회로 확인한 목적지 없음으로 처리한다. `onRouteCancelled`는 전역 이벤트 사실로 기록하고 새 SDK 조회로 목적지 유무를 확인한다. 부분 누락·공백·이상 값·timeout은 삭제로 추측하지 않는다. 수동 조회상 목적지 없음은 서버 공유값을 지우거나 적용 성공을 만들지 않는다. 자동 공유를 시작한 대표는 실제 빈 조회에 한해 공유 null을 발행한다. `공유 목적지 지우기`는 대표의 별도 명시적 조작이며 각 차량의 순정 경로를 취소하지 않는다. 같은 목적지의 재공유를 억제한다. 같은 버전이며 현재 순정 조회값도 일치할 때만 중복 적용을 막는다. 사용자가 순정 내비를 따로 바꿨다면 같은 공유값도 다시 직접 적용할 수 있다.

SDK는 `DestinationInfo.poiSubId`를 nullable로 반환하지만 `RouteInfo.poiSubId`는 필수 nonnull이며 기본값이 없다. 공식 두 에뮬레이터의 공개 POI 실험에서 source sub-ID null을 SDK 입력의 빈 문자열로 전달했을 때 B의 실제 후속 값이 A와 일치했다. 방/모델에는 null을 그대로 보관하고 실제 adapter 경계에서만 빈 문자열로 바꾼다. 임의의 `0`은 만들지 않는다. POI ID나 메타데이터가 없는 v1 목적지는 수신/표시하고 차량 적용은 비활성화한다. [실제 SDK 근거](evidence/native-sdk-observations.json), [API 대조](evidence/sdk-api.md)를 따른다.

## 실제 SDK 전용 계측 probe

`app/src/androidTest`의 작은 framework `Instrumentation` runner는 production Activity를 시작하지 않고 실제 NaviHelper 공개 API만 호출한다. **이 runner는 production 패키지를 target하므로 실행 시 운영 앱 프로세스와 FGS를 끊을 수 있다. 활성 자동 세션의 연속 수명 검증에는 실행하지 않는다.** #12 수동 증거를 재현하기 위한 도구로 보존한다. 기본 `query` 모드는 연결·목적지 조회, 명시적 `route` 모드는 공개 SDK 문서의 42dot POI 경로 요청·콜백 대기·후속 목적지 조회를 수행한다. UI 클릭·입력·화면 조회·스크린샷, 숨은 서비스 API, 사설 인증/키 탐색, SDK 변조를 하지 않는다. 앱의 사설 테스트 후문도 추가하지 않는다. 일반 Android에서 성공한 모의 결과를 만들지 않는다.

총괄이 승인된 전용 포트·serial만 지정해 실행한다. 기존 ADB 서버나 기기를 사용하지 않는다.

```sh
./gradlew --no-daemon :app:assembleDebugAndroidTest
adb -P 5041 -s emulator-5570 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -P 5041 -s emulator-5570 shell am instrument -w -r -e mode query \
  ai.pleos.destinations.vehicle.probe/ai.pleos.destinations.vehicle.NaviSdkProbe
adb -P 5041 -s emulator-5570 shell am instrument -w -r -e mode route \
  ai.pleos.destinations.vehicle.probe/ai.pleos.destinations.vehicle.NaviSdkProbe
```

다른 전용 차량에는 serial을 `emulator-5572`로 명시한다. `route-empty-subid`는 실제 A 조회의 공개 42dot 목적지 좌표/주소를 `sourceLat`, `sourceLng`, `sourceAddress`로 지정하고 null source sub-ID를 SDK 요청의 빈 문자열로 전달하는 test-only 호환성 실험이다. name/좌표/POI ID/provider/nullable sub-ID의 실제 B 후속값 일치를 검사했다. 이 실제 결과를 근거로 production adapter에만 null→빈 문자열 bridge를 적용했다.

`route-dream-center`는 공식 [Waypoints](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/waypoint-settings)의 두 번째 공개 POI fixture다. 원문 SHA와 다른 문서의 이름/주소 불일치를 [API 기록](evidence/sdk-api.md)에 남겼다. seed 요청은 실제 SDK의 취소나 strict mismatch를 그대로 기록하며, 다른 후속 query의 OBSERVED와 혼동하지 않는다.

이 probe는 실제 순정 목적지를 공개 테스트 POI로 요청하므로 기존 사용자 환경에 실행하지 않는다. query 연결 15초·조회 8초, route 확인은 45초 예산으로 제한한다. 경로/변경 이벤트 또는 2초 간격의 single-flight 후속 조회에서 실제 목적지 일치만 확인한다. routingCompletedObserved는 별도로 기록하며, 미관찰을 탐색 완료로 표시하지 않는다. 초기 조회와 경로 요청은 별도 helper/listener 세션을 사용한다. cleanup은 listener 제거·release이며 순정 경로를 임의로 취소하지 않는다.

`am instrument` 프로세스의 exit 0만으로 성공을 판단하지 않는다. `probe_json.status`의 `OBSERVED`는 실제 조회 응답, `VERIFIED`는 요청 뒤 실제 목적지 일치, `BLOCKED`는 연결·지도·응답 자원 문제, `FAILED`는 mismatch/예외를 뜻한다. 원본 callback·조회 시각과 실제 필드/poiSubId 누락 여부는 앱의 사설 `noBackupFilesDir/sdk-probe.json`과 계측 결과에 남는다. 이 기록은 공개 테스트 장소에 대한 작업 전용 원본 증거로만 보관하고, Git에는 선별 결과를 기록한다. 계측 probe의 목적지 일치는 앱의 A→공유방→B UI 실증과 별도다.

## 별도 프로세스의 실제 native driver

`nativeDriver`는 `ai.pleos.destinations.driver`라는 별도 test-only 앱이다. Activity가 없고 instrumentation target도 자기 패키지다. 운영 앱의 숨은 명령, 모의 내비, 사설 SDK를 사용하지 않는다. 실제 NaviHelper 3.0.5로 공개 42dot·소프트웨어드림센터 POI를 요청하거나 실제 목적지를 조회한다. 공개 POI ID와 #12에서 관찰한 canonical 입구 좌표·nullable sub-ID를 고정한다. 임의 목적지나 인증 비밀을 인자로 받지 않는다. SDK 요청 경계에서만 null sub-ID를 빈 문자열로 전달한다.

```sh
./gradlew --no-daemon :nativeDriver:assembleDebug
adb -P 5041 -s emulator-5570 install -t -r nativeDriver/build/outputs/apk/debug/nativeDriver-debug.apk
adb -P 5041 -s emulator-5570 shell am instrument --user 10 -w -r -e mode query \
  ai.pleos.destinations.driver/.NativeDestinationDriver
adb -P 5041 -s emulator-5570 shell am instrument --user 10 -w -r -e mode route-dream-center \
  ai.pleos.destinations.driver/.NativeDestinationDriver
adb -P 5041 -s emulator-5570 shell am instrument --user 10 -w -r -e mode route-42dot \
  ai.pleos.destinations.driver/.NativeDestinationDriver
```

`--user 10`은 현재 전용 공식 이미지의 실제 foreground 사용자다. 다른 이미지에서는 실제 승인된 사용자를 먼저 확인한다. 결과 `probe_json`과 driver 사설 `noBackupFilesDir/native-driver.json`은 PID·targetPackage·요청/조회 시각·실제 목적지·strict match·탐색 완료 관찰을 기록한다. `am instrument`의 종료 코드만으로 목적지 반영 성공을 판정하지 않는다. 이 driver의 재실행은 driver 프로세스만 재시작해야 하며 운영 PID·FGS epoch·Activity 미복귀는 별도로 직접 확인한다. 이전 #12 driver/probe 결과나 새 driver 빌드 자체는 AC5 성공 증거가 아니다.

## 목적지 API 하위 호환 확장

기존 `POST /api/rooms/:roomId/destination`의 `{ name, latitude, longitude }` 요청과 그 응답 필드는 동일하다. 대표 권한은 서버가 검사한다. 아래 optional `pleos`가 있으면 snapshot과 SSE에도 그대로 보존한다. 실제 SDK에서 조회한 값만 전달한다.

```json
{
  "name": "42dot",
  "latitude": 37.48544722,
  "longitude": 127.03636666,
  "pleos": {
    "poiId": "36khp37hh8gqcm5nqhe5",
    "poiSubId": "0",
    "address": "서울시 강남구 남부순환로 2621",
    "poiProvider": "",
    "displayLatitude": null,
    "displayLongitude": null
  },
  "expectedRevision": 0
}
```

`pleos`의 여섯 필드는 모두 필요하다. ID/sub-ID는 최대 256자(sub-ID는 null 허용), 주소는 최대 500자, provider는 최대 128자다. 제어 문자는 거절한다. 표시 좌표는 둘 다 null이거나 둘 다 유한한 유효 위·경도여야 한다. 필드를 채우기 위한 가짜 ID나 기본 sub-ID를 서버가 만들지 않는다.

`expectedRevision`은 선택적 0 이상의 안전한 정수다. 현재 버전과 다르면 `409 destination_revision_conflict`이며 이벤트·버전·목적지를 바꾸지 않는다. 차량 앱은 이 필드를 사용해 지연된 공유 요청이 최신값을 덮어쓰는 것을 막는다. 기존 클라이언트는 생략할 수 있다.

대표가 `{ "destination": null, "expectedRevision": <current> }`을 보내면 공유 목적지를 지우고 revision/sequence를 증가시킨다. `destination: null`인 기존 snapshot 형식을 그대로 사용한다. 참여자·타 방 토큰·초대 비밀에는 여전히 대표 목적지 변경 권한이 없다. 방 수명은 기존 6시간이며 종료·만료·탈퇴 시 인증과 SSE를 정리한다.

## 비밀과 증거 보관

bearer·초대 비밀은 Authorization 헤더로만 보낸다. 앱은 주소에 userinfo/query/fragment가 없는 명시적 포트의 loopback HTTP만 허용하고 redirect를 따르지 않는다. 세션은 앱 `noBackupFilesDir`의 AES-GCM 암호문과 AndroidKeyStore 키로 저장한다. 백업·기기 이전과 입력 view-state 저장을 끈다. 해독 실패 또는 명시적 연결 지우기는 저장 정보를 제거한다. 네트워크 문제로 서버 탈퇴를 확인할 수 없을 때 로컬 정보만 지우면 서버 권한은 종료 또는 6시간 만료 전까지 남을 수 있다.

앱의 사설 `noBackupFilesDir/navigation-events.jsonl`은 이벤트명, 시각, SDK/서비스/방 세션·작업 번호, revision·역할·자동 상태·연결·확인·UI attach 수·일치 여부만 보관한다. 최신 같은 상태의 비밀 없는 snapshot은 `session-state.json`이다. #17은 `proposal-state.json`에도 공개 후보명/POI ID, proposal ID/version/status, base/accepted revision, 회원 ID별 응답과 lease·busy·publication 보류를 기록한다. 이유·회원 이름·토큰·초대 비밀·개인 키·일반 순정 목적지 이름/주소/좌표를 기록하지 않는다. journal은 최대 약 512KiB로 제한한다. 원본 vendor 파일·캐시·APK·장치 원본 로그는 Git에 넣지 않는다. 저장소에는 환경·명령·exit·공식 출처·해시·검증 결과를 선별해 기록한다.

## 저장한 일반 장소 (#21)

공유방 연결 후 제안 카드에서 “저장한 장소 불러오기”를 누르고 이름·주소를 확인해 선택한 뒤 “이 장소로 변경 제안”을 누른다. 장소는 순정 내비의 ☆로 먼저 저장한다. 불러온 목록은 같은 연결에서 다시 선택할 수 있으며 새로 저장한 항목은 “순정 내비 연결 / 다시 연결” 후 읽는다. 8초 시간 초과·취소·공유방/회원 변경도 재연결 후 다시 불러온다. 북마크 목록은 서버나 journal에 기록하지 않는다.

일반 장소는 “선택한 장소로 목적지 설정 확인”, 기존 순정/공개 예제는 “좌표 일치 확인” 방식이다. 원본 선택 좌표와 차량별 실제 안내 지점은 다를 수 있다. 전원 수락 전에는 경로를 요청하지 않으며, 요청 전송/경로 이벤트만으로 성공 표시하지 않는다. [지원 조건·모델·검증 계약](../../docs/product/poi-place-confirmation.md)을 따른다.
