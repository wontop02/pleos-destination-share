# Issue #21 builder handoff

## Frozen implementation

- Base: `ff3e6a15067ee8285c83f318ec715899ed3f4364`.
- Source head: `72b778d69651a2b6e836e5bada4246f8d16a3633`.
- Branch: `be/issue-21-poi-confirmation`.
- Production APK: `freeze-1/app-debug.apk`.
- APK SHA-256: `5845c358d2a63c4ead083c699d94446d0353f3fcedc92f6afb8c0521832cc35a`.
- `freeze-1/manifest.json` lists all 24 changed paths and identifies base/head/APK. `source-sha256.json` identifies 45 frozen source/build inputs; `source/` contains their exact bytes. `source.diff` is the exact base/head diff.
- The worktree was clean after the source-only commit. Parent-owned evidence was not staged. No nativeDriver source changed.

The custom builder role was not exposed. The assigned built-in worker followed `.codex/agents/builder.toml`, the repository/harness rules, frozen issue and the three skills already announced by the parent. It did not delegate, manipulate devices/UI, install packages, use GitHub or start a product runtime server. HTTP tests retain their normal ephemeral fixture servers.

## What changed

The original exact comparison body is byte-identical to base; its SHA-256 is recorded in `baseline-preservation-final.json`. New `pleos_place` requests preserve immutable bookmark source metadata and separately retain the independently observed native point in `DestinationConfirmation`. Complete name/provider/ID/nullable sub-ID and supported source are checked within the existing fresh operation/SDK/room lifetime. A selected/room DTO cannot masquerade as an actual native observation. Native name whitespace now fails normalization rather than being trimmed into a false identity match.

The authenticated proposal path preserves `kind`/`source` through pending, atomic acceptance, SSE, snapshot and accepted provenance. Direct native sharing rejects selected mode. Legacy mode omission is exact; unknown modes/sources and source without kind fail closed. Existing consent, TTL, revision/version/CAS, replay and stop/reconnect protections remain.

The real public bookmark loader shares the existing helper/listener and adds no permission or foreground lease. It reads once per native connection, with 8-second deadline and immutable cached choices. Mixed unsupported entries are omitted without logging; all-invalid or oversized callbacks fail closed. Timeout/cancel/room/member/native lifetime changes fence candidates; stale dialog cancellation is snapshot-scoped. Only an explicit proposal sends one selected source. UI distinguishes selected-place setting confirmation from exact coordinate confirmation and shows source versus actual guidance separately.

Fresh same-place native observations coalesce automatic publication without restoring confirmation after reconnect/restart/cold observation. Structural equality remains unchanged. Changed native identity and ordinary exact coordinate changes still publish.

## Executed checks

All Gradle commands used the existing approved JDK/Gradle/SDK values silently from the parent's build-env.json, with no dependency/SDK changes or environment/auth output. Exact argv and elapsed/exit data are in the named JSON files; full output is beside each JSON.

| Artifact | Command | Result |
|---|---|---|
| `baseline-red` | approved Gradle `--no-daemon --offline :core:test --tests ai.pleos.destinations.core.SelectedPlaceConfirmationTest` | exit 1, 8.60s, one expected assertion failure |
| `core-green` | same command after explicit selected-place intent | exit 0, 8.09s |
| `publication-lifetime-red` | approved Gradle `--no-daemon --offline :core:test --tests ai.pleos.destinations.core.PlaceIntentSafetyTest.samePlaceCoalescesAfterReconnectRestartAndColdObservationWithoutManufacturingConfirmation` | exit 1, 7.03s, reconnect coalescing assertion failed |
| `protocol-targeted` | `node --test prototype/test/poi-place-confirmation.test.mjs` | exit 0, 4 tests |
| `place-integration` | approved Gradle `--no-daemon --offline :core:test :app:testDebugUnitTest` | exit 0, 14.05s |
| `full-node` | `npm test` | exit 0, 7.60s, 94 passed |
| `frozen-vehicle` | approved Gradle `--no-daemon --offline :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug` | exit 0, 13.33s, core 93/app 59 passed |
| `freeze-1/apk-checks.json` | aapt badging/permissions/manifest and apksigner verify | all four exit 0 |

Final total is 246 passing tests: Node 94 + core 93 + app 59; failures/errors/skips 0. Final JUnit XML is frozen under `freeze-1/junit`. App lint has zero errors and six warnings (UnusedResources 2, MissingApplicationIcon 1, SetTextI18n 3); exact reports are frozen. No driver build was required because driver source/build inputs were unchanged. `git diff --check` passed.

The first RED captures the missing selected-place request meaning, not a faulty old exact matcher. Exact mode correctly rejects the approximately 51m café difference. Its original newly created baseline test was not copied before the first edit; `baseline-red-source.kt` is an exact reconstruction from the executed content, with that provenance explicitly recorded. The assertion/JUnit/log are original execution outputs. The later lifetime RED copied test/coordinator source and hashes before execution; its original JUnit is retained. Both regressions pass in the final full run.

One incidental failed attempt is retained in `node-first-attempt.json`: a preceding no-match `rg` skipped shell `&&` file creation, so Node reported the target file missing and no product test ran. After creating the file, targeted/full Node tests passed. Intermediate successful build/check attempts remain beside the final results; none was overwritten.

## Acceptance mapping and remaining work

| AC | Builder evidence | Scope of conclusion |
|---|---|---|
| AC1 | Real #19 café input in `SelectedPlaceConfirmationTest`, original RED/GREEN, unchanged strict matcher, separate source/actual model and focused product contract | Source and deterministic production-seam regression pass; original #19 observations unchanged |
| AC2 | `PlaceIntentSafetyTest`, protocol rejection tests and existing full guard suite; real HTTP/SSE selected-mode integration | Complete identity, native-only actual, invalid/error/deadline/old epoch/stop/room/consent guards pass locally; actual SDK behavior awaits root |
| AC3 | `SavedPlacesTest`, new loader/selection UI, strict protocol mode/source tests and selected-only proposal seam | Read-only candidate lifetime, cached reopen, mixed invalid data, stale dialog, timeout/cancel/reconnect/scope/duplicate protections pass locally; UI/device verification awaits root |
| AC4 | Publication lifetime RED/GREEN, selected HTTP/SSE >45s/reconnect/restart/cold tests, ordinary exact regressions, full Node/core/app/build/lint | Local automatic loop/replay regressions and required build checks pass |
| AC5 | Frozen production APK/source/hash supplied | NOT RUN by builder; two actual vehicle runs, independent native queries, bidirectional café/other-type reject/accept, preaccept route0, >45s stability, exact runtime regression and separate verifier remain root-owned |

The public docs describe POI map identity and sub-ID sequence; they do not guarantee coordinate canonicalization or entrance/route equality. The implementation makes no such claim. New-mode support is restricted to NaviHelper3.0.5 bookmarks with explicitly preserved empty native provider, nonempty POI ID, nullable sub-ID and complete supported metadata. Other providers/sources and lists over 200 entries fail closed. Bookmarks are refreshed only after reconnect; READY means the cached immutable list and is labelled accordingly.

For isolated runtime QA, enter loopback4188 in the existing server-address field (its historical default4182 is unchanged). A native connected room is required before loading places. After source acceptance starts a native operation, the picker selection is intentionally invalidated but the agreed source remains in the shared/proposal/confirmation models. Production confirmation kind is visible in UI/notification and the journal's `confirmationKind`; a fresh observation alone never creates it. Parent must stop existing automatic modes before the device phase and preserve old evidence/demo/server state per the issue.
