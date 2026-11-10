# Destination change requests — builder evidence

## Current corrected candidate — cycle 2

The current production head is `95073bc9a63545e1323d425281ac04b7cae7de80`. It corrects independently reproduced AC2 failures in the first candidate: a membership change before the first request left a sender preview valid, and a native failure after review left the same stale preview visible/sendable. A third confirmed boundary allowed C1-control member names through the server while the new Android request parser rejected them. The initial candidate's evidence remains below as history; it is not the final runtime candidate or an AC2 PASS.

Sender review and its unfinished native re-read now belong to the last observed room sequence. Any newer frame invalidates them, including a `members` frame whose request remains null. Equal-sequence duplicates retain the review. This is a conservative sender fence that leaves the request wire/API unchanged; a later recipient response also requires fresh sender review. Cancelling an unfinished read retires its SDK listener and pending sender callback authority. A reviewed preview also disappears when native state loses its connected/current-present observation, including failure, absence, disconnect or a new read. `canSend` independently requires that currently connected native value to equal the reviewed target.

The server rejects the complete C0/DEL/C1 control ranges in member names at create/join, matching the new Android person parser. Normal Korean, Latin, punctuation and emoji names remain accepted. This is the only server change in cycle 2; the final server must replace only the coordinator-owned isolated4196 process, leaving4190/4192 intact.

Two new JVM regressions first failed against the old code, then passed after correction. They cover join/leave before any request, stale native callback during a send-time read, equal-sequence duplicates, and failure/absence/disconnect after review. A third real VehicleSession HTTP/SSE test proves the membership frame reaches and invalidates the actual sender controller. The C1 HTTP test first observed201 instead of400, then passed for U+0080/U+0085/U+009F at both entry paths while retaining ordinary names. [Red logs, exact commands and corrected results](builder/cycle-2/summary.json) preserve the failure and fix.

| Corrected check | Result |
|---|---|
| Node full suite | exit0,101/101 pass; fail/cancel/skip0 |
| Core JVM |94 tests; failure/error/skip0 |
| App JVM |116 tests; failure/error/skip0 |
| App and driver build/lint |exit0; lint errors0, warnings14/8 |
| Package/signature checks |9 commands exit0; same previous update signer |
| Source input manifest |67 files; hash mismatch0 after commit |

| Corrected artifact | SHA-256 |
|---|---|
| App APK |`3bc9d57db01e94e31382110eca3cd24f0a1544fa4359a15d22452310330e8c08` |
| Driver APK (unchanged) |`913cf8ada6340c14cb0cb93ec4f1fcef62164ed07399630de8142ec6f0575ba6` |
| Server source |`6338890a40005b7ba02149d9cff8838c344d477025eba73d9d4978ee639674f6` |
| Source input manifest |`d1b1ac1b10ce518088bc9bfcea33a9c25e41ec5b3dad3f0dc2008a23c3a6fa17` |

The new immutable APK copies are outside Git at `product-run-artifacts/destination-change-requests-20261110/builder/cycle-2/candidate/app-debug.apk` and `driver-debug.apk`. [Corrected source hashes](builder/cycle-2/source-inputs.json), [package audit](builder/cycle-2/package-audit.json), [build result](builder/cycle-2/build-result.json), [test/lint counts](builder/cycle-2/test-counts.json), [full Node log](builder/cycle-2/node-all.log) and [artifact hashes](builder/cycle-2/artifact-hashes.json) bind this revision. No device state or live server was changed by this builder. Actual same-build two-car validation and a new independent verifier verdict remain required. The original deadline and execution state were preserved.

## Initial candidate — preserved history

Implementation base: `5ccc9ed4480fd12d96a2166ec2d4cc1850fe7f23`. Tested implementation head: `7a56c139cfe946e9f5fb5173cdedf83ed7e4e148`. The original execution deadline remains `2026-11-10T12:53:02Z`; no execution state was reset. This builder used the repository's builder behavioral contract as an assigned built-in worker, not an automatically discovered custom role.

## Result and boundary

The new primary flow is 길맞춤 actual native name/category search → user sets native destination → fresh name/address review → explicit peer request. A sender never routes itself again. Each recipient independently accepts or rejects; only a live local acceptance and its authenticated matching response starts one native operation. The existing strict follow-up verification remains separate from server acceptance and SDK transmission. Legacy unanimous proposals and old HTTP/SSE remain compatible through separate request state/events.

Builder completed Node/JVM regressions, both APK builds/lint, source/package hashes and update-signature checks. **Actual same-build two-car runtime, real search-result selection, Android notification observation and independent verifier verdict are not established by these tests.** The builder did not install an APK, mutate an AVD, start a live search/route, change a live server binding, or modify the original 4190/4192 rooms/auth. The coordinator owns those remaining observations.

## Executed validation

| Check | Result | Evidence |
|---|---|---|
| `npm test` from `prototype` | exit 0; 100/100 pass, fail/cancel/skip 0 | [full log](builder/node-all-final.log) |
| `node --test prototype/test/destination-change-requests.test.mjs` | exit 0; 6/6 pass | [focused HTTP/SSE log](builder/node-requests-final.log) |
| `:core:test` | 94 tests, failure/error/skip 0 | [counts](builder/test-counts.json) |
| `:app:testDebugUnitTest` | 113 tests, failure/error/skip 0 | [counts](builder/test-counts.json) |
| `:app:assembleDebug :app:lintDebug` | exit 0; lint 0 errors, 14 warnings | [final command/result](builder/candidate-build-result.json), [log](builder/candidate-build.log) |
| `:nativeDriver:assembleDebug :nativeDriver:lintDebug` | exit 0; lint 0 errors, 8 warnings | same command/result and log |
| APK signature, badging, permissions, manifest | all 9 commands exit 0; update signer equals previous installed-build signer | [package audit](builder/candidate-package-audit.json) |
| `git diff --check` | exit 0 before implementation commit | builder command record; no whitespace findings |

