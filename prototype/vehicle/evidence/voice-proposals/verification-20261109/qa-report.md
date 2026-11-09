# UltraQA report — issue #25

## Goal and verdict

고정한 AC1–AC5를 실제 두 Pleos 가상 차량에서 검증하고, 독립 exact-head PASS 뒤에만 PR26을 main에 squash한다. 제품 런타임 사례는 PASS다. 전체 AC/병합 판정은 별도 verifier의 최종 exact-head 보고서와 PR 최종 재결합 기록이 결정하며, 이 root 보고서의 자체 판정으로 병합하지 않는다.

이번 실행은 사용자 “해바”에 따른 별도 검증 묶음이다. 2026-11-09 03:43:58–05:43:58 UTC 한도 안에서 수행했다. 이전 11/8 구현 묶음의120분 종료·BLOCKED를 변경하거나 초기화하지 않았다. UltraQA iteration1이며 제품 변경/수정 재실행 cycle은0회다. OMX 실행 파일/상태 도구가 없어 frozen execution.json과 별도 qa-state.json으로 기록했다. 이는 설치나 글로벌 설정 변경으로 대체하지 않았다.

## Inputs and environment

| 항목 | 고정 값 |
|---|---|
| base main | `8b6321276f33923f24f15a8437205b6126bf92ca` |
| start head | `b883042cbc9610a551b3329bb6effdd219eabd06` |
| product source | `105cd7ef5b47e194be1fbfd2271ba6d96c0fd32f` |
| installed app SHA256 | `5b9eec0cca7f645e25ef4615cd5096d8e31b9256141d35f8d5018bc2c398317e` |
| installed testOnly driver SHA256 | `5f99cb5355e12f9af818e1b6740aa3a4f7a9974b387de93a3f8e504639e5a4ea` |
| devices | owned ADB5041, emulator-5570/Pleos12Leader and5572/Pleos12Follower, user10 |
| production PIDs | A32650, B19726 throughout collector generation0 |

[final-input-binding.json](final-input-binding.json) verifies72 of the73 freeze7 manifest files unchanged. The single difference is the authorized product document; all compiled product/test inputs remain unchanged. Installed user/UID/APK binding is recorded separately. The independent tests below built a fresh archive of b883042, whose compiled inputs are identical to the runtime source and subsequent evidence/document commits. Final verifier rechecks the exact new head; it does not inherit an old approval.

## Scenario matrix

| ID | Result | Direct evidence |
|---|---|---|
| Q01 defaults | Both device default4182 create201, isolated cleanup200; current rooms untouched | Q01-default-endpoint-verification.json |
| Q02 cancel | A/B actual app microphone starts, explicit cancel physically releases; no late PCM/final/route | Q02-A/B-result.json |
| Q03 exit/retry | A page exit and B actual onStop physical release; B post-recreate start/cancel twice; previous SDK release precedes second init33ms | Q03-A-page-exit-result.json, Q03-B-opaque-result.json, Q03d-B-explicit-retry-result.json |
| Q04 recognition/edit/failure | Same-APK prior B human final + user phrase report; current UI edit is separate; actual stop closes physical capture then bounded TIMEOUT with visible retry/manual entry | human-recognition-B-20261108; Q04b/c; Q05b/c |
| Q05 candidates/negative | Actual native cafe and editable reason; negation clears selection/send; fuel matches exactly one actual gas bookmark; generic cafe cannot infer garden category; literal hostile instructions cannot send | Q05b/c, UI snapshots |
| Q06 font/long | Both actual resources1.3,400char editable UI; upper/lower controls reachable,56dp buttons; no-selection send disabled | completed A case inside Q06d false overall apparatus run, independent Q06d-A audit; Q06f B |
| Q07 pending/reject | Real A send/B receipt; frozen2members;10sec+pending then reject; route0 each, revision1 unchanged | Q07 result + raw offsets, partial independent review |
| Q08 unanimous | Pre-accept route0; actual unanimity then route1 each; same-epoch fresh positive native match, typedplace/revision2;185sec+ host observation has no duplicate | Q08b result/extended observation, independent SDK query/provenance/review |
| Q09 hostile/misleading | Both invalid name types/length400; unchanged revision2. CLI0+actionAccepted=false rejected as action success. Instruction literal remains text/no send | Q09 invalid/noop; Q05c hostile |
| Q10 integrity/cleanup | Old846 evidence preserved; collector gaps/errors0, fonts1.0, mic inactive, rooms/native connected, no active lease/proposal | final runtime/input audits; Q10b; collector-result; final exact-head review |

