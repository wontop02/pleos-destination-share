# PTT 등록과 APNs 전달 어댑터

`prototype/server.mjs`의 실제 회원 인증·원음 API에 연결된 서버 구현이다. Node 22 이상 표준 `http2`, `crypto`, `fs`만 사용한다. 기본 서버는 `not-configured`이며 APNs 연결이나 토큰 저장을 하지 않는다. 모의 HTTP/2 응답 `200`은 실제 APNs 전달이나 iPhone 배경 재생의 성공 증거가 아니다.

## 기본 실행과 검증

```sh
npm start
node --test prototype/test/ptt-http.test.mjs prototype/test/ptt-apns.test.mjs
npm test
```

`createPrototypeServer()`는 환경 변수를 읽지 않는다. 테스트나 임베딩에서 명시적으로 `pttProvider`를 주입해야 전달 기능을 구성할 수 있다. `npm start`의 CLI 진입점만 아래 환경 설정을 읽는다. 기본 바인딩은 기존과 같은 `127.0.0.1:4180`이다.

## 명시적 APNs 설정

| 환경 변수 | 값 |
|---|---|
| `PLEOS_APNS_TEAM_ID` | Apple Team ID, 대문자 영숫자 10자 |
| `PLEOS_APNS_KEY_ID` | APNs 키 ID, 대문자 영숫자 10자 |
| `PLEOS_APNS_BUNDLE_ID` | 앱의 원래 bundle ID. `.voip-ptt`는 어댑터가 붙인다. |
| `PLEOS_APNS_KEY_FILE` | 사용자가 지정한 APNs EC P-256 `.p8` 개인 키 파일 경로 |
| `PLEOS_APNS_ENVIRONMENT` | `sandbox` 또는 `production` |

다섯 값이 모두 없으면 disabled다. 하나라도 명시했으면 다섯 값이 모두 유효해야 시작한다. 빈 값, 잘못된 ID/환경, 미지원 `PLEOS_APNS_*` 변수, 읽을 수 없는 파일은 시작을 실패시킨다. `PLEOS_APNS_ENDPOINT`나 raw key 환경 변수는 지원하지 않는다. Keychain·홈 디렉터리·계정·키를 자동 탐색하지 않는다.

키 파일은 일반 파일, 최대 16 KiB, PEM 개인 키, EC `prime256v1`이어야 한다. 읽기도 16 KiB + 1까지만 수행하고 파싱 뒤 임시 바이트 버퍼를 지운다. 실제 운영 키는 저장소 밖 사설 파일로 관리해야 한다. 키 내용이나 JWT를 출력하는 진단 명령은 없다.

설정이 유효해도 시작·등록만으로 외부 연결을 만들지 않는다. 수신 등록이 있고 다른 회원의 새 WAV가 접수될 때 처음 연결한다. 목적지는 `https://api.sandbox.push.apple.com` 또는 `https://api.push.apple.com` 두 개뿐이다. TLS 1.2 이상과 정상 인증서 검증을 강제한다. 설정 여부는 로컬 구성이 유효하다는 의미이며 Apple 계정·capability·토큰 환경의 실제 유효성을 보장하지 않는다.

## HTTP 계약

모든 경로는 기존 공유방 회원의 `Authorization: Bearer <member token>`만 허용한다. 초대 secret, 다른 방 token, URL 쿼리 인증은 거절한다. POST/DELETE는 `Content-Type: application/json`이며, 객체의 지정 필드만 허용한다. 본문은 기존 JSON 상한인 4096바이트다. 본문을 읽은 뒤 방·회원 수명을 재인증한다.

| 요청 | 본문 | 결과 |
|---|---|---|
| `GET /api/rooms/:roomId/ptt` | 없음 | 현재 회원의 등록과 revision |
| `POST /api/rooms/:roomId/ptt` | `{channelUUID, token, expectedRevision}` | CAS 성공 시 교체 등록 |
| `DELETE /api/rooms/:roomId/ptt` | `{channelUUID, expectedRevision}` | CAS 성공 시 해제 |

`channelUUID`는 하이픈이 있는 `8-4-4-4-12` hex UUID다. 대소문자를 받아 소문자로 정규화한다. `token`은 공백·괄호·접두사 없는 짝수 길이 hex 문자열, 1–512바이트다. 32바이트라고 가정하지 않으며 소문자로 정규화한다. APNs 토큰은 HTTP 응답·push payload·로그·통계에 넣지 않는다.

성공은 `200`이며 응답은 다음과 같다. 시각은 Unix epoch 밀리초다.

