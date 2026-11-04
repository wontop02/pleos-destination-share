# 함께 가는 길 · Android 네이티브 프로토타입

Kotlin과 플랫폼 Activity/UI, AudioRecord, AudioTrack, 수신 전용 포그라운드 서비스로 만든 실제 앱이다. WebView를 사용하지 않는다. [공통 API v1](../README.md)의 공유방·인증·목적지·원음 계약을 그대로 사용한다. 로컬 코드·빌드 검증본이며 모든 Galaxy의 잠금 수신을 검증한 제품은 아니다.

## 빌드와 설치

고정 버전은 Gradle 8.11.1, Android Gradle Plugin 8.9.2, Kotlin 2.2.20, compile/target SDK 35, build-tools 35.0.0, 최소 Android 8.0/API 26, Java bytecode 17이다. 이 작업에서는 별도 설치된 JDK 21을 사용했다. 기본 셸의 Java 25를 사용하지 않는다. 런타임 네트워크 의존성은 OkHttp 4.12.0이며 Kotlin 표준 라이브러리와 함께 Google Maven/Maven Central에서 받는다. 테스트는 JUnit 4.13.2와 JVM용 org.json 20240303을 사용한다.

실행한 명령은 아래와 같다. JDK·SDK·Gradle 캐시는 프로세스별 경로를 지정하며 사용자 전역 설정을 바꾸지 않는다.

```sh
cd '/Users/wontop/Documents/ChatGPT/현대 모비스 해커톤/pleos-destination-share/prototype/android'
env JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home \
  ANDROID_HOME=/private/tmp/pleos-android-toolchain-20261104/sdk \
  GRADLE_USER_HOME=/private/tmp/pleos-android-toolchain-20261104/gradle-user \
  ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug \
  :app:processReleaseMainManifest --no-daemon
```

다른 환경에서는 위 세 경로만 해당 환경의 JDK 17–23, SDK, 별도 Gradle 캐시로 바꾼다. Node 22 이상이 PATH에 있어야 실제 서버 계약 테스트가 실행된다. SDK의 platform 35와 build-tools 35.0.0을 먼저 설치한다. 첫 빌드는 고정된 무료 의존성 다운로드를 위해 네트워크를 사용한다.

APK 경로는 `app/build/outputs/apk/debug/app-debug.apk`, application ID는 `kr.together.road`, 시작 Activity는 `.MainActivity`다. APK·SDK·캐시·빌드 산출물·서명 키는 커밋하지 않는다. 디버그 키는 빌드 시 `app/build/signing/debug.keystore`에만 생성한다. 공개된 Android 표준 디버그 암호를 사용하며 운영 서명 키가 아니다. `clean` 이후에도 같은 작업 전용 경로에서 다시 생성한다. 사용자 `~/.android/debug.keystore`를 사용하지 않는다.

연결된 테스트 기기에서 설치할 때는 해당 작업의 격리된 SDK/ADB를 선택한다.

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

이 작업에서는 격리된 API 35 ARM64 에뮬레이터에 설치하고 SDK UI 검사로 조작했다. 물리 휴대폰에는 설치하지 않았다. 자세한 범위와 APK 버전은 [에뮬레이터 검증](evidence/verification.md)에 기록한다.

## 로컬 연결과 조작

1. 저장소 루트에서 `npm start`로 기존 서버를 실행한다. 기본값은 호스트 컴퓨터의 `127.0.0.1:4180`이며 서버를 외부에 공개하지 않는다.
2. Android Emulator에서는 앱의 기본 주소 `http://10.0.2.2:4180`을 사용한다. 이름을 입력해 만들거나 대표 차량이 복사한 초대 링크를 붙여 넣어 참여한다.
3. 대표 차량이 목적지 이름·위도·경도를 입력해 전달한다. 참여자는 받은 목적지를 확인하고 `네이버 지도에 경로 요청`을 직접 눌러 지도 앱에 전달한다. 실제 길 안내 시작은 지도 앱에서 확인한다.
4. `마이크로 녹음 시작`에서 RECORD_AUDIO 권한을 요청한다. `중지하고 보내기`, `녹음 취소`, 15초 자동 종료를 사용할 수 있다. 받은 원음 목록에서는 과거 원음과 자기 원음도 직접 재생할 수 있다.
5. `자동 수신 시작`을 직접 누르면 알림 권한을 확인한 뒤 지속 알림이 있는 서비스를 시작한다. 앱 버튼이나 알림의 `수신 중지`로 현재 재생·다운로드·대기열을 중지한다. 대표는 방을 종료하고 참여자는 자신만 나갈 수 있다.

