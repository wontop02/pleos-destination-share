# 함께 가는 길 · iPhone 네이티브 프로토타입

SwiftUI·AVFoundation·Keychain·PushToTalk 소스와 Foundation 공통 코어다. 초기 기준은 iOS 16 이상이며 모든 구형 iPhone 지원이나 실제 잠금 수신 완료를 뜻하지 않는다. 기존 서버의 [공유방·원음 API](../README.md)와 [PTT 등록·전달 계약](../ptt/README.md)을 사용한다.

**현재 상태(2026-11-05):** [최신 복원 안내 검증](evidence/relaunch-status-verification.json)에서 새 전체 Simulator 빌드와 재설치 없는 cold-relaunch 후 동일 공유방·목적지·연결 안내를 확인했다. XCTest 24개·동일 Foundation 24개·Node 94개는 관련 Core 13개/Node 28개 blob이 같은 `cfd486c` 독립 실행 결과를 재사용한다. 실제 마이크·청취·시스템 PTT callback과 iPhone 실증은 미검증이며, 정상 실기기 서명·PTT capability·APNs 자격증명도 미제공/미검증으로 BLOCKED다. Simulator 결과만으로 앱 전체나 잠금 수신을 완료 처리하지 않는다.

## 파일과 재현 명령

| 경로 | 역할 |
|---|---|
| `TogetherRoad.xcodeproj`, `Config/` | iOS 16 target·shared scheme·Debug/Release Info.plist·실기기 PTT/APNs 선언·Simulator 전용 Keychain entitlement |
| `App/TogetherRoadApp.swift`, `ContentView.swift` | 앱 시작 시 모델·PTChannelManager 생성, 한국어 화면과 scene 수명 |
| `App/RoomStore.swift` | 공유방·목적지·원음·등록 CAS·재연결·수신 상태 연결 |
| `App/NativeAudio.swift`, `KeychainStore.swift`, `PTTBridge.swift` | AVFoundation·앱 전용 Keychain·실제 시스템 PTT delegate |
| `Core/` | SwiftPM RoadCore, 동일 시나리오의 실행형 검사와 XCTest wrapper |

저장소 루트에서 실행한다. 전체 Xcode와 Swift 6 toolchain, Node 22 이상이 필요하며 외부 Swift 패키지는 설치하지 않는다. 전역 `xcode-select`를 바꾸지 않고 `DEVELOPER_DIR`을 각 프로세스에 지정한다. 아래 명령은 새 scratch/DerivedData 경로를 사용한다.

```sh
ios_sdk_artifacts="$(mktemp -d /private/tmp/pleos-ios-sdk.XXXXXX)"
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer \
  swift run --package-path prototype/ios/Core \
  --scratch-path "$ios_sdk_artifacts/foundation" RoadCoreChecks
```

`RoadCoreChecks`는 Foundation과 실제 임시 Node 서버를 쓰는 검증 실행 파일이다. 24개 시나리오 중 하나라도 실패하면 오류를 출력하고 exit 1로 끝난다. 빌드 최적화로 사라지는 `assert`를 사용하지 않는다. XCTest wrapper도 같은 24개 시나리오를 호출하며 두 실행을 48개의 새 커버리지로 합산하지 않는다.

```sh
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer \
  swift test --package-path prototype/ios/Core \
  --scratch-path "$ios_sdk_artifacts/xctest"
```

현재 전체 Xcode에서는 XCTest 24개가 실패 0개로 실행됐다. Core 13개·API 4개·등록 경합 7개다. 마지막 Swift Testing의 0 tests 출력은 XCTest 24개 결과와 별도다. 이전 CLT의 `no such module 'XCTest'` 결과는 과거 증거에 보존한다.

SDK 컴파일만 확인하는 unsigned simulator 빌드 명령은 다음과 같다. iOS 27.2 SDK로 arm64·x86_64 앱을 컴파일·링크했으며 deployment target iOS 16과 기존 Swift 5 앱 언어 모드를 유지했다. 이 산출물은 실제 Keychain 실행 검증에 사용하지 않는다.

```sh
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer \
  xcodebuild -project prototype/ios/TogetherRoad.xcodeproj -scheme TogetherRoad \
  -configuration Debug -sdk iphonesimulator \
  -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath "$ios_sdk_artifacts/DerivedData-unsigned" CODE_SIGNING_ALLOWED=NO build
```

