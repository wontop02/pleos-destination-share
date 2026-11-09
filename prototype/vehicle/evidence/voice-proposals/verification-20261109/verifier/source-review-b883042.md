# Issue #25 — source and independent execution review, 2026-11-09

Current verdict: **BLOCKED pending mandatory runtime evidence and final head binding**. This document is not PASS or merge approval. No new source defect has been confirmed. The independent build and regression checks below passed.

## Contract and exact input

The reviewer is a builtin default agent in a separate context, following `.codex/agents/verifier.toml`. This is not execution of a custom verifier role. `AGENTS.md`, `docs/harness/README.md` and the verifier contract were reread. The fixed issue AC1–AC5, new verification-only `execution.json` and scenario matrix were read directly. The new approved window is2026-11-09T03:43:58Z–05:43:58Z; the November8 implementation cap/checkpoint is preserved. No device, ADB, production server or GitHub operation was performed by this reviewer. Test servers created by the authorized isolated JVM/Node suites are distinct from the coordinator's4190/4192 services.

- base: `8b6321276f33923f24f15a8437205b6126bf92ca`
- reviewed head: `b883042cbc9610a551b3329bb6effdd219eabd06`
- product source: `105cd7ef5b47e194be1fbfd2271ba6d96c0fd32f`
- PR26 is reported Draft/unmerged by the coordinator; GitHub mutation is coordinator-owned.

Initial HEAD/merge-base were exact and the worktree was clean. The105cd7e→b883042 delta consists of22 added evidence files under `prototype/vehicle/evidence/voice-proposals/` only. No product, test or policy file changed in that delta. All base-to-head diff paths remain within the issue scope. Both diff patches and command exits are recorded in this verifier directory. The immutable input issue and execution manifest hashes are in `source-integrity-b883042.json`.

All73 freeze7 source-manifest files match current bytes. All114 declared builder evidence hashes match. All846 baseline evidence blobs and working files remain byte-for-byte unchanged. Closeout and human-recognition manifests also match their declared files. The original product APK is SHA-256 `5b9eec0cca7f645e25ef4615cd5096d8e31b9256141d35f8d5018bc2c398317e`; the driver is `5f99cb5355e12f9af818e1b6740aa3a4f7a9974b387de93a3f8e504639e5a4ea`. These are actual external file hashes, not copied declarations. Raw Gradle trailing whitespace is retained and distinguished from source defects in the integrity report.

## Independent checks from fresh archive

A new `git archive b883042` was extracted to external `verifier/source-b883042`; it contains no preexisting build products. Archive SHA-256 and all73 source hashes are recorded in `build-inputs.json`, and inputs remained unchanged after execution. Existing dedicated build-env values were loaded without printing them. One offline Gradle used no daemon reuse, workers1, Xmx1024MiB, Metaspace384MiB and in-process Kotlin. It ran `:core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :nativeDriver:assembleDebug :nativeDriver:lintDebug`.

Gradle exit0 in165.646967333seconds. Independent core93+app105=198tests passed, with failures/errors/skips0. App and driver assemble/lint completed; errors0, warnings app13/driver8. The complete raw log, copied JUnit XML and lint XML are preserved in this directory. Independent `npm test` passed94/94, fail/cancel/skip0, exit0 in15.501354375seconds. These are current independent executions rather than reuse of builder XML. No failed current build attempt was discarded.

The independent APK full hashes are app `a4e7a6d0368b66f8445661d07aa819921d8f1170fc586088ef0e54aa1a74ab7a` and driver `f457c4261f0be536cf63ef7ab0b72badfbe56dc65e1ff12dc2977e5fa716abe9`. Full-file hashes differ from the coordinator's operational packages. Every decompressed ZIP entry has the same name and SHA-256, app886/driver883, with zero payload differences. `apk-payload-comparison.json` preserves this distinction. The reason for the outer-container byte difference is not inferred as a source or runtime difference.

Eight independent package commands (signature verification, permissions, badging and manifest for each module) returned exit0. The product declares RECORD_AUDIO and the existing specialUse FGS type; no microphone FGS type was added. The driver remains testOnly and its instrumentation targets its own package. Static package permissions do not establish the actual user10 grant state; that requires the coordinator's device evidence.

## Source review and explicit limits

The PCM source checks RECORD_AUDIO before construction and start, validates AudioRecord initialized and recording state, reads PCM16 short arrays nonblocking and guards native close with AtomicBoolean. The bounded capture worker uses explicit little-endian conversion, a480000-byte/15-second cap, handles partial reads and negative results, closes before dispatching completion, and clears capture/sample/queued-delivery buffers on cancellation. Source creation, start, read and completion are guarded against cancellation. File persistence and network capabilities are absent from this capture class.

