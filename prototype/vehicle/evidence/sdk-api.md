# NaviHelper 3.0.5 API 대조 기록

확인일: 2026-11-05. 공개 문서, 공식 Maven POM/module/AAR, 실제 Kotlin 컴파일 결과를 대조했다. vendor 원본 AAR·추출 jar·전체 `javap` 출력은 Git에 넣지 않는다. 출처와 다운로드 SHA-256은 [sdk-artifacts.json](sdk-artifacts.json)에 기록했다.

## 출처와 버전

의존성은 `ai.pleos.playground:NaviHelper:3.0.5`, 저장소는 `https://nexus-playground.pleos.ai/repository/maven-releases/`다. POM과 Gradle module이 `navi-data:3.0.4`, `navi-aidl:3.0.1`, Kotlin 1.9.25, core-ktx 1.8.0, coroutines-android 1.6.3을 선언한다. navi-data POM의 serialization-json은 1.6.3이다. [공식 SDK 가져오기](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/importing-sdk)

| 실제 다운로드 아티팩트 | SHA-256 |
|---|---|
| NaviHelper-3.0.5.aar | `15fadd11c7c611b1abdf7ebf9ce91f2a6ce2728e60b4e38b4343e11bb9c12e97` |
| NaviHelper-3.0.5.pom | `39e93d10d8da7758e52c83186620a8965b3b0a46438851e1a2c0dab34d292c42` |
| navi-data-3.0.4.aar | `7ca00e592134b53237a48cc3b7864063ecc5e2885465e3ce9c8c2a29789a2e2b` |
| navi-aidl-3.0.1.aar | `384e62b9b17102ce618272f4afbef5021867c04bd13e46a6592218643ceb40ff` |

## 앱이 사용하는 공개 API

`ai.pleos.playground.navi.helper.NaviHelper(Context)`의 연결 콜백이 있는 `initialize`, `addListener`, `removeListener`, `release`, `getDestinationInfo`, `requestRoute(RouteInfo)`를 실제 AAR에서 확인했다. `cancelRoute()`도 공개 메서드로 존재한다. 앱은 공유방 해제와 순정 경로 취소를 섞지 않으므로 자동으로 cancelRoute를 호출하지 않는다. [초기화·해제](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/initialization-and-release)

`NaviHelperEventListener`의 실제 콜백인 `onDestinationInfo`, `onDestinationChanged`, `onRouteStarted`, `onRoutingCompleted`, `onRouteCancelled`, `onError`를 등록했다. requestRoute는 Unit을 반환한다. 반환이나 onRoutingCompleted만으로 앱의 일치 상태를 만들지 않는다. [공식 이벤트 설명](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/event-listeners), [공식 목적지 설명](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/destinations)

`DestinationInfo`는 latitude/longitude, nullable displayLatitude/displayLongitude, name/address, remainDistance/remainTime/tollFare, poiId, nullable poiSubId, poiProvider를 가진다. 조회 결과에는 앱 요청 ID·원본 응답 시각·목적지 존재 여부 boolean이 없다. 공개 문서는 빈 목적지 데이터의 sentinel을 정의하지 않는다. 총괄이 전용 공식 Pleos의 무목적지 조회에서 모든 좌표·남은 거리·시간·요금은 0, name/address/poiId/provider는 정확한 빈 문자열, sub-ID/표시 좌표는 null임을 직접 관찰했다. 앱은 이 전체 패턴만 빈 조회로 처리하며 부분 데이터·공백·timeout을 삭제로 해석하지 않는다. [DestinationInfo reference](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/navi-helper/ai.pleos.playground.navi.data/destination-info/entry)

## RouteInfo 생성자와 기본값

실제 Kotlin 필드 순서는 latitude, longitude, displayLatitude, displayLongitude, poiName, poiId, address, poiSubId, routeOption, poiProvider, isMobileRequest다. 공개 Java 예제의 좌표 순서와 혼동하지 않도록 production adapter는 named arguments를 사용한다. [RouteInfo reference](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/navi-helper/ai.pleos.playground.navi.data/route-info/entry)

