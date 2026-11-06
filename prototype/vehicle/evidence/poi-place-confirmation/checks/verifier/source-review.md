# Issue #21 / PR #22 preliminary source verification

**Preliminary verdict: FAIL for one AC1 fixture/document provenance defect. No additional functional source defect found. This is not a final AC1–AC5 verdict or merge approval.**

- Base: `ff3e6a15067ee8285c83f318ec715899ed3f4364`.
- Reviewed source head: `72b778d69651a2b6e836e5bada4246f8d16a3633`.
- Branch: `be/issue-21-poi-confirmation`; PR: https://github.com/wontop02/pleos-destination-share/pull/22.
- Worktree: `/Users/wontop/Documents/ChatGPT/현대 모비스 해커톤/pleos-destination-share/.harness/worktrees/issue-21-poi-confirmation`.
- Frozen requirement: sibling `issue.md`, read directly together with AGENTS.md, docs/harness/README.md, DESIGN.md and .codex/agents/verifier.toml. The custom verifier role is not exposed in this session; a separate-context built-in agent follows that role file. No claim is made that the custom role ran.

## Finding

**[P2] Restore the exact #19 source and actual address literals before describing the fixture as original data.** `docs/product/poi-place-confirmation.md:25` and the new café fixtures use `경기도 성남시 수정구 창업로40번길6` and `수정구시흥동`. The immutable original `prototype/vehicle/evidence/issue-19-places/root/cafe-route-source.json:8` and `builder/runtime-route-comparison.json` retain `경기도 성남시 수정구 창업로40번길 6` and `수정구 시흥동`. The source comment at `core/src/test/.../SelectedPlaceConfirmationTest.kt:6` says these are actual originals. Ten altered address occurrences were found across the focused document and six test files; exact paths and lines are in `source-provenance.json`.

The production bookmark and native normalizers preserve address bytes correctly; the defect is the claimed provenance of fixture/document literals. Coordinates, complete identity and the approximately 51.03 m difference are accurate. Address is deliberately outside the identity matcher, so correcting these literals does not call for changing the matcher. Root acknowledged the finding and requested a new builder freeze. The original RED execution outputs and reconstructed RED source must remain immutable and retain their reconstruction disclosure.

## Direct source inspection

| Area | Evidence and conclusion at the reviewed head |
|---|---|
| Strict and selected semantics | `core/.../Destination.kt:21,47,56`: typed `PLEOS_PLACE` / `PLEOS_BOOKMARK`, complete nonempty expected ID/name, explicit empty supported provider, nullable sub-ID, finite geographic/display coordinates; actual must be `EXACT_POINT` with null source. Place match checks name/provider/ID/sub-ID, not only ID. The exact matcher body is byte-identical to base, SHA-256 `68f2e65204ff887967ca57b3d100768267da9f72f01980da79ac1c5af9930d91`. No tolerance expansion or learned expected point. |
| Actual observation and confirmation lifetime | `core/.../VehicleCoordinator.kt:74,408,422,541`: existing request/read/SDK epochs, dirty-read discard, active room/revision and original deadline gates remain in the confirmation seam. Only an issued follow-up read can create separate `DestinationConfirmation(source, actual)`. Route return/start does not confirm. Central update clears typed confirmation when confirmation state, room or source authority is lost. Error/timeout/stop/room replacement cannot restore it. |
| Proposal transport and authority | `prototype/server.mjs:43,205,372,409`, `app/.../RoomProtocol.kt:95,107,129`, `VehicleSession.kt:247,303`: kind/source remain in selected proposal, unanimous commit, SSE/snapshot and accepted provenance. Legacy omission remains exact; unknown kind/source, source without kind, omission of complete Pleos fields and unsupported provider reject. Direct publication rejects selected mode. A selected shared frame requires matching accepted provenance; an app proposal requires the current selected snapshot object. Existing consent/120-second expiry/version/CAS/replay protections remain. |
| Bookmark loading and UI | `app/.../SavedPlaces.kt:21,54,62,68,79,98,103`, `PleosNavigationGateway.kt:18,42`, `MainActivity.kt:197,245`: public helper/listener only; one read per native epoch, 8-second deadline, immutable cached reopening, unsupported-item filtering, all-invalid/oversized failure, room/member/native/UI lifetime invalidation. Dialog selection/cancellation captures its own snapshot; stale dismiss cannot cancel a newer snapshot. List is not uploaded or journaled; explicit proposal sends one selected candidate. UI reuses 20sp text/rows, at least 56dp buttons/cards/scroll and distinct place/coordinate wording. Actual current guidance has a separate card. No new permissions or foreground service. |
| Automatic lifetime | `core/.../VehicleCoordinator.kt:478`: fresh same-place native identity suppresses only redundant publication, without setting confirmation or overwriting selected source. Different native identity and ordinary exact coordinate changes keep the existing publication behavior. Existing session accepted-revision replay fence remains independent of publication protection. Source tests cover reconnect, restart and cold observations without manufactured success. |