The gateway explicitly constructs Pleos ON_DEVICE/Korean and has no SDK request-microphone fallback. It only submits the in-memory bytes after capture completion. Generation checks discard stale callbacks and zero stale output. Submitted-only result acceptance, pending engine cleanup and a fixed15-second final wait prevent interim promotion or repeated ended events extending the window. Android permission results are one-shot and must match current foreground/composer/room/member/room epoch/native epoch/page generation before capture can begin. UI stop, cancel, exit and convoy stop clear authority. Actual microphone release remains a runtime claim; neither a trace name nor a cleanup deadline proves physical release.

The original negative-utterance bug is covered by the current independent VoiceDraftTest and preserved original failure evidence. Local rules are explicitly bounded and do not claim generative AI understanding. Candidate matching filters original current native saved-place objects, never creates IDs/coordinates or infers the category of `가든서비스`. No automatic proposal or route capability is present in recognition or draft analysis. Existing snapshot/text-revision guards, explicit name/address selection, editable reason and explicit send continue into the unchanged proposal/unanimous/fresh-native confirmation path. Existing exact-point and selected-place meanings remain separate.

A pending test risk was sent to the coordinator: the engine cleanup queue is owned by each Activity's gateway instance. A rapid Activity recreation/new start can overlap the previous instance's delayed release even though same-instance retry is serialized. This is not a confirmed failure. The coordinator added immediate font-change/recreation/restart observation to Q03. It must be assessed with actual timestamps rather than assumed safe from the JVM seam.

## Current official API check

The official [Pleos mode guide](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/SpeechToText/intro) remains based on3.3.1 and identifies ON_DEVICE as vehicle-local, without network/app registration. The implementation names ON_DEVICE explicitly; no cloud key, external LLM or paid service was introduced. The official [PCM guide](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/SpeechToText/sending-audio-data) supports whole-buffer sendAudio followed by completeAudioSend. These contracts do not prove a successful actual recognition.

The [Android AudioRecord API](https://developer.android.com/reference/android/media/AudioRecord) documents PCM16 short reads, nonblocking read semantics and recording-state/stop/release distinctions. The [runtime permission guide](https://developer.android.com/training/permissions/requesting) requires permission at use and contextual user approval. Both were directly opened for this review. Public AAR/POM hashes and independently executed javap-public declarations are recorded in `sdk-public-audit.json`; AAR SHA-256 is `35109ad13400a7fa103cca9fabbf9b785e63a6a98b17a0698a1aef51458d37bf`. No private SDK internals, credentials, encrypted session blobs, raw audio or private utterances were inspected.

## Evidence already inspected

The committed human-recognition-B observation names the correct device/user/product APK and includes actual init, app recording, stop, app closure, SDK submission, started recognition and accepted finalText events. It has no failure event in that attempt. The recording→stop interval is4366ms, stop→final8026ms and submission→final6084ms, within the15-second wait. The app-op read showed no active recording. Phrase accuracy is the user's screen observation of a public test phrase; the journal intentionally contains lifecycle only. This supports that historical B attempt and must not be expanded to A, cancellation, current UI, proposal consent or45-second continuity.

Earlier synthesized-speaker timeout and freeze6 timeout evidence remains preserved. Earlier accepted route activity observed before the new scenarios is a baseline, not current pending/reject0 or new unanimous-acceptance evidence. The coordinator reported a corrected apparatus classification for onDestinationInfo and native foreground; those old apparatus attempts remain distinct from current cases.

## AC status before final runtime handoff

| AC | Status | What remains |
|---|---|---|
| AC1 | BLOCKED | Source/JVM lifetime guards reviewed; current A/B explicit cancel, stop, exit, retry and physical microphone observations, including rapid recreation. |
| AC2 | BLOCKED | Analysis and snapshot guards pass source/JVM review; current actual edited-draft, no-match/negative and stale selection/candidate UI evidence. |
| AC3 | BLOCKED | Existing safeguards pass regressions; current two-car receipt, pending/reject routes0, explicit unanimous apply, actual fresh query and separate selected/native metadata. |
| AC4 | BLOCKED | Independent JVM/HTTP/build/lint done; actual Pleos recognition/edit/candidate/send/failure/cancel, long-text and fontScale1.3 evidence. |
| AC5 | BLOCKED | Existing B human recognition is bounded evidence; current exact-package two-AVD scenarios and final head binding are still required. |

The reviewer will inspect the coordinator's final raw execution evidence and any evidence-only commit, recheck73-file binding and baseline preservation, and issue a new exact-head verdict. All mandatory conditions must have direct evidence before PASS. A changed base/head or requirement invalidates an earlier exact-head verdict; product changes require renewed source/build review. This intermediate document must not be represented as approval.
