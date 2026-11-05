# Issue #14 builder verification

- Base: `7d9dcc053c8dfe2b8ce1f22fef3c17b9c8c54856`
- Branch: `be/issue-14-vehicle-auto-follow`
- Version: uncommitted runtime freeze 2; final Git head is assigned by the coordinator after review.
- Scope: #14 only. Prior #12 evidence is preserved. No ADB, emulator, UI, Git commit/push, PR, or issue mutation was performed by the builder.
- Source/APK manifest: [source-manifest.json](source-manifest.json). Frozen runtime source SHA-256 `76c4350628c272f0c04d6405b2819c732954ab9a6ef2db405ed36d925034e0cb`; all 40 build inputs SHA-256 `72af78492463b80978ad5882ea229114c94ce4db5bd7e0eb2de0fda9dc93a5a9`.

## Executed checks

| Command/check | Result | Evidence |
| --- | --- | --- |
| `npm test` | exit 0; 76/76 pass | RAW `builder/npm-freeze-2.log`, hash in source manifest |
| Gradle `:core:test` | exit 0; existing 42 + new 29 = 71 pass | frozen JUnit XML and `builder/gradle-freeze-2.log` |
| Gradle `:app:testDebugUnitTest` | exit 0; existing 21 + new 5 = 26 pass | frozen JUnit XML; actual Node HTTP/SSE ephemeral loopback fixture |
| `:app:assembleDebug :app:assembleDebugAndroidTest :nativeDriver:assembleDebug` | exit 0 | frozen APK hashes and build log |
| `:app:lintDebug :nativeDriver:lintDebug` | exit 0; 0 errors | frozen lint XML/text; app 5 warnings, driver 2 warnings |
| aapt permissions/badging/manifest | exit 0 | app exact 6 permissions; driver exact 2 NAVI permissions; self target and no Activity |
| apksigner verify/print-certs | exit 0 | frozen app/driver signer output |

The five existing lint warnings are unused `muted`/`space` tokens, application icon, and two concatenated UI text warnings. Driver has application icon and data extraction rules warnings. The test-only driver stores only public POI test reports and disables cloud backup; it contains no room credentials. No suppressions or weaker lint/test criteria were introduced. Deprecated systemWindowInset getters remain from the existing View implementation.

Automatic core checks cover opt-in/default off, role isolation, nullable metadata, event coalescing, bounded 15-minute simulated identical observations, publish coalescing, actual absent versus invalid/timeout/cancel, latest-target replacement, same-target higher revisions, strict match, null waiting without native cancel, stale listener/room epochs, explicit stop, off→on, original-deadline query-only cancellation, same-room query-only resume, and failed-version request loop suppression.

The service-owned runtime checks use real authenticated Node HTTP/SSE and cover UI observer detach/rebind without runtime duplication, continued offscreen strict confirmation and later changes, queued SSE delivery versus immediate stop, explicit restart, room-close authentication cleanup, process replacement restoring only credentials, and close during pending authentication. The stop check requires the synchronous fence to complete within one second. Before the transport fix the owner suite took about 45 seconds due to a blocking SSE disconnect; the fixed suite completes without waiting for a heartbeat (0.14 seconds at freeze 1). The closed transport's bounded cleanup thread never retries or calls the SDK.

## Freeze 2: observed post-confirmation cancellation race

The coordinator directly reproduced a freeze-1 defect before starting the 15-minute window: request `1793859026894`, strict match `1793859032275`, then a global route cancellation `1793859033624` after the operation had completed. The FGS and process stayed alive, but the code discarded the recent confirmation and used an OBSERVE read that could not restore the confirmation state. The coordinator's actual native query still matched the shared public POI. Those freeze-1 observations remain historical evidence, not a passing continuity run.

Freeze 2 changes only `VehicleCoordinator.kt` and `VehicleAutomationTest.kt` among the 40 build inputs. The coordinator captures a recent confirmation only for the same authenticated room, latest revision/target, and original deadline, then starts a fresh listener and query-only verification. It clears success before the read and requires a new strict actual match; it does not re-request the route or extend the deadline. Identical-target higher revisions adopt the recent snapshot revision, and a late destination-change event follows the same fresh-query rule. Empty/wrong/unanswered/expired results, stop/leave, a newer target, stale callbacks, and repeated cancellation cannot restore the old success.

Ten additional core tests exercise those cases and assert no additional SDK route requests. Node 76, core 71, and app 26 pass at freeze 2 (173 total); all three APK builds, both lint tasks, manifest/permission and signer checks exit 0. [Freeze-1 source manifest](source-manifest-freeze-1.json) and all raw APKs/logs remain preserved. The required native run must use the new freeze-2 app hash and new initial opt-in.

