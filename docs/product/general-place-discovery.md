# 일반 장소 검색·선택 조사 (#19)

NaviHelper 3.0.5에서 카페·주유소·식당의 순정 검색 결과와 ☆ 저장 후 공개 북마크 메타데이터 회수를 재현했다. 그러나 카페 POI를 그대로 경로 요청했을 때 후속 목적지 좌표가 약 51m 달라 **기존 strict 검증은 실패했다**. 별도 `requestTrip`도 두 장소에서 입력과 다른 지점을 반환했고, 카페에서는 실제 안내 좌표와도 달랐다. 현재 판정은 **PARTIAL: 후보 확보 가능, 엄격한 일반 장소 적용 연결 미해결**이며 제품 본체는 변경하지 않았다.

공개 검색 화면 입력과 검색 결과 반환은 서로 다른 기능이다. `SearchPlace(keyword)`는 있지만 새 검색 후보 또는 선택한 장소를 직접 반환하는 메서드·콜백·결과 intent 계약은 확인되지 않았다. 컴파일 성공, SDK 반환이나 화면 열림을 실제 적용 성공으로 판단하지 않는다.

확인일은 2026-11-06 KST다. 대상은 기존 Pleos Connect 2.1.0/API 34/arm64, `ai.pleos.playground:NaviHelper:3.0.5`와 그 의존성 `navi-data:3.0.4`다. 공식 소개 문서도 3.0.5를 기준으로 명시한다. 다른 SDK 버전·다른 내비 구현까지 불가능하다고 일반화하지 않는다. [공식 소개](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/NaviHelper/intro)

