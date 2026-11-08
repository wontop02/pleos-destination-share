# 차량 음성 제안 초안 (#25)

기준은 #23/PR #24가 병합된 main `8b6321276f33923f24f15a8437205b6126bf92ca`다. 짧은 말을 수정 가능한 목적지 변경 초안으로 정리하고 기존 저장 장소 선택·명시 제안·전원 수락 흐름에 연결한다. 원음 전송/재생, 통화, 휴대폰, 자유 지도 검색과 지도 공백 #16은 포함하지 않는다. 정차 중이거나 동승자가 조작하는 프로토타입이다.

## 사용 흐름

`목적지 바꾸기 → 말로 작성 → 들은 말 확인/수정 → 초안 정리 → 후보 확인 → 이름·주소와 이유 확인 → 제안 보내기` 순서다. 기존 `저장한 장소 선택`과 예제 선택은 유지한다. 인식이 안 되거나 잘못 들으면 들은 말을 직접 고치거나 수동으로 장소를 선택할 수 있다. `말하기 끝`은 마이크 입력을 끝내며 `음성 초안 지우기`는 해당 초안과 음성으로 고른 선택을 버린다.

초안 정리는 로컬의 보수적인 규칙 처리다. 생성형 AI, 원격 LLM이나 장소 검색 엔진을 사용하지 않는다. `가자`, `들르자`, `어때`, `가고 싶어`, `들르고 싶어` 등 지원하는 명시적인 제안·희망 문장 끝과 카페·식당·주유소·휴게소 단서를 추출한다. 동사 일부가 문장 안에 있다는 이유만으로 변경 의도로 판정하지 않는다. `안 돼/안 됩니다`, `않아/않아요`, `못 가/들르지 못해`, 원하지 않음·불가·금지를 먼저 차단하고 이유와 후보를 비운다. 갈 수 없음·변경할 의향 없음·아님·모름·어려움·제외와 미완성/인용 등 명확하지 않은 표현도 긍정을 추측하지 않고 수동 확인으로 보낸다. 서로 다른 종류의 복수 단서와 대안도 직접 확인하도록 안내한다. 이는 한정된 형태/문장 끝 규칙이며 모든 부정 범위·이중 부정·인용·방언을 해석하는 한국어 이해기가 아니다. 이름과 부정/불확실 단어가 겹치거나 지원하지 않는 문장 끝이면 유효한 말을 보류할 수 있다. 이런 경우 인식문을 고치거나 실제 장소를 직접 선택한다.

현재 불러온 순정 ☆ 저장 장소의 정확한 이름 또는 이름에 실제로 들어 있는 종류 단어만 매칭한다. 북마크에는 일반 장소의 신뢰할 수 있는 업종 정보가 없으므로 `가든서비스`를 카페로 추측하지 않는다. 같은 이름의 여러 장소는 주소로 직접 골라야 한다. 현재 snapshot이 없거나 맞는 이름이 없으면 목록 불러오기·수동 선택을 안내한다. 후보의 이름·주소·POI ID·provider·nullable sub-ID·좌표는 원본 객체를 그대로 사용한다. 분석기가 새 ID나 좌표를 만들지 않는다.

이유 초안은 말에 포함된 종류 단서에서 짧은 고정 문구로 정리한다. 사용자가 전송 전에 수정할 수 있고, 알 수 없는 이유는 비워 둔다. 원래 발화 전체를 이유로 자동 복사하지 않는다. 이름이나 주소를 고른 것만으로 제안을 보내지 않는다. 기존 `제안 보내기`와 다른 회원의 명시 수락이 필요하다.

## SDK와 처리 위치

2026-11-08 공식 문서와 Maven AAR의 공개 선언을 확인했다. `ai.pleos.playground:SpeechToText:3.3.1`의 `SpeechToText(applicationContext, Mode.ON_DEVICE)`를 명시 사용하고 `initialize → addListener → setLocale(Locale.KOREA) → request` 순서로 차량 마이크를 요청한다. 실제 `onReady` 또는 `onStartedRecognition`을 받은 뒤 듣는 중으로 표시하며, 중복 준비 신호로 기한을 늘리지 않는다. `request()` 반환은 듣기 준비 완료가 아니다. 준비 전 결과는 무시하고 `completed=true` 결과만 확정 인식문으로 사용한다. 중간 인식문은 듣는 중 표시이며 확정 초안으로 승격하지 않는다. 종료 이벤트만 오면 짧게 확정 결과를 기다리고 없으면 실패한다.

공식 계약상 ON_DEVICE는 차량 내 엔진으로 처리하며 네트워크나 앱 등록이 필요 없다. 기본값 HYBRID는 서버 우선이고 앱 등록을 요구하므로 쓰지 않는다. 이 구현은 앱 등록·키 발급·유료 API·새 외부 서버를 필요로 하지 않으며 비용이 발생하는 서비스를 활성화하지 않는다. 공개 `pleos.car.permission.STT_SERVICE`를 manifest에 선언하고, 권한이나 엔진 실패는 사용자에게 표시한다. 별도 권한 부여나 특권 우회는 하지 않는다. AAR manifest의 CAAS 최소 버전은 4.1.29/4001029다. 실제 이미지 호환성과 시스템 권한 부여 상태는 실행 증거에서 확인한다.

- [SDK 모드와 ON_DEVICE](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/SpeechToText/intro)
- [공식 의존성](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/SpeechToText/importing-sdk), [권한](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/SpeechToText/declaring-permissions)
- [초기화/해제](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/SpeechToText/initialization-and-release), [결과 listener](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/SpeechToText/result-listeners), [한국어 locale](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/SpeechToText/set-locale)
- [차량 마이크 request/stop](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/SpeechToText/start-and-stop), [오류](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/SpeechToText/error-code)