The final Gradle invocation used the existing offline JDK21/Gradle8.11.1/SDK35 toolchain and original `ANDROID_USER_HOME`/debug keystore, one worker, in-process Kotlin and bounded JVM memory. It finished in 25.938 seconds; unchanged tasks were up-to-date. The exact argv and environment-source reference are preserved in the result JSON. App JVM integration tests now declare the real Node server source as a Gradle test input, preventing an unchanged Kotlin test cache from hiding a server change. No lint baseline or suppression was added. Warnings concern unused resources, icon, View KTX suggestions, localized text and the driver's existing extraction-rules declaration; they are retained in reports.

The focused server suite uses actual authenticated HTTP/SSE with at least three members. It covers independent accepted/pending/rejected rows, concurrent different recipients, same-response idempotency, opposite-response conflict, duplicate-create current frame versus historical result, stale creation/request versions, foreign credentials, membership invalidation, exact expiry, metadata bounds, bounded history and unchanged legacy destination/revision/events. It does not call a native SDK.

Seven new request-controller JVM scenarios cover changed target after preview requiring reconfirmation, sender route 0, page-exit cancellation and late callback fencing, receipt route 0, individual acceptance route 1 with fresh strict verification, another recipient's rejection, duplicate frame/tap guards, stop before HTTP completion, cold accepted snapshot display-only, replacement/stale action, preview/request expiry, and already-issued verification retaining its original bound after response expiry. A session-level real SSE test proves a new request stops legacy automatic following and cannot be replayed through its generic path. A core test proves repeated connection callbacks cannot issue another route. These tests use a deterministic fake native gateway and are not actual vehicle success evidence.

## Exact candidate

| Item | SHA-256 |
|---|---|
| App APK (`0.4.0-issue29`, versionCode 2) | `8ef0133744879640a982caaf22e2d196ec29c15026e61a8bcf772de7892ae781` |
| testOnly self-targeted driver APK | `913cf8ada6340c14cb0cb93ec4f1fcef62164ed07399630de8142ec6f0575ba6` |
| Previous app APK preserved for signer comparison | `5b9eec0cca7f645e25ef4615cd5096d8e31b9256141d35f8d5018bc2c398317e` |
| All three certificate SHA-256 | `75637c02c025fbaae54561060852049f962cba132025e7b755a2dbc8133332c1` |
| `prototype/server.mjs` | `2fb1d0686d4f3dd50808792d3dfb25d236723d357def1c1a673473325c3e0142` |
| 67-file source input manifest | `ec5bacccc79812dd4028d9db101a192d0000a25f09af19d8c8294ecbc77d8f52` |

The immutable candidate copies are outside Git at `product-run-artifacts/destination-change-requests-20261110/builder/candidate/app-debug.apk` and `driver-debug.apk`. Built outputs remain at the normal app/nativeDriver debug APK locations. [Source input hashes](builder/source-inputs.json), [package commands](builder/candidate-package-audit.json), [build summary](builder/build-summary.json) and [curated artifact hashes](builder/artifact-hashes.json) bind the evidence. No APK, keystore, cache, encrypted session blob, credential, invitation or raw audio is committed.

Production adds only the documented normal `NAVI_CUSTOM_MAP` declaration required for public SearchPlace. Earlier #19 observed its actual protectionLevel 0; this build does not force-grant it or change platform security. Existing microphone permission remains for the legacy optional voice screen, while the new primary flow never requests microphone permission or starts capture. Driver instrumentation still targets its own package and the helpers operate only visible bounded product input/button IDs.

## AC mapping and remaining work

| AC | Builder evidence | Remaining runtime evidence |
|---|---|---|
| AC1 | Foreground Activity-context public SearchPlace, bounded keyword/init/cancellation, no fabricated result callback/list/provider | Actual name/category result panels, user selects and sets native destination, fresh observed name/address on same candidate |
| AC2 | Fresh preview plus send-time re-read/reconfirmation, 15-second preview, page/scope fences, sender route 0 JVM | Actual search→set→return→review→send and sender native destination unchanged by app send |
| AC3 | Independent authenticated recipient protocol, real 3-member HTTP/SSE, strict once-only JVM application and verification | Actual receiving car route 0 before accept, route 1 after accept and fresh native match |
| AC4 | Separate high-importance request notification; explicit receive start/stop; FGS lifetime; stale/expiry/stop/cold/automatic fences | Notification while native nav is foreground, tap opens current review, stop/replace/expiry cancels old authority on real AVDs |
| AC5 | Node 100, core 94, app 113; app/driver build/lint; exact hashes and matching update signer; no existing runtime mutation | Coordinator's isolated4196 HTTP/SSE rerun against final server, same-build two-car runtime, preservation/restore evidence, independent verifier |

## Preserved failures

The first Gradle run failed compilation on three nullable request accesses in MainActivity. An explicit non-null sender guard fixed them. The second run compiled/tests/built the APK but lint failed `MissingPermission` for `pleos.car.permission.NAVI_CUSTOM_MAP` at the public SearchPlace call. The normal manifest declaration fixed that error. Their original logs and result JSON remain under the builder's external product-run-artifacts directory. Later successful commands do not replace those failure records. Earlier Node 94-test regression and preliminary success builds are not counted as extra final tests. No runtime/AC5 PASS is inferred from compilation or these corrections.
