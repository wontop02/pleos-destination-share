# Remaining UI/lifetime evidence — independent audit

The newly supplied runtime evidence closes the functional UI/lifetime gaps noted earlier. No new product defect was confirmed. Overall verdict remains **BLOCKED only pending final cleanup/evidence packaging and exact-head binding**; this is not yet final PASS. Reviewed product/evidence head isb883042, product source105cd7e, base8b63212. No build, product edit or device/ADB/SDK operation was repeated by this verifier.

## Valid font and launcher path

B's Q06f actual1.3 resource configuration,400-character readback and top/bottom PNG/XML agree with A's earlier successful case. Both actual enlarged-font screens are readable and vertically navigable with56dp controls; no-selection send remains disabled. AC4 did not require both vehicles for this item, but both are now directly evidenced. The separate launcher-apparatus correction changes external QA only: MAIN/LAUNCHER with normal NEW_TASK+RESET_TASK_IF_NEEDED and launch wait. Earlier repeated plain component am-start calls stacked MainActivity instances and could leave DrivingView in front. Those failed apparatus steps remain preserved and are not relabeled product failures or current successes.

## Explicit retry and real cleanup ordering

Q03d's16events are an exact prefix of the raw B collector after its checkpoint. Counting actual production event names, not the initially mistaken summary predicate, yields start/init/record/listen/cancel/stop-pending/release twice each; **voice:sdkPcmCompleted0, voice:finalText0**. A/B route counts are0. Running→inactive app-op observations establish actual microphone recording and closure for both attempts. Visible recording and post-cancel PNG/XML show listening controls while active, then the cancellation message, blank/public-hint heard-text field and disabled analysis after cancellation.

The second explicit start occurs1515ms after the first sdkStopPending, during engine cleanup. The old sdkReleased event precedes the next sdkInitializedOnDevice by33ms. Thus same-instance cleanup serialization is directly exercised rather than inferred from a long idle retry. This is a valid explicit retry after font/Activity recreation; it is not falsely described as a new Activity starting within3seconds of the previous Activity's release. That optional cross-instance overlap remains a stated limit rather than an added requirement.

The summary atUtc04:55:13 is start metadata earlier than its before-checkpoints. It was not used as an end boundary. Raw last-release host time04:55:43.879974 closes the verified retry sequence. Full exact counts and timing are in late-runtime-independent-audit.json.

## Actual bounded failure and capture correction

Q04b has one actual app microphone trial with no intentional speech or playback. It records physical app-op duration1.603s, explicit stop/closure, one SDK PCM completion, one failureTIMEOUT and no accepted finalText. Stop-to-timeout is15097ms, consistent with the original15-second final window and tick scheduling. SDK release follows. This is real bounded no-result handling, not a recognition-accuracy success.

The initial case's pass=false is its screenshot privacy guard mistaking the public EditText hint for entered content. Q04c does not start a new microphone attempt. The raw interval through Q04c still contains only that one start; the actual fields independently match the known public hint. The reviewed PNG/XML visibly displays the plain Korean timeout message, explicit retry/manual input choices and manual place selection. No private transcription was captured. This resolves AC4's actual failure-view gap while preserving the original guard failure and correction.

## Negative, real fuel and no-match UI

Actual Q05c screenshots/hierarchies show that editing a previously chosen cafe to the negative public sentence clears the selection, hides candidate confirmation and disables send. The real fuel phrase produces one candidate named HD현대오일뱅크 세종주유소 with full address 경기도 성남시 수정구 여수대로 10. The user must explicitly select it before send becomes enabled; no send is performed. Editing to generic cafe finds no literal stored cafe candidate, clears the fuel choice and disables send; the app does not infer the category of 가든서비스.

The coordinator also records a literal ASCII instruction-looking string as untrusted EditText data, with no candidate action or enabled send. It is not an instruction to the verifier or an STT result. Source/JVM and the actual edited-draft controls support the separation between text data and proposal/route authority. No whole bookmark list, raw microphone file, authentication value or private transcript appears in the reviewed outputs.

Raw occurrence counts from the negative-case checkpoint through the bounded-failure UI cutoff remain A/B requestRoute0. Both current room revisions remain2; accepted historical provenance is not turned into new route consent by these UI/recreation/retry operations. Collector gap/error files remain absent at this audit, rather than being filtered away by the case summaries.

## Exact remaining gate

Against the original AC text, the functional evidence now covers foreground mic start/stop/cancel/retry and invalidation; actual B human final with attributed input source; corrected/ambiguous/negative editable drafts and real current candidates; explicit reviewed send and peer receive/reject0; unanimous one-route-per-car actual fresh native confirmation with original/actual data separate; actual failure/cancel/long/font UI; and independent198JVM+94Node/build/lint. The previous failures remain preserved with apparatus and input-source limits.

Only final closeout must still be directly reviewed: restored font/mic state and stopped collector, final preserved artifact/old-evidence/source hashes, final issue/requirements and exact base/head, and the evidence-only delta binding to the independently built73-file source. The verifier has not issued a merge-usable PASS before those inputs arrive. A future source change requires renewed source/build verification; an evidence-only head still requires direct delta/hash review.
