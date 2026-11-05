# Issue #14 verification history and freeze-3 native evidence

- Base: `7d9dcc053c8dfe2b8ce1f22fef3c17b9c8c54856`
- Branch: `be/issue-14-vehicle-auto-follow`
- Build/source snapshot: runtime freeze 3, based on reviewed head `0b92a6b4df1fb97c811842d0f0b455ccc9e5590b`. The initial builder hand-off was at `2026-11-05T07:19:25.276683+00:00`; final independent verdict and merge state are in [PR #15](https://github.com/wontop02/pleos-destination-share/pull/15).
- Scope: #14 only. Prior #12 evidence is preserved. No ADB, emulator, UI, Git commit/push, PR, or issue mutation was performed by the builder.
- Source/APK manifest: [source-manifest.json](source-manifest.json). Frozen runtime source SHA-256 `f9215df3cff1ac036a3ce4d7a5ec44ff20064060bb30dc74001afa4142cf316b`; all 40 build inputs SHA-256 `7036d13a14dda56a79481282f77f211fb24f4ab1bba6b15c6304bc91f0ee707a`. The prior current manifest is preserved byte-for-byte as [source-manifest-freeze-2.json](source-manifest-freeze-2.json), SHA-256 `a5bfcef798928a8b64ef1323e56f69b78dfc5f94a052f19b1d3d5db5a3eddae4`.

## Freeze-3 executed checks

| Command/check | Result | Evidence |
| --- | --- | --- |
| `npm test` | exit 0; 76/76 pass | RAW `builder/freeze-3/npm.log`, hash in source manifest |
| Gradle `:core:test` | exit 0; existing 42 + new 29 = 71 pass | frozen JUnit XML and `builder/freeze-3/gradle.log` |
| Gradle `:app:testDebugUnitTest` | exit 0; existing 21 + new 5 = 26 pass | frozen JUnit XML; actual Node HTTP/SSE ephemeral loopback fixture |
| `:app:assembleDebug :app:assembleDebugAndroidTest :nativeDriver:assembleDebug` | exit 0 | frozen APK hashes and build log |
| `:app:lintDebug :nativeDriver:lintDebug` | exit 0; 0 errors | frozen lint XML/text; app 5 warnings, driver 2 warnings |
| aapt permissions/badging/manifest | exit 0 | app exact 6 permissions; driver exact 2 NAVI permissions; self target and no Activity |
| apksigner verify/print-certs | exit 0 | all three frozen APKs; combined with aapt, 12 inspection checks exit 0 |

All 173 existing checks were rerun; Gradle used `--rerun-tasks` and executed all 125 tasks. No string-mirroring test was added for the notification edit. The five existing lint warnings are unused `muted`/`space` tokens, application icon, and two concatenated UI text warnings. Driver has application icon and data extraction rules warnings. The test-only driver stores only public POI test reports and disables cloud backup; it contains no room credentials. No suppressions or weaker lint/test criteria were introduced. Deprecated systemWindowInset getters remain from the existing View implementation.

Automatic core checks cover opt-in/default off, role isolation, nullable metadata, event coalescing, bounded 15-minute simulated identical observations, publish coalescing, actual absent versus invalid/timeout/cancel, latest-target replacement, same-target higher revisions, strict match, null waiting without native cancel, stale listener/room epochs, explicit stop, off→on, original-deadline query-only cancellation, same-room query-only resume, and failed-version request loop suppression.

The service-owned runtime checks use real authenticated Node HTTP/SSE and cover UI observer detach/rebind without runtime duplication, continued offscreen strict confirmation and later changes, queued SSE delivery versus immediate stop, explicit restart, room-close authentication cleanup, process replacement restoring only credentials, and close during pending authentication. The stop check requires the synchronous fence to complete within one second. Before the transport fix the owner suite took about 45 seconds due to a blocking SSE disconnect; the fixed suite completes without waiting for a heartbeat (0.14 seconds at freeze 1). The closed transport's bounded cleanup thread never retries or calls the SDK.

## Freeze 2: observed post-confirmation cancellation race

The coordinator directly reproduced a freeze-1 defect before starting the 15-minute window: request `1793859026894`, strict match `1793859032275`, then a global route cancellation `1793859033624` after the operation had completed. The FGS and process stayed alive, but the code discarded the recent confirmation and used an OBSERVE read that could not restore the confirmation state. The coordinator's actual native query still matched the shared public POI. Those freeze-1 observations remain historical evidence, not a passing continuity run.

Freeze 2 changes only `VehicleCoordinator.kt` and `VehicleAutomationTest.kt` among the 40 build inputs. The coordinator captures a recent confirmation only for the same authenticated room, latest revision/target, and original deadline, then starts a fresh listener and query-only verification. It clears success before the read and requires a new strict actual match; it does not re-request the route or extend the deadline. Identical-target higher revisions adopt the recent snapshot revision, and a late destination-change event follows the same fresh-query rule. Empty/wrong/unanswered/expired results, stop/leave, a newer target, stale callbacks, and repeated cancellation cannot restore the old success.

Ten additional core tests exercise those cases and assert no additional SDK route requests. Node 76, core 71, and app 26 pass at freeze 2 (173 total); all three APK builds, both lint tasks, manifest/permission and signer checks exit 0. [Freeze-1 source manifest](source-manifest-freeze-1.json) and all raw APKs/logs remain preserved. The following coordinator run used those freeze-2 APKs and new explicit opt-in. It does not validate the subsequent freeze-3 APK.

## Coordinator native execution on freeze 2

The builder directly read the coordinator-owned `RAW/continuous-freeze2/result.json`, `changes.json`, `source-start.json`, `source-end.json`, `RAW/notification-stop-receipt.json`, and completed `RAW/stop-negative-freeze2/result.json`. The coordinator operates ADB/UI and selects the repository [runtime evidence](runtime/); the builder did not execute device actions or modify that directory. The independent verifier checked these observations at exact head `0b92a6b4df1fb97c811842d0f0b455ccc9e5590b`; its overall F1 failure is recorded below. They do not substitute for a fresh freeze-3 run.

The continuous window began at `2026-11-05T06:25:58.594303Z` and ran for **907.743 seconds (15 minutes 7.743 seconds)** by the collector's monotonic measurement. The 57 paired state samples show both native Navigation surfaces in the foreground, the convoy Activities STOPPED with `uiCount=0`, foreground services active, and fixed production PID/service epochs. Lifecycle journals record zero Activity reentries for either vehicle. A uses PID `20878` and service epoch `12836184`; B uses PID `4948` and service epoch `12421167`. Source checks before and after the run compare all 40 inputs with zero mismatches against freeze 2. The freeze-2 app APK SHA-256 is `f96663e4da4ab428e9656841b0d2e4bbce592008e712eb0e79a0e9af8ee57fe4`; self-target driver APK SHA-256 is `d71239e388a415489aa163f7d7a809eb9d0cfbf92ed94d6f786f66eb1a5a406f`.

| A change after start | Actual public native destination | Shared revision confirmed by B | New B route requests | Actual A/B strict match |
| --- | --- | --- | --- | --- |
| 24.436 seconds | 소프트웨어드림센터 | 3 | 1 | observed |
| 302.358 seconds | 42dot | 5 | 1 | observed |
| 633.147 seconds | 소프트웨어드림센터 | 7 | 1 | observed |

The third change occurs after ten minutes. A's native replacement exposes an actual complete empty response before each new target, so the leader records six automatic publications: three nulls and three target values. B records zero publications and three route requests in the window. Shared null leaves B's native guidance intact while it waits; it does not add a route request or issue a native cancel. Each final B result has a fresh strict native query match. SDK routing-completed events were not observed and are not claimed as successful route search completion.

After the window, the coordinator opened the real notification panel (`RAW/ui/22-notification-panel.png`) and tapped the `동행 중지` action exposed by `com.android.systemui`. The receipt records B's `serviceDestroy`, automatic OFF, disconnected room/SDK state, no foreground service, native Navigation still foreground, and `uiCount=0`. This is an actual notification action, not a production test hook or an Activity return.

The subsequent stop-negative run completed in 98.255966 seconds, including **77.967521208 seconds after the new A source operation completed**. A changed to actual native 42dot and its shared revision advanced from 7 to 9 through actual empty/target observations. B made **zero** new route requests and **zero** Activity reentries, kept automatic OFF with no foreground service, and its actual native query remained 소프트웨어드림센터 before and after. The source driver and both B query calls target only the separate driver package; recorded production PID checks remain unchanged. The continuous collector's earlier `stopNegativeStatus: PENDING` field is superseded by this separate completed stop-negative result, not rewritten or treated as completion evidence by itself.

## Independent freeze-2 F1 verdict and freeze-3 correction

The independent terminal verdict at head `0b92a6b4df1fb97c811842d0f0b455ccc9e5590b` was **overall FAIL**: AC1 and AC3 failed F1; AC2, AC4, and AC5 passed for that exact freeze-2 source/APK binding. The verifier directly recalculated the 907.743-second run and stop evidence, reproduced another actual native change and notification stop, and checked the source/tests. The functional continuity result did not satisfy the notification readability requirement. The preserved [freeze-2 review](review-freeze-2/) remains the authority for that historical verdict.

The actual Pleos SystemUI screenshot `RAW/verifier/ui/06-follower-notification.png` and XML show the status only in the dark body: RGB(51,51,51) on RGB(40,42,45), strongest measured contrast 1.138966520614428:1. The title and stop action are bright. The builder read the report/verdict/contrast data and inspected the unedited screenshot/XML. Freeze 3 changes one notification builder line: the existing computed status leads the standard title as `status · 동행 중 · role`, while the duplicate `setContentText` and BigText body are removed. Role, stop PendingIntent, status branches, SDK policy, permissions, and all ten test source files are unchanged. No custom RemoteViews, media workaround, or new permissions are introduced.

Frozen freeze-3 APKs and exact input hashes are in the current source manifest. The new APK hashes are:

| APK | SHA-256 |
| --- | --- |
| app | `264605564a27d7cdc05578769d509b1c28220917cdf7dc605df43437e11cc216` |
| app-target probe, not used during continuity | `d6fae10675031385048cf4b6ae769dd46ddabe6824fe390fa096046e3cc78a7c` |
| self-target native driver | `8e534886360623b27aede4c49f8fdc1c33b8d89bd2e0b6d2b4949fa59e397ea9` |

The driver source did not change, but its container hash changed on the full rebuild. Public APK comparison (`RAW/builder/freeze-3/apk-reproducibility.json`) confirms all 51 ZIP entry payloads and timestamps are identical; entry order, offsets, container overhead, and the resulting signing-block bytes differ. Both use the same public certificate SHA-256 `75637c02c025fbaae54561060852049f962cba132025e7b755a2dbc8133332c1` and verify successfully. The app has only `classes4.dex` payload changes, from 134892 to 134652 bytes; its other 53 entry payloads and timestamps are unchanged. Only public APK/signature outputs were read; no private keys, settings, or credentials were inspected. Actual installation must still match the new frozen whole-APK hashes.

## Coordinator native execution on freeze 3

The initial builder hand-off at `2026-11-05T07:19:25.276683Z` preceded native contrast and continuity checks. Later, the builder directly read the coordinator-owned `RAW/freeze-3-run/notification-contrast-preflight.json`, unedited 18b PNG/XML, `continuous-freeze3/result.json`, `changes.json`, and source-start/end checks. The coordinator alone operates ADB/UI and owns [freeze-3 selected runtime evidence](runtime-freeze-3/); the builder did not modify that directory. The coordinator's freeze-3 selection contains 106 byte-identical raw artifacts totaling 4,621,432 bytes; its [manifest](runtime-freeze-3/manifest.json) SHA-256 is `a2f98ac396776f8ce7c1a9d6e1738685b3ea395f264a5c2ccc90f71d41d0b63a`. The historical freeze-2 selection of 96 artifacts remains under `runtime/`, with its separate F1 review preserved. These observations are coordinator execution evidence, not a new independent verdict or merge approval. Final independent verdict and merge state are in [PR #15](https://github.com/wontop02/pleos-destination-share/pull/15).

The actual status-first title `순정 목적지 일치 확인 · 동행 중 · 참여 · 자동 따르기` is fully displayed in `ui/18b-freeze3-confirmed-notification.png` and its SystemUI XML. The measured solid title RGB is (255,255,255), background RGB is (40,42,45), and contrast is **14.390448297873283:1**. The unreadable duplicate body is absent; the role and stop action remain visible. The initial 18 capture showed native Navigation because its final transition closed the notification panel. That failed observation is preserved. The coordinator reopened the panel and obtained 18b without app reentry, process/session restart, or another opt-in; the builder also inspected 18b visually. The 18 frame is not counted as notification evidence or a product failure.

The fresh continuous window began at `2026-11-05T07:27:30.544477Z` and lasted **908.721 seconds (15 minutes 8.721 seconds)**. Device uptime deltas were 908.77/908.72 seconds. The 55 paired samples show both native Navigation surfaces foreground, convoy Activities STOPPED with `uiCount=0`, foreground services active, and fixed production PID/service epochs. Journals record Activity reentries A/B 0/0. A PID/epoch is `24562/16526086`, B is `10889/16132415`. All 40 source/test/build inputs match freeze 3 at the beginning and end.

| A change after start | Actual public native destination | Shared revision confirmed by B | New B route requests | Actual A/B strict match |
| --- | --- | --- | --- | --- |
| 23.099 seconds | 소프트웨어드림센터 | 15 | 1 | observed |
| 304.198 seconds | 42dot | 17 | 1 | observed |
| 633.089 seconds | 소프트웨어드림센터 | 19 | 1 | observed |

The third change occurs after ten minutes. Actual empty/target observations produce six A publications, and B publishes zero. Each new target produces one B request and a fresh strict native query match; B totals three requests and three matches. Shared null remains a wait state and does not cancel native guidance. `onRoutingCompleted` was not observed and is not implied by these destination matches.

The builder directly read the completed `RAW/freeze-3-run/stop-negative-freeze3/result.json` and both actual notification-stop receipts before recording the stop result. B's real SystemUI `동행 중지` action produced serviceDestroy, automatic OFF, SDK DISCONNECTED, no foreground service, `uiCount=0`, and native Navigation foreground. A then changed to actual 42dot and advanced shared revision 19→21. The check lasted 107.922877333 seconds overall, including **81.347894375 seconds after the new A source operation completed**. B issued **zero** new route requests and **zero** Activity reentries; actual before/after queries remained 소프트웨어드림센터 with identical native metadata. The initial continuous result's `stopNegativeStatus: PENDING` remains unchanged historical output; the completed separate stop result supplies the later evidence.

A was then also stopped through its actual notification action. Its immediate receipt precedes service destruction; the builder also directly read `RAW/freeze-3-run/root-resource-final.json` at `2026-11-05T07:47:14.394007Z`. That final record shows both vehicles serviceDestroy, automatic OFF, SDK DISCONNECTED, `roomConnected=false`, no ServiceRecord/foreground service, `uiCount=0`, native Navigation foreground, and the same production PIDs. It reconfirms both dedicated official Pleos API-34 fingerprints and foreground user 10. The frozen source manifest's runtime-evidence PENDING records its earlier creation time; the later native/stop results are separate immutable observations. None of these records is rewritten to imply an independent verdict.

## Evidence scope and final decision

The preserved freeze-2 functional result (907.743 seconds) and independent overall F1 FAIL remain historical evidence for that source/APK/head. The freeze-3 source correction, measured title contrast, and fresh native execution form a separate binding. They do not rewrite the first review or claim an independent PASS for the new head. Final independent verdict and merge state are recorded in [PR #15](https://github.com/wontop02/pleos-destination-share/pull/15). Physical vehicles, routing-completed events, Google Play/Pleos distribution review, and specialUse store approval remain unverified. Automatic mode needs no return within 45 seconds; the original **per-operation** 45-second native confirmation deadline remains.