default constructor와 compile-only probe에서 확인한 기본값은 표시 좌표의 null, RouteOption.RECOMMENDED, provider의 빈 문자열, isMobileRequest의 false다. 이름·POI ID·주소·sub-ID는 필수다. `DestinationInfo.poiSubId`는 nullable이지만 `RouteInfo.poiSubId`는 nonnull이며 기본값이 없다. null을 임의의 `0`으로 바꾸지 않는다.

| Kotlin compile-only 입력 | 결과 |
|---|---|
| 실제 공개 예제 name/좌표/poiId/address/poiSubId를 지정하고 나머지 default 필드를 생략 | exit 0 |
| 동일 입력에서 poiSubId만 생략 | exit 1: `no value passed for parameter 'poiSubId'` |

이 probe는 실제 NaviHelper transitive `navi-data`와 Android API 35 classpath로 컴파일했다. APK나 서비스에 가짜 결과를 주입하지 않았다. 컴파일 성공은 해당 POI의 실제 지도 탐색 성공 증거가 아니다.

## 권한과 환경

앱은 사용 API에 필요한 `pleos.car.permission.NAVI_ROUTE`, `pleos.car.permission.NAVI_ROUTE_SEARCH`, loopback 서버용 `android.permission.INTERNET`만 선언한다. 화면 삽입용 NAVI_CUSTOM_MAP이나 위치·마이크·오디오 서비스 권한은 사용하지 않는다. SDK manifest의 NAVI_START query와 최소 Navi 서비스 버전 메타데이터는 정상 manifest merge로 포함된다. [공식 권한](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/declaring-permissions)

실제 서비스가 없는 환경은 연결 실패/미연결로 표시한다. 공식 Pleos 두 인스턴스의 서비스·지도 동작 검증은 [검증 기록](verification.md)에 별도 판정하며, 일반 Android나 JVM fake gateway로 대체하지 않는다. [Pleos Emulator 안내](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator)

## 실제 서비스에서 확인한 호환성

[선별 SDK 관찰](native-sdk-observations.json)은 총괄이 전용 공식 Pleos 두 인스턴스에서 실행한 결과이며 builder가 원본 JSON의 해시·필드를 직접 대조했다. 유효한 A 목적지에서도 sub-ID가 null이었다. A의 실제 42dot 좌표는 문서 입력과 다른 37.41289678/127.09383464였다. 이 실제 source를 기대값으로 삼아 B의 SDK 입력에만 빈 sub-ID를 전달했을 때 이름·좌표·POI ID·null sub-ID·provider가 일치했다. 탐색 완료 이벤트는 관찰하지 못했으므로 목적지 일치와 분리한다.

처음 문서 좌표 요청의 BLOCKED와 두 번째 seed의 route_cancelled는 실패 기록으로 보존한다. SDK가 반환한 실제 source를 공유하고 실제 B 값을 비교하며, seed의 좌표나 sub-ID를 성공에 맞춰 바꾸지 않는다. 기존 안내 교체 중 전역 취소 이벤트가 발생해도 새 요청의 성공/실패나 목적지 삭제로 단정하지 않는다. listener를 교체하고 원래 기한 안의 새 조회만 사용한다.

## 두 번째 공개 fixture 출처

한국어 [Waypoints](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/waypoint-settings)의 소프트웨어드림센터 예제를 확인했다. 2026-11-05 수집한 HTML은 73,840 bytes, SHA-256 `ce0b04d756300ccb0c35ed5960c2d476835de48c633ea3ab5b8f7a45101ad53f`다. 공개 필드는 위도 37.41275833, 경도 127.09481944, POI ID `36khp2hdj2cfcm5nqhe3`, sub-ID `0`, 주소 `경기도 성남시 수정구 창업로40번길 6`이다. androidTest의 `route-dream-center`만 이 fixture를 사용한다.

다른 [경유지 포함 경로 문서](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/route-with-waypoints)는 같은 ID에 판교 현대백화점과 다른 도로명을 적고 있다. 두 입력을 합치지 않았다. 원본 HTML과 전체 SDK 아카이브는 ignored 증거에만 보관하며, 실제 source/후속 query 값은 별도 실증 자료로 기록한다.