Simulator에서 공유방을 만들고 재실행 후 Keychain 세션을 복원하려면 다음 실행용 빌드를 사용한다. `CODE_SIGNING_ALLOWED=NO` override를 전달하지 않는다.

```sh
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer \
  xcodebuild -project prototype/ios/TogetherRoad.xcodeproj -scheme TogetherRoad \
  -configuration Debug -sdk iphonesimulator \
  -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath "$ios_sdk_artifacts/DerivedData-simulator" build
```

`iphonesimulator` SDK에만 `CODE_SIGNING_ALLOWED=YES`, identity `-`와 `TogetherRoad-Simulator.entitlements`를 적용한다. Xcode의 `Sign to Run Locally`가 앱을 ad hoc 서명하며 개발 팀·개인 키·프로필을 사용하지 않는다. Simulator용 `application-identifier`와 유일한 `keychain-access-groups` 값은 앱 bundle ID `kr.together.road.ios`다. Xcode 27.2는 이 두 값을 Mach-O의 `__TEXT,__entitlements` Simulator section에 넣고 실제 macOS 서명 entitlement는 빈 dictionary로 둔다. 두 아키텍처의 실제 section과 strict/deep 서명을 검사했다. Simulator 선언에는 PTT/APNs 권한을 넣지 않는다.

`iphoneos`는 기존 Manual signing·`CODE_SIGNING_ALLOWED=NO`, 빈 팀·identity·프로필과 실기기 PTT/APNs entitlement 파일을 유지한다. 실제 iPhone 설치에는 사용자가 제공하는 정상 서명·PTT와 Push Notifications capability가 필요하다. APNs topic은 bundle ID 뒤의 `.voip-ptt`이며 Debug/Release의 APNs 환경을 서버 설정과 맞춰야 한다. Simulator local 서명이나 entitlement 선언은 실기기 권한을 부여하지 않는다.

## 실제 조작과 연결

1. 서버를 `npm start`로 실행한다. iOS Simulator의 Debug 기본 주소는 `http://localhost:4180`이다. release 기본값은 미설정 안전 주소 `https://localhost.invalid`이며 실제 신뢰된 HTTPS endpoint를 별도로 지정해야 한다.
2. 이름으로 공유방을 만들거나 초대 링크를 붙여 넣어 참여한다. 대표 차량은 목적지 변경·초대 복사·공유방 종료, 참여자는 받은 목적지 확인과 나가기를 사용한다.
3. 자동 수신을 종료하고 채널 탈퇴가 확인된 전경 화면에서 1–15초 녹음한다. 중지 후 전송하거나 취소할 수 있고 15초에 자동 종료한다. 목록에서는 인증 다운로드 후 직접 재생한다.
4. `자동 수신 시작`을 직접 누르면 서버 설정을 먼저 확인한다. 기본 서버가 `not-configured`이면 수신을 시작하지 않았다고 표시한다. 설정과 권한이 준비된 경우에만 listen-only PTT 채널 참여와 임시 토큰 등록을 진행한다.
5. `자동 수신 종료`는 재생·대기열을 취소하고 서버 등록 해제와 시스템 채널 탈퇴를 요청한다. 탈퇴 확인 전에는 전경 마이크·수동 재생을 허용하지 않는다.

Debug HTTP는 Endpoint와 ATS 설정으로 localhost 시연에 제한한다. 초대의 localhost/127.0.0.1 별칭은 Debug HTTP에서 같은 port일 때만 인정한다. foreign origin·쿼리 bearer·사용자 정보가 포함된 주소는 거절한다. release는 임의 HTTP나 TLS 검증 우회를 허용하지 않는다. 실제 iPhone의 localhost는 호스트 Mac이 아니므로 기기가 도달할 수 있는 신뢰된 개발 HTTPS endpoint가 필요하다. 이 작업에서 서버를 외부에 공개하거나 계정·유료 자원을 만들지 않았다.

