# 공유방 웹 프로토타입과 원음 API

Node 표준 라이브러리로 실행하는 로컬 제품 시연이다. 공유방·목적지·실제 WAV 바이트 전달을 구현한다. 계정, 데이터베이스, 외부 스크립트, 음성 합성, 공개 배포는 사용하지 않는다.

## 실행

저장소 루트에서 Node 22 이상으로 실행한다. 패키지 설치는 필요 없다.

```sh
npm start
# http://127.0.0.1:4180
npm test
npm run test:prototype
```

`PORT=4181 npm start`로 포트를 바꿀 수 있다. CLI는 항상 `127.0.0.1`에만 바인딩한다. 상위 폴더의 기존 `../prototype`과 4173 서버는 변경하지 않는다. 서버 종료는 `Ctrl+C`다.

1. 첫 브라우저에서 이름을 입력해 공유방을 만든다. 대표 차량이 초대 링크를 복사한다.
2. 별도 탭 또는 독립 브라우저에서 초대 링크를 연다. 이름을 입력해 참여한다. 초대 fragment는 읽은 즉시 주소 표시줄에서 지운다.
3. 대표 차량이 목적지를 변경한다. 참여자 화면에서 받은 장소명·좌표를 확인한다.
4. 각 참여자가 마이크를 허용하고 1–15초 녹음한다. `중지하고 보내기` 또는 15초 자동 종료 후 서버에 전송한다. 받은 목록에서 직접 재생하거나, `자동 재생 켜기` 후 도착하는 새 원음을 듣는다.
5. 대표 차량이 종료하면 연결·권한·보관한 원음을 제거한다. 참여자는 자신만 나갈 수 있다.

마이크는 localhost/loopback 또는 신뢰된 HTTPS의 보안 컨텍스트가 필요하다. 일반 LAN의 `http://192.168…`로 마이크를 쓸 수 있다고 보장하지 않는다. 현재 서버는 LAN에 열지 않는다. 정차 또는 동승자 조작용이다.

브라우저 자격 증명은 탭의 `sessionStorage`에 보관해 새로고침 시 재접속한다. 새 탭에서 같은 공유방 초대 링크로 참여하면 별도 참여자 토큰을 받는다. `sessionStorage`가 차단되면 현재 페이지 메모리에서만 동작한다. 자동 재생 선택은 저장하지 않는다.

## HTTP 계약 v1

기본 주소는 `http://127.0.0.1:4180`이다. 네이티브 후속 구현도 아래 같은 JSON·WAV 계약을 사용한다. TLS, 사용자 계정, 기기 푸시, 네이티브 서비스는 이 로컬 서버 계약의 구현 범위 밖이다.

모든 인증은 `Authorization: Bearer <secret>` 헤더만 사용한다. 쿼리 인증은 거절한다. 대표·참여자 access token과 초대용 `inviteSecret`은 서로 다른 32바이트 난수의 base64url 문자열(43자)이다. 토큰을 URL, 로그, 분석 이벤트에 넣지 않는다. 서버는 요청이나 토큰을 로깅하지 않는다.

초대 링크 형식은 `http://127.0.0.1:4180/#join=<roomId>.<inviteSecret>`이다. fragment는 서버 HTTP 요청에 전송되지 않는다. 초대 secret은 참여자 토큰을 발급하는 권한만 가지며 방 조회·음성 다운로드에는 사용할 수 없다. 링크를 아는 사람은 참여할 수 있으므로 신뢰하는 사람에게만 전달한다. access token은 링크에 포함하지 않는다.

JSON 요청은 `Content-Type: application/json`을 사용한다. JSON 객체의 필드는 아래에 지정한 것만 허용한다. 모든 시각은 Unix epoch 밀리초 숫자다. ID는 소문자 16진수 24자다. `sequence`는 방 전체 이벤트 순서, `revision`은 목적지 변경 순서다.

| 요청 | 권한 | 본문·결과 |
|---|---|---|
| `POST /api/rooms` | 없음 | `{ "name": "앞차 민수" }` → `201`, 대표 자격 증명과 snapshot |
| `POST /api/rooms/:roomId/members` | 초대 secret | `{ "name": "뒷차 지수" }` → `201`, 새 참여자 자격 증명과 snapshot |
| `GET /api/rooms/:roomId` | 해당 방 참여자 | `200`, 최신 snapshot |
| `GET /api/rooms/:roomId/events` | 해당 방 참여자 | `200 text/event-stream`, 아래 이벤트 계약 |
| `POST /api/rooms/:roomId/destination` | 대표 차량 | 장소명·위도·경도 → `200`, 최신 목적지 update |
| `POST /api/rooms/:roomId/voice` | 해당 방 참여자 | 원본 WAV 바이트와 `X-Client-Message-Id` → 신규 `201`, 동일 재시도 `200` |
| `GET /api/rooms/:roomId/voice/:messageId/audio` | 해당 방 참여자 | `200 audio/wav`, 전송한 바이트 그대로 |
| `DELETE /api/rooms/:roomId/members/me` | 일반 참여자 | `200 { "left": true }`, 자신의 토큰·이벤트 연결 무효화 |
| `DELETE /api/rooms/:roomId` | 대표 차량 | `200 { "closed": true }`, 공유방·모든 자격 증명·원음 제거 |