Q08 selected source is the original native bookmark at37.41255277/127.09591666/full street address. Actual native destination is separately observed at37.412475/127.09534722/address 수정구 시흥동. Both share actual POI36khp2h36p68cm5nqhec, provider empty and sub-ID null. The app hierarchy retains original shared PLEOS_PLACE coordinates and separate current EXACT_POINT coordinates. PLEOS_BOOKMARK source is inferred from the reviewed constructor invariant; it is not an observed HTTP wire field. The selected source/name/address and actual native identity prove place confirmation, not identical coordinates, entrance, map tiles or completed route calculation.

## Commands and exits

The verifier independently ran fresh-archive Gradle with --offline --no-daemon --max-workers=1, Xmx1024MiB/Metaspace384MiB and Kotlin in-process: `:core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :nativeDriver:assembleDebug :nativeDriver:lintDebug`. Exit0,165.647seconds, core93+app105=198 tests; failures/errors/skips0. Both assemble/lint pass; lint errors0, warnings13/8. Complete raw command, log and JUnit/lint reports are preserved in verifier/.

Independent `npm test`: exit0,15.501seconds,94/94 tests, failure/cancel/skip0. Eight package/signature/permission/manifest commands exited0. Independent full APK hashes differ from operational packages, but every decompressed entry matches: app886/driver883, zero payload differences. The outer hash difference is retained rather than treated as runtime identity.

Dynamic commands use only explicit ADB5041, approved serials/user10 and owned public app UI/self-targeted testOnly instrumentation. Each instrumentation result preserves arguments, command exit, result fields, duration and before/after production PID. TextProbe reports ui_edit_not_speech_recognition; no STT text injection into production exists. Driver bookmark/native operations are read-only and retain actual callbacks, source identity and route0. Endpoint probes never print credentials and clean only their own isolated rooms. CLI exit0 alone does not establish product/action success: explicit pass/result checks and raw events are audited separately.

## Preserved failures and apparatus corrections

All failed attempts remain alongside the succeeding evidence. No new source defect was confirmed or product code changed during this run. The earlier negative-utterance defect had already been corrected in source105cd7e and remains covered by independent current regression.

Android HOME left Main resumed/uiCount1 and therefore did not test onStop. Its natural15sec recording/15sec wait and failure were preserved. An owned opaque SpeechProbeActivity then produced real Main uiCount0 and physical mic release, but the helper automatically initializes the SDK. The erratum forbids using this as an isolated SDK-cleanup race test. A separate valid public B retry after recreation proves same-instance serialization; different-Activity sub3sec overlap is not directly exercised.

Cancelling the saved-place dialog invalidated its snapshot, so the first Q05 attempt correctly had no voice candidates. A subsequent actual explicit native selection restored a current immutable list. Scrolled/previous-HOME assumptions blocked Q08 preparatory actions before any send; Q08b actual send/accept is separately checkpointed.

Font-change attempts initially targeted a recreated HOME or a DrivingView active window. Plain component am start had stacked Main Activities and could leave car navigation in front. The external harness now uses actual MAIN/LAUNCHER NEW_TASK|RESET_TASK_IF_NEEDED and -W; no product change was made. Completed A font1.3 evidence remains within an overall false Q06d run, and B later passed independently in Q06f. These failures are not relabeled as full passes.

