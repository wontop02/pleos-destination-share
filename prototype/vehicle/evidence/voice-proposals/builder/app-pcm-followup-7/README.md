# 앱 전경 PCM 입력 — 동결 7

기준 head는 `c5fe5e44b3e98bea1a8ff83b772ace4791245271`다. 사용자가 실제 B 음성 시험에서 준비 뒤 약 8초 녹음/말하기 끝을 거쳐 시간 초과한 결과를 보냈다. root는 request 위임 마이크의 partial/final/onStartedRecognition 0회와 CAAS 실제 녹음 종료를 관찰했다. 별도 ON_DEVICE supplied PCM final과 이 실패는 서로 다른 입력 경로다. 근본 원인이 음향 입력/위임 마이크/완료 신호 중 무엇인지는 확정하지 않았다. 이 구현은 원래 실사용 repro가 해결됐다는 판정이 아니다.

동결 7은 SDK request 마이크를 사용하지 않는다. 실제 사용자 RECORD_AUDIO 승인 뒤 전경 AudioRecord를 열고 RECORDSTATE_RECORDING을 확인한 다음 듣는 중으로 표시한다. PCM16 short array를 명시적 little endian 16kHz mono 바이트로 메모리에만 보관한다. 명시 말하기 끝/15초/480000바이트에서 AudioRecord를 stop/release하고 worker 종료 뒤 공개 ON_DEVICE sendAudio/completeAudioSend로 한 번 보낸다. 취소·이탈·scope 변경은 버퍼/queued delivery를 지우고 SDK 전송을 차단한다. read는 nonblocking이며 close/interrupt가 입력 대기를 끝낸다. 실제 native close는 AtomicBoolean으로 한 번만 수행한다. SDK helper의 기존 3초 정리는 엔진 정리이고 실제 마이크 수명과 분리했다.

새 RECORD_AUDIO는 dangerous runtime permission이며 Manifest 선언/코드 준비만 했다. builder는 adb grant/권한 조작/실제 마이크/단말 조작을 하지 않았다. 권한 callback은 현재 foreground·작성 화면·room/member/room epoch/native epoch/page generation과 일치하는 일회성 의도만 소비한다. 거절/늦은 승인/이탈/중지는 녹음을 시작하지 않는다. FGS 유형·배경 녹음·외부 공급자·키·서버·파일/Intent/백업 저장은 추가하지 않았다. 사용자가 실제 Android 권한 화면을 승인한 뒤에만 총괄이 기기 검증을 수행해야 한다.

결과 대기는 cold PCM final 약 9.5초 관찰을 반영해 15초로 제한했다. SDK onEnded는 이를 줄이지 않고 중복 이벤트가 연장하지도 않는다. 15초가 지나면 시간 초과이며 중간 결과를 확정문으로 승격하지 않는다.

| 검사 | 결과 |
|---|---|
| 제품 APK SHA-256 | `5b9eec0cca7f645e25ef4615cd5096d8e31b9256141d35f8d5018bc2c398317e` |
| core/app JVM | 93+105=198 tests, failures/errors/skips 0 |
| assemble/lint | exit 0, 173.835118042초, lint errors 0/warnings 13 |
| 원본 입력 | 73개 build 전후 동일 |
| 서명/permissions/manifest | 각각 exit 0 |
| 서버·제안/동의·실제 목적지 확인·분석 규칙 | 보호한 10개 입력 모두 동일 |
| 기존 증거 | main 기준 846개 변경 0 |
| debug 임시 태그 | `[DEBUG-voice-20261108]` 0개 |

기존 191개 JVM 검사를 유지하고 7개 회귀를 추가했다. 새 seam은 실제 capture worker의 부분 short read→LE 변환, sample 상한, queued completion 이후 취소, read 중 취소, permission/녹음 상태/읽기 오류와 늦은 permission scope를 검사한다. controller는 실제 9.5초 final 대기와 원래 15초 기한을 검사한다. 해당 기간 검사 변경은 명시적으로 늘어난 제품 계약을 검증하며 원래 실기기 실패를 통과로 바꾸지 않는다. fake source는 실제 AudioRecord·마이크·STT 증거가 아니다.

단일 Gradle에 `--offline --no-daemon --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx1024m -XX:MaxMetaspaceSize=384m' -Pkotlin.compiler.execution.strategy=in-process :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`를 실행했다. 57 tasks 중22개 실행/35개 up-to-date다. core/Node/driver는 소스가 같아 기존 결과를 재사용하며 새 실행으로 주장하지 않는다. driver SHA-256 `5f99cb5355e12f9af818e1b6740aa3a4f7a9974b387de93a3f8e504639e5a4ea`는 그대로다.

공식 [AudioRecord](https://developer.android.com/reference/android/media/AudioRecord)와 [runtime permission](https://developer.android.com/training/permissions/requesting)을 Context7/공식 페이지에서 직접 확인했다. PCM16은 short read를 사용하고, 실제 녹음 상태·최소 버퍼·오류·stop/release를 구분한다. 실제 Pleos 입력 장치/오디오 라우팅/사용자 발화/STT final/취소 뒤 물리 마이크 해제와 두 차량 native 확인은 아직 이 builder 보고서로 검증하지 않았다. 총괄의 원래 acoustic 재현 루프와 새 APK 실행, 별도 verifier가 필요하다.