Gleo Interface의 action schema/Android intent는 별도의 앱 기능 호출 통합 경로다. 이번처럼 전경 입력을 검토하고 기존 명시 동의를 거치는 초안 작성에는 추가 exported 명령 수신면을 만들지 않았다. [공식 Gleo action schema](https://document.pleos.ai/en/docs/pleos-only/app-integration-guide/guide/app-capability)

## 수명과 동의

`VoiceInputController`는 마이크 gateway·monotonic 시계·변경 알림만 받는다. 제안 API, 경로 gateway, 장소 선택과 foreground service를 소유하지 않는다. Activity 전경과 현재 작성 화면에서만 시작하며 방·회원·room epoch·화면 generation·native epoch에 묶는다. 화면 이탈, Activity 중지, 방/회원/내비 연결 교체, 새 pending 제안과 동행 중지는 발화를 지우고 UI generation을 즉시 무효화한 뒤 마이크 stop을 요청한다. SDK stop은 비동기여서 helper/listener를 바로 release하지 않는다. onEndedRecognition 또는 최대 3초 종료 대기 뒤 listener 제거와 release를 수행한다. 다음 명시 인식은 이전 정리가 끝날 때까지 준비 중으로 기다리며, 이전 release가 새 인식과 겹치지 않는다. 이 기한은 물리 마이크 종료의 증거가 아니므로 실제 appops/audio 관찰로 종료를 따로 확인한다. 지연된 초기화·중간/최종 결과·오류는 이전 generation에서 수용하지 않는다. cold start에서 초안이나 마이크 동의를 복구하지 않는다.

초기화/이전 종료 대기 10초, 실제 준비 신호 뒤 듣기 15초와 종료 뒤 최종 결과 확인 3초 상한을 사용한다. 실제 종료 이벤트 뒤에는 최대 2초만 최종 결과를 기다린다. timeout은 callback에서도 검사한다. 자동 재시도나 계속 듣기가 없다. 화면의 편집 입력은 400자로 제한하며 저장·자동완성을 끈다. 관찰 tick이 편집 중인 입력을 덮지 않는다.

후보 검토는 문장 revision과 장소 snapshot에 묶인다. 문장을 고치거나 다시 정리하면 음성으로 고른 선택을 해제하되 실제 불변 목록은 다시 조회하지 않는다. 오래된 후보 dialog는 현재 문장/scope/snapshot 검사를 통과할 수 없다. 실제 선택은 기존 `SavedPlacesController.select`, 전송은 기존 `proposeSelectedPlace`를 사용한다. 인식·정리·후보 확인만으로 제안 동의·FGS lease·경로를 만들지 않는다.

전원 수락 이전 경로 요청 0회, 거절·만료·중지·stale/중복 보호와 수락 후 fresh native 조회를 유지한다. 선택 지점은 기존 `pleos_place` 원본이며 실제 안내 지점은 별도 confirmation에 보존한다. [장소 확인 계약](poi-place-confirmation.md), [제안 계약](vehicle-proposals.md), [차량 UI 계약](vehicle-ui.md)을 따른다.

## 개인정보와 검증

제품 앱은 원음 파일을 만들거나 전송하지 않으며 STT에 임의 파일을 넣는 경로도 없다. 발화·중간 인식문·전체 후보 목록은 메모리에만 둔다. journal에는 고정 음성 이벤트 이름과 기존 epoch/counter만 쓰며 인식문/이유/원음을 쓰지 않는다. 명시 제안 때 선택한 실제 장소 한 개와 사용자가 확인한 이유만 기존 인증 API로 전송한다.

별도 `nativeDriver`의 testOnly `SpeechProbeActivity`는 공개 SDK의 ON_DEVICE 초기화·차량 마이크·`sendAudio/completeAudioSend`를 분리하여 진단한다. PCM 입력은 작업자가 저장한 자기 앱의 고정 사설 파일 `files/speech-fixture.pcm`, 최대 15초/480000바이트다. `microphoneAudioPersisted=false`는 마이크 녹음을 저장하지 않는다는 뜻이며, 공급한 합성/녹음 fixture 파일의 저장과 구분한다. 보고서에는 해당 사설 fixture 파일명과 바이트 수를 별도 기록한다. probe도 같은 최대 3초 stop 유지 후 해제를 사용하고, 새 초기화는 이전 해제 뒤에만 수행한다. 한 초기화당 입력은 한 번이며 다음 입력은 명시적으로 다시 초기화한다. 이 경로는 운영 앱에 없다. 인식문은 probe 화면에만 표시하고 onStop에 지우며 보고서는 입력 종류·성공/오류·문자 수·RMS 존재만 기록한다. [공식 PCM API](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/SpeechToText/sending-audio-data)

JVM 검사는 인식 수명·늦은 callback·부정/모호/복수 후보·원본 객체 보존과 선택 무효화를 검사한다. 이는 실제 STT나 차량 라우팅 증거가 아니다. 실제 두 공식 AVD의 소스/APK·입력 출처·STT 결과·오인식 수정·상대 수신·거절/대기 0회·수락 후 실제 native 조회는 총괄이 별도로 동결한다. 합성 PCM/소리·사용자 마이크·실제 사람 발화를 구분한다. 실제 사람 발화가 없으면 사람의 발화 성공을 주장하지 않는다. 최종 PASS와 병합은 별도 문맥 verifier가 정확한 head/증거를 확인한 뒤 총괄이 수행한다.
