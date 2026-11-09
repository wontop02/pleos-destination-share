# Q08 source/native provenance addendum

The requested metadata gap is resolved at the current evidence level. Overall remains **BLOCKED** pending the remaining actual UI/lifetime conditions and final head. Reviewed head is `b883042cbc9610a551b3329bb6effdd219eabd06`; no product or test was changed or rerun.

A self-targeted public NativeDestinationDriver bookmark read returned exit0/OBSERVED. Its query2 and onBookmarkInfo callback share session1. The actual public filter retains only 가든서비스: one allowlisted entry out of four, with three other entries omitted entirely. It requested no route, screen or trip, and its actual before/after native destination remained unchanged. The driver PID7278 differs from product PID32650, which stayed unchanged. This was a later read after the Q08b critical interval, not the original composer snapshot or a fabricated HTTP body.

| Field | Actual public BookmarkInfo original | Actual independent native destination |
|---|---|---|
| Name | 가든서비스 | 가든서비스 |
| Latitude/longitude |37.41255277 /127.09591666|37.412475 /127.09534722|
| Address |경기도 성남시 수정구 창업로40번길 6|수정구 시흥동|
| POI ID |36khp2h36p68cm5nqhec|same|
| Provider/sub-ID |empty string/null|same|
| Display coordinate pair |null/null|null/null|

The observed coordinates differ by approximately51.031m; this is separate source/native metadata, not a relaxed exact-point match or a claim of equal entrance/route/calculation. The existing1e-5 exact-point coordinate check would reject those differences. The current feature uses explicit selected-place identity and keeps the legacy exact contract unchanged.

The actual app hierarchy's developer-detail text equals the supplemental observedText exactly. It shows current native as EXACT_POINT at37.412475/127.09534722 and the shared version2 target as PLEOS_PLACE at the original37.41255277/127.09591666 with the full saved address. Thus the accepted shared value retains original metadata rather than being replaced by the later actual destination. The source label PLEOS_BOOKMARK is **inferred from the validated Destination constructor invariant** for PLEOS_PLACE; it was not a separately observed SDK source field or captured HTTP wire body. Wire preservation remains covered by the unchanged source and current independent HTTP/JVM regressions, not misrepresented as this read's payload capture.

The app was reopened after the earlier confirmation lease ended. It correctly shows no current selection pointer and no restored typed confirmation in developer details; these cold-state values must not be combined with the earlier Q08b live CONFIRMED journal state. The separate native read is current observation, not renewed consent or automatic reapplication.

The PNG shows the developer-detail close control, while the coordinate text is below the current viewport. The hierarchy directly supports text content, but this PNG does not prove visual readability of those coordinate lines. That distinction was returned to the coordinator. No further private bookmark entries, raw audio, transcript or credential was read.

Detailed field comparison, original file hashes and limitations are in `Q08-provenance-audit.json`. Remaining final blockers: actual fontScale1.3/long-text and negative UI, a valid probe-free recreation/retry path with truthful timing, cleanup/installation continuity and exact final head binding. No full-AC PASS is issued by this addendum.