다른 방의 토큰이나 일반 참여자의 목적지 변경·공유방 종료는 `403`이다. 대표 차량은 `/members/me`로 나가는 대신 공유방을 종료해야 한다. 일반 참여자가 나가도 이미 공유한 원음은 남은 참여자가 만료·종료까지 재생할 수 있다.

### 생성·참여 응답과 snapshot

```json
{
  "roomId": "<24 hex>",
  "memberId": "<24 hex>",
  "role": "host",
  "token": "<43 base64url>",
  "inviteSecret": "<43 base64url; creation only>",
  "state": {
    "room": { "id": "<24 hex>", "createdAt": 1800000000000, "expiresAt": 1800021600000 },
    "self": { "id": "<24 hex>", "name": "앞차 민수", "role": "host" },
    "members": [{ "id": "<24 hex>", "name": "앞차 민수", "role": "host" }],
    "destination": null,
    "revision": 0,
    "sequence": 0,
    "voiceMessages": [],
    "serverTime": 1800000000000
  }
}
```

참여 응답은 `role: "member"`이며 `inviteSecret` 필드가 없다. 조회와 SSE snapshot은 `state` 안의 객체를 직접 반환하며 자격 증명을 포함하지 않는다. 이름은 공백을 제거한 1–40자, 목적지 이름은 1–100자다. 제어 문자와 빈 이름은 거절한다.

목적지 요청 예:

```json
{ "name": "가상 쉼터 & 동행", "latitude": 37.5209436, "longitude": 127.1230074 }
```