검토 가능한 원본 사본은 [이슈 #19 선별 증거](../../prototype/vehicle/evidence/issue-19-places/)와 [복사 출처·해시 manifest](../../prototype/vehicle/evidence/issue-19-places/source-manifest.json)에 있다. 아래 `root/`·`builder/` 경로는 이 증거 디렉터리 또는 원본 `general-places-20261106` artifact 아래의 상대 경로다. 전체 raw·AAR·추출 jar·전체 SDK 선언 출력은 저장소 밖에 보존했다.

## 현재 판정과 범위

| 경로 | 공개 계약에서 확인한 내용 | 현재 판정 |
|---|---|---|
| 일반 키워드 → 앱의 새 POI 후보 목록 | 문서화된 `NaviHelper` 엔트리와 listener에 keyword search 결과 반환 API가 없다. | 이 버전에서 연결할 지원 계약을 확인하지 못함 |
| `SearchPlace(keyword)` → 순정 검색 화면 | keyword를 받는 화면 요청이 있고 `requestNavigationScreen`은 값을 반환하지 않는다. | A 카페·전경 A 주유소·전경 B 식당의 실제 결과 화면과 경로 0회·목적지 표본 불변 확인 |
| 순정 후보 선택 → 직접 결과 callback/intent | 선택 결과 callback, result receiver, request ID 또는 result intent 계약을 확인하지 못했다. | 직접 반환 경로는 현재 확인 범위에서 NO-GO |
| 순정 검색 → ☆ 저장 → 북마크 조회 | 별도 공개 callback으로 카페·주유소·식당의 실제 이름·좌표·주소·POI ID를 받았다. 새 검색 결과 callback은 아니다. | 관측한 3개 후보 확보 경로 GO, 주유소 저장 시점의 연속 관측에는 간격 있음 |
| 실제 이름·좌표 → 빈 ID `requestRoute` | 후속 조회의 이름·좌표·빈 ID를 관측했다. 정상 POI 요청과 남은 거리도 다르고 빈 ID의 공식 계약은 확인되지 않았다. | 관측만 완료, 정식 일반 장소 적용의 대안으로 채택하지 않음 |
| 실제 북마크 POI → `requestRoute` | 실제 이름·ID·nullable sub-ID·provider는 같지만 후속 좌표가 약 51m 다르다. | 기존 strict 기준 FAIL, 제품 연결 NO-GO |
| 실제 북마크 → `requestTrip` → target 후보 | 두 차량에서 실제 callback과 후속 목적지 불변을 관측했다. 카페 target은 북마크와 실제 안내의 어느 좌표와도 같지 않다. | 정보 경로 관측 GO, canonical target 해법으로 사용 불가 |
| 기존에 관측한 native POI → 수락 후 요청·조회 | #17의 제안 프로토콜과 strict-match 경계를 재사용할 수 있다. | 기존 두 공개 장소의 완료 근거를 일반 장소로 확대하지 않음 |

최종 구현측 판정은 **PARTIAL**이다. 앱·core·서버·두 공개 장소 선택기는 바꾸지 않았다. 이 문서는 조사와 진단의 완료 근거이며 일반 장소 기능 구현 완료를 뜻하지 않는다. 독립 verifier 판정은 별도 증거를 따른다.

## 공식 자료와 공개 SDK의 대조

공식 문서 원본 17페이지를 HTTP 200으로 수집하고 URL·조회 시각·바이트 수·SHA-256을 별도 원본 manifest에 기록했다. 대표 조회 시각은 `2026-11-05T16:02:10Z`(KST 11월 6일)다. 일부 API Reference의 서버 HTML은 `dokka-transform-contents`가 비어 있어 제목이나 사이드바로 상세 계약을 추정하지 않았다. 이후 해당 페이지가 선언한 공식 public JavaScript 자산의 정적 Dokka HTML 문자열을 읽어 실제 문서 본문을 복원했다. JavaScript 실행이나 vendor APK 분석은 하지 않았다. 상세 메서드는 승인된 Maven AAR의 `classes.jar`에 `javap -public`만 실행해 대조했다. SDK 바이트코드·private API·vendor APK·내부 DB·vendor 일반 logcat은 읽지 않았다.

| 실제 입력 artifact | SHA-256 |
|---|---|
| `NaviHelper-3.0.5.aar` | `15fadd11c7c611b1abdf7ebf9ce91f2a6ce2728e60b4e38b4343e11bb9c12e97` |
| `navi-data-3.0.4.aar` | `7ca00e592134b53237a48cc3b7864063ecc5e2885465e3ce9c8c2a29789a2e2b` |
| 위 AAR의 `classes.jar` | 원본 `builder/sdk-public/manifest.json`의 `classesJarSha256`에 각각 기록 |

두 AAR 해시는 기존 [NaviHelper API 대조](../../prototype/vehicle/evidence/sdk-api.md)의 입력과 일치한다. 공식 SDK 저장소는 `https://nexus-playground.pleos.ai/repository/maven-releases/`이며 새 SDK를 받거나 버전을 바꾸지 않았다. 전체 AAR·추출 jar·전체 `javap`는 Git에 넣지 않는다.

| 공개 선언 | 의미와 경계 |
|---|---|
| `void requestNavigationScreen(NavigationScreenRequest)` | 내비 화면 요청. 후보 목록·선택 결과를 직접 반환하는 signature가 아니다. |
| `SearchPlace(String keyword)` | 키워드를 받는 화면 요청 모델. 결과 callback·receiver·앱 복귀용 intent 필드가 없다. |
| `POIDetailPopup(String poiId, String poiProvider)` | 이미 알고 있는 공급자/POI를 상세 화면에 전달하는 입력이다. 좌표로 POI ID를 얻는 검색 API가 아니다. |
| `void getBookmarkInfo()` / `onBookmarkInfo(List<BookmarkInfo>)` | 저장된 목록을 별도로 조회하는 공개 경로다. |
| `void getRecentDestinationInfo()` / `onRecentDestinationInfo(List<RecentDestinationInfo>)` | 최근 목적지 목록의 별도 조회다. 순정 경로 설정을 먼저 한 뒤 목록을 읽는 방법은 수락 전 안내 유지의 해결책으로 인정하지 않는다. |
| `void requestRoute(RouteInfo)` / `void getDestinationInfo()` | 경로 변경과 현재 목적지 조회다. 검색 후보 획득을 대신하지 않는다. |

이 선언은 [공식 NaviHelper 메서드 목록](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/NaviHelper/navi-helper/ai.pleos.playground.navi.helper/navi-helper/entry), [화면 요청 모델 목록](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/NaviHelper/navi-helper/ai.pleos.playground.navi.data/navigation-screen-request/entry), [SearchPlace 항목](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/NaviHelper/navi-helper/ai.pleos.playground.navi.data/navigation-screen-request/search-place/entry)과 같은 범위에서 확인했다. `NaviHelper.getInternalApi()`라는 public getter가 보이지만 문서화된 일반 검색 계약의 근거로 사용하거나 내부 API를 호출하지 않았다.

`NaviHelperEventListener`의 공개 콜백 전체에는 현재 목적지·북마크·최근 목적지·경유지·경로·운전 정보와 오류 이벤트가 있다. 새 keyword POI 후보 또는 선택 결과를 전달하는 콜백은 없다. 공식 이벤트 문서도 검색이라는 표현을 **경로 탐색** 이벤트에 사용한다. `onRouteStarted`를 장소 검색 결과나 실제 목적지 적용으로 해석하지 않는다. [공식 이벤트 분류](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/NaviHelper/event-listeners)

위 부재 판정은 조사한 문서화된 NaviHelper 3.0.5 엔트리·listener·입력 모델에 한정한다. 모든 Android intent, 모든 공급자, 미공개 인터페이스 또는 다른 버전에 대한 부재 증명이 아니다.

## ID·좌표와 실제 적용의 구분

`RouteInfo`의 공개 생성자는 좌표 2개, nullable 표시 좌표 2개, 이름·POI ID·주소·sub-ID String, 경로 옵션, provider String, mobile-request boolean을 받는다. Kotlin 인자는 이름으로 지정한다. 필수 `poiId`/`poiSubId`를 생략할 수 있다는 근거는 없으며, 같은 해시 artifact의 기존 compile-only 검사는 `poiSubId` 생략을 컴파일 오류로 확인했다. Java `String` signature만으로 비어 있지 않음이나 nullability를 증명하지 않는다. [기존 생성자 검사](../../prototype/vehicle/evidence/sdk-api.md), [공식 목적지 요청 예제](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/NaviHelper/destinations)

현재 driver는 `poiId = ""`, `poiSubId = ""`, `poiProvider = ""`를 명시한 일반 좌표 입력으로 컴파일했다. 이 사실은 **String 입력을 구성할 수 있음**만 증명한다. 빈 문자열이 공식 좌표 목적지 sentinel이라는 설명이나, 다른 공급자의 ID를 Pleos POI로 바꿔 쓸 수 있다는 계약은 확인되지 않았다. 기존 42dot ID를 다른 좌표에 재사용하지 않는다.

경유지 포함 요청도 확인했다. `RouteWithWaypointsRequest.destination`은 동일한 `RouteInfo`이고, `RoutePointInfo`를 별도의 좌표 목적지로 받는 경로가 아니다. 공개 enum의 `RoutePointSettingType`은 `AUTO`/`MANUAL`, `RoutePointPoiType`은 `NORMAL`/`EV_CHARGING`이며 좌표-only 또는 검색 결과 반환 계약을 제공한다는 근거가 없다. enum 이름으로 지원을 추정하지 않았다. 공개 선언은 `builder/sdk-public/javap-routepoint-public.txt`에 별도 보관했다. [공식 경유지 포함 요청](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/NaviHelper/route-with-waypoints)

`DestinationInfo`의 실제 후속 조회는 name/address, latitude/longitude, nullable display coordinates, poiId, nullable poiSubId, poiProvider를 별도로 반환한다. 북마크·최근 목적지 모델도 같은 종류의 이름·좌표·POI 메타데이터를 갖는다. 공급자 값의 허용 목록이나 외부 공급자 ID의 호환 계약은 확인되지 않았다. 기본 provider가 빈 문자열이라는 사실을 외부 ID 호환성으로 확대하지 않는다. [DestinationInfo 항목](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/NaviHelper/navi-helper/ai.pleos.playground.navi.data/destination-info/entry), [BookmarkInfo 항목](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/NaviHelper/navi-helper/ai.pleos.playground.navi.data/bookmark-info/entry), [RecentDestinationInfo 항목](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/NaviHelper/navi-helper/ai.pleos.playground.navi.data/recent-destination-info/entry)

공식 문서는 `requestRoute` 호출 성공과 최종 경로 탐색 완료를 구분한다. 이번 판정은 더 나아가 실제 후속 `getDestinationInfo`의 정체성과 입력·출처를 대조한다. 화면 열림, 요청 접수, `onRouteStarted`, `onRoutingCompleted`만으로 검색 성공·적용 성공을 표시하지 않는다. callback에는 앱 request ID가 없으므로 driver의 query 번호는 로컬 관측 번호다. 초기 조회 후 listener를 새 SDK session으로 바꿔 이전 조회와 요청 후 증거를 분리하고, timeout은 목적지 없음·불변·일치로 바꾸지 않는다. [공식 목적지 처리](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/NaviHelper/destinations)

## 권한과 최소 재현

공식 권한 표는 정보 조회에 `NAVI_ROUTE`, 경로 요청에 `NAVI_ROUTE_SEARCH`, 화면 요청에 `NAVI_CUSTOM_MAP`을 구분한다. 선언이 필요하다는 사실만으로 signature 권한이라고 단정하지 않는다. 먼저 Android 공개 `PackageManager.getPermissionInfo`로 정확한 `pleos.car.permission.NAVI_CUSTOM_MAP`의 정의를 확인한다. 정상 SDK 권한의 최소 선언은 허용 범위이며, signature/privileged 거부 시 강제 부여·root·보안 설정 변경으로 우회하지 않는다. [Pleos 권한 표](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/NaviHelper/declaring-permissions), [Android PermissionInfo](https://developer.android.com/reference/android/content/pm/PermissionInfo), [Android PackageManager](https://developer.android.com/reference/android/content/pm/PackageManager#getPermissionInfo(java.lang.String,%20int))

총괄이 기존 A 차량 user10에서 초기 진단 APK를 설치하고 `permission-info`를 실행했다. 원본 `root/permission-info-A.json`에서 `protectionLevel = 0`, `protection = 0`, `protectionFlags = 0`, `flags = 1073741824`, `normalProtection = true`, `sourcePackage = ai.pleos.playground.service.navi`를 확인했다. 이 실행은 SDK 연결과 경로 요청이 모두 0회이며, 원본 파일 SHA-256은 `f3f4bad7609e964c296e347a235057f778b4c8ceba9b8e86373760e345d10d11`이다. 이 관측에 따라 driver에만 정상 `NAVI_CUSTOM_MAP` 선언을 추가했으며 강제 grant나 보안 설정 변경은 수행하지 않았다.

`nativeDriver`는 계속 자기 package만 대상으로 하는 test-only instrumentation이다. 제품 서비스·동의·제안 흐름을 호출하지 않는다. 기존 `query`, `route-42dot`, `route-dream-center` 모드와 nullable sub-ID의 strict 비교는 보존했다.

| 진단 모드와 입력 | 수행·기록 | 성공으로 주장하지 않는 항목 |
|---|---|---|
| `permission-info` | 정확한 권한의 name/sourcePackage/protectionLevel/protection/protectionFlags/flags. SDK 연결·경로 요청 0회 | 권한 선언·grant 또는 화면 지원 완료 |
| `observe`, 선택적 `durationMs`(1,000–90,000, 기본 20,000) | 초기 및 주기적 실제 목적지, 전체 조회 응답 여부, 목적지 정체성 불변, driver 경로 요청 횟수 0 | 관측 구간 밖 상태, 응답 없는 구간의 안내 유지 |
| `search-screen`, `keyword` 및 선택적 `durationMs` | normal 권한 grant 확인 후 공개 `SearchPlace(keyword)` 1회, 초기·주기적·종료 시 실제 목적지, driver 경로 요청 0회 | 화면이 실제 표시됨, 선택 결과가 앱으로 반환됨 |
| `bookmarks`, `publicNamesJson`(서로 다른 공개 테스트 이름 1–5개) | `getBookmarkInfo` callback의 exact 이름 허용목록과 일치한 이름·좌표·POI 필드만 원형대로 기록. 나머지는 개수만 기록. 초기/후속 목적지 비교, 경로 요청 0회 | 새 키워드 검색 목록임, 저장 성공, 미일치 항목의 값, 이후 제안 적용 |
| `route-public-poi`, `publicDestinationJson` | 실제 공개 SDK 북마크의 9개 필드, 주소와 nullable sub-ID를 보존. 새 session 경로 요청 1회·최대 45초 실제 후속 strict 비교 | JSON 자체의 출처 증명, 실제 제안 전원 수락, 다른 유형의 일반화 |
| `trip-public-poi`, `publicDestinationJson` | 실제 9개 필드·빈 provider만 허용. 새 session Trip 1회·경로 0회·최대 30초, 필터된 실제 routePoint와 callback 이후 native 조회 | 안내 불변의 공식 보장, 반환 지점의 canonical target 의미, 실제 적용 성공 |
| `route-coordinate-only`, `latitude`/`longitude`/`name`/`address` | ID·sub-ID·provider를 모두 빈 문자열로 고정한 요청 1회. 원형 그대로의 후속 목적지와 이름/좌표 비교·정체성 변경 여부 | 공식 좌표-only 지원, 검색, 제안 수락, canonical POI strict match |

진단에는 카테고리나 새 장소 fixture를 추가하지 않았다. 입력 좌표는 finite와 지리 범위, 이름은 공백 제외와 길이를 검사한다. 장소 값은 실제 공개 SDK callback과 출처 해시에 묶었다. 카페·식당·주유소 3개 후보를 확인했고 별도의 주소 키워드 실험은 수행하지 않았다.

검색 keyword는 비어 있지 않은 최대 120자 문자열이며 제어 문자를 거부한다. 북마크 허용목록도 제어 문자를 거부하고 이름당 최대 160자로 제한한다. callback의 관측 필드에 값을 만들거나 42dot 등의 ID로 보충하지 않는다. `navigationScreenRequestReturned`는 메서드 반환만 뜻하고 `screenOpenedObserved`는 driver에서 항상 미확인이다. 실제 화면은 총괄의 UI 증거로 따로 판단한다. 장치·ADB·브라우저·계정·프로세스 조작은 총괄만 수행한다.

## 실제 카페 검색·저장·조회

총괄은 A 차량의 순정 카페 검색 결과에서 `가든서비스` 상세를 열고 ☆로 저장했다. 순정의 `목적지로` 버튼으로 안내를 먼저 바꾸지 않았다. 검색 API 요청과 화면 표시는 `root/ui/search-cafe-A.json` 및 같은 이름의 화면 PNG/XML, 저장 단계는 `root/ui/cafe-detail-save-observe-A.json`으로 분리했다. builder는 장치를 조작하지 않고 이 원본 JSON과 해시를 직접 대조했다.

| 실제 단계 | 관측 |
|---|---|
| 카페 키워드 검색 | 공개 `SearchPlace("카페")` 1회, 20초 동안 초기 포함 목적지 표본 8개가 모두 42dot 정체성과 동일, driver 경로 요청 0회, 모든 조회 응답 |
| 상세 열기·☆ 저장 | 18초 동안 초기 포함 표본 7개가 모두 42dot과 동일, 경로 요청 0회, 모든 조회 응답 |
| 저장한 카페 공개 조회 | `onBookmarkInfo`의 전체 3개 중 허용목록 일치 1개, 나머지 2개는 개수만 기록. 조회 전후 42dot 동일, 경로 요청 0회 |

실제 반환 후보는 이름 `가든서비스`, 위도 `37.41255277`, 경도 `127.09591666`, POI ID `36khp2h36p68cm5nqhec`, sub-ID `null`, provider `""`, 표시 좌표 `null`이다. 이 값은 새 하드코딩 후보로 추가하지 않았다. 첫 북마크 진단은 주소를 의도적으로 직렬화하지 않았으며 **SDK에 주소 필드가 없다는 뜻이 아니다**. 후속 진단은 공개 `BookmarkInfo.getAddress()`로 같은 허용목록 항목의 실제 주소도 수집했다. 주소를 추측하거나 다른 장소의 값을 채우지 않았다.

주소를 추가한 실제 재조회 `root/ui/cafe-bookmark-address-A.json`은 같은 후보에 `경기도 성남시 수정구 창업로40번길 6`을 반환했다. 원본 SHA-256은 `b8bee89984eefaee9d6f2dd7644117bd93e7b30688577e0a977f387d4a229ec4`이며 조회 전후 42dot 정체성 동일·경로 요청 0회다. `root/cafe-route-source.json`은 이 원본 해시와 9개 실제 필드를 요청 입력으로 고정했다. 주소를 채워 만든 입력과 구분한다.

`builder/runtime-observations.json`은 위 세 원본의 SHA-256·단계·표본 수·경로 요청 수와 필터된 실제 callback을 기록한다. 카페의 수락 후 연결 가능성을 보기 위해 `route-public-poi` 진단을 추가했다. `publicDestinationJson`은 새 실제 bookmark callback의 `name`, `latitude`, `longitude`, `address`, `poiId`, `poiSubId`, `poiProvider`, `displayLatitude`, `displayLongitude` 9개 필드를 그대로 요구한다. null sub-ID는 원형을 유지하고 SDK 입력에서만 빈 문자열로 전달한다. 새 SDK session에서 경로 1회와 최대 45초 실제 후속 조회를 수행하며, 기존 core와 같은 이름·좌표 오차 0.00001·ID·nullable sub-ID·provider 일치만 `VERIFIED`로 판단한다. 주소·표시 좌표 일치는 별도로 기록한다. 실제 제안의 전원 수락 검증을 대신하지 않는다.

## 실제 경로 진단과 좌표 계약의 한계

총괄이 `root/cafe-route-source.json`의 실제 북마크 값을 `route-public-poi`에 전달했다. 요청은 1회였으며 45초 동안 후속 조회가 원래 후보 좌표와 일치하지 않아 `FAILED / post_request_public_poi_identity_did_not_match`였다. 이름·POI ID·nullable sub-ID·provider는 같았다. `onRouteStarted`는 관측했지만 `onRoutingCompleted`는 관측하지 못했다. 전자는 실제 목적지 일치의 근거가 아니다. 원본 `root/ui/cafe-public-route-A.json` SHA-256은 `4b5a4889eebc9782786f8e38b13a74053e3ea6781d6faff2d7aa2cd25cfa63f1`이다.

| 실제 값 | 북마크 원본·POI 요청 입력 | POI 요청 후 실제 목적지 | 빈 ID 좌표 요청 후 실제 목적지 |
|---|---|---|---|
| 이름 | 가든서비스 | 가든서비스 | 가든서비스 |
| 위도, 경도 | 37.41255277, 127.09591666 | 37.412475, 127.09534722 | 37.41255277, 127.09591666 |
| POI ID | `36khp2h36p68cm5nqhec` | 같은 ID | 빈 문자열 |
| sub-ID / provider | null / 빈 문자열 | null / 빈 문자열 | null / 빈 문자열 |
| 주소 | 경기도 성남시 수정구 창업로40번길 6 | 수정구 시흥동 | 수정구 시흥동 |
| 표시 좌표 | null | null | null |
| 후속 `remainDistance` / `remainTime` | 북마크에는 없음 | 60 / 60 | 1600 / 360 |

북마크와 POI 요청 후 실제 목적지의 직선 좌표 차이는 구면 거리 근사로 약 51m다. 이 차이가 출입구·도로 연결점·canonicalization 때문이라는 설명은 **추론이며 공식 근거를 확보하지 못했다**. 공개 클래스 문서는 세 모델 모두 latitude/longitude를 일반 지리좌표로, display 좌표를 지도 표시용으로 설명한다. 동일 POI에서 두 모델의 좌표가 같다는 보장이나 출입구 해석·사전 변환 API는 찾지 못했다. 세 값 모두 display 좌표가 null이므로 표시 좌표로 바꾸어 검증할 근거도 없다. [BookmarkInfo](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/NaviHelper/navi-helper/ai.pleos.playground.navi.data/bookmark-info/entry), [DestinationInfo](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/NaviHelper/navi-helper/ai.pleos.playground.navi.data/destination-info/entry), [RouteInfo](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/NaviHelper/navi-helper/ai.pleos.playground.navi.data/route-info/entry)

공식 사이트의 정적 Dokka 원본은 `official-documents/public-site-assets/`의 field/entry manifest와 발췌 JSON으로 보관했다. [BookmarkInfo 실제 문서 자산](https://document.pleos.ai/en/assets/js/2945b0f8.b47729f0.js), [DestinationInfo 실제 문서 자산](https://document.pleos.ai/en/assets/js/e23c1b13.4ca33874.js), [RouteInfo 실제 문서 자산](https://document.pleos.ai/en/assets/js/b679c257.632d6fac.js)의 클래스 설명도 대조했다. 이 내용은 ID/sub-ID의 타입과 default, 표시 좌표의 의미를 확인하지만 두 좌표 사이의 변환 계약을 제공하지 않는다. 릴리스 노트는 3.0.5의 생성자 순서 변경을 기록하며 이번 코드는 named arguments를 사용한다. 그 변경을 현재 51m 차이의 원인으로 단정하지 않는다. [3.0.5 릴리스 노트](https://document.pleos.ai/en/docs/pleos-only/release-note/sdk-release-note)

별도 좌표-only 진단은 `OBSERVED`다. 빈 ID 요청 후 이름·입력 좌표·빈 ID·null sub-ID·빈 provider가 실제 조회됐지만 정상 POI 요청과 `remainDistance`도 다르다. 같은 안내 목적지나 유효한 진입 경로임을 입증하지 않았고, 공식 빈 ID 계약도 미확인이다. 원본 `root/ui/cafe-coordinate-route-A.json` SHA-256은 `b54866ce9105d736e74e74990567b63f03900c17f68fe93f6c0b9d1c94cabeb5`이며 두 진단의 원형 값은 `builder/runtime-route-comparison.json`에 나란히 보관했다. 기존 `canRequestRoute`·strict 기준을 낮추어 이 경로를 제품에 넣지 않는다.

`RouteStartInfo`도 공개 선언과 공식 문서로 확인했다. 필드는 `duration`, `distance`, `tollFare`, `name`, `remainDestinationChargingValue` 5개뿐이며 모두 Int/String이다. 목적지 좌표·POI ID/sub-ID/provider·nested target·앱 request ID가 없다. 공식 설명의 name도 목적지 또는 출발점 이름이며 요청의 canonical target 증명이 아니다. 따라서 `원본 북마크 → request-bound RouteStartInfo target → 별도 목적지 strict 확인` 연결은 이 버전의 이 타입으로 만들 수 없다. 원본은 `sdk-public/javap-routestart-public.txt`와 `javap-routestart-command.json`(exit 0, stderr 없음), 공식 정적 문서는 entry manifest에 기록했다. [RouteStartInfo](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/NaviHelper/navi-helper/ai.pleos.playground.navi.data/route-start-info/entry), [실제 문서 자산](https://document.pleos.ai/en/assets/js/787824e4.d767400e.js)

제품 모델의 후속 제약도 남는다. 현재 `Destination`의 data-class equality는 주소·좌표를 포함한 전체 metadata를 비교하므로, 제안의 북마크 값과 순정 목적지 조회 값을 같은 공유값으로 취급하면 자동 공유에서도 차이가 생긴다. 관측한 새 좌표를 곧바로 기대값으로 덮어 self-match하는 방식은 해결책이 아니다. 기존 안내를 유지하는 공식 POI target 해석 또는 원본 요청과 연결된 canonical target 응답 계약이 먼저 필요하다.

## headless 화면 실험과 전경 Activity 진단

headless instrumentation의 카페 1회는 실제 결과 화면이 열렸다. 이후 주유소 2회(`search-fuel-A.json`, `search-fuel-from-app-A.json`)와 식당 1회(`search-restaurant-A.json`)는 SDK 반환·경로 0회·목적지 불변은 관측했지만 부모의 PNG에서 해당 결과 패널을 확인하지 못했다. 이 세 기록을 삭제하거나 성공으로 바꾸지 않는다. `SearchPlace` 전체의 일반적인 NO-GO 또는 모든 카테고리 검색 성공으로 일반화하지 않는다.

Android는 배경 Activity 시작을 제한한다. 그러나 그 일반 규칙이 이 SDK 실험의 원인이라는 직접 증거는 없으며, HMI/전경 상태·호출 Context 차이도 아직 가설이다. NaviHelper 공식 초기화 예제는 applicationContext를 사용하고 화면 메서드는 normal 권한을 명시한다. 조사한 공식 설명에는 모든 화면 요청에 전경 Activity가 필수라는 조건은 찾지 못했다. [Android Activity 시작 규칙](https://developer.android.com/guide/components/activities/secure-bal), [공식 초기화·해제](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/NaviHelper/initialization-and-release), [공식 화면 메서드 자산](https://document.pleos.ai/en/assets/js/91f83154.78f89514.js)

이 가설을 분리하기 위해 test-only `SearchScreenProbeActivity`를 추가했다. 일반 권한 3개를 유지하며 실제 전경 버튼을 눌러야만 호출한다. onConnected 뒤 버튼을 활성화하고, 실제 pre-query 응답과 Activity resumed 상태를 확인한 뒤 Activity Context에서 공개 `SearchPlace`를 1회 호출한다. 경로 요청 코드는 없다. 20초 동안 후속 실제 목적지를 비동기 Handler로 관측하며 SDK 반환과 화면 표시는 별도로 기록한다.

연결 timeout 15초·개별 조회 timeout 8초·전경 이탈 전 호출 실패·명시 중지·Destroy·20초 완료와 SDK dispose를 구분한다. 순정 UI로 이동하면서 발생한 정상 onStop에서만 한정된 관측을 계속하며, 명시 중지/Destroy/관측 완료 때 pending query·timer·listener·SDK를 정리한다. 강제 프로세스 소실의 미완료 checkpoint는 성공으로 바꾸지 않는다. 자기 package의 `no_backup/search-screen-activity.json`에 공개 SDK 값만 원자적으로 기록한다. BAS 우회·특권 부여·새 foreground service는 추가하지 않았다.

전경 Activity의 실제 버튼에서는 A 주유소 결과 6개와 B 식당 결과 5개가 PNG/XML로 확인됐다. 두 20초 진단 모두 초기 포함 실제 목적지 표본 8개가 42dot과 같고, 경로 요청 0회·resumed 상태의 화면 호출 1회·관측 완료·SDK dispose가 기록됐다. B 식당의 fast PNG는 loading placeholder였으며 실제 결과 근거는 `foreground-restaurant-B-screen.png`와 XML이다. 화면 성공을 SDK 반환에서 추정하지 않았다. 기록은 `foreground-fuel-A-activity.json`(SHA-256 `5183ebb6b35833c27a8a532099b5ee3feb4c6b9aa9162755c8ce9b3c1c36a888`)과 `foreground-restaurant-B-activity.json`(`c924656e20dec8cb77cca15d7628cdfbac964cec1a6d724c07e46d82f784a12b`)이다.

카페 이외의 실제 북마크도 별도 조회했다. 아래 값은 source callback을 문서에 기록한 것이며 제품 후보로 하드코딩하지 않았다. 두 항목의 sub-ID와 표시 좌표는 null, provider는 빈 문자열이다.

| 공개 후보 | 실제 북마크 좌표 | 주소 | POI ID |
|---|---|---|---|
| HD현대오일뱅크 세종주유소 | 37.42205413, 127.10318229 | 경기도 성남시 수정구 여수대로 10 | `36khp23245r6cm5nqhe3` |
| 청계산담백미 | 37.40931388, 127.09780277 | 경기도 성남시 수정구 달래내로 22 | `36khp2h5fjcfcm5nqhe3` |

주유소는 ☆ 저장 화면과 이후 북마크 조회 전후의 실제 42dot·경로 0회가 확인됐다. 다만 22초 observe는 상세 단계만 덮고, 저장은 종료 약 2.13초 뒤였다. 따라서 저장 순간을 포함한 연속 관측이라고 주장하지 않는다. `fuel-bookmark-address-A.json`은 저장 이후의 조회 근거다(SHA-256 `0a83934f50d6ed7f9b5e776f192b9da0ccea6c5b8d2d0354ac0a9ef54b964e57`). 식당은 50초 observe의 18개 실제 표본이 상세·저장 단계를 덮고 모두 42dot·경로 0회였으며, 별도 북마크 조회도 동일했다. `restaurant-bookmark-address-B.json` SHA-256은 `2f63e9be282be75f649880fe70bf614fd2dea087fa59b96dc5b0526919da0367`이다.

## 마지막 supplied-target Trip 진단

공개 `TripRequest`는 latitude/longitude, poiId, poiName, nullable poiSubId를 모두 입력으로 받는다. 무인자 현재 trip 조회가 아니다. `requestTrip`은 normal `NAVI_ROUTE` 권한과 `onTripInfo` 응답 가능성을 명시하지만, 현재 안내 불변이나 반환 좌표가 안내용 canonical target이라는 보장은 설명하지 않는다. `TripInfo.summary.routePoints`에는 이름·ID·좌표·pointType이 있고 provider/sub-ID/request ID는 없다. [공식 TripRequest 문서 자산](https://document.pleos.ai/en/assets/js/191a754b.1970a6b6.js), [requestTrip 문서 자산](https://document.pleos.ai/en/assets/js/670fd184.2b407233.js), [TripSummary 문서 자산](https://document.pleos.ai/en/assets/js/b2fe55f6.46b1bf20.js)

`trip-public-poi`는 실제 북마크 9개 필드를 받고 provider가 정확히 빈 문자열인 경우만 허용한다. 초기 조회 뒤 새 SDK session에서 requestTrip 1회, 최대 30초 동안 실제 목적지를 조회한다. 원본 null sub-ID를 그대로 전달하며 route를 요청하지 않는다. callback에서는 입력의 공개 이름과 일치하는 routePoint만 원형대로 기록하고 나머지는 개수만 남긴다. callback 뒤 시작한 별도 native 조회까지 끝나야 `OBSERVED`이며 canonical 지원을 뜻하는 `VERIFIED` 상태는 만들지 않는다.

| 실제 Trip 대상 | 북마크 좌표 | 반환된 DESTINATION point | 별도 실제 안내 좌표 |
|---|---|---|---|
| A 가든서비스 | 37.41255277, 127.09591666 | 37.4122913, 127.096056 | 이전 POI 요청에서 37.412475, 127.09534722 |
| B 청계산담백미 | 37.40931388, 127.09780277 | 37.4094339, 127.0980126 | 이 식당의 실제 안내 적용 진단은 수행하지 않음 |

두 callback 모두 대상 이름·POI ID가 같고 pointType `DESTINATION`이었다. 각 Trip 요청 1회·경로 요청 0회·native route-start callback 0회, Trip callback 뒤 시작한 실제 목적지 조회 응답과 42dot 불변을 확인했다. 카페 point는 북마크와 실제 안내 좌표 어느 쪽과도 같지 않으므로 이 공개 정보 경로도 현재 strict 확인의 해결책이 되지 못했다. 식당 point 역시 원본 북마크와 다르며 canonical 의미는 미확인이다. 원본 `cafe-trip-A.json` SHA-256은 `998b87f80a2bb01473a8ed1da0618fe27028ed048a718a770845dc496cc5145f`, `restaurant-trip-B.json`은 `10b6cc9c7d1971e4b74bc82a2a77f5acf886bd4da8458f031444d555e274ba82`다.

최신 shared parser는 정확한 9개 key와 JSON number 좌표를 요구한다. 이전 `public-poi-build`는 필수 필드 외 추가 key를 무시하고 숫자 문자열도 변환했으며, 그 단계가 이를 거절했다고 주장하지 않는다. 실제 과거 입력은 정확한 9개 필드라 원래 관측을 보존한다. 최신 추가 key 거절 검증은 `FAILED / IllegalArgumentException`과 경로 0회·Trip 0회를 확인했다(`trip-extra-field-rejected-A.json`, SHA-256 `5342740d4c515788a40efc708b9d301097ef98af0fd6b497448f2dd27ee3a3d5`). 이는 기대한 입력 거절의 PASS이며 기능 성공이 아니다.

## 제품 연결의 최소 경계 제안

**선결 제약:** 북마크 point를 현재 `Destination`으로 옮겨 그대로 strict 적용하는 경로는 실제 실패했다. 사전 target 해석이나 요청에 결합된 canonical target 계약이 확보되기 전에는 아래 UI 연결을 구현하지 않는다. 좌표 오차를 넓히거나 ID만으로 성공 처리하지 않는다. [DESIGN.md의 차량 제안 계약](../../DESIGN.md)에 따라 기존 20sp 본문·56dp 버튼·세로 스크롤과 실제 결과 구분을 재사용한다.

1. 기존 제안 카드에 키워드와 `순정 내비에서 검색`, `저장한 장소 불러오기`, 이름·주소로 구별하는 선택을 추가한다. `현재 안내를 유지하려면 순정 화면의 “목적지로”를 누르지 말고 ☆로 저장한 뒤 돌아오세요`라고 안내한다. 선택한 immutable `Destination`만 기존 `proposalAction { propose(target, reason) }`에 전달한다. 일반 카테고리나 새 고정 POI를 추가하지 않는다.
2. 앱 계층의 장소 조회 인터페이스와 controller를 둔다. 기존 `PleosNavigationGateway`의 동일 `NaviHelper`·listener에 공개 화면 요청·북마크 조회만 확장하고, 장소 결과를 현재 목적지 callback으로 전달하지 않는다. 실제 BookmarkInfo를 엄격히 정규화하며 원형 ID·provider·nullable sub-ID·주소를 보존한다. server·core의 기존 제안/수락/strict-match 기준은 변경하지 않는다.
3. 서비스가 읽기 전용 장소 상태와 pending 요청을 소유하고 UI는 별도로 관찰한다. 검색·조회로 새 foreground service·동의·제안 lease를 만들지 않는다. 자동 꺼짐에서 순정 화면으로 전환하며 기존 bound service가 종료돼도 복귀 후 명시적으로 다시 불러오면 된다. 장소 UI 종료·중지·탈퇴·방/회원 변경은 후보·선택·timer·token을 폐기하고 기존 동행/수락 확인 수명은 유지한다.
4. 북마크 요청은 main thread의 단일 in-flight와 8초 기한, SDK epoch·방/회원 scope에 묶는다. timeout/취소 뒤 같은 helper에 바로 새 요청을 붙이면 request ID가 없는 옛 응답을 새 결과로 오인할 수 있다. 해당 장소 조회 epoch를 소진시키고 기존 `순정 내비 연결 / 다시 연결`의 정상 새 SDK epoch 이후만 재시도한다. 장소 조회를 위해 진행 중 coordinator를 자동 재연결하지 않는다. 선택 dialog는 표시한 immutable snapshot을 캡처해 바뀐 목록의 index로 다른 장소를 선택하지 않는다.
5. 예상 제품 변경은 앱의 controller/정규화기, gateway/MainActivity/service/manifest와 관련 JVM 검사·DESIGN/README다. 기존 `VehicleSession.propose`를 재사용하면 서버·core 변경은 필요하지 않다. 빈 목록·잘못된 메타데이터·지연 응답·timeout·재연결·scope 교체·선택 무효화·중복 탭과 실제 두 차량의 미수락 경로 0회/수락 후 실제 일치를 검사해야 한다.

## 검사·증거와 남은 연결점

기준 커밋은 `3a4c734093406bcb54e8c33bffeb9c0661f6720d`, 작업 브랜치는 `be/issue-19-general-place-discovery`다. 실행 artifact와 이후 소스 변경을 혼동하지 않도록 빌드별 source 사본·manifest·APK를 고정했다. 코드 head와 증거 head는 최종 freeze 기록에서 각각 확인한다.

```text
approved Gradle 8.11.1 --no-daemon --offline :nativeDriver:assembleDebug :nativeDriver:lintDebug
exit 0; BUILD SUCCESSFUL in 24s
lint errors 0; warnings 2 (DataExtractionRules, MissingApplicationIcon)
```

초기 `permission-probe-build` APK SHA-256은 `50493eb63a4df4f92c2600f7e42c9bce64d317523bfb545f345ce2b9b27f392f`, Kotlin 입력 SHA-256은 `c69c602770521fdc96a15c5bca27b3f77789a0f60e6a1265f388d0091e5f1a00`, driver manifest SHA-256은 `f277482f5585abc19fade70334fd2070c733fc7a1df166884d28c7d02af07c91`이다. 승인된 기존 `build-env.json`을 재사용했고 환경값·자격값은 출력하지 않았다.

공개 검색 화면·북마크 모드의 실행 입력은 `search-bookmark-build/freeze-2`로 별도 고정했다. 같은 Gradle 명령이 exit 0(`BUILD SUCCESSFUL in 20s`), lint 오류 0·기존 경고 2개로 통과했다. APK SHA-256은 `fe7a9ec1c673a10d3193ef7061f0e739eccedf04081e065b051f4c4c794cc521`, Kotlin 입력 SHA-256은 `73db3ae098fb4b2763d42fbee5359230b13d7b472b0568b47090a5a8d7c42088`이다. `aapt`의 badging/permissions/xmltree와 `apksigner verify --print-certs`가 모두 exit 0이며 자기 package 대상 instrumentation·`testOnly`·기존 두 권한과 추가 `NAVI_CUSTOM_MAP`만 확인했다. 첫 검색 빌드와 초기 권한 진단의 APK·입력도 그대로 보존한다.

주소 필드와 `route-public-poi`의 입력은 `public-poi-build`로 새로 고정했다. 같은 build/lint 명령이 exit 0(`BUILD SUCCESSFUL in 22s`)이며 APK SHA-256은 `8dbf5626ef7f4bf617e29a70d90b338456e6a6a07404699b80be8807624ef424`, Kotlin 입력 SHA-256은 `370764b229d1de31a49fecb829ef45f1aeaaa18a739c7590aedb78b05ac82b41`이다. 정확한 9개 필드·허용 길이·null·단일 요청·판정 의미는 같은 폴더의 `public-destination-schema.json`에 기록했다. 이 APK의 실제 카페 POI 요청은 앞 절의 좌표 불일치로 strict FAIL이다.

전경 버튼 진단은 `foreground-activity-build`로 고정했다. 같은 build/lint 명령 exit 0(`BUILD SUCCESSFUL in 25s`), lint 오류 0·경고 6개(기존 2개와 테스트 화면 문자열 연결 4개)다. APK SHA-256은 `8265a4cadd0062a960ea7b7a2bc0db8e8cde5731e1685b3e1f009bb9ac14399a`, Activity source SHA-256은 `3f87837fedd38cf67147de20f553c7c5e12ff4a7a6a26d0af037cd8081854fc3`, manifest SHA-256은 `e1f9e3565d1fa4dcdb39e2983874d0a9d70d291fc74cb522237756a63c785f7c`다. 자기 test-only package의 Activity만 exported로 추가했다. 제품 APK는 빌드/설치하지 않았다.

최종 `trip-build`도 같은 build/lint 명령 exit 0(`BUILD SUCCESSFUL in 23s`), lint 오류 0·경고 6개다. APK SHA-256은 `470c696a36d17c04089073a4ed4b6c33cd03e0630608601ee9d8085c29be7ad7`, driver source SHA-256은 `bd333294d5e687c0244f4058c0b55be00c2937a3c8f97cf76f308dd0f2ad797b`이며 Activity·manifest는 전경 빌드와 같다. aapt의 badging/permissions/xmltree와 apksigner verify가 모두 exit 0이다. 최종 두 차량의 실제 목적지는 42dot이고 진단 query의 경로·Trip 요청은 0회였다. 기존 제품 APK·자동 꺼짐·proposal lease 없음은 `root/production-preserved-final.json`에 별도로 확인했다. 제품 source가 바뀌지 않아 기존 220개 제품 회귀는 이번에 재실행하지 않았으며 새 통과로 주장하지 않는다.

최종 `builder/baseline-preservation-final.json`은 기준의 tracked 경로 406개를 blob 단위로 비교한다. 허용된 기존 driver Kotlin/manifest 2개만 바뀌었고 나머지 404개가 동일하다. 기존 `prototype/vehicle/evidence/`의 315개 tracked 파일도 모두 동일하다. 연구 문서와 test-only Activity는 새 파일이며 부모가 추가하는 이번 이슈 증거와 구분한다.

원본은 저장소 상위 작업 artifact의 `general-places-20261106/builder/` 아래에 보관한다. `official-documents/manifest.json`은 문서 URL·조회시각·해시, `sdk-public/manifest.json`과 `javap-supported-command.json`/`javap-screen-command.json`은 AAR·공개 선언, `permission-probe-build/`는 command/log/source/APK/lint를 고정한다. 최초 `javap`의 잘못된 `constants.NavigationScreen` 조회는 exit 0임에도 stderr에 class-not-found가 있었으며 실패 원본을 보존했다. 정확한 `NavigationScreenRequest.SearchPlace`로 정정한 두 실행은 exit 0·stderr 없음이다.

다음 제품 작업의 구체적 재개 조건은 다음과 같다.

1. 공개 계약에서 선택 지점과 실제 안내 target의 좌표 의미·관계를 확보한다. 현재 북마크와 Trip point 어느 것도 카페의 실제 안내 좌표를 예측하지 못했다. source POI ID/provider/sub-ID를 보존한 사전 target 해석 또는 원래 요청에 결합된 canonical target 응답이 필요하다.
2. 그 계약을 근거로 `SelectedPlace`와 `ResolvedNavigationTarget`을 구분하는 정규화 모델을 설계한다. 원본 출처·실제 좌표·주소를 각각 보존하고, 전원 수락 이전 경로 요청 0회와 이후 별도 실제 목적지의 strict 일치를 두 차량에서 다시 검증한다. 후속 관측값을 기대값으로 학습하거나 거리 허용치를 확대하지 않는다.
3. 그 뒤에만 위 검색·저장·명시 선택 UI와 기존 제안 프로토콜을 연결한다. 빈 ID 좌표 요청을 정식 POI 대체로 쓰지 않는다. 새 공급자가 필요하면 접근권·사용권·metadata를 별도 승인 범위에서 확인하며 이번 작업에서는 계정·결제·새 약관·외부 문의를 수행하지 않았다.

지원 경로가 확인되면 후보 선택과 안내 target을 구분하는 모델을 먼저 정의하고, 전원 수락 후 SDK 요청·실제 후속 strict 확인을 연결해야 한다. 현재는 원본 후보를 후속 관측값으로 덮거나 ID-only·넓은 tolerance로 통과시키지 않는다. 정확한 head에 대한 최종 독립 verifier 판정은 증거 디렉터리의 검증 기록을 따른다.
