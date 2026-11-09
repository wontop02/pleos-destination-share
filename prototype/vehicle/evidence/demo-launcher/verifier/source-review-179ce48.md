# Independent source verification: issue #27

- Verifier: built-in default agent in a separate context following `AGENTS.md`, `docs/harness/README.md`, and `.codex/agents/verifier.toml`. This is not execution of the custom verifier role.
- Frozen issue: `../issue-27-frozen.md`; execution limit remains `2026-11-09T09:49:09Z` and was not reset.
- Base: `4a9865f1b1584cb13a32f3a7022b08928ebad158`.
- Reviewed source HEAD: `179ce4838b9899f1828589de7c8240dd4751b8c8`.
- Launcher SHA-256: `51bea95cde0bff56206f56a897d60dad7573934f6b056bb59857ea13b989773c`.
- Entry SHA-256: `9eb99d3fe19a8f0f1b4f1dfc29236d3db28f9c25de73e626ceeec21c20c38ba0`; Git mode `100755`.

## Result at this stage

No blocking source defect found. Independent fresh-archive tests pass: 34 tests, zero skipped tests, exit 0. This is a source/test result, not the final AC1–5 approval. Exact-head real warm/cold/concurrency and two default-address API evidence are still required. AC5 remains BLOCKED pending those artifacts and final evidence-only HEAD rebind.

## Scope and implementation observations

The complete base-to-head diff contains nine files, all in the frozen allowlist: entry, launcher/config/tests/docs, and ignored runtime cache paths. No Android application, server, official image, previous evidence, or original checkout change appears in this diff. `git diff --check BASE..HEAD` exits 0. The original source checkout was read only during this verification.

- `Pleos 데모 실행.command:1–11` resolves its own real path through Python, supports spaces and symlinks, and propagates launcher exit status with `exec`. Tests invoke this actual entry from another cwd, through a spaced symlink, and through the Mac NFD spelling of the target path.
- `launcher.py:142–201` requires the same repository directory and server file identity, approved two AVD/port/user tuples, exact server/APK hash formats, and finite numeric limits. `samefile` accepts the Mac NFC/NFD spelling without accepting a copied foreign server.
- `launcher.py:204–222, 415–452` derives an allowlisted environment from the preexisting owned-ADB metadata, discards inherited ADB/Android settings, requires mDNS 0/unmatched USB allowlist/owned key path, scopes cold daemon start to 5041, and scopes all clients to `-H 127.0.0.1 -P 5041` and the two explicit emulator serials. No verifier access to the real metadata or credentials occurred.
- `launcher.py:283–295, 361–411` requires a loopback listener, actual OS executable, PID/start/command fingerprint, pinned script bytes and exact command before server reuse. Empty-port startup preserves any preexisting listener and runs the pinned Node server only. HTTP readiness disables proxy and redirects and verifies the exact public-page hash.
- `launcher.py:457–531` checks emulator process, exact AVD/port, paired transport ownership, same named AVD on unexpected ports, actual emulator AVD name, boot completion, foreground user 10, and `RUNNING_UNLOCKED`. Cold startup uses existing AVD data and `-no-snapshot`; no install/wipe/snapshot restore/user switch occurs. `hostmicon` restores the already-approved host connection, not recording.
- `launcher.py:533–559` checks both device4182→host4192 and device4192→host4192 each run, adds missing mappings with `--no-rebind`, and refuses conflicting mappings without overwriting them.
- `launcher.py:561–624` verifies the installed base APK hash, exactly one own user10 MainActivity, resumed state and a public product resource ID inside the approved pane. Reentry uses the same own MAIN/LAUNCHER intent, at most three total attempts spaced at least three seconds. Failed attempts consume the budget. Already-resumed own Activity without a page does not reopen, preserving dialogs.
- `launcher.py:627–656` writes READY only after the two vehicle/app preparations and a second server/routes/activity/pane check. Failure in any stage does not emit the overall READY event.
- `launcher.py:112–122, 664–719` serializes execution with an OS lock. Launcher Failure persistence happens while the lock is still held; an independent-process test confirms the lock is busy during failed-state save and free afterward.
- `launcher.py:49–58, 93–109, 697–715` emits curated Korean statuses/failure messages and selected status fields. It does not log captured subprocess output, UI XML/text, raw exceptions, environment values, token/invitation/key contents, or audio. No product command creates/deletes rooms, requests routes/recognition/proposals, grants permissions, installs, or terminates vendor/unknown resources.