위도·경도는 유한한 JSON 숫자여야 하며 각각 `[-90, 90]`, `[-180, 180]` 범위다. 목적지 update는 `{ destination: { name, latitude, longitude, changedAt, changedBy }, revision, sequence, serverTime }`이다. `changedBy`는 대표 참여자 ID다. 웹의 네이버 지도 링크는 [공식 URL Scheme](https://guide-fin.ncloud-docs.com/docs/maps-url-scheme)의 `nmap://navigation`에 좌표·URL 인코딩한 이름·웹 주소 `appname`을 전달한다. 공식 좌표 범위(위도 31.43–44.35, 경도 122.37–132.00) 밖에는 링크를 제공하지 않는다. 이는 경로 요청이며 앱 실행·실제 안내 성공 응답은 아니다. 앱이 없으면 화면 안내에 따라 별도로 설치해야 한다. 자동 스토어 이동은 하지 않는다.

### 원음 바이트와 재시도

업로드는 JSON/base64나 multipart가 아닌 **WAV 파일 바이트 그대로** 전송한다.

```http
POST /api/rooms/<roomId>/voice
Authorization: Bearer <member access token>
Content-Type: audio/wav
X-Client-Message-Id: <unique ID retained across retries>

<RIFF WAVE bytes>
```

`X-Client-Message-Id`는 참여자가 만든 16–80자의 `[A-Za-z0-9_-]` 문자열이다. 웹은 `crypto.randomUUID()`를 쓴다. 동일 참여자·동일 ID·동일 파일 SHA-256이면 원래 message를 반환하고 새 이벤트·보관량·분당 수를 추가하지 않는다. 동일 ID에 다른 바이트는 `409 message_id_conflict`다. ID의 범위는 참여자별이다. 네트워크 응답 유실 후 재시도에는 같은 ID와 같은 바이트를 유지한다.

서버는 `RIFF`/`WAVE`, 정확한 RIFF 크기, chunk 경계·홀수 padding, 단일 `fmt `와 `data`, PCM 형식 1, 단일 채널, 16000Hz, 16bit, blockAlign 2, byteRate 32000을 검사한다. `fmt `는 16바이트 또는 extension 크기가 0인 18바이트만 받는다. 알 수 없는 chunk는 전체 헤더 상한 안에서 허용한다. 데이터는 짝수 길이이며 16000–240000개의 표본, 즉 1–15초여야 한다. MIME, 파일 이름, 클라이언트의 길이 주장은 검증 근거로 사용하지 않는다.

응답은 `{ "message": <아래 metadata>, "duplicate": false, "serverTime": <ms> }`다. 재시도는 `duplicate: true`이며 message의 `acceptedAt`·`autoPlayUntil`을 갱신하지 않는다.

```json
{
  "id": "<24 hex>",
  "sender": { "id": "<24 hex>", "name": "앞차 민수" },
  "sequence": 3,
  "acceptedAt": 1800000000000,
  "autoPlayUntil": 1800000030000,
  "durationMs": 1000,
  "byteLength": 32044,
  "sampleCount": 16000,
  "sampleRate": 16000,
  "channels": 1,
  "format": "pcm_s16le"
}
```

`byteLength`는 WAV 컨테이너 전체 길이다. 다운로드는 해당 방 bearer 인증이 필수이며 HTTP Range·공개 미디어 URL은 제공하지 않는다. 자동 재생 유효기간이 지난 음성도 방이 살아 있는 동안 수동 다운로드·재생할 수 있다. 종료·만료 후에는 `404`이며 메모리에 보관한 바이트를 지운다. 이미 기기에 다운로드한 바이트나 이미 들은 소리는 회수할 수 없다.

### 이벤트·재연결

브라우저는 access token을 URL로 넘기는 `EventSource` 대신 `fetch` + `Authorization` + `ReadableStream`으로 수신한다. 각 이벤트는 다음 SSE 프레임이다.

```text
id: 3
event: voice
data: {"message":{...},"sequence":3,"serverTime":1800000000000}

```

| 이벤트 | 데이터 |
|---|---|
| `snapshot` | 연결마다 현재 snapshot 전체. 목적지 최신값·보관한 원음 metadata 포함 |
| `destination` | `{ destination, revision, sequence, serverTime }` |
| `members` | `{ members, sequence, serverTime }` |
| `voice` | `{ message, sequence, serverTime }` |
| `closed` | `{ reason, sequence, serverTime }`; reason은 `closed`, `expired`, `left`, `server_stopped` 중 하나 |

주석 heartbeat는 15초마다 보낸다. 원음 이벤트는 발신자에게도 전달하므로 클라이언트가 `sender.id === self.id`를 자동 재생에서 제외해야 한다. 서버는 연결별로 같은 순서로 이벤트를 쓴다. 느린 연결은 보관 큐가 무한히 늘어나기 전에 끊고 클라이언트가 다시 snapshot을 받게 한다.

이 서버는 이벤트 로그 재생이나 `Last-Event-ID` 이후 증분 replay를 제공하지 않는다. 연결이 끊기면 최대 1–10초 간격으로 재연결하고 snapshot을 병합한다. 원음 ID로 중복을 막고 목적지는 `revision`이 큰 값만 적용한다. 닫힘·만료·권한 오류에는 재시도를 중지한다. 전송하지 못한 녹음을 재연결 때문에 자동 재전송하지 않는다.

### 열린 화면 자동 재생

기본값은 꺼짐이다. 사용자가 켠 시점 이전에 본 목록은 나중에 다시 자동 재생하지 않는다. 새 원음은 발신자 자신을 제외하고 `sequence` 순으로 한 개씩 처리한다. enqueue 전과 다운로드·decode 후 시작 직전에 서버가 정한 `acceptedAt ≤ now < autoPlayUntil`을 검사한다. 메시지별 30초 창을 늘리지 않는다. 시각 차이의 영향을 줄이기 위해 서버 시각과 요청 왕복 시간을 이용한 보수적 시계 추정, 이후 monotonic 경과 시간을 사용한다. 통신 지연만큼 일찍 만료될 수 있다.

해제·재생 중지·수동 재생 선택 시 현재 source, 다운로드와 대기열을 취소한다. 화면이 숨겨지면 자동 재생을 끄고 녹음을 취소·폐기한다. 돌아와도 직접 켜야 한다. 마이크·재생 미지원, 권한 거절, 오디오 중단은 실패·일시정지로 표시한다. 네트워크에 접수된 원음을 상대 기기가 실제로 재생했다고 표시하지 않는다.

녹음은 AudioWorklet이 최대 2048표본씩 전송하고 입력 표본 수와 15초 타이머를 모두 제한한다. mono로 합쳐 16kHz로 변환한 뒤 PCM16 WAV를 만든다. 중지·취소·나가기·방 교체 시 track과 AudioContext를 정리한다. 전송 실패 원음은 최대 30초만 메모리에 남기며 같은 ID로 **사용자가 직접** 재시도한다. 취소·화면 숨김·방 교체 시 폐기한다.

## 제한·오류·수명주기

| 자원 | 기본 상한·정책 |
|---|---|
| 공유방 | 동시에 100개, 만든 뒤 6시간 만료, 활동으로 연장하지 않음 |
| 참여자 | 방마다 대표 포함 20명 |
| 원음 업로드 | 참여자마다 rolling 60초에 10개; 동일 바이트 재시도 무료 |
| 원음 길이·요청 | 1–15초, data 최대 480000바이트, 전체 WAV 최대 484096바이트, data 외 metadata 최대 4096바이트 |
| 원음 보관 | 방마다 100개, 전체 방의 WAV 바이트 합계 32MiB. 상한 이후 새 업로드 거절; 기존 기록을 몰래 덮어쓰지 않음 |
| 처리 중 업로드 | 동시에 최대 8개, 각 요청의 입력 버퍼도 위 WAV 크기로 제한 |
| 이벤트 연결 | 참여자마다 최대 2개 |
| JSON | 최대 4096바이트 |

오류 응답은 `{ "code": "machine_readable_code", "error": "한국어 설명" }`이다.

| 상태 | 대표 코드·의미 |
|---|---|
| `400` | `invalid_name`, `invalid_destination`, `invalid_json`, `invalid_message_id`, `query_not_supported` |
| `401` | `authentication_required`: bearer 없음·형식 오류 |
| `403` | `forbidden`, `invalid_invite`, `host_required`, `host_must_close`, `cross_origin`, `invalid_host` |
| `404` | `room_gone`, `voice_not_found`, `not_found` |
| `405` | `method_not_allowed` |
| `409` | `message_id_conflict` |
| `413` | `payload_too_large` |
| `415` | `json_required`, `wav_required` |
| `422` | `invalid_wav` |
| `429` | `voice_rate_limit` (`Retry-After` 초), `room_limit`, `member_limit`, `stream_limit`, `upload_concurrency_limit` |
| `507` | `history_limit`, `audio_storage_limit` |

변경 요청의 `Origin`이 있으면 현재 로컬 서버 origin과 같아야 한다. 브라우저의 다른 사이트·같은 사이트 다른 origin 요청을 거절하며 CORS를 열지 않는다. 네이티브 HTTP 클라이언트는 Origin 없이 bearer를 사용할 수 있다. Host는 loopback 이름·주소만 받는다. HTML은 사용자 입력을 `textContent`·폼 value로만 다루며 access token을 HTML로 주입하지 않는다. CSP·no-store·no-referrer·nosniff를 설정한다.

`createPrototypeServer({ clock, ttlMs, cleanupIntervalMs, limits })`는 `{ server, close, cleanupExpired, stats }`를 반환한다. 기본 시계는 `Date.now`, 만료 sweep은 30초마다 실행하며 timer는 unref한다. 요청 전에도 만료를 정리한다. `close()`는 이벤트·연결·timer·방·보관 바이트를 정리하는 await 가능한 종료 함수다. `stats()`는 방·참여자·메시지·연결 수와 보관 바이트 합계만 반환하며 비밀값·오디오를 노출하지 않는다. 테스트는 임시 포트와 주입 시계로 외부 네트워크 없이 실행한다.

메모리와 bearer로 구현한 로컬 프로토타입이다. 재시작 시 모든 상태가 사라진다. 저장·전송의 종단 간 암호화를 제공하지 않으며 운영 서비스 수준의 인증·보안·신뢰성을 검증한 제품이 아니다.

## 검증 범위

`npm test`는 기존 하네스 32개와 제품의 실제 HTTP/SSE 통합 테스트 및 WAV·queue·parser·오디오 수명주기 테스트를 함께 실행한다. 생성한 가상 PCM 신호로 바이트 전달·오디오 처리 코드를 검사한다. 단위 테스트의 브라우저 오디오 객체는 명시적 테스트 대역이며 실마이크 성공 증거가 아니다.

실제 브라우저의 독립 참여·목적지·반응형 화면·합성 마이크/오류 시나리오 증거는 `prototype/evidence/`에 별도 기록한다. 실제 사람의 마이크 녹음, iPhone Safari, Android 실기기, 차량 내비, 화면 잠금·앱 전환 중 수신·첫 소리는 별도 확인 항목이다. 웹 전경 재생은 잠금 수신을 통과한 증거가 아니다. 후속 iPhone PushToTalk/APNs와 Android 네이티브 수신 서비스 구현·실기기 검증이 필요하다.