No new production nativeDriver change, external provider, synthetic production POI, keyword callback, private SDK body or unrelated scope was found.

## Public support and provenance

The three cited public official website assets for BookmarkInfo, RouteInfo and DestinationInfo were read from the preserved #19 download and their SHA-256 values independently matched the recorded official URL manifests. They describe POI map identity, sub-ID sequence and distinct geographic/display coordinates. They do not promise input/output coordinate preservation, a canonical input coordinate, common entrance or common route. The implementation/document support claim is restricted to the existing NaviHelper 3.0.5 / navi-data 3.0.4 / Pleos Connect 2.1.0 environment and its explicit native empty provider. Actual two-car support remains to be verified. Parent's existing Context7 result was read; no additional external service/SDK inspection was performed.

Original baseline RED XML/log show `CONFIRMED` expected but `VERIFYING` actual. This demonstrates missing selected-place request meaning; exact rejection is correct. Its source file is explicitly an after-run exact reconstruction, whose hash matches its reconstruction metadata; it is not a pre-run frozen file. The later publication-lifetime RED has separately captured source whose hashes match its manifest and original one-failure XML (`reconnect must not publish a coordinate-only difference`). Pre-run capture timing is builder provenance, not an observation this verifier made at execution time.

## Independent commands and artifact checks

| Check | Direct result |
|---|---|
| `npm test`, with TMPDIR under verifier-owned artifacts | exit 0; 94 passed, 0 failed/cancelled/skipped; 9.866 seconds. See `source-node.json` / `source-node.log`. Fixture servers bind ephemeral loopback ports, with no connection to root's product runtime. |
| `git diff --check BASE HEAD` | exit 0; no output. |
| `git rev-parse HEAD`, `git status --short`, exact `git diff`, `git ls-tree`, and input blob reads | exit 0; reviewed head exact; worktree clean at capture. `source-integrity.json` records each integrity command and exit. |
| Python SHA-256/tree/manifest checks | exit 0; all 45 frozen inputs match worktree, committed bytes and copied freeze; frozen diff matches exact Git diff; APK SHA-256 `5845c358d2a63c4ead083c699d94446d0353f3fcedc92f6afb8c0521832cc35a`; source manifest SHA-256 matches. Base has 504 paths, 486 unchanged, 18 permitted modified plus 6 additions. All 411 prior evidence paths unchanged. |
| Frozen builder JUnit/lint inspection | Parsed independently: core 93 / app 59, all failures/errors/skips 0; lint 0 errors, 6 warnings. Builder Gradle output/argv report exit 0. These are inspected builder results, not an independently executed verifier Gradle build. |

Exploratory lookups of absent `prototype/lib` and a guessed `publication-lifetime-red-source.json` returned exit 2 / 1; authoritative code and the actual source directory were then found. A verifier provenance-inspection script initially assumed every JSON manifest was an object and exited 1; corrected traversal exited 0. None ran a product check or changed the worktree.

Gradle/build execution is deliberately deferred by root while the two emulator processes are live. This verifier did not access ADB, devices, browser/UI, logcat, vendor-private data, accounts, GitHub mutations, or root's servers. Its only writes are under this verifier artifact directory.

## Acceptance boundary

| AC | Preliminary status |
|---|---|
| AC1 | FAIL only for the exact-address fixture/document provenance finding above; model, unchanged strict matcher and original RED evidence inspected. |
| AC2 | Source contract inspected; no functional defect found. Full verifier JVM run and exact-head runtime evidence remain pending. |
| AC3 | Source/controller/transport/UI contract inspected; no additional defect found. Actual product picker/device proof remains pending. |
| AC4 | Independent Node suite PASS; frozen JVM/build/lint results inspected; source publication/replay regression inspected. Verifier build and actual >45-second behavior remain pending. |
| AC5 | NOT EVALUATED in this early phase. Root owns actual two-car proof; no completion claim. |

Next verification input: corrected frozen head and manifest, then root's complete runtime evidence and authorization for the isolated final local build. Any source/evidence/head change invalidates reliance on this preliminary head and requires re-audit.
