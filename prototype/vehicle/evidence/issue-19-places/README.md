# 일반 장소 검색·선택 조사 증거

이 디렉터리는 이슈 #19의 실행 증거다. 제품의 일반 장소 기능 구현 완료 보고가 아니다. API 해석, 지원 범위와 후속 설계는 [단일 조사 문서](../../../../docs/product/general-place-discovery.md)를 기준으로 한다.

## 입력과 범위

- 고정 요구사항: [issue.md](issue.md).
- 기준 커밋: `3a4c734093406bcb54e8c33bffeb9c0661f6720d`.
- 진단 소스·조사 문서 커밋: `9d0bfcedc5644b30856f6112b9be444f4030664f`. 이후 증거 추가 커밋의 정확한 head와 독립 판정은 최종 PR에 기록한다.
- 작업 브랜치: `be/issue-19-general-place-discovery`.
- 장치: 전용 ADB `5041`, `emulator-5570`과 `emulator-5572`, Android 사용자 `10`만 사용했다.
- SDK: `NaviHelper 3.0.5`, `navi-data 3.0.4`. AAR와 공개 선언 입력은 [SDK 요약](builder/sdk-public-summary.json), [원본 해시](builder/sdk-public/manifest.json), [문서 조회 이력](builder/official-documents/manifest.json)에 기록했다.
- 기존 제품 APK는 두 장치 모두 SHA-256 `e855437d1c7a44eb5612d56e564d44da3c16b95cd92f5ff7284e34c62e1e4db9`로 유지했다. 검사 전 자동 모드를 명시적으로 껐으며 제안 유지 lease도 없다. [실제 APK·상태 확인](root/production-preserved-final.json).

## 실제 재현

| 사례 | 실제 화면 | 공개 SDK 결과 | 판정에 사용한 관측 |
|---|---|---|---|
| 카페 검색·저장, A | [검색](root/ui/search-cafe-A-screen.png), [상세](root/ui/cafe-detail-A.png), [저장](root/ui/cafe-save-dialog-A.png) | [검색 관측](root/ui/search-cafe-A.json), [저장 단계](root/ui/cafe-detail-save-observe-A.json), [주소 포함 북마크](root/ui/cafe-bookmark-address-A.json) | 원래 42dot 목적지 유지, route 요청 0회, 실제 카페의 이름·주소·좌표·POI 메타데이터 회수 |
| 주유소 검색·저장, A | [전경 검색](root/ui/foreground-fuel-A-screen.png), [저장](root/ui/fuel-bookmark-saved-A.png) | [전경 SDK 관측](root/ui/foreground-fuel-A-activity.json), [상세 관측](root/ui/fuel-save-observe-A.json), [북마크](root/ui/fuel-bookmark-address-A.json) | 전경 버튼에서 검색 요청 1회, route 요청 0회. 상세 관측 창은 저장 버튼보다 먼저 끝났으며 저장 후 북마크 조회에서 다시 42dot 불변을 확인 |
| 식당 검색·저장, B | [검색](root/ui/foreground-restaurant-B-screen.png), [저장](root/ui/restaurant-bookmark-saved-B.png) | [전경 SDK 관측](root/ui/foreground-restaurant-B-activity.json), [상세·저장 관측](root/ui/restaurant-save-observe-B.json), [북마크](root/ui/restaurant-bookmark-address-B.json) | 검색 후 20초, 상세·저장 중 50초의 실제 목적지 표본이 모두 42dot. route 요청 0회 |

전경 검색의 SDK 보고서에서 `screenOpenedObserved`는 `null`이다. 화면 열림은 해당 PNG/XML을 별도로 검사해 판정했으며 메서드 반환을 화면 성공으로 바꾸지 않았다. 식당의 빠른 첫 캡처는 로딩 표시였고 표의 최종 PNG/XML에서 실제 식당 결과를 확인했다. 북마크의 허용된 공개 장소만 기록하고 다른 항목은 개수만 남겼다.

## 실패와 제한 관측