```json
{
  "configured": true,
  "status": "registered",
  "revision": 1,
  "registration": {
    "id": "1234567890abcdef12345678",
    "channelUUID": "ab012345-6789-4abc-8def-0123456789ab",
    "expiresAt": 1800003600000
  },
  "serverTime": 1800000000000
}
```

`status`는 `registered`, `unregistered`, `not-configured` 중 하나다. 미등록과 미설정의 `registration`은 `null`이다. 기본 미설정 서버도 schema와 CAS를 적용하고 성공한 POST/DELETE마다 revision을 증가시킨다. 예를 들어 초기 GET `revision:0`, POST `expectedRevision:0` 성공 `revision:1`, DELETE `expectedRevision:1` 성공 `revision:2`다. 이때 모두 `configured:false, status:"not-configured", registration:null`이며 토큰은 저장하지 않는다. 클라이언트는 이 응답을 수신 가능 상태로 표시하면 안 된다.

revision은 회원별 독립적인 0 이상의 safe integer다. 성공한 POST/DELETE와 활성 등록의 lease 만료·APNs 무효화가 증가시킨다. GET은 lease를 연장하지 않는다. POST는 같은 채널/토큰이어도 새 registration ID와 lease를 발급한다. 회원당 활성 등록은 하나이며 정규화된 토큰은 서버 전체에서 다른 회원이 동시에 쓸 수 없다. 만료·해제·탈퇴·종료는 토큰 점유를 해제한다.

CAS 불일치는 `409 ptt_revision_conflict`이며 `code`, `error`와 **현재 응답 필드를 최상위에** 포함한다. 클라이언트는 GET으로 동기화한 뒤 현재 채널/사용자 의도를 확인해야 한다. 자동으로 최신 revision에 낡은 요청을 다시 적용하면 안 된다. 현재 등록과 다른 채널의 DELETE는 `409 ptt_channel_conflict`, 다른 회원의 토큰 점유는 `409 ptt_token_in_use`다. 미등록 DELETE도 revision을 증가시켜 아직 본문을 읽는 과거 POST를 차단한다. 다른 schema 오류는 `400 invalid_ptt_registration`이다.

기본 lease는 **1시간**, 방 만료보다 늦지 않다. 30분 무음 상태를 피하려고 짧게 해제하지 않는다. 시스템 채널 재참여·임시 토큰 변경·lease 갱신 때 클라이언트가 현재 revision으로 등록한다. `pttLimits.leaseMs`를 비롯한 주입 옵션은 결정적인 로컬 테스트에 쓰며, CLI에는 lease를 줄이는 환경 설정이 없다. lease 만료는 단말의 채널 상태를 추측하는 기능이 아니므로 클라이언트의 명시적 탈퇴/해제도 필요하다.

## 새 원음 전달과 수명

실제 WAV가 검증·저장되어 신규 `201`을 받는 경우에만 자기 자신을 제외한 해당 방의 활성 등록에 전달한다. 같은 `X-Client-Message-Id` 재시도 `200`, 다른 바이트의 충돌 `409`, 잘못된 WAV, 새 등록, 재접속, 탈퇴 이벤트는 과거 원음을 푸시하지 않는다. 기존 public voice metadata만 넣는다.

```text
{v:1, roomId, channelUUID, registrationId, message, serverTime, roomExpiresAt}
```

`message`는 기존 voice 응답과 같은 `id`, `sender`, `sequence`, `acceptedAt`, `autoPlayUntil`, `durationMs`, `byteLength`, `sampleCount`, `sampleRate`, `channels`, `format`이다. bearer, 다운로드 URL, 원음 바이트는 없다. 수신 앱은 현재 채널·registration ID·방 수명·자기 음성·중복·순서·30초 신선도를 검사한 뒤 기존 인증 API로 WAV를 받는다.

기본 동시 전달은 4개, 대기열은 64개, 개별 요청은 최대 5초, 큐 대기는 최대 10초다. 전달 기한은 원음의 `autoPlayUntil`, 방 만료, 등록 만료보다 늦을 수 없다. 상한 초과·기한 초과는 버리고 자동 재시도하지 않는다. 업로드의 `201`은 원음 접수만 의미한다. APNs의 `200`도 APNs 접수만 의미하며 단말 재생을 증명하지 않는다.

큐에서 꺼낼 때 방·수신자·발신자·등록 ID·lease·원음 신선도를 다시 확인한다. 등록 교체/해제, 발신자 또는 수신자 탈퇴, 방 종료/만료, 서버 종료는 관련 큐를 지우고 active 요청의 AbortSignal을 취소한다. 실제 provider는 HTTP/2 stream을 `NGHTTP2_CANCEL`로 종료한다. 이미 APNs에 접수된 요청을 회수할 수는 없으므로 단말의 검증도 필수다.

