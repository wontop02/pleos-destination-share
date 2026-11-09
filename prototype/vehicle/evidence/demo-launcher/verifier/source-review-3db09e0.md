# Independent source re-verification: issue #27, 3db09e0

Base: `4a9865f1b1584cb13a32f3a7022b08928ebad158`.
Exact code HEAD: `3db09e0714e86cde6c83642977500e53cec0ff0d`.
Launcher SHA-256: `172553d4510986da142de3b230ac0f32e517d53532b2f5079880dd0e7e53d8c0`.
Entry SHA-256: `9eb99d3fe19a8f0f1b4f1dfc29236d3db28f9c25de73e626ceeec21c20c38ba0`, Git mode `100755`.

The same independent built-in default agent followed the repository verifier TOML contract in a context separate from the builder. No custom role execution is claimed. Source and GitHub access were read only. No live ADB, AVD, server, credentials, or private metadata access occurred. Only verifier artifacts and the spawned isolated-test Node child were changed.

## Source and test result

No new blocking source defect found in the exact corrective delta. The initial full nine-file review is documented in `source-review-179ce48.md`; that head's actual cold4 failure remains retained and is not called a pass. This re-verification reads the committed delta of launcher, its tests and operating README. All nine base-to-head source/doc changes remain within the frozen allowlist. `git diff --check BASE..HEAD` exits 0.

A second newly extracted `git archive 3db09e0` ran all **38 tests**, with zero skips and exit 0. See `unittest-3db09e0.result.json` and stdout/stderr logs. The real isolated Node cold-start/reuse and isolated room 201/GET200/delete200 test passed again. Tests use only mock SDK/ADB fixtures and an OS-assigned free Node port excluding all live ports. No old test result is inherited as a substitute for rerun evidence.

## Correction to F1

- `oem_pane_observation` recognizes only the observed native Maps public hierarchy: every node must belong to the exact Maps package, root/content/ComposeView must occupy the exact approved right pane, and explicit own, permission, unknown, password, Dialog, Popup and EditText cases are refused. It reads no text attributes and writes no UI data.
- The cold-only boot path waits for a product or narrowly identified Maps pane to remain observed for at least three seconds, within the original finite boot deadline. False/missing/failed observations reset the stability timer. Warm boot behavior does not acquire this barrier.
- `public_ui` clears its per-vehicle OEM flag before each dump, so a failed dump cannot reuse stale evidence. If exactly one verified own user10 MainActivity is reported RESUMED but the public hierarchy contains only the strictly identified Maps surface, the launcher may bring its same task forward using the existing MAIN/LAUNCHER path. The existing three-attempt, three-second spacing and unchanged readiness deadline still apply. Unknown/permission/own-modal surfaces remain preserved.
- Maps itself never becomes app-ready: overall READY still requires actual own product resource IDs inside the approved pane and the final two-vehicle check. No vendor stop, task deletion, permission grant, global setting, route/recognition/proposal or room operation was introduced.

Additional independent parser execution used the **actual cold4 hierarchy**, not only a hand-built fixture. The new guard recognizes that observed Maps surface, refuses wrong geometry, unknown/own/permission/dialog/password mutations and malformed XML, and the product-readiness parser still rejects the Maps hierarchy. All nine assertions pass; see `observed-pane-3db09e0.json` with source/fixture hashes.

F1 is source-corrected and independently unit/parser-verified, but remains **pending actual exact-source cold verification**. The new cold barrier precedes route repair, so the real cold test must still prove existing room reconnection becomes CONNECTED rather than infer it from app visibility. Final AC1–5 approval is withheld until exact-source cold5/warm/concurrency/default-address and preservation evidence is inspected and the final packaged HEAD is explicitly rebound.
