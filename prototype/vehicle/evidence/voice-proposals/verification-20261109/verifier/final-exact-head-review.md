# Issue 25 — exact H1 독립 검증

**PASS — base `8b6321276f33923f24f15a8437205b6126bf92ca` / head `f65183b8f568ddfef63530a7faaaa6418d5d8a74`에 한정한다.** 필수 AC1–AC5의 직접 근거를 확인했다. 제품 수정이나 기기·ADB·서버·GitHub 조작 없이, 구현과 별도 문맥의 **builtin default**가 `.codex/agents/verifier.toml` 계약을 수행했다. 사용자 정의 verifier 역할을 실행했다고 주장하지 않는다. 이 판정은 향후 H2, 병합 또는 이슈 닫힘의 승인이 아니다.

## 버전과 무결성

제품 source는 `105cd7ef5b47e194be1fbfd2271ba6d96c0fd32f`다. 새 archive `b883042cbc9610a551b3329bb6effdd219eabd06`에서 독립 빌드했으며 H1까지 컴파일·테스트 입력은 그대로다. 73개 동결 입력 중 72개 hash가 같고, 차이는 승인된 `docs/product/vehicle-voice-proposals.md` 한 개뿐이다. H1의 문서 diff는 관찰·입력 출처·실패/미측정 한계를 실제 증거대로 좁혀 기술하며 요구를 낮추지 않는다.

H1 신규 delta239개는 위 문서와 새 검증 증거만 포함한다. manifest의 **237개/22,537,087바이트**를 모두 직접 hash 대조했고 누락·미등록·불일치0이다. manifest SHA256은 `92da08c2a1e6c4f4fccd20c96f2f2d3badcdac0772625bf86e609e4ed46104e2`. 기준 기존 증거846개와 이전 tracked1104개(승인 문서 제외)의 blob/실제 파일을 보존했다. 허용 경로 위반0, worktree clean, local origin/main 및 merge-base는 고정 base와 같다. GitHub 최신 상태는 총괄의 별도 게이트다. [전체 무결성](h1-integrity-audit.json)

운영 앱 SHA256: `5b9eec0cca7f645e25ef4615cd5096d8e31b9256141d35f8d5018bc2c398317e`; testOnly driver: `5f99cb5355e12f9af818e1b6740aa3a4f7a9974b387de93a3f8e504639e5a4ea`. 설치 관측과 외부 원본 APK hash를 대조했다. 독립 APK 전체 hash는 app `a4e7a6d0368b66f8445661d07aa819921d8f1170fc586088ef0e54aa1a74ab7a`, driver `f457c4261f0be536cf63ef7ab0b72badfbe56dc65e1ff12dc2977e5fa716abe9`로 다르다. 압축 해제한 모든 entry는 app886/driver883개, payload 차이0이다. 전체 hash가 같다고 처리하지 않았다. [APK 대조](apk-payload-comparison.json)

## AC별 판정

| 조건 | 판정 | 직접 근거와 적용 한계 |
|---|---|---|
| AC1 | PASS | 공식 Pleos3.3.1 ON_DEVICE 문서·실제 공개 AAR API, AudioRecord/권한·PCM/stop/release 소스, 독립 수명 JVM. Q02 A/B 명시 취소, Q03 A 페이지 이탈·B 실제 onStop, Q03d 두 번 재시도의 실제 app-op/저널, Q04b/c stop 후 TIMEOUT/실제 실패 화면을 대조했다. 취소 뒤 PCM/final0, scope/늦은 callback 가드를 확인했다. |
| AC2 | PASS | 독립 VoiceDraft/선택 무효화 회귀와 Q05b/c 실제 UI. 수정문→보수적 로컬 초안, 현재 불변 ☆ 객체만 후보, 실제 이름/주소·이유 편집, 부정/없음/복수/모호 처리. 카페·주유소 원본과 업종 추측 금지, 수정 뒤 기존 선택 제거/전송 비활성 확인. 좌표/ID 생성·자유 검색·원음/전체 후보 journal/server 저장 경로 없음. |
| AC3 | PASS | Q07 명시 send→B 수신→10초 이상 pending→거절 전 구간 route A0/B0·revision1. Q08b 새 제안 전원 수락 전0, 이후 각1회. 새 query→positive callback→destinationMatch true가 같은 epoch/operation에 결합하며 typed pleos_place/revision2를 확인했다. 보수적 host185초 이상 추가 route0. 기존 제안/expiry/replay/scope·엄격 exact/place 분리 회귀도 통과. |
| AC4 | PASS | 실제 듣기/취소/편집/후보/전송/timeout 한국어 PNG/XML, A Q06d의 완료 부분과 B Q06f actual font1.3/400자 상·하단을 직접 봤다. 20sp/56dp 토큰·도달 가능한 동작·선택 없을 때 보내기 비활성을 확인했다. 아래 독립 JVM/HTTP/build/lint 통과. |
| AC5 | PASS | 승인된 두 AVD/ADB5041/user10, 설치 hash/PID/raw 세대 고정. B 동일 freeze7의 11/8 사람 발화 final을 사용자 화면 보고·실제 이벤트/hash와 결합하고 A 사람 발화 성공은 주장하지 않는다. 현재 public edited text와 별도 구분하여 실제 양쪽 제안/거절0/전원 수락/native를 직접 원시 증거로 확인했다. 이 보고서가 별도 문맥 exact-H1 판정이다. |

상세 직접 계산: [소스 검토](source-review-b883042.md), [부분 원시 감사](partial-runtime-audit.json), [Q08b](Q08b-independent-audit.json), [원본/실제 지점](Q08-provenance-audit.json), [최종 UI/재시도](late-runtime-independent-audit.json). 당시 BLOCKED 중간 보고서는 당시 시점 기록이며 소급 수정하지 않았다.