`410`, `400 BadDeviceToken`, `400 DeviceTokenNotForTopic`은 **그 요청의 등록 객체가 여전히 현재일 때만** 무효화하고 revision을 증가시킨다. 취소 후 늦게 도착한 과거 응답은 새 등록을 지우지 못한다. 인증 오류·일시 장애는 등록을 성공으로 간주하지 않으며 재전송하지 않는다. 현재 HTTP 상태 API는 전달 이력을 표시하지 않는다.

## APNs 전송 구현

provider는 ES256 JWT의 `kid`, `iss`(Team ID), `iat`(UTC 초)를 생성하고 `ieee-p1363`의 64바이트 `r || s` 서명을 base64url로 인코딩한다. JWT는 50분 재사용 후 다음 요청에서 갱신한다. HTTP/2 연결을 재사용하고 GOAWAY 연결은 폐기한다. 연결 수립과 요청 모두 기한을 두며, 응답 본문도 4096바이트로 제한한다. 서버 종료는 stream·session·JWT·개인 키 참조를 정리한다. 전송 오류는 토큰이나 socket 원문 없이 정해진 오류 코드만 반환한다.

전송은 `POST /3/device/<hex token>`, `apns-push-type: pushtotalk`, `apns-topic: <bundle>.voip-ptt`, `apns-priority: 10`, `apns-expiration: 0`이다. path와 authorization은 HTTP/2 민감 헤더로 표시한다. 압축하지 않은 JSON은 4096바이트 이하다.

주입 provider 계약은 `send({token, payload, signal, timeoutMs}) → Promise<{status, reason?}>`, `close()`다. provider는 AbortSignal을 준수해야 한다. 테스트의 늦은 응답 mock은 일부러 취소를 무시하여 서버의 등록 ID 보호를 검증한다. `app.pttStats()`는 설정 여부와 등록·큐·active **개수만** 반환하며 기존 `app.stats()` 형식은 유지한다.

## 검증 근거와 보류

`ptt-http.test.mjs`는 실제 loopback HTTP 서버로 인증·CAS·본문 경합·토큰 상한·경로 격리·신규 WAV/중복·자기 제외·큐/timeout·신선도·교체/탈퇴/종료/만료·30분 무음 뒤 lease를 검사한다. 기본 factory 테스트는 APNs connector를 실패하도록 계측하고 명시적 환경 오염에서도 연결 0회를 확인한다.

`ptt-apns.test.mjs`는 생성한 임시 EC 키와 명시적 mock transport만 쓴다. 로컬 HTTP/2 peer에 실제 Node stream을 연결하여 path·헤더·payload·JWT 서명·응답 제한·GOAWAY·timeout·abort·close를 검사한다. 서버→provider 연결도 같은 mock transport로 검사한다. Apple 호스트로 연결하지 않는다.

실제 APNs 요청·iPhone 잠금/내비 중 수신·30분 무음 뒤 배경 원음 재생은 **미실행/BLOCKED**다. Apple 개발 계정, 실제 앱 bundle/capability와 PTT 권한, 명시적 APNs 키, Xcode 전체 SDK 빌드·서명, 연결된 iPhone과 별도 송신 참여자가 필요하다. 이를 준비한 뒤 명시적 설정으로 등록→신규 WAV→실제 APNs 응답→PTT callback→`didActivate` 뒤 재생을 별도 증거로 기록해야 한다. 현재 모의 테스트를 그 증거로 대체하지 않는다.

공식 문서는 2026-11-04에 확인했다.

- [Creating a Push to Talk app](https://developer.apple.com/documentation/pushtotalk/creating-a-push-to-talk-app): 임시 가변 토큰과 PTT 전용 헤더·수신 수명.
- [Sending notification requests to APNs](https://developer.apple.com/documentation/usernotifications/sending-notification-requests-to-apns): 환경별 endpoint, HTTP/2/TLS, path·헤더·payload 상한.
- [Establishing a token-based connection to APNs](https://developer.apple.com/documentation/usernotifications/establishing-a-token-based-connection-to-apns): ES256, kid/team/iat와 JWT 갱신.
- [Handling notification responses from APNs](https://developer.apple.com/documentation/usernotifications/handling-notification-responses-from-apns): 410과 무효 토큰 응답.
- [Node HTTP/2](https://nodejs.org/docs/latest-v22.x/api/http2.html), [Node crypto](https://nodejs.org/docs/latest-v22.x/api/crypto.html): stream 취소/종료와 P1363 서명.
