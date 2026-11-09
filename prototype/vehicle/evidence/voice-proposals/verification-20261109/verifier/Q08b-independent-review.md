# Q08b — independent runtime evidence audit

The current Q08b evidence is consistent with the required two-car unanimous apply and actual native confirmation. Overall remains **BLOCKED** until the remaining UI/lifetime scenarios and final exact head are reviewed. This report is bound to source/evidence head `b883042cbc9610a551b3329bb6effdd219eabd06`, base `8b6321276f33923f24f15a8437205b6126bf92ca`. It does not reuse the coordinator's pass field as its basis.

## Raw occurrence counts and consent

The reviewer read complete collector rows after Q08b-before offsets A68/B90 through the extension boundary2026-11-09T04:28:40.875491Z. These intervals contain46A rows and45B rows. Every raw requestRoute occurrence was counted without set/event deduplication: A1/B1. Before the explicit B accept-begin marker, both counts are0. The new proposal994d714aa66333c17676e84b is distinct from the previous rejected proposal and prior accepted target. Both frozen members are accepted before route issue, accepted version2/revision2 is associated with the same proposal, and original base revision1 is preserved.

Q08 and Q08a pre-send apparatus interruptions are not taken as product failures or successes. Their reviewed candidate/reason UI remains useful input provenance, but only Q08b's checkpoint-to-result interval is counted as the completed acceptance case. Input here is explicit edited public text plus selection from the actual native saved list; it is not a new human recognition test or STT-result injection.

## Fresh native confirmation seam

| Car | Issued route | Fresh verification after native cancellation/reconnect | Positive callback and match |
|---|---|---|---|
| A/emulator-5570 | native epoch4, operation4, one request | New query-only epoch5, same operation4 | getDestinationInfo→onDestinationInfo→destinationMatch(true), epoch5/op4 |
| B/emulator-5572 | native epoch2, operation2, one request | New query-only epoch3, same operation2 | getDestinationInfo→onDestinationInfo→destinationMatch(true), epoch3/op2 |

Each side first receives two absent responses and only later accepts a positive callback. The new epoch resumes verification without another route. The matching query begins after the one issued route, and the positive callback and match share the query epoch/operation. Both enter actual `CONFIRMED` with `confirmationKind=pleos_place`, current sharedKind=pleos_place, revision2 and matching accepted-proposal provenance. Route-start events alone are not treated as confirmation. The original roughly45-second lease ends and both services are destroyed with uiCount0 and lease=false; the later quiet observation is not described as an active186-second service.

Across the extension there are170 state observations per device, maximum adjacent sample intervals2.287892s/2.356659s, constant production PIDs32650/19726 and sourceGeneration0. No collector gap/error is recorded in the case interval. The host action log has no additional recorded action during the extension. Root reports no SDK/UI operations then, and the two later independent queries start after the extension boundary.

The extension summary's186.306/186.430seconds subtract a device wall-time event from host time. This review does not claim that mixed-clock value is exact. Using host timestamps of the first observed requestRoute wrappers gives conservative post-route lower bounds185.208566seconds(A) and185.9812seconds(B). Both comfortably exceed45seconds, and no extra route appears throughout the bounded raw interval.

## Independent public native reads

Both later NativeDestinationDriver queries returned exit0/OBSERVED, mode=query, targetPackage=the self-targeted driver, routeRequestCount0, navigationScreenRequestCount0 and tripRequestCount0. Driver PIDs8232/7974 differ from the product PIDs, which remain unchanged. The source's query branch performs a real NaviHelper connect/read and uses callback data, not an expected-value copy. Each output contains a new getDestinationInfo/query1 followed by onDestinationInfo/query1 in the same session1.

Both actual native results independently report name 가든서비스, POI36khp2h36p68cm5nqhec, provider empty string and nullable sub-ID null. Actual navigation coordinates are37.412475/127.09534722, address 수정구 시흥동. The reviewed original selection screen instead shows the full saved address 경기도 성남시 수정구 창업로40번길 6. These facts are reported separately. The current raw files show the original name/address; a complete original one-place DTO has been requested for a full field comparison without exposing a bookmark list or authentication data.

The independent reads occur after the critical extension, so they cannot serve as an injected success during the application confirmation window. Application journal's earlier positive callback/match is independent of these later read-only driver calls.

## Installation binding and preserved limits

The post-case installed binding identifies both foreground users as10, product UID1010174, stable product PIDs32650/19726, actual product APK hash `5b9eec0cca7f645e25ef4615cd5096d8e31b9256141d35f8d5018bc2c398317e` and driver hash `5f99cb5355e12f9af818e1b6740aa3a4f7a9974b387de93a3f8e504639e5a4ea`. These match the independently hashed builder artifacts and the continuous collector PID observations. The reviewer read this actual coordinator device evidence; no ADB or package operation was performed by the reviewer.

Detailed raw occurrence counts, correlated positive query seams, typed states, independent query data, input file hashes and stable collector-prefix snapshots are in `Q08b-independent-audit.json` and the `partial-*-through-Q08b.jsonl` files in this verifier directory. This scoped audit establishes the current two-car route/confirmation evidence while keeping the remaining final AC/head checks open.
