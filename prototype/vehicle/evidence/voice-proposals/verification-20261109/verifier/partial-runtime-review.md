# Partial runtime audit — Q02/Q03/Q05/Q07

Overall remains **BLOCKED** pending the rest of the required scenarios and final exact-head binding. This is an independent read-only artifact review at head `b883042cbc9610a551b3329bb6effdd219eabd06`, base `8b6321276f33923f24f15a8437205b6126bf92ca`. No source/build rerun or device/ADB/SDK/server operation was performed.

## Directly observed evidence

| Case | Direct finding | Limit |
|---|---|---|
| Q02 A/B explicit cancel | Actual app microphone init/recording/listening, explicit accepted public cancel actions, and stopped package app-op durations1.569s/1.435s. Recording-to-cancel traces1538ms/1417ms are shorter than automatic15s. Raw collector continued15s after cancel with no SDK PCM submission, accepted finalText or requestRoute. | A result lacks a separate running app-op snapshot; recording state and the closed app-op duration corroborate the interval. Complete installation/user binding is assessed with final global inputs. |
| Q03 A page exit | Explicit nav_home, microphone scope invalidation after1300ms, app-op stopped duration1.363s and delayed SDK release. No PCM/final/route in the15-second follow-up window. | Composer exit, not Activity recreation. |
| Q03 B opaque Activity | scopeCleared→activityStopDetach uiCount0→serviceDestroy and app-op stopped duration2.451s; no PCM/final/route in the15-second follow-up. | SpeechProbeActivity auto-initializes ON_DEVICE SDK onCreate. Erratum correctly supersedes probeSdkInitialized=false. This supports app microphone/UI detachment only; it is not isolated engine-cleanup or probe-free fast-retry proof. |
| Q05 missing snapshot | Explicit edited-text fixture displays a no-current-list instruction; no invented candidate. | Correct guard for the canceled place-list state, not a populated saved-place candidate success. |
| Q05b actual candidate/review | PNG and hierarchy show actual name 가든서비스 and address 경기도 성남시 수정구 창업로40번길 6 together, one explicit candidate, then selected name/address and an editable public reason with send enabled. Result says sent=false. | Input is an edited public UI fixture, not STT or human speech. Full candidate metadata is intentionally not journaled; source permits only current immutable native objects. |
| Q07 pending→reject | Explicit send on A, same real name/address/reason on B with accept/reject controls, explicit B reject, and both final screens restore prior 청계산담백미 with rejection text. Home does not claim new native confirmation. | This is rejection, not unanimous acceptance or current native success. |

## Independent raw occurrence count for Q07

The Q07-before checkpoints are A offset45 and B offset78. Through result time2026-11-09T04:11:51.655780Z, raw collector intervals are A45→54 (9rows) and B78→86 (8rows). Every requestRoute occurrence was counted; no set/event deduplication was applied. Counts are A0/B0, and every state revision remains1. Production PIDs stay32650/19726 and sourceGeneration stays0. Root result traces are exact prefixes of these raw windows and report the same counts.

There are34A and33B state observations during the case; maximum adjacent sampling intervals are1.386224s and1.411069s. No collector gap/error file or matching case error/gap was present at audit time. Send tap is recorded04:11:24.937262Z, reject tap04:11:46.759166Z, supporting more than10s of waiting. The latest proposal changes from pending version1 to rejected version2 with the same id0cdedfffc86a7036aa77396e, while prior accepted provenance e669d8a5dd735bef86b9ff48/revision1 stays separate. This is consistent with no candidate commit or path request.

The reviewer inspected all six Q05b/Q07 PNGs and matching hierarchy data. The candidate row and selected preview show the same actual name/address; the sender waiting card and peer response card carry the edited public reason. Both rejected views retain the old shared place and neutral historical-accepted wording. The stated input source is consistent with VehicleUiTextProbe fixed strings and public UI taps. No raw audio, private transcript, auth value, invitation or whole saved-place list appeared in the reviewed case outputs.

## Apparatus and preservation notes

Raw .xml files include `UI hierchary dumped to: /proc/self/fd/1` after the closing hierarchy element. A strict ElementTree parse therefore failed with junk-after-document-element. The failed local parser attempt is retained in `partial-audit-preparation-failure.json`; recovery parsed only the complete hierarchy and recorded each original suffix/hash. Original root evidence files were not changed. This is a raw-output packaging detail, not a product failure.

`partial-runtime-audit.json` contains the detailed recalculation, public action chronology, extracted hierarchy fields, case evidence hashes and exact bounded limits. Own stable collector-prefix snapshots through Q07 are stored only in this verifier directory. The completed Q08, fontScale1.3/long-text, probe-free Activity recreation/fast-retry and final global installation/head/cleanup inputs remain to be audited. No full-AC PASS is issued by this partial review.