## Coordinator native execution on freeze 2

The builder directly read the coordinator-owned `RAW/continuous-freeze2/result.json`, `changes.json`, `source-start.json`, `source-end.json`, `RAW/notification-stop-receipt.json`, and completed `RAW/stop-negative-freeze2/result.json`. The coordinator operates ADB/UI and selects the repository [runtime evidence](runtime/); the builder did not execute device actions or modify that directory. These observations await the separate verifier and exact final Git head.

The continuous window began at `2026-11-05T06:25:58.594303Z` and ran for **907.743 seconds (15 minutes 7.743 seconds)** by the collector's monotonic measurement. The 57 paired state samples show both native Navigation surfaces in the foreground, the convoy Activities STOPPED with `uiCount=0`, foreground services active, and fixed production PID/service epochs. Lifecycle journals record zero Activity reentries for either vehicle. A uses PID `20878` and service epoch `12836184`; B uses PID `4948` and service epoch `12421167`. Source checks before and after the run compare all 40 inputs with zero mismatches against freeze 2. The frozen app APK SHA-256 is `f96663e4da4ab428e9656841b0d2e4bbce592008e712eb0e79a0e9af8ee57fe4`; self-target driver APK SHA-256 is `d71239e388a415489aa163f7d7a809eb9d0cfbf92ed94d6f786f66eb1a5a406f`.

| A change after start | Actual public native destination | Shared revision confirmed by B | New B route requests | Actual A/B strict match |
| --- | --- | --- | --- | --- |
| 24.436 seconds | 소프트웨어드림센터 | 3 | 1 | observed |
| 302.358 seconds | 42dot | 5 | 1 | observed |
| 633.147 seconds | 소프트웨어드림센터 | 7 | 1 | observed |

The third change occurs after ten minutes. A's native replacement exposes an actual complete empty response before each new target, so the leader records six automatic publications: three nulls and three target values. B records zero publications and three route requests in the window. Shared null leaves B's native guidance intact while it waits; it does not add a route request or issue a native cancel. Each final B result has a fresh strict native query match. SDK routing-completed events were not observed and are not claimed as successful route search completion.

After the window, the coordinator opened the real notification panel (`RAW/ui/22-notification-panel.png`) and tapped the `동행 중지` action exposed by `com.android.systemui`. The receipt records B's `serviceDestroy`, automatic OFF, disconnected room/SDK state, no foreground service, native Navigation still foreground, and `uiCount=0`. This is an actual notification action, not a production test hook or an Activity return.

The subsequent stop-negative run completed in 98.255966 seconds, including **77.967521208 seconds after the new A source operation completed**. A changed to actual native 42dot and its shared revision advanced from 7 to 9 through actual empty/target observations. B made **zero** new route requests and **zero** Activity reentries, kept automatic OFF with no foreground service, and its actual native query remained 소프트웨어드림센터 before and after. The source driver and both B query calls target only the separate driver package; recorded production PID checks remain unchanged. The continuous collector's earlier `stopNegativeStatus: PENDING` field is superseded by this separate completed stop-negative result, not rewritten or treated as completion evidence by itself.

## Acceptance evidence and independent review

| AC | Evidence now available | Review state |
| --- | --- | --- |
| AC1 | default-off role opt-in; service ownership/type/property; owner/lifecycle tests; actual native foreground, FGS/notification continuity and Activity reentries 0/0 | separate verifier pending |
| AC2 | actual native metadata/null, role/CAS/epoch/revision/coalescing and real HTTP/SSE regression; actual A changes and six publications, B publications 0; server unchanged | separate verifier pending |
| AC3 | automatic latest target, original-deadline query-only cancellation and strict-match tests; three actual B queries matching A with one request per new target and no Activity return | separate verifier pending |
| AC4 | volatile consent, nonsticky/task-stop manifest, stop/leave/auth/process/async fence tests; actual notification stop/service destruction; 77.967521208 seconds after new A source completion with B requests/reentries 0 and actual destination unchanged | separate verifier pending |
| AC5 | frozen self-target driver; 907.743-second official two-emulator run, 57 paired samples, three changes including after ten minutes, exact APK/source binding and completed stop-negative observation | selected runtime hash reconciliation, final head and separate verifier pending |

No overall acceptance or merge approval is recorded here. Unit tests, source review, SDK builds, and coordinator runtime observations remain distinct from the independent verifier's decision. Physical vehicles, routing-completed events, Google Play/Pleos distribution review, and specialUse store approval are unverified and outside this prototype run. Automatic mode needs no return within 45 seconds; the original **per-operation** 45-second native confirmation deadline remains.