- headless 주유소 2회와 식당 1회는 메서드 반환과 실제 목적지 불변을 관측했지만 해당 결과 패널을 확인하지 못했다. [주유소 1](root/ui/search-fuel-A.json), [화면](root/ui/search-fuel-A-screen.png), [주유소 2](root/ui/search-fuel-from-app-A.json), [화면](root/ui/search-fuel-from-app-A-screen.png), [식당](root/ui/search-restaurant-A.json), [화면](root/ui/search-restaurant-A-fast.png). 이후 전경 Activity 버튼에서 결과를 실제로 확인했으며 앞선 기록은 보존했다. 원인이 배경 실행 제한이라는 점은 입증하지 않았다.
- 실제 카페 북마크를 그대로 `requestRoute`에 보낸 진단은 [strict FAIL](root/ui/cafe-public-route-A.json)이다. 같은 이름과 POI ID가 반환됐지만 좌표가 약 51m 달랐다. 좌표 오차 범위를 넓히거나 ID만으로 성공 처리하지 않았다. [출처 결합](root/cafe-route-source.json), [비교값](builder/runtime-route-comparison.json).
- [빈 ID의 좌표 요청](root/ui/cafe-coordinate-route-A.json)은 현재 이미지에서 입력 좌표의 목적지가 관측됐다. `OBSERVED`이며 공식 지원이나 같은 POI 경로의 검증 완료를 뜻하지 않는다. 일반 POI 요청과 안내 거리가 60m 대 1600m로 달랐고 `onRoutingCompleted`도 관측하지 못했다.
- 위 두 경로 변경은 승인된 SDK 가능성 진단이다. 검색 후보를 얻는 제품 흐름으로 사용하지 않았으며 일반 사용자의 미수락 제안을 내비에 반영하는 구현은 추가하지 않았다.
- 공개 `requestTrip` 진단은 [카페 A](root/ui/cafe-trip-A.json), [식당 B](root/ui/restaurant-trip-B.json)에서 각각 실제 `onTripInfo` 응답을 받았다. 요청은 1회, route 요청과 실제 route-start 콜백은 0회였다. 응답 뒤에 시작한 실제 목적지 조회도 원래 42dot을 반환했다. 다만 카페의 Trip 좌표 `37.4122913, 127.096056`은 북마크와 실제 길안내 좌표 모두와 달랐다. 이 응답을 최종 내비 목적지의 정규 좌표로 채택하지 않는다.
- 최종 parser는 정확한 9개 키와 JSON 숫자를 검사한다. [추가 키 거부](root/ui/trip-extra-field-rejected-A.json)는 예상한 `IllegalArgumentException`으로 끝났으며 trip·route 요청은 각각 0회였다. 이 raw 보고서의 `FAILED`는 잘못된 입력에 대한 진단 상태이며 실패를 성공으로 바꾼 것이 아니다. 이전 POI 빌드의 parser는 추가 키를 무시했으나 실제 카페 입력은 정확히 9개 필드였다.
- 최종 APK에서 [A 실제 목적지](root/ui/final-native-A.json), [B 실제 목적지](root/ui/final-native-B.json)를 다시 조회했다. 두 차량 모두 42dot이었다.

## 빌드와 설치 입력

각 빌드 폴더의 `command.json`, `source-manifest.json`, `gradle.log`, lint 보고서가 실제 입력과 결과다. AAR·JAR·APK, 전체 공식 문서 본문, 인증 환경은 Git에 넣지 않았다. 전체 원본과 빌드별 소스 사본은 로컬 raw 디렉터리에 보존했다.

| 단계 | APK SHA-256 | 용도 |
|---|---|---|
| `permission-probe-build` | `50493eb63a4df4f92c2600f7e42c9bce64d317523bfb545f345ce2b9b27f392f` | Android 공개 권한 정의·좌표 진단 |
| `search-bookmark-build/freeze-2` | `fe7a9ec1c673a10d3193ef7061f0e739eccedf04081e065b051f4c4c794cc521` | 카페 검색·북마크. 이 단계의 출력은 주소 필드를 의도적으로 생략했다. API에 주소가 없다는 뜻이 아니다. |
| `public-poi-build` | `8dbf5626ef7f4bf617e29a70d90b338456e6a6a07404699b80be8807624ef424` | 주소 포함 북마크·실제 카페 POI·좌표 요청 |
| `foreground-activity-build` | `8265a4cadd0062a960ea7b7a2bc0db8e8cde5731e1685b3e1f009bb9ac14399a` | 실제 전경 버튼의 주유소·식당 검색·북마크. [A 설치](root/foreground-driver-installed-A.json), [B 설치](root/foreground-driver-installed-B.json) |
| `trip-build` | `470c696a36d17c04089073a4ed4b6c33cd03e0630608601ee9d8085c29be7ad7` | 최종 진단 소스. 실제 북마크를 입력한 Trip 조회·추가 키 거부·최종 목적지 재조회. [A 설치](root/trip-driver-installed-emulator-5570.json), [B 설치](root/trip-driver-installed-emulator-5572.json) |

최종 빌드/lint 명령은 `Gradle 8.11.1 --no-daemon --offline :nativeDriver:assembleDebug :nativeDriver:lintDebug`이며 exit 0이다. 제품 app/core/server를 변경하지 않았으므로 제품 기능 구현 완료나 새 기능의 두 차량 수락 검증을 주장하지 않는다. 최종 빌드 이전의 화면·콜백은 각 행의 실제 APK와 소스 해시에 결합한다.

[보존 검사](root/final-preservation.json)는 기존 tracked 파일 406개 중 허용된 driver 2개만 달라졌고 나머지 404개와 기존 증거 315개가 같음을 실제 SHA-256으로 확인했다. [GitHub 입력 재조회](root/github-input-refetch.json)는 요구사항이 같고 `main`이 기준 커밋임을 확인했다. 로컬 `issue.md`의 마지막 줄바꿈만 GitHub 본문과 다르다.

원본과 복사본의 바이트 수·SHA-256은 [source-manifest.json](source-manifest.json)에 있다. 이 목록에 없는 후속 기록과 최종 base/head·독립 검증은 최종 PR의 Evidence에 별도로 명시한다. custom builder/verifier 역할은 현재 세션에 노출되지 않아, 각 역할 파일의 지침을 별도 문맥의 기본 worker에게 전달했다.
