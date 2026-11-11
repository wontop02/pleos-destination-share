# Final two-vehicle verification — 2026-11-11

The coordinator verified the corrected frozen APK on both approved Pleos AVDs. This is the current runtime evidence; older builder-only sections are retained as history. The production source is `d3a9766b54158c54da63c5431538f57a3d695d1e`, base `5ccc9ed4480fd12d96a2166ec2d4cc1850fe7f23`. Subsequent commits add evidence only. Independent exact PR-head review is recorded in PR #30 before merge, separately from the source-level observations archived here.

## Bound version and environment

- App `0.4.0-issue29`, versionCode2, SHA-256 `f0811d7d2ec32eb8be9bb9d595a6d7b44e74de999cb169086faa39e3349390e2`, identical installed bytes on emulator5570 and5572. The testOnly self-targeted driver remains `913cf8ada6340c14cb0cb93ec4f1fcef62164ed07399630de8142ec6f0575ba6`.
- Existing update signer `75637c02c025fbaae54561060852049f962cba132025e7b755a2dbc8133332c1` matches. Both user10 sessions are unlocked; normal `NAVI_CUSTOM_MAP` permission was granted by installation. No privileged grants, uninstall, user switch or data clear were used.
- The67 source files match the committed source manifest `f4e5831356ce5d5c2803b9eaf81294a30f83f0f4c6e51903ee93aa84d8e8f741`. The only final correction changes two non-Present home fallback strings to neutral “현재 목적지 확인 전” and a current-query hint.
- Isolated loopback server4196 uses exact `server.mjs` SHA `6338890a40005b7ba02149d9cff8838c344d477025eba73d9d4978ee639674f6`. Actual authenticated HTTP/SSE29 checks passed after restart; the independent4 adversarial HTTP tests and prior Node101/core94/app116/3boundary checks remain tied to unchanged server/core sources. Final UI-only build reran116 app tests, assembleDebug and lintDebug, exit0,14warnings/noerrors. Existing driver lint has8warnings/noerrors.

## Direct AC evidence

| AC | Observed result | Primary files |
|---|---|---|
| AC1 | Final APK inputs a real place name 가든서비스 and category 카페. Public Activity SearchPlace opens the actual native result panels. The user selects 프레소커피랩, sets it as destination and native guidance is observed. No bookmark, voice, fabricated result callback or new provider key is involved. | `ui/R11-final-name-*`, `ui/R11-final-category-*`, `ui/R11-final-native-place-selected.*`, `ui/R11-final-native-guidance-started.*` |
| AC2 | Actual name/address freshly queried and displayed; explicit visible Send is accepted. Sender app route count remains0. The sender already set its native destination; send never routes it again. Membership/null-frame and native Failed/Absent/disconnect preview guards have independent red→green regression proof. | `R11-final-result.json`, `ui/R11-final-02-A-fresh-preview.*`, `R11-final-sender-after-events.json` |
| AC3 | Request revision4 is bound to its recorded ID and immutable target. B and C are both pending before acceptance. B has0 product routes through receipt and actual notification review; one visible Accept produces exactly1 `request:requestRoute`, followed by same-operation fresh getDestinationInfo→onDestinationInfo→destinationMatch(true). C remains pending when B matches, and later C rejection preserves B accepted. | `R11-final-preaccept-*`, `R11-final-after-accept-events.json`, `R11-final-match-while-C-pending.json`, `R11-final-C-rejected-B-still-accepted.json` |
| AC4 | User starts receiving. B is in actual native navigation when the request arrives. The real Android notification shade displays the new request, and tapping its rendered card opens the current review before acceptance. Cold process restart with historical accepted revision3 issues0 new routes. After the test observer leaves, old authority is invalidated; visible stop/restart also issues no extra route. Legacy destination remainsnull/revision0 and its automatic path does not route this request. | `ui/R11-final-01-*`, `ui/R11-final-04-*`, `ui/R11-final-05-*`, `R11-final-cold-history-no-replay.json`, `R11-final-observer-teardown.json`, `R11-final-stop-restart-no-replay.json` |
| AC5 | Both installed bytes are identical, real HTTP/SSE and JVM checks are separate from actual SDK proof, and a read-only independent verifier checked raw package/source/JUnit/lint/screens/route events. Original encrypted session bytes are preserved. Actual current/native evidence is not inferred from build success, server acceptance or SDK send return. | `installed-candidate-final.json`, `R11-final-independent-apk-source-audit.json`, `R11-final-original-session-preserved.json`, `verifier/`, `builder/cycle-3/` |