서버가 Host를 loopback으로 제한하므로 **디버그 HTTP의 10.0.2.2 연결에만** `Host: 127.0.0.1:<port>`를 보낸다. 일반 호스트·HTTPS·release에는 이 변경을 적용하지 않는다. 웹이 만든 `http://127.0.0.1:4180/#join=...`도 디버그에서만 같은 scheme·port의 `127.0.0.1`/`localhost`/`10.0.2.2` 별칭으로 인정한다. 다른 origin, 사용자 정보, 쿼리, 임의 경로는 거절한다. 초대 링크의 origin으로 기존 bearer를 옮겨 전송하지 않는다. 앱은 선택한 endpoint에만 연결한다.

대안은 테스트 기기에서 `adb reverse tcp:4180 tcp:4180`을 설정하고 앱 서버 주소를 `http://127.0.0.1:4180`으로 지정하는 방법이다. 이는 기기 확보 후 명시적으로 적용하는 개발용 연결이며 LAN 공개가 아니다. 휴대폰에서 호스트 컴퓨터의 `127.0.0.1`로 직접 연결할 수 있다고 가정하지 않는다. 본 작업의 에뮬레이터 UI·실기기 연결 확인 여부는 아래 검증표에 따로 기록한다.

debug 네트워크 보안 설정은 `10.0.2.2`, `127.0.0.1`, `localhost` 세 정확한 이름에만 HTTP를 허용한다. subdomain은 허용하지 않는다. release는 HTTP를 거절하며 정상 인증서의 HTTPS만 사용한다. TLS 인증서/호스트 검증 우회나 사용자 보안 설정 변경은 없다. release 서명·운영 endpoint·공개 배포는 구성하지 않았다.