받은 목적지의 `네이버 지도에 경로 요청`은 [공식 iOS URI 계약](https://guide-fin.ncloud-docs.com/docs/maps-url-scheme)에 따라 좌표·인코딩한 이름·bundle ID를 보낸다. 위도 31.43–44.35, 경도 122.37–132.00만 허용하고 `nmap` 존재/처리 실패를 표시한다. 스토어 이동·설치·GPS 권한은 추가하지 않는다. URL 열기 수락은 실제 길 안내 시작을 뜻하지 않으며 목적지는 유지한다.

## 원음과 직접 오디오 세션

실제 녹음 소스는 AVAudioRecorder다. 마이크 권한을 사용자 조작에서 요청하고 거절·취소·scene 비활성 전환을 처리한다. PCM16 mono 16kHz를 요청해 최대 15초만 녹음하며, 녹음 파일의 실제 AVAudioFile 표본 수·형식을 확인해 공통 WAV 컨테이너로 다시 인코딩한다. 읽기 크기와 표본 수는 제한하고 임시 파일은 전송 준비·취소·실패 후 지운다. 음성 합성이나 시스템 PTT 전송 이벤트를 이용한 백그라운드 녹음은 없다.

재시도는 원래 WAV와 UUID clientMessageId를 유지한 사용자의 명시적 조작으로만 허용한다. 전송되지 않은 원음은 메모리에만 최대 30초 남고 scene 숨김·취소·방 교체 때 폐기한다. 재연결이 녹음을 자동 재전송하지 않는다. 다운로드는 484096바이트와 전체 20초 제한을 두고 RIFF/fmt/data, 표본·크기 metadata 일치를 다시 검사한다. 완료되지 않은 요청과 이전 room epoch 결과는 폐기한다.

마이크·수동 재생에서 직접 활성화한 AVAudioSession은 소유 플래그로 추적해 scene이 비활성이 되어도 반납한다. PTT 활성화에는 이 플래그를 설정하지 않으며 PTT 재생 경로에서 `setActive`를 호출하지 않는다. 시스템 오디오 중단·미디어 서비스 초기화·이어폰 제거는 녹음과 재생을 중단한다. 전송 접수·원음 수신·실제 재생 callback은 구분해 표시한다.

자동 수신 참여 전에는 Store의 수동 다운로드·재생 작업을 취소하고 재생 epoch와 작업 표시를 초기화한 뒤 직접 오디오 세션을 반납한다. 재생을 시작하지 못한 수동 다운로드도 작업 표시를 정리한다. 이 전환의 호출 경로와 SDK 컴파일은 확인했으며 실제 AVFoundation·PTT 전환은 기기에서 추가 검증해야 한다.

## PushToTalk 수명주기

앱 delegate가 시작할 때 PTChannelManager를 만든다. Keychain은 `afterFirstUnlockThisDeviceOnly`, iCloud 동기화 없음, shared access group 없음, 생체 인증 요구 없음으로 앱 전용 세션·채널 UUID/descriptor·등록 ID·수신 선택·중복/순서 정보를 보관한다. 따라서 최초 잠금 해제 이후 잠금 중 시스템 재시작에서 읽을 수 있는 정책이다. 임시 push token Data 자체는 디스크에 저장하지 않고 채널 수명 동안만 메모리에 둔다.

restoration delegate는 캐시 descriptor를 동기적으로 즉시 반환하며 네트워크·Keychain·메인 큐 대기를 하지 않는다. 시스템의 실제 activeChannelUUID와 캐시된 사용자 선택·방/lease·회원·등록 정보를 확인한 기존 채널만 복원한다. 새로운 room epoch를 부여하고 오래된 callback은 거절한다. 캐시 불일치, 최초 잠금 해제 전 Keychain 접근 불가, uptime이 초기화된 재부팅 등 확인 불가능한 상태는 수신 준비 완료로 표시하지 않고 채널을 떠난다. 새 채널 join은 전경의 사용자 조작에서만 한다.

채널 참여가 확인되고 listenOnly 설정이 성공한 뒤에만 임시 token을 서버에 등록한다. token은 1–512바이트의 가변 길이를 lowercase hex로 변환하며 고정 32바이트를 가정하지 않는다. POST/DELETE는 회원별 expectedRevision CAS다. 409에는 GET로 동기화한다. 늦은 POST 정리는 그 응답 registration ID와 현재 ID가 모두 같을 때만 삭제하며, 이전 leave의 늦은 조회도 새 join intent를 지우지 않는다. `not-configured`는 APNs 전달이나 배경 수신 성공이 아니다.

명시적 수신 종료, 시스템 UI·정책에 의한 실제 채널 탈퇴 확인, 참여 실패는 같은 서버 등록 정리 경로를 사용한다. 진행 중 등록을 취소하고 캡처한 room epoch·채널 수명만 폐기한다. 아직 접수되지 않은 POST는 DELETE의 CAS fence로 차단하고, 이미 접수된 늦은 응답은 그 등록 ID만 정리한다. 종료 요청 뒤의 탈퇴 callback은 같은 정리 작업을 공유하며 이전 채널의 정리가 새 참여 의도를 취소하지 않는다. CAS 충돌은 최대 3회까지 시도하고 권한·연결 실패는 정리 확인 실패로 표시한다. 무한 재시도나 등록 해제 성공 표시는 하지 않는다.

`incomingPushResult`는 최대 4096바이트 payload와 잠금으로 보호한 캐시만 검사하고 즉시 실제 PTPushResult를 반환한다. room/channel/registration ID·lease·방 수명·version·30초·자기 음성·중복·순서를 검사한다. 거절에는 문서에 존재하는 `.leaveChannel`을 사용한다. `.ignore` 같은 결과는 만들지 않는다. malformed·stale·중복·이전 registration push 모두 시스템 채널 탈퇴와 사용자 재시작으로 이어질 수 있다. 오래된 원음 한 개만 무시하고 계속 수신하는 정책이 아니며 실제 수신 연속성을 검증한 결과도 아니다. 실제 SDK callback과 iPhone에서 이 정책의 영향을 검증해야 한다.

유효 push는 active participant를 반환하고 `didActivate` 이후에만 다운로드한 원음을 재생한다. 이미 시스템 오디오가 활성인 동안의 새 push는 매번 didActivate가 다시 오리라 가정하지 않고 활성 상태에서 큐를 처리한다. 같은 채널의 token rotation은 활성 상태를 보존하고 이전 바인딩의 pending/epoch를 폐기한다. 재생 직전에 신선도·방/lease·등록·epoch를 다시 확인하며, 뒤늦게 도착한 더 낮은 sequence는 역순 재생하지 않는다. 대기열은 최대 16개다. 완료·실패·중지 시 active remote participant를 nil로 정리하며, scene의 timer가 잠금 중 실행된다고 가정하지 않는다. 만료는 push 진입·다운로드 후·재생 직전·전경 복귀에도 검사한다.

## 검증 상태와 재개 조건

복원·재연결 안내 수정의 새 빌드·cold-relaunch UI와 기존 테스트 재사용은 [evidence/relaunch-status-verification.json](evidence/relaunch-status-verification.json)에 기록한다. [이전 SDK 실행 기록](evidence/xcode-sdk-verification.json), 기존 Simulator UI 증거와 CLT 환경의 `evidence/verification.json`·`evidence/core-checks.log`는 당시 증거로 그대로 보존한다. 가상 PCM 표본과 actor 지연 응답은 프로토콜·경합 검사이며 실마이크나 실제 APNs 성공 증거가 아니다.

이전 독립 검토에서 발견한 시스템 탈퇴 등록 누수와 수동 재생 후 PTT 대기 정체를 수정했다. 5개 actor/CAS 회귀는 접수 전 POST와 탈퇴 fence, 접수 후 취소 응답 정리, 이전 채널 정리와 새 참여 경합, CAS 충돌 재시도, 권한 실패의 제한된 종료를 실제 실행한다. Store·플랫폼 callback 전환의 실기기 동작은 Foundation 검사 수에 포함하지 않는다.

이번 SDK 빌드에서 `AVAudioRecorderDelegate`의 nonisolated 요구와 MainActor 상태의 충돌 경고를 확인했다. 두 녹음 callback은 고유한 녹음 URL과 성공 여부만 MainActor로 전달하고 현재 녹음과 일치할 때 처리하도록 수정했다. SDK가 이름을 변경한 Bluetooth HFP 옵션도 갱신했다. 수정 후 앱 컴파일 경고는 없어졌고 AppIntents 미사용 metadata 안내만 남았다. 실제 녹음 callback 실행은 기기 검증이 필요하다.

새 Foundation 실행 중 테스트 정리가 `Process.waitUntilExit()`에서 멈추는 현상을 두 차례 포착했다. API 검증 후 자식 프로세스 종료를 5ms 간격의 async 대기와 2초 실패 상한으로 확인하도록 바꿨다. 기존 24개 시나리오와 모든 계약 assertion은 유지하며 종료 대기도 통과해야 성공한다. 새 변경의 정확한 base/head 독립 검증은 총괄이 커밋을 고정한 뒤 별도로 수행한다.

초기 unsigned 산출물의 실제 Simulator 공유방 생성은 Keychain 저장에 실패했다. 해당 Simulator의 securityd 로그에서 `SecItemUpdate`의 `OSStatus -34018`과 `application-identifier`·`keychain-access-groups` 부재를 확인했다. SDK 조건부 local 서명과 앱 전용 entitlement를 추가했으며 Keychain API·접근성 정책과 암호화된 OS 저장 경로는 유지했다. 수정 산출물의 총괄 CUA 재검증에서 공유방 생성·연결과 재설치 없는 프로세스 종료·재실행 후 같은 공유방 복원이 통과했다. 생성 전 이름/참여 화면으로 돌아가지 않았고 대표 차량·참여자 수·만료 시각이 유지됐다. 앱의 기본 Keychain 접근 그룹과 entitlement의 관계는 [Apple 설명](https://developer.apple.com/documentation/security/sharing-access-to-keychain-items-among-a-collection-of-apps)을 따른다.

| 항목 | 현재 결과 | 재개·추가 증거 |
|---|---|---|
| Swift Foundation 공통 모듈·실행형 검사 | 24/24 PASS | 실제 HTTP/SSE·WAV 바이트·CAS·취소·순서/신선도/회전·탈퇴 경합 |
| XCTest wrapper 실행 | 24 tests·실패 0 PASS | 실행형과 같은 24개 시나리오이며 macOS 호스트 검사 |
| 전체 iOS 앱·Simulator SDK 빌드 | unsigned Debug PASS | Xcode27.2·iOS27.2 SDK, arm64·x86_64 컴파일·링크, iOS16 target |
| Simulator 실행용 local ad hoc 빌드 | PASS | 앱 전용 simulated entitlement section·strict/deep 서명 확인 |
| Simulator cold-relaunch 복원·연결 안내 | PASS · 총괄 CUA 실행 | 재설치 없이 동일 공유방·역할·목적지·만료 시각 복원. 실제 snapshot 후 연결 안내로 바뀌며 초기 생성·참여 안내가 남지 않음. [새 영수증](evidence/relaunch-status-verification.json) |
| 실제 마이크·원음 재생·PTT delegate | BLOCKED | 서명 설치한 iPhone에서 실제 입력·권한·중단·완료 관찰 |
| Apple 서명·PTT capability·APNs | BLOCKED | 사용자가 제공하는 정상 팀·capability·프로필·APNs 자격 증명 |
| iPhone 내비 앱 사용·잠금 중 첫 소리 | BLOCKED · 필수 제품 게이트 | 모델/iOS/내비 버전, 실제 수신·첫 소리 시각과 청취 증거 |
| 15분/30분 무음 뒤 잠금 첫 소리 | BLOCKED · 필수 제품 게이트 | 두 조건을 각각 실제 기기로 실행·실패/성공 구분 |

현재 macOS는 26.7.2(25G332), 전체 Xcode는 `/Applications/Xcode.app`의 27.2(27B52), 사용한 Swift는 6.4.2다. iOS 27.2 simulator runtime도 설치됐다. 전역 `xcode-select -p`는 `/Library/Developer/CommandLineTools`로 유지하고 모든 빌드·테스트 프로세스에 `DEVELOPER_DIR`을 지정했다. 이번 앱 검사에서 서명 팀·계정·키·프로필을 탐색하거나 자동 provisioning을 사용하지 않았다. Node94/94는 이전 실행 head `7c66b71a43fb63fe8585e37928b031e47dfb49bf`와 관련 28개 Git blob이 같은지 확인해 재사용한 결과다.

구현·빌드 차단 상태를 모든 iPhone의 배경 원음 수신 통과나 앱 전체 완료로 바꾸어 표시하지 않는다. 실제 iOS SDK에서 발견되는 컴파일/API 차이는 수정 후 같은 검사와 별도 verifier의 정확한 base/head 검토를 다시 수행해야 한다.

## 공식 자료

- [Creating a Push to Talk app](https://developer.apple.com/documentation/pushtotalk/creating-a-push-to-talk-app): 초기 manager 생성, 사용자 join, listenOnly, 가변 토큰, 즉시 push result, 시스템 audio 활성화·nil participant 계약.
- [PTChannelManagerDelegate](https://developer.apple.com/documentation/pushtotalk/ptchannelmanagerdelegate), [PTChannelManager](https://developer.apple.com/documentation/pushtotalk/ptchannelmanager): 실제 callback·메서드 이름.
- [네이버 지도 URL Scheme](https://guide-fin.ncloud-docs.com/docs/maps-url-scheme): iOS appname=bundle ID·좌표·경로 요청 계약.
- [Keychain accessibility](https://developer.apple.com/documentation/security/ksecattraccessibleafterfirstunlockthisdeviceonly): 기기 전용 최초 잠금 해제 이후 접근 정책.