The final independent SDK query requests0 routes and returns the selected name, POI ID, nullable sub-ID, provider, address and coordinates matching the sent actual native source; coordinate deltas are0. The B baseline ESeo and selected Freso are distinct actual POIs that share a canonical building-entrance point. This proves the selected POI changed and native metadata matches; it does not establish different road geometry or completed route calculation.

The new flow uses manual text entry via a testOnly visible-EditText helper, explicitly labelled as UI editing rather than speech recognition. No voice control or audio input is used. Scoped RECORD_AUDIO app-op observations are not running. No raw audio is archived.

## Execution and preservation

The user paused on11/10, then explicitly resumed the remaining work on11/11. The original execution record was kept. Prior active work of55m10.8s, including in-flight final checks, was retained within the120 active-minute cap; the paused interval was excluded rather than resetting that cap. The final correction is cycle3 of3, with a fixed resumed deadline09:05:50Z. No additional issue was started.

On11/11 initial preflight, the former4190/4192 processes were already not listening. The coordinator did not restart, delete or overwrite those servers or their original evidence. Only a new isolated4196 server and its generated room were used. The original A room ciphertext still has SHA `a912ed68486d2091706e0f978d64f843cadc0b6d97134e5df072859144275aa2`; its AndroidKeyStore alias was never cleared. Credentials remain in ignored0600 artifacts, not Git. No invitation screen, bearer token, encrypted session blob, key, APK, SDK cache or raw audio is included here.

The observer C was created only for the independent-response test and removed using its own normal leave endpoint after assertions. The two actual app members remain connected and receiving for the user. Source checkout `be/issue-7-android-prototype` and historical upper submission files were not changed.

## Preserved incomplete observations

Initial runtime revision1 involved separate UI actions outside the controlled collector; it is excluded from route-count proof. Controlled revision2 demonstrated actual Android notification tap, explicit acceptance and a fresh native match, but the pre-existing bounded524288-byte journal rolled over during that operation, losing the earlier route-request line. Its exact request count is therefore inconclusive, not a PASS. That raw partial observation is retained under `history/`.

Supplemental revision3 on the prior candidate and final revision4 on the corrected APK use fresh small journals, complete preaccept/afteraccept windows and archived live snapshots. Final revision4 is the completion proof. The final UI correction did not alter navigation/protocol logic. Earlier compile/lint and independently found sender/C1 failures remain in the builder history with corrected regressions.

## Scope and command records

Commands ran from the original ignored `product-run-artifacts/destination-change-requests-20261110` working directory: `python3 root/api_scenarios.py` (exit0), final signed update install (exit0), name/category search collectors (exit0), and `python3 root/R11-final-flow.py <frozen-sha> <production-head>` (exit0). Archived `scripts/` are exact execution-source snapshots with environment-specific path resolution; they are evidence, not a portable launcher or instruction to modify another device. Actual source/build/package commands and exits are in the cycle3 builder JSON/logs.

This verifies two approved Pleos AVDs and the local prototype. Actual automotive hardware, mobile background/lock-screen delivery, public deployment, release signing/submission and a floating heads-up banner are not claimed. The observed request is the real Android notification shade and its actual tap/review path.

Final UI state is the two `ready-receiving` screenshots. Intermediate cleanup frames are archived under `history/ui/`. Two no-click collector attempts looked for the Home restart control while the sender Activity retained its CHANGE page. The collector then used the actual visible Home action before successful Stop/Start on both; no extra route was issued, and no production change was made for these collector errors.

`git diff --cached --check` on this evidence reports one trailing space in the untouched Gradle daemon message at `builder/cycle-3/build.log:2` (exit2). The raw execution bytes are deliberately retained; the production patch check passed without whitespace findings.
