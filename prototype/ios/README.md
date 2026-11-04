# 함께 가는 길 · iPhone 네이티브 프로토타입

SwiftUI·AVFoundation·Keychain·PushToTalk 소스와 Foundation 공통 코어다. 초기 기준은 iOS 16 이상이며 모든 구형 iPhone 지원이나 실제 잠금 수신 완료를 뜻하지 않는다. 기존 서버의 [공유방·원음 API](../README.md)와 [PTT 등록·전달 계약](../ptt/README.md)을 사용한다.

**현재 상태:** Foundation 실행형 검증 19개는 실행할 수 있다. 전체 iOS SDK 컴파일, XCTest 실행, 서명·PTT capability·APNs·iPhone 실증은 필요한 자원 부족으로 BLOCKED다. `-frontend -parse`와 plist 검사는 iOS 앱 typecheck·빌드 성공이 아니다.

## 파일과 재현 명령

| 경로 | 역할 |
|---|---|
| `TogetherRoad.xcodeproj`, `Config/` | iOS 16 target·shared scheme·Debug/Release Info.plist·PTT/APNs entitlement 선언 |
| `App/TogetherRoadApp.swift`, `ContentView.swift` | 앱 시작 시 모델·PTChannelManager 생성, 한국어 화면과 scene 수명 |
| `App/RoomStore.swift` | 공유방·목적지·원음·등록 CAS·재연결·수신 상태 연결 |
| `App/NativeAudio.swift`, `KeychainStore.swift`, `PTTBridge.swift` | AVFoundation·앱 전용 Keychain·실제 시스템 PTT delegate |
| `Core/` | SwiftPM RoadCore, 동일 시나리오의 실행형 검사와 XCTest wrapper |

저장소 루트에서 실행한다. Swift 6 toolchain과 Node 22 이상이 필요하며 외부 Swift 패키지는 설치하지 않는다.

```sh
swift run --package-path prototype/ios/Core \
  --scratch-path /private/tmp/pleos-ios-swift-build-20261104 RoadCoreChecks
```

`RoadCoreChecks`는 Foundation과 실제 임시 Node 서버를 쓰는 검증 실행 파일이다. 19개 시나리오 중 하나라도 실패하면 오류를 출력하고 exit 1로 끝난다. 빌드 최적화로 사라지는 `assert`를 사용하지 않는다. SwiftPM 공통 시나리오를 XCTest wrapper도 호출하므로 전체 Xcode가 있는 환경에서는 같은 검사를 다음 명령으로 실행할 수 있다.

```sh
swift test --package-path prototype/ios/Core
```

현재 CLT에서는 `no such module 'XCTest'`로 exit 1이다. `import Testing`도 제공되지 않는다. 실행형 19/19와 XCTest BLOCKED는 별도 결과다.

전체 Xcode와 iOS SDK가 준비된 뒤의 unsigned simulator 빌드 명령은 다음과 같다. 현재 환경에서는 실행했으나 Xcode가 없다는 오류로 exit 1이다.

```sh
xcodebuild -project prototype/ios/TogetherRoad.xcodeproj -scheme TogetherRoad \
  -configuration Debug -sdk iphonesimulator \
  -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO build
```

프로젝트는 Manual signing·`CODE_SIGNING_ALLOWED=NO`를 기본으로 두며 팀·개인 키·프로필을 지정하거나 자동 탐색하지 않는다. 실제 iPhone 설치에는 사용자가 제공하는 정상 서명·PTT와 Push Notifications capability가 필요하다. bundle ID는 `kr.together.road.ios`, APNs topic은 이 bundle ID 뒤의 `.voip-ptt`이며 Debug/Release의 APNs 환경을 서버 설정과 맞춰야 한다. entitlement 선언만으로 권한을 부여받은 것은 아니다.

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

## PushToTalk 수명주기

앱 delegate가 시작할 때 PTChannelManager를 만든다. Keychain은 `afterFirstUnlockThisDeviceOnly`, iCloud 동기화 없음, shared access group 없음, 생체 인증 요구 없음으로 앱 전용 세션·채널 UUID/descriptor·등록 ID·수신 선택·중복/순서 정보를 보관한다. 따라서 최초 잠금 해제 이후 잠금 중 시스템 재시작에서 읽을 수 있는 정책이다. 임시 push token Data 자체는 디스크에 저장하지 않고 채널 수명 동안만 메모리에 둔다.

restoration delegate는 캐시 descriptor를 동기적으로 즉시 반환하며 네트워크·Keychain·메인 큐 대기를 하지 않는다. 시스템의 실제 activeChannelUUID와 캐시된 사용자 선택·방/lease·회원·등록 정보를 확인한 기존 채널만 복원한다. 새로운 room epoch를 부여하고 오래된 callback은 거절한다. 캐시 불일치, 최초 잠금 해제 전 Keychain 접근 불가, uptime이 초기화된 재부팅 등 확인 불가능한 상태는 수신 준비 완료로 표시하지 않고 채널을 떠난다. 새 채널 join은 전경의 사용자 조작에서만 한다.

채널 참여가 확인되고 listenOnly 설정이 성공한 뒤에만 임시 token을 서버에 등록한다. token은 1–512바이트의 가변 길이를 lowercase hex로 변환하며 고정 32바이트를 가정하지 않는다. POST/DELETE는 회원별 expectedRevision CAS다. 409에는 GET로 동기화한다. 늦은 POST 정리는 그 응답 registration ID와 현재 ID가 모두 같을 때만 삭제하며, 이전 leave의 늦은 조회도 새 join intent를 지우지 않는다. `not-configured`는 APNs 전달이나 배경 수신 성공이 아니다.