## 명령과 실제 결과

독립 fresh archive에서 단일 offline Gradle: `--offline --no-daemon --max-workers=1`, `-Xmx1024m -XX:MaxMetaspaceSize=384m`, Kotlin in-process로 `:core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :nativeDriver:assembleDebug :nativeDriver:lintDebug`를 실행했다. **exit0 /165.647초**, core93+app105=198, 실패·오류·skip0. assemble와 lint 성공, lint error0/warning app13·driver8. 이후 컴파일 입력 변경이 없어 반복 빌드하지 않았다. [명령/결과](build-result.json) · [원본 Gradle](independent-gradle.log)

독립 `npm test` **exit0 /94개 통과 /15.501초**, 실패·취소·skip0. 패키지/서명/권한/manifest 검사8개 모두 exit0. [Node](node-result.json) · [패키지](package-checks.json). 소스·237개 manifest·baseline846 무결성 Python과 전체 원시행 재계산, 최종 assertion 모두 exit0이다.

신규 delta 전체 `git diff --check`는 **exit2**다. 예외는 원문 패치3개와 원문 Gradle log1개에 한정했고, 정확히 네 파일을 제외한 나머지는 **exit0**이다. 제품·문서의 공백 예외는 없다. 과거 원문 로그 예외도 보존했다. [정확한 출력/예외](h1-integrity-audit.json)

## 전체 원시 수집과 정리

중복 제거 없이 모든 행을 읽었다. A376/B282개 저널에서 requestRoute는 각각1회이며 둘 다 Q08b다. 관측은 각각4208개, PID A32650/B19726·generation0이 유지됐다. 최대 host 샘플 간격 A2.645506/B3.382756초, 수집 gap/error0. 종료는 `05:00:26.819148Z`; 총괄이 process exit0을 관측했고 verifier는 정상 종료 결과/collector 소스·원시 파일을 직접 확인했다. 별도 프로세스를 조작하거나 종료 코드를 재현하지 않았다. [전체 재계산](h1-global-runtime-audit.json)

Q03d 실제 이벤트명으로 start/init/record/listen/cancel/release 각2, `voice:sdkPcmCompleted`와 `voice:finalText` 각0이다. 두 번째 start는 old sdkStopPending 뒤1515ms, 이전 release 뒤33ms에 새 initialize가 이어져 **같은 gateway** 정리 직렬화를 실측했다. 다른 Activity 간3초 미만 시작을 실측한 것은 아니다.

Q10b 실제 PNG/XML과 allowlisted state에서 양쪽 font1.0·마이크 inactive·room/native 연결·revision2·busy/lease 없음, A home/B 빈 작성 화면을 확인했다. 이전 unavailable-control을 빈/비활성 입력이라고 해석한 문구는 증거로 쓰지 않고 `final-runtime-audit.json` 정정을 적용했다. 외부 `actions.jsonl`에는 이후 Q10c가 추가됐으나 H1의 커밋된 manifest/원시 critical 구간은 동결 상태로 정확하다.

## 입력 출처·개인정보·한계

사람 발화 정확 문구는 B 사용자 화면 관측이며 privacy journal은 문장을 보관하지 않는다. 실제 final 이벤트·마이크 수명·APK 일치는 직접 근거다. A 사람 인식 성공이나 새 단일 사람발화→카페제안→route 연속 성공을 주장하지 않는다. 합성 음향 timeout 원인은 미확정이다. current TextProbe는 공개 문장의 UI 편집이며 production STT 결과 주입이 아니다.

선택 원본37.41255277/127.09591666/전체 주소와 실제 native37.412475/127.09534722/수정구 시흥동은 분리됐다. 동일한 실제 POI/provider/sub-ID로 장소를 확인하며 좌표·입구·완료 경로의 동일성을 주장하지 않는다. 후속 공개 bookmark 읽기는 원래 snapshot/HTTP wire가 아니고 PLEOS_BOOKMARK는 생성자 invariant의 추론이다. 이후 재개 시 confirmation 없음과 앞선 live CONFIRMED를 혼합하지 않는다.

SpeechProbe opaque 테스트의 SDK 자동 초기화 혼입, HOME이 onStop을 만들지 않은 시도, font/launch 화면 전제 실패, 공개 hint 캡처 guard 오류와 잘못된 이벤트명 predicate는 원본·정정으로 보존했다. 실제 완료된 조건만 근거로 삼았다. 새 확정 결함·필수 차단은 없다. 서로 다른 Activity 간3초 미만 SDK 정리 겹침은 미측정이고 전역 직렬화 보장은 없다.

신규 증거 파일·소스 스키마와 credential 패턴을 검사했다. raw audio/APK/AAR/JAR/backup/credential artifact 및 실제 credential pattern 검출0. 패턴 검사는 소스·스키마 검토의 보조이며 다른 인증 저장소를 읽지 않았다. 음성은 전경 메모리 PCM→동일 차량 ON_DEVICE 처리이고 생성형 AI/외부 공급자·키 사용을 주장하지 않는다. 실차·폰7/8·지도16·외부 배포는 범위 밖이다.

Q10c cold 재시작/PID 교체는 H1 뒤의 별도 증거다. H2에 포함되면 그 raw·설치/PID 바인딩·자동 재요청0·새 manifest/delta를 새 정확한 head로 다시 검사해야 한다. 이 PASS는 H2를 자동 승인하지 않으며 base/head/요구가 바뀌면 재검증한다. GitHub draft 해제·expected-head squash·merged/main 포함·issue closure는 총괄에게 남은 별도 게이트다.
