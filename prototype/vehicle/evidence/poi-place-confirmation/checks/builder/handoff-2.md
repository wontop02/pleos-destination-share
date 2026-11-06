# Issue #21 exact-address provenance correction handoff

The verifier's P2 AC1 finding is corrected in seven test/document paths. Eleven address string occurrences on ten changed lines now preserve exactly `경기도 성남시 수정구 창업로40번길 6` and `수정구 시흥동`, read from the immutable issue19 source and post-request response. No production normalizer, matcher, UI, protocol or build input changed in this correction.

- Base: `ff3e6a15067ee8285c83f318ec715899ed3f4364`.
- Previous implementation head: `72b778d69651a2b6e836e5bada4246f8d16a3633`.
- Address correction commit: `4820fb7e9dc294e5065bf1453999795bde39b515`.
- Final source head: `a26b9419804874052329001ad86e60ff1bccb42c`.
- Final source manifest SHA-256: `a269f8461b0a5972c24516d0a1973c933443fb376ad7caae8177f2cb4abb3d8c`.
- Production APK SHA-256: `5845c358d2a63c4ead083c699d94446d0353f3fcedc92f6afb8c0521832cc35a`.
- APK: `freeze-2/app-debug.apk`; it is byte-identical to freeze-1, not a new runtime binary.

`freeze-2/manifest.json` lists exact current base/head, all45 frozen source/build inputs, the seven-path correction delta, current JUnit/lint copies, previous APK equality and check timing. `freeze-2/source.diff` is the exact original-base/final-head diff. `freeze-2/source-sha256.json` identifies every frozen source byte. Production equality is independently checked against the prior source manifest and by the correction delta containing only tests/docs. All411 original tracked evidence files are unchanged.

One authorized Gradle run was executed after address correction: approved existing Gradle `--no-daemon --offline :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`, exit0 in28.17s. Core93 and app59 passed with0 failures/errors/skips. AssembleDebug was UP-TO-DATE because production inputs did not change. Lint0 errors/6 warnings; exact output and argv are `provenance-vehicle.log` / `provenance-vehicle.json`. Then only a documentation disclosure was committed; production and test inputs remained identical to those checked.

The changed Node fixture was rerun with `node --test prototype/test/poi-place-confirmation.test.mjs`, exit0 in0.41s,4 passing. `provenance-node.log` / `.json` are current. The full94-test Node run belongs to prior head72b778d and was not rerun for this literal-only correction; freeze-2 explicitly distinguishes it. Prior aapt/signature outputs likewise belong to freeze-1; their verified APK is byte-identical to the current frozen APK, and no repeat verification is claimed.

The initial RED source/log/XML, after-run reconstruction disclosure, lifetime RED source/hash/XML, prior command artifacts and freeze-1 are preserved. The focused product document now explicitly discloses that the initial RED preserved observed name/coordinates/identity but omitted internal address spaces, while the current regression and table have exact original address bytes. It does not rewrite or relabel the original execution.

AC1's reported fixture/document provenance defect is corrected. No functional change or new device result is claimed. Root's current two-vehicle runtime continues on the identical production APK; root owns the remaining AC5 evidence and separate verifier re-audit for this exact final source head. No device/UI/GitHub/server action, installation or extra Gradle task was performed by this worker.