`incomingPushResult`는 최대 4096바이트 payload와 잠금으로 보호한 캐시만 검사하고 즉시 실제 PTPushResult를 반환한다. room/channel/registration ID·lease·방 수명·version·30초·자기 음성·중복·순서를 검사한다. 거절에는 문서에 존재하는 `.leaveChannel`을 사용한다. `.ignore` 같은 결과는 만들지 않는다. malformed·stale·중복·이전 registration push 모두 시스템 채널 탈퇴와 사용자 재시작으로 이어질 수 있다. 오래된 원음 한 개만 무시하고 계속 수신하는 정책이 아니며 실제 수신 연속성을 검증한 결과도 아니다. 실제 SDK callback과 iPhone에서 이 정책의 영향을 검증해야 한다.

유효 push는 active participant를 반환하고 `didActivate` 이후에만 다운로드한 원음을 재생한다. 이미 시스템 오디오가 활성인 동안의 새 push는 매번 didActivate가 다시 오리라 가정하지 않고 활성 상태에서 큐를 처리한다. 같은 채널의 token rotation은 활성 상태를 보존하고 이전 바인딩의 pending/epoch를 폐기한다. 재생 직전에 신선도·방/lease·등록·epoch를 다시 확인하며, 뒤늦게 도착한 더 낮은 sequence는 역순 재생하지 않는다. 대기열은 최대 16개다. 완료·실패·중지 시 active remote participant를 nil로 정리하며, scene의 timer가 잠금 중 실행된다고 가정하지 않는다. 만료는 push 진입·다운로드 후·재생 직전·전경 복귀에도 검사한다.

## 검증 상태와 재개 조건

실행 증거는 `evidence/verification.json`과 `evidence/core-checks.log`에 기록한다. 가상 PCM 표본과 actor 지연 응답은 프로토콜·경합 검사이며 실마이크나 실제 APNs 성공 증거가 아니다.

| 항목 | 현재 결과 | 재개·추가 증거 |
|---|---|---|
| Swift Foundation 공통 모듈·실행형 검사 | 19/19 PASS | 실제 HTTP/SSE·WAV 바이트·CAS·취소·순서/신선도/회전 경합 |
| XCTest wrapper 실행 | BLOCKED | 현재 CLT에 XCTest/Testing 모듈 없음. 전체 Xcode에서 동일 시나리오 실행 |
| App source parse·plist/PBX/scheme 구조 | 정적 검사만 | iOS SDK typecheck가 아님 |
| 전체 iOS 앱·simulator 빌드·화면 | BLOCKED | 현재 macOS 26.4 호환 전체 Xcode 26.6 등과 iOS SDK |
| 실제 마이크·원음 재생·PTT delegate | BLOCKED | 서명 설치한 iPhone에서 실제 입력·권한·중단·완료 관찰 |
| Apple 서명·PTT capability·APNs | BLOCKED | 사용자가 제공하는 정상 팀·capability·프로필·APNs 자격 증명 |
| iPhone 내비 앱 사용·잠금 중 첫 소리 | BLOCKED · 필수 제품 게이트 | 모델/iOS/내비 버전, 실제 수신·첫 소리 시각과 청취 증거 |
| 15분/30분 무음 뒤 잠금 첫 소리 | BLOCKED · 필수 제품 게이트 | 두 조건을 각각 실제 기기로 실행·실패/성공 구분 |

현재 `xcode-select -p`는 `/Library/Developer/CommandLineTools`, macOS는 26.4, Swift CLI는 6.3이다. [Apple 시스템 요구 표](https://developer.apple.com/xcode/system-requirements/)상 Xcode 26.6은 macOS 26.2–26.x를 지원하므로 OS 업그레이드가 반드시 필요한 것은 아니다. App Store의 최신 Xcode 27 계열은 별도 호스트 요구가 있으므로 구분한다. 호환 전체 Xcode가 준비되면 `DEVELOPER_DIR`을 그 앱으로 프로세스별 지정해 위 명령을 재실행할 수 있다. 이 작업에서 OS 업그레이드, 계정 가입, 약관 동의, 서명 정보·프로필 탐색은 수행하지 않았다.

구현·빌드 차단 상태를 모든 iPhone의 배경 원음 수신 통과나 앱 전체 완료로 바꾸어 표시하지 않는다. 실제 iOS SDK에서 발견되는 컴파일/API 차이는 수정 후 같은 검사와 별도 verifier의 정확한 base/head 검토를 다시 수행해야 한다.

## 공식 자료

- [Creating a Push to Talk app](https://developer.apple.com/documentation/pushtotalk/creating-a-push-to-talk-app): 초기 manager 생성, 사용자 join, listenOnly, 가변 토큰, 즉시 push result, 시스템 audio 활성화·nil participant 계약.
- [PTChannelManagerDelegate](https://developer.apple.com/documentation/pushtotalk/ptchannelmanagerdelegate), [PTChannelManager](https://developer.apple.com/documentation/pushtotalk/ptchannelmanager): 실제 callback·메서드 이름.
- [네이버 지도 URL Scheme](https://guide-fin.ncloud-docs.com/docs/maps-url-scheme): iOS appname=bundle ID·좌표·경로 요청 계약.
- [Keychain accessibility](https://developer.apple.com/documentation/security/ksecattraccessibleafterfirstunlockthisdeviceonly): 기기 전용 최초 잠금 해제 이후 접근 정책.
