# Issue27 actual Mac launcher verification

The user requested only the one-click execution task. The frozen acceptance criteria are in `issue-27-frozen.md`; issue27 and PR28 are the only GitHub work items. The separate voice task25/PR26, existing product/server code, SDK/images and previous evidence remain unchanged.

This report is an operator QA record. Independent verifier artifacts are separate and must approve the exact final PR head before merge. Built-in worker/default agents followed the repository builder/verifier contracts; the custom role API was not available. These AI checks are not a separate-account GitHub approval review.

## Actual environment and verification limits

- Base main: `4a9865f1b1584cb13a32f3a7022b08928ebad158`.
- Dedicated ADB5041 only, `emulator-5570/Pleos12Leader` and `emulator-5572/Pleos12Follower`, user10.
- Installed unchanged vehicle APK SHA256: `5b9eec0cca7f645e25ef4615cd5096d8e31b9256141d35f8d5018bc2c398317e`.
- Existing server4192/PID69453 and older4190/PID40320 were never stopped. Actual server cold-spawn is exercised by an independent free-port real Node integration test; preserving the live4192 room takes priority over stopping it for a test.
- The project-parent `.command` symlink and ignored actual local configuration are installed. LaunchServices `/usr/bin/open` was actually dispatched, followed by the launcher's own new structured run log and actual guest UI/boot/resource observations. Terminal UI inspection was blocked by the computer-use surface and was not bypassed or claimed.
- This is normal AVD/ADB shutdown and restart, not a full host-Mac reboot. APK/JVM rebuilding was not repeated because no Android/server product source changed. Permission dialogs, an unrecognized vendor surface or conflicting resources deliberately cause a bounded failure.
- Launching prepares two visible product screens and their API paths. Native navigation SDK connectivity/routing and speech recognition are not revalidated by this task. Their actual observed states remain in journals; a native SDK `FAILED` observation must not be presented as native readiness PASS. No speech, proposal or route request is initiated by the launcher.

## Preserved attempts and corrections

1. `resource-baseline.json` initially tokenized flat ps command text with shlex, which split Korean paths containing spaces. This is an instrumentation mistake, not actual process argv. `resource-identity-baseline.json` supersedes that interpretation with proc_pidpath, raw command and start identity; the original remains preserved.
2. `warm-entry-result.json` correctly failed because `cmd user is-user-unlocked 10` is unavailable on this actual image. The supported `am get-started-user-state 10` returns `RUNNING_UNLOCKED`; the source now uses it. Root repaired its deliberately removed reverse mapping after that failure; this is not counted as a launcher repair PASS.
3. `warm2-result.json` passed actual entry from `/tmp`, repaired deliberately removed4182 mappings on both devices and retained existing process IDs, single MainActivity, pane and room state.
4. `warm3-launchservices-result.json`, `warm4-os-diagnostic-result.json` and Unicode diagnostics preserve a real macOS NFC/NFD path-identity failure. A symlink-only Unicode probe was insufficient. Direct target samefile diagnostics identified it; validation now uses filesystem identity and canonical configured spelling.
5. `warm5-os-result.json` records a successful launcher READY but an overall root assertion failure: both apps were already disconnected before invocation. This must not be relabeled PASS or blamed on the launcher. The reason for that older room disconnect was not conclusively observed (the six-hour server room lifetime is a possible explanation).
6. `fresh-ui-room-fixture-result.json` is an operator-created valid fixture using actual public product UI and the default4182 address: A created, B joined, both restored host/member revision0 with automatic following OFF. Invitation data was kept in RAM/stdin only, never clipboard, screenshots, argv or saved evidence. This is not launcher automation. Its room identity is recorded only as a SHA256 digest.
7. `cold-entry-result.json` preserves dedicated ADB bootstrap failure: inherited remote-address overrides block daemon startup. The starter-only environment removes those two address overrides and explicitly binds isolated loopback5041; ordinary clients keep the approved environment.
8. `cold2-entry-result.json` preserves the native startup focus race. Opening our app early allowed OEM Maps to displace it. `cold3-entry-result.json` passed in64.696seconds with bounded own-only reentry, but it used an earlier source and does not approve the final code.
9. `cold4-final-entry-result.json` failed on source51bea after two own launches. Its static screenshot/XML/activity diagnostic proves the own activity can remain RESUMED in a wrong multi-window task while OEM Maps occupies the approved right pane. Merely checking resumed cannot recover it. The final fix requires an observed approved OEM Maps pane before cold app entry and allows bounded own-only recovery only from that strict OEM surface; own dialogs, permission surfaces and unknown packages are preserved.

## Data and side effects

The root QA helper reads only allowlisted product diagnostic files, validates every persisted journal row against the fixed schema, compares journal prefixes and counts all newly observed requestRoute events. It never reads auth/key stores. Local config, emulator environment/key metadata, credentials, invitation values, raw audio, APK/SDK binaries and fresh-archive test trees are excluded from this package. A manifest lists bytes and SHA256 for each packaged artifact, excluding itself.

