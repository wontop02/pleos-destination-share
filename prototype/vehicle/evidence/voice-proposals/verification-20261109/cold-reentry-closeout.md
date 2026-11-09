# Normal cold re-entry after runtime verification

The critical collector ended05:00:26UTC with A PID32650/B19726, generation0 and4208 observations each. Its raw journal, observations and H1 snapshots remain unchanged. This separate cleanup removes the multiple MainActivity instances created by the earlier plain-component launch harness.

At05:10:16 A and05:10:30 B, only the owned product package was normally force-stopped and relaunched with actual MAIN/LAUNCHER NEW_TASK|RESET_TASK_IF_NEEDED, windowingMode6/display0. AVDs, package data/auth/room, installed packages, driver, vendor apps and servers were preserved. Each app returned with exactly one MainActivity. A PID19921/B12020 are explicitly new and are not joined to the old continuous collector generation.

Q10c-clean-cold-reentry.json retains the old journal prefix, every new event and12 post-entry samples per vehicle spanning12.23/12.32seconds. RequestRoute occurrences are0 each, shared revision2 is restored with automaticOFF/IDLE/no proposal lease. Font1.0 and microphone inactive remain. A is on home, B on a clean composer. Q10c-final-installed-package-binding.json independently rechecks installed app/driver SHA256 under user10 and binds the new PIDs to the same packages.

This proves restoration of the historical accepted revision without automatic reapply in that bounded window. A idle fresh query was positive, B idle query observed absence; both currentConfirmedAtElapsed are null. It is not a new liveCONFIRMED/native-place assertion and does not replace or expand Q08b's earlier fresh unanimous apply evidence. The product correctly does not restore a stale green confirmation or secretly route from a historical accepted proposal.

Q10c-actions-delta.jsonl contains only the later actions appended after the immutable H1 action snapshot. Q10c-cold-reentry-audit.json states sample bounds, PID changes and exact occurrence counts. No full AVD reboot was performed; the transport default-route helper remains necessary after a future AVD boot.