## Independent execution evidence

`unittest-179ce48.result.json`, `unittest-179ce48.stdout.log`, and `unittest-179ce48.stderr.log` record the command, exact source hashes and exit 0. Tests ran from a newly extracted `git archive HEAD`, with TMPDIR confined below this verifier directory and Python bytecode disabled. The archive itself is retained as `source-179ce48.tar`.

The actual Node case uses an OS-assigned free loopback port and explicitly excludes 4190, 4192, 5041, 5570 and 5572. It starts one Node child, verifies cold readiness, creates an isolated room (201), constructs a new Launcher that reuses the same fingerprint and room (GET 200), deletes only that test room (200/closed), and checks that token/invite secret are absent from logs. Cleanup revalidates the child fingerprint and executable, then terminates only that spawned child. This validates the otherwise unused server-cold branch without touching live servers.

The remaining cases directly execute entry/lock paths with temporary fixture config and fake SDK files, and inspect mocked Android/host calls for conflict refusal, process/PID reuse checks, bounded boot and UI timeouts, no false READY, reverse repair, wrong-user refusal, duplicate-activity refusal, warm compose preservation, failed-state locking, modal preservation and finite failed AM retry.

## Limits and evidence treatment

No live ADB command, emulator action, live API request, resource shutdown, APK build, GitHub mutation, or product edit was performed by this verifier. Real-vehicle behavior cannot be inferred from the mocked tests. Earlier warm2/cold3 results use previous launcher hashes and are historical support only; they do not bind the current source by themselves. The old disconnected-room fixture is not evidence that the launcher deleted a room, and its cause is unconfirmed. A new operator-created UI fixture must be described separately from the isolated API probes. Final AC verdict will inspect the root's raw artifacts and exact packaged HEAD.

## Runtime update at 09:14 UTC

The root's completed `cold4-final-entry-result.json` binds exact HEAD `179ce4838b9899f1828589de7c8240dd4751b8c8` and launcher SHA `51bea95c...`. It does not pass: the root's LaunchServices open exits 0, but the launcher emits terminal `failed` / phase `app_5570` / exit code 5 after 77.76 seconds, with no overall READY. An OS `open` return code alone is therefore not treated as launcher success. Initial own-app launch and reentry attempt 2 occurred; no actual app-ready event followed. The underlying foreground/page cause is not yet established. AC1 and AC5 acceptance are FAIL for this run pending diagnosis and new direct evidence; the source-only review and 34 tests remain their narrower results. See `cold4-audit-179ce48.json`.

## Confirmed blocking finding F1 after actual surface diagnosis

**P1 — Cold OEM occlusion is not recovered when the own multi-window Activity remains RESUMED (`launcher.py:610–617`).** `cold4-A-focus.json` shows the own user10 task in `Rect(0, 0 - 1682, 1350)` and state RESUMED. `cold4-A-diagnostic.json` reports exactly one own Activity and `publicUi: null`. The actual screenshot and hierarchy show OEM Maps in the approved right pane and no own package node. The reentry branch checks only `not current["resumed"]`, so this real OEM occlusion is indistinguishable from a preserved own modal and never receives another corrective action. The required cold one-click path fails before the follower vehicle. Preserve modal/warm-page safety, account for the specifically observed cold OEM layout state with finite checks, and repeat actual exact-source cold execution. The earlier source-only no-finding result is superseded by this observed counterexample. See `findings-179ce48.json`.