Q03c and Q04b screenshot guards mistook public accessibility placeholder text for populated user fields. Actual lifecycle/timeout traces remain valid; Q04c first matched every field to a fixed public placeholder allowlist, then captured the same failure screen without new recording. The earlier Q03c failed capture occurred during recording and its explicit later cleanup/bounded lifecycle remains in raw observations; it is not silently counted as a successful cancellation test. Q03d is the valid two-cancel retry case.

The initial API probe asserted an error code in the human message field; the server correctly returned400/code invalid_name. The corrected probe used the code field. The Q03d summary initially checked incorrect PCM event names; final-runtime-audit.json directly counts voice:sdkPcmCompleted/finalText as0. Q10's unavailable control returned false/empty bounds; Q10b's initial explanatory string over-interpreted this as an empty draft. The final audit corrects that interpretation; separate successful inspections/cancel and final UI are the cleanup evidence.

## Observation and cleanup

Collector stopped2026-11-09T05:00:26.819148Z and exited0. Each device has4208 observations. Entire-run maximum host sample intervals are A2.646sec/B3.383sec, gaps/errors0 and generation0/PIDs unchanged. Every route occurrence is retained: one each, both inside the declared Q08b acceptance. The critical post-request independent audit establishes conservative185sec+ observation, including after the original approximately45sec lease ended; it is not a claim that the service ran185sec.

Both font scales restored1.0 and app-op shows no active recording. A is on home and B on clean composer with enabled 말로 작성. No pending/busy proposal or live proposal lease remains, room/native connected and revision2. Collectors/probes finished. The two owned AVDs, original4190, task4192, existing app identities/auth and intentional diagnostic evidence remain available. No raw microphone audio, invitation/credential, APK/JAR or unrelated checkout changes are committed. Evidence-only packaging preserves historical raw log whitespace.

## Residual limits

B human phrase accuracy is the user's screen report, corroborated by actual lifecycle/finalText/hash; the privacy journal intentionally omits transcription. No A human recognition success is claimed. Earlier synthetic speaker timeout cause is unresolved. Local conservative text rules may defer unsupported wording or category inference to manual selection. Different-Activity sub3sec SDK cleanup overlap is unmeasured and no defect was observed. Default room creation is actual API/default-route verification, not full manual UI coverage. Real vehicles, phone7/8, map16, external deployment, entrance/route equivalence and route-calculation completion are outside this verification.

Final independent AC verdict, expected-head squash and actual merged/main/issue closure remain coordinator gates; successful runtime and this report alone cannot bypass them.

Subsequent operation uses [environment/ensure-default-route.py](environment/ensure-default-route.py), which refuses an absent ADB server/conflicting mapping, preserves4192 and prints its result without overwriting archived evidence. The current idempotent execution exited0 and changed no mapping; [helper check](environment/helper-idempotency-check.json) records it.

The full staged whitespace check exits2 only for the three archived raw patches and original Gradle log. Exact exceptions/output are retained in whitespace-check.json; the explicit remaining-file check exits0. Product/document changes have no whitespace error.

## Final evidence handoff

Independent AC1–AC5 PASS is recorded for H1 `f65183b8f568ddfef63530a7faaaa6418d5d8a74` in [verifier/final-exact-head-review.md](verifier/final-exact-head-review.md). The report, its integrity/global/reference audits and separate [normal cold re-entry cleanup](cold-reentry-closeout.md) are added in the subsequent evidence commit. That exact new head requires an explicit final rebind; this paragraph does not approve it. The final exact-head verdict is posted in PR26 before expected-head squash.

The new cleanup window deliberately replaces app PIDs with A19921/B12020 and leaves one MainActivity each. Its same-installed-package rebind,12 post-entry samples each and route0 are separate from the immutable earlier collector. It restores historical revision2 without a new liveCONFIRMED claim; B's idle native query was absent. Earlier Q08b confirmation and its185sec+ observation remain valid only in their own recorded scope.
