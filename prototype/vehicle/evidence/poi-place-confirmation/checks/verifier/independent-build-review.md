# Issue #21 independent offline build verification

**PASS for the independent local JVM/build/lint checks at source head `a26b9419804874052329001ad86e60ff1bccb42c`. AC5 and final evidence-head acceptance remain pending.**

Base: `ff3e6a15067ee8285c83f318ec715899ed3f4364`. Branch: `be/issue-21-poi-confirmation`. PR: https://github.com/wontop02/pleos-destination-share/pull/22. This extends the source-only correction review; it is not a final verifier verdict for every mandatory AC.

Root explicitly authorized one bounded independent offline Gradle invocation after recovery stabilized. The verifier archived the exact source commit under `verifier/independent-build-a26b941/source`, independently matched all 45 freeze-2 input hashes to current worktree, committed blobs and archive bytes, and built only that isolated copy. The original source worktree remained clean at the same head with all 45 hashes unchanged after the run. The approved existing toolchain configuration was consumed silently; environment values and credentials were not emitted.

## Actual command and result

One Gradle invocation used the approved Gradle executable with:

```text
--no-daemon --offline --max-workers=1
-Dorg.gradle.jvmargs=-Xmx768m -XX:MaxMetaspaceSize=512m -Dfile.encoding=UTF-8
-Pkotlin.compiler.execution.strategy=in-process
:core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

The JVM property above is one argv element; the exact argv and cwd are in `independent-build-a26b941/result.json`.

| Check | Independent result |
|---|---|
| Invocation | exit 0 in 97.498 seconds; no timeout, no Gradle retry. All 55 actionable tasks executed from the fresh project copy. |
| Core JUnit | 93 tests, 0 failures/errors/skips. |
| App JVM JUnit | 59 tests, 0 failures/errors/skips. |
| App assemble | Successful production debug APK generated in the isolated copy. |
| App lint | 0 errors, 6 warnings. |

Exact current tests, compiled source and resources were used. With the already independent corrected-head `npm test` result of 94 passing tests (`correction-node.json`), all 246 local tests passed under verifier execution. Source/nativeDriver inputs are unchanged; no driver build was introduced or required by these changes. No device/runtime behavior is inferred from these local tests.

The no-daemon invocation uses a single-use build process that exits afterward. One worker and in-process Kotlin bound compiler memory/process growth. A 240-second process-group timeout was installed but did not trigger. No UI slowdown reruns were attempted.

## APK comparison and runtime boundary

The frozen installed/runtime APK remains SHA-256 `5845c358d2a63c4ead083c699d94446d0353f3fcedc92f6afb8c0521832cc35a`. The isolated fresh-build APK SHA-256 is `5d87bd26483beafb7d57cc2a7cb3758e2e791dd80850853b40373e274537997a`; it was not installed or used for root's live scenarios.

ZIP-only inspection established that all 54 entry byte contents, checked central entry metadata, and all 54 compressed payload byte sequences are identical between the two APKs. The frozen package has 220,596 bytes of gap between ZIP entry ranges; the fresh package has none. Container positions/local layout and the corresponding signature block differ. Full APK byte equality is explicitly false. The inspection found no compiled-code/resource payload difference and did not disassemble SDK implementations or inspect private keys. Runtime evidence must continue binding to the actual frozen installed APK, as root's `runtime-source-binding.json` records.

The reason for differing container layout across these builds was not experimentally reproduced; no extra build was authorized or run. The directly observed payload equality and container-layout differences are sufficient to distinguish this full-file hash difference from a source/code/resource mismatch.

## Preserved artifacts and limits

The run's `inputs.json`, exact runner, `result.json`, complete `gradle.log`, generated JUnit XML, lint XML/HTML/text and APK all remain under `verifier/independent-build-a26b941`. APK metadata/hash comparisons are `apk-comparison.json`, `apk-layout-comparison.json` and `apk-signing-container-comparison.json` in that directory. No source/evidence/Git/GitHub, device, UI, server or recovery control was performed.

Before Gradle launch, preparation hit `TypeError: extractall() got an unexpected keyword argument 'filter'` because the host Python tarfile API lacks that parameter. The fix validates all archive members as ordinary relative files/directories before using the supported extraction API. `preparation-failure.json` preserves the error and records `gradleStarted: false`; the single Gradle invocation above followed that preparation fix.

Next required input is the final frozen evidence head and complete root-owned AC5 proof. The final reviewer must re-audit exact head, installed/runtime source binding, actual two-car proposal/rejection/unanimous consent, native queries, confirmation-kind UI, preaccept route count, >45-second stability and replay guards before issuing full PASS.
