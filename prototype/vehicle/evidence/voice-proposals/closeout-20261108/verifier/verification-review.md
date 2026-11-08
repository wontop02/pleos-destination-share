# Issue #25 — latest independent verification

**BLOCKED**, base `8b6321276f33923f24f15a8437205b6126bf92ca`, head `105cd7ef5b47e194be1fbfd2271ba6d96c0fd32f`. This is not PASS or merge approval. No new source defect was confirmed in this review. The original negation defect is corrected; the current microphone-to-engine path and mandatory two-vehicle behavior remain unverified.

## Direct checks

The worktree was clean at entry and report time. All 73 files in the freeze7 source manifest match current bytes, all 114 declared builder evidence hashes match, and all 846 baseline evidence blobs and working files remain unchanged. Product APK SHA-256 is `5b9eec0cca7f645e25ef4615cd5096d8e31b9256141d35f8d5018bc2c398317e`; driver is `5f99cb5355e12f9af818e1b6740aa3a4f7a9974b387de93a3f8e504639e5a4ea`. `integrity-audit.json` records these checks and raw whitespace exceptions. Actual base-to-head diff is preserved in `diff.patch`. All additional code, tests and PCM/permission documentation changes since the initial review were inspected. Product/server/navigation-consent code was not modified by this verifier.

The original two negative inputs were independently invoked on the existing latest compiled production analyzer with a 64MiB JVM: both now return NEGATED, empty reason, zero candidates. The affirmative cafe control still returns CHANGE and its affirmative reason. Command exit0 and exact source hash are in `negative-regression.json`. This direct invocation is not a fresh-source rebuild. The previous head's FAIL is retained separately and is not reused as the current verdict.

## Capture and permission review

`AndroidVoicePcmSource.kt` checks RECORD_AUDIO before construction/start, builds AudioRecord for VOICE_RECOGNITION, 16kHz mono PCM16, validates minimum buffer and initialized/recording state, reads short arrays nonblocking, and guards stop/release with AtomicBoolean. `VoicePcmCapture.kt` owns a bounded480000-byte memory buffer and a single daemon worker. It converts each partial short read to explicit little endian, bounds samples/time, closes the source in finally before posting completion, clears memory/sample arrays, drops queued delivery after cancellation, and checks finishing around native source creation/start and each read. Stop/cancel closes the current source and interrupts the worker; no audio file/network capability exists in this class. The actual platform close remains a required runtime observation; a caught stop/release exception or trace label is not physical microphone proof.

`PleosVoiceInputGateway.kt` uses ON_DEVICE, initializes/listens/sets KOREA, and begins app capture only after initialization. LISTENING now follows actual RECORDSTATE_RECORDING. It never calls SDK request as a fallback. Only the completed capture callback can set submitted and sendAudio/completeAudioSend; stale generations zero the output. Cancel increments generation, cancels capture, clears pending PCM, and retains the existing bounded SDK cleanup. SDK results require active generation and submitted=true. `VoiceInputController` allows one original15-second final wait without promotion of interim results; duplicate ended events do not extend it. Place/voice revisions, immutable candidate objects, explicit selection/send, proposal consent and strict place/exact boundaries remain intact.

`VoicePermissionIntent.kt` consumes a one-shot scope match; MainActivity requests RECORD_AUDIO only from explicit voice action and rechecks actual permission, foreground, compose page, room/member/room epoch/native epoch/page generation before capture. Activity stop/page exit/cancel/convoy stop cancel pending permission authority. Runtime permission denial and unsupported/error states retain manual input. No new FGS type or background recording path was found. Actual user grant on both AVDs, scope changes during permission UI, device-specific AudioRecord stop/retry behavior and recognized final results are not established by source inspection.

Official [AudioRecord API](https://developer.android.com/reference/android/media/AudioRecord) and [Android permission workflow](https://developer.android.com/training/permissions/requesting) were opened directly. Public Pleos3.3.1 AAR/API and ON_DEVICE supplied-PCM documentation were independently inspected in the prior report; the AAR/driver version did not change. These API contracts do not prove this AVD's input routing or recognition success. No private SDK implementation, credentials, raw audio or private utterances were read.

## Independent execution

A fresh `git archive105cd7e` was extracted only to this external verifier directory. Toolchain build-env variables were loaded without printing their values. A single offline Gradle with workers1, Xmx1024MiB/Metaspace384MiB and in-process Kotlin runs core:test, app:testDebugUnitTest, app:assembleDebug, app:lintDebug, nativeDriver:assembleDebug and nativeDriver:lintDebug. `build-start.json` records the exact command/archive hash; `independent-gradle.log` is the raw log. At report generation the final Gradle exit/result may not yet exist; `verification-result.json` captures its actual state. `build-result.json`, when produced by the already running runner, is the authoritative completed result and must be checked before claiming success. APK full-file hash and ZIP payload comparison must remain separate; independent APK comparison is pending until assemble completes.

Independent npm test completed:94tests,94pass,fail/cancel/skip0,exit0. Command/time are in `node-result.json`, raw output in `independent-node.log`. Builder freeze7 JUnit XML was separately parsed: core93/app105, failures/errors/skips0. These builder198 tests are not relabeled as independently rerun tests. Initial source-copy preparation failed because host Python tarfile lacked the filter keyword; no build started on that failed attempt. Failure and explicit path-validation recovery are preserved in `preparation-failure.json`.

## AC verdict and missing evidence

| AC | Verdict | Why |
|---|---|---|
| AC1 | BLOCKED | Source guards reviewed; latest app-owned microphone actual stop/cancel/timeout/foreground exit/retry and successful final recognition not yet independently observed. |
| AC2 | BLOCKED | Original negation defect fixed by direct class invocation; current full independent JVM completion and actual recognized/edited draft-to-real-snapshot UI evidence remain incomplete at reporting time. |
| AC3 | BLOCKED | Source retains explicit send/unanimous/fresh-query guards; latest APK's two-car pending/reject route0 and unanimous native confirmation are not supplied as completed direct evidence. |
| AC4 | BLOCKED |20sp/56dp/shared View system remains; latest actual Pleos recognition/edit/candidate/send/failure/cancel/long-text/fontScale1.3 evidence and completed independent build/lint remain required. |
| AC5 | BLOCKED | Latest source/APK hashes fixed; A permission grant and current two-vehicle proposal/accept/native scenario absent. Human/microphone recognition success is not demonstrated. |

The coordinator reported freeze7 acoustic capture reaching appMicrophoneRecording, appMicrophoneClosed and sdkPcmCompleted but no final before timeout; this review does not treat that communicated state as recognition success. Earlier freeze4 microphone cleanup evidence has a different microphone owner and cannot prove freeze7. No device/ADB/server/GitHub manipulation was performed here. Source/head or runtime-evidence commits require renewed binding checks. All mandatory ACs and final base/head must be directly confirmed before PASS.

## Completed independent build addendum

The already-running fresh archive Gradle completed after the initial report: exit0,169.918812583seconds,104tasks all executed. Independently rerun core93+app105=198tests passed with failures/errors/skips0. App and driver assemble/lint completed; lint errors0, warnings app13/driver8. Raw evidence is build-result.json and independent-gradle.log. This completion was observed after12:13UTC; it is not claimed to have completed before the coordinator's run deadline. No additional build was started.

APK full-file hashes differ between builder and independent artifacts. apk-payload-comparison.json separately compares every decompressed ZIP entry and lists the actual payload differences. The full hash difference alone is not interpreted as a source behavior difference. These successful local checks do not resolve the required runtime BLOCKED conditions or authorize a PASS.