Only QA preparation and deliberately scoped diagnostic actions manage temporary rooms, remove approved mappings, shut down owned AVDs or stop dedicated5041. The launcher itself never creates/deletes rooms, grants permissions, wipes/restores images, kills vendor components, performs global settings changes or starts recording. API probes create isolated rooms from each actual guest4182 and delete only their own newly created identity; they are not full manual room UI acceptance.

The actual current process identities, UI geometry, source head/hash, installed APK hash, both reverse mappings, boot IDs, room role/revision and microphone inactivity are recorded in final case artifacts. The final exact-head independent review is linked from PR28. The approved execution deadline remains2026-11-09T09:49:09Z and was not reset during fixes.

## Final implementation and actual runtime results

Exact code HEAD: `3db09e0714e86cde6c83642977500e53cec0ff0d`.
Launcher SHA256: `172553d4510986da142de3b230ac0f32e517d53532b2f5079880dd0e7e53d8c0`.
Entry SHA256: `9eb99d3fe19a8f0f1b4f1dfc29236d3db28f9c25de73e626ceeec21c20c38ba0`, executable0755.

- `cold5-result.json`: PASS. Both dedicated AVDs and5041 were confirmed off after normal shutdown, then the actual project-parent entry was dispatched once with LaunchServices. Two actual home pages became READY in139.501seconds, each with one MainActivity/user10 and correct pane, fresh boot IDs, persistent prior journals, restored host/member room revision0 and automaticOFF. Native state was actually CONNECTED in both final captures; native routing/voice functionality remains outside this launcher test. Both mappings verified, no new requestRoute/recording. New5041/A/B PIDs93034/93044/93736; servers4190/4192 unchanged. B was already off before this attempt; the explicitly labeled last-live cold3 journal prefix is its baseline, not a fresh stopped-device read.
- `final-warm3-result.json`: PASS. Two actual entries from different working directories run concurrently produced exit codes0 and3 in18.224seconds. No server, ADB, emulator or app PID changed. Both deliberately removed4182 reverse mappings were recreated;4192 mappings retained. B stayed on its compose page, A on home; both rooms/role/revision/automatic state survived, one Main/pane each, no new route or recording.
- `final-default-api-result.json`: PASS. Both actual guests, both4182 and4192 addresses: four isolated room POST201 and own-room DELETE200 pairs, tool exit0. Existing app room was not modified; credentials remained in memory/stdin only. This confirms actual addresses, not a full manual new-room UI retest.
- Builder38tests and the independent fresh-archive38tests passed with no skips, including a real Node cold/reuse/room201/GET200/delete200 integration on an isolated free port. Compilation and whitespace checks passed. The independent source reviewer also replayed the observed cold4 XML and negative guards; final cold5 closes the actual OEM task race.

Two warm preparation failures remain visible: `final-warm-result.json` mistook an EditText's static source hint for entered text before invoking the launcher; `final-warm2-result.json` tried the home-only change_destination control while already on compose, also before invocation. The helper now accepts only the two exact published hint strings and recognizes the existing page. These failures never removed reverse mappings and are not product failures or passed concurrency runs. The successful actual concurrency run is only final-warm3. `preservation-probe-schema-error.json` records an operator key mismatch before a read-only identity comparison; the exact baseline field is commandRaw, clarified in final-preservation-result.json.

## Acceptance coverage

| Criterion | Direct evidence |
|---|---|
| AC1 | Actual cold5 LaunchServices entry, exact source/APK, boot/user10, both one-Main/public pane screenshots and XML; final-warm3 direct entry cwd variations; strict failure history never claimed READY. Ignored actual config and parent symlink installed. |
| AC2 | cold5 new dedicated resources/data restoration; final-warm3 same-resource concurrent0/3, page preservation, deliberately missing4182 repair, both reverse maps;38 finite-wait/lock/identity tests. |
| AC3 | final preservation identity and unchanged original checkout, source9file diff, allowlisted resource commands and privacy schema; journal prefixes/roles/revisions and no new route/mic; conflict refusal tests. |
| AC4 | Real Korean launcher stdout and curated phase logs; independent38tests and real isolated Node startup/reuse/room lifecycle; observed cold/config/timeout failures preserved. |
| AC5 | Exact3db source172/entry9eb/APK5b9 artifacts, cold5+warm3+four API address cases and valid room restore; independent review followed by evidence-only final HEAD rebind. Actual merged/main/issue closure must be recorded externally after merge and is not presumed by this premerge report. |

The final PR evidence commit changes only this new evidence package. Its source9file hashes must equal the reviewed3db implementation. The exact final approval is posted verbatim to PR28 to avoid a self-referential report-commit loop.