네이버 지도 요청은 [공식 Android URL Scheme 계약](https://guide-fin.ncloud-docs.com/docs/maps-url-scheme)에 따라 `nmap://navigation`에 `dlat`, `dlng`, 한 번 URL 인코딩한 `dname`, Android application ID `appname=kr.together.road`를 전달한다. 공식 범위인 위도 31.43–44.35, 경도 122.37–132.00만 허용한다. Intent 대상과 package visibility query는 `com.nhn.android.nmap` 한 앱으로 제한한다. 앱이 없거나 좌표·권한을 처리하지 못하면 화면에 실패를 표시하고 받은 목적지는 유지한다. `startActivity`가 요청을 수락해도 실제 안내 성공으로 표시하지 않는다. GPS 권한, 자동 지도 실행, 스토어 이동·설치는 사용하지 않는다.

## 원음·수명주기

AudioRecord가 전경 Activity에서 16kHz·mono·PCM16 표본을 직접 읽는다. 실제 표본 16000–240000개로 WAV를 만들며 길이는 1–15초다. 15초 타이머와 표본 수 상한을 함께 적용하고 취소·오류·화면 숨김 시 마이크를 stop/release한다. 필요한 녹음 형식을 지원하지 않거나 마이크 권한을 거절하면 녹음 성공으로 표시하지 않는다. 수신 서비스는 마이크 권한을 요구하거나 마이크를 열지 않는다.

업로드는 WAV 바이트와 UUID `X-Client-Message-Id`를 사용한다. 실패한 원음은 메모리에만 최대 30초 남고 사용자가 직접 재시도할 때 동일 ID·동일 바이트를 쓴다. HTTP 전송 자동 재시도는 끈다. 재연결 때문에 오래된 녹음을 다시 보내지 않는다. 성공·취소·만료·공유방 교체 시 보관 버퍼를 지운다. 서버 접수와 상대 재생 완료를 구분한다.

다운로드는 bearer 인증으로 크기를 제한해 읽고 RIFF/fmt/data, PCM 형식과 실제 표본 수를 다시 검사한다. metadata의 크기·표본 수와도 비교한다. AudioTrack은 검증한 PCM을 bounded static buffer로 재생하며 음성 합성을 사용하지 않는다. 재생 완료 callback, 포커스 거절·상실, 중지와 오류를 구분한다. 소리를 시작하기 직전에 speech용 오디오 포커스를 요청하고 끝나면 반납한다. 포커스를 잃으면 현재 소리와 자동 수신을 중지하며 직접 다시 시작해야 한다.

자동 수신 기본값은 꺼짐이다. 사용자가 시작한 시점 이후의 새 원음만 받아 `sequence` 순으로 처리하고, 자신의 원음·이미 본 ID·30초 지난 이력은 제외한다. snapshot 재연결에도 동일 ID를 재생하지 않는다. 다운로드 뒤 실제 AudioTrack 시작 직전에 신선도·수신 선택·방 수명을 다시 확인한다. 서버 시각에 왕복 시간을 더한 보수적인 추정과 monotonic 경과 시간을 사용하므로 지연만큼 일찍 제외할 수 있다. 수동 이력 재생은 자동 수신을 끈 뒤 열린 화면에서 실행한다.

수신 서비스는 `mediaPlayback` 타입·권한으로 전경 Activity의 조작에서만 시작한다. 지속 알림과 immutable stop PendingIntent를 제공한다. 백그라운드 SSE는 인증 헤더를 사용하고 1–10초 간격으로 재연결한다. 사용자 중지·방 종료·알려진 만료 시각·401/403/404에는 수신과 오디오를 정리한다. `START_NOT_STICKY`, `stopWithTask`를 사용하며 재부팅·알람·강제 재시작으로 되살리지 않는다. phoneCall/remoteMessaging 타입, 무음 재생 루프, 배터리 최적화 예외 강요는 사용하지 않는다.

FGS가 조용히 살아 있다는 사실만으로 Doze·Galaxy 절전·내비 사용·잠금 중 첫 소리가 검증되지는 않는다. 푸시 wake-up이나 장기 수신 생존 보장도 이 구현에 포함하지 않는다. 이 서비스는 검증할 수 있는 후보 구현이며 실제 제품 통과에는 아래 장치 검증이 남아 있다.

토큰은 Authorization 헤더에만 둔다. access token을 URL·로그·Intent·알림·화면에 넣지 않는다. 초대 링크에는 가입용 secret만 포함한다. 세션은 Android Keystore AES-GCM으로 암호화해 앱의 `noBackupFilesDir`에 보관한다. 백업과 device transfer를 제외하며 다른 앱에 service/storage를 export하지 않는다. 저장된 세션은 다시 열 수 있지만 자동 수신 선택은 저장하거나 자동 재시작하지 않는다. 서버 변경·나가기·방 교체 시 이전 요청·스트림·오디오 callback을 취소하고 세대 번호로 늦은 결과를 폐기한다.

## 검증과 남은 기기 게이트

JVM 테스트 20개는 순서·신선도·자기 음성 제외·중복·재시도·WAV·SSE·세대 취소, 네이버 지도 요청 인코딩·좌표 범위와 실제 Node 서버 HTTP 계약을 검사한다. API 계약 테스트는 임시 포트에서 원본 서버를 띄우고 생성한 **가상 PCM 표본**으로 인증·목적지·WAV 바이트·재전달·충돌·SSE·취소·나가기·종료를 확인한다. 실제 사람의 녹음이나 실기기 소리 성공 증거가 아니다. SSE 취소는 heartbeat를 기다리지 않고 1초 안에 worker가 끝나는지 검사한다.

| 검증 | 현재 결과 | 필요한 증거 |
|---|---|---|
| 고정 도구·디버그 APK·unit/API·lint | 실행 결과는 `evidence/build.json` | 명령 종료 코드, 테스트 수, APK SHA-256 |
| Android 전경 설치·한국어 UI·생성/참여·목적지 | 에뮬레이터 실행, [증거](evidence/verification.md) | 물리 기기와는 별도 결과 |
| 실제 마이크 녹음·1/15초·취소·권한 거절 | 에뮬레이터 권한 거절 확인. 실제 입력은 미실행 | 장치의 실제 마이크 입력, 업로드된 원본 WAV, 정리 관찰 |
| 명시적 수신·알림 중지·오디오 포커스 | 에뮬레이터에서 실행. 실제 청취·포커스 경합은 미실행 | 실제 알림·소리·다른 앱 포커스 전환 증거 |
| Galaxy 내비 앱 사용 중 새 원음 | 미실행 · 필수 제품 게이트 | 모델·OS·내비 버전, 송신/첫 소리 시각, 실제 청취 |
| Galaxy 화면 잠금 중 새 원음 | 미실행 · 필수 제품 게이트 | 잠금 상태·수신 세션·실제 첫 소리 증거 |
| Galaxy 잠금 후 15분 무음 → 첫 원음 | 미실행 · 필수 제품 게이트 | 조용한 15분 유지 후 새 원음의 실제 첫 소리·지연 |
| Galaxy 잠금 후 30분 무음 → 첫 원음 | 미실행 · 필수 제품 게이트 | 조용한 30분 유지 후 새 원음의 실제 첫 소리·지연 |

미실행·실패·성공을 서로 바꾸어 기록하지 않는다. 장치 검증에는 기본 절전 설정을 포함한 모델·OS·앱 버전, 테스트 시작/송신/첫 소리 시각과 관찰 증거를 남긴다. 전경 JVM 네트워크 성공, 에뮬레이터 합성 오디오, 지속 알림만으로 위 네 개 Galaxy 제품 게이트를 통과시키지 않는다. 정확한 base/head의 독립 verifier 판정은 총괄의 PR 검증에 별도 기록한다.

## 공식 출처와 도구 출처

| 항목 | 출처·검증 |
|---|---|
| SDK 35/build-tools/명령행 도구 | [Google 공식 저장소 XML](https://dl.google.com/android/repository/repository2-3.xml)의 checksum과 실제 archive 비교. 기록은 `evidence/toolchain.json` |
| Gradle 8.11.1 | [공식 distribution](https://services.gradle.org/distributions/gradle-8.11.1-bin.zip), [공식 SHA-256](https://services.gradle.org/distributions/gradle-8.11.1-bin.zip.sha256). wrapper에도 `distributionSha256Sum` 고정 |
| AGP 8.9.2/SDK·Gradle·JDK | [공식 호환 표](https://developer.android.com/build/releases/agp-8-9-0-release-notes) |
| Kotlin 2.2.20 | [공식 Gradle/AGP 호환 표](https://kotlinlang.org/docs/gradle-configure-project.html). 2.1.20 대신 AGP 8.9.2를 지원 범위에 포함하는 버전 선택 |
| FGS·오디오 포커스·녹음 | [서비스 타입](https://developer.android.com/develop/background-work/services/fgs/service-types), [포커스](https://developer.android.com/media/optimize/audio-focus), [AudioRecord](https://developer.android.com/reference/android/media/AudioRecord) |
| HTTP/TLS 정책·취소 | [Android 네트워크 보안 설정](https://developer.android.com/privacy-and-security/security-config), [고정 OkHttp 4.12.0 Call 계약](https://github.com/square/okhttp/blob/parent-4.12.0/okhttp/src/main/kotlin/okhttp3/Call.kt) |

일부 최신 SDK CLI 메타데이터는 AGP의 SDK reader보다 새 XML 버전이라는 경고를 낼 수 있다. 빌드·검사 성공 여부와 경고는 구분해 기록한다. SDK·도구·APK·캐시·키 원본은 이 저장소에 포함하지 않는다. Gradle wrapper의 공식 생성 스크립트와 JAR만 재현 가능한 소스로 포함한다.
