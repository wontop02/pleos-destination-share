"""Restore only the two approved live AVD default API routes on this Mac."""
from pathlib import Path
import datetime
import json
import runpy
import socket

# Refuse to auto-start an absent ADB server. The loaded helper scopes all calls
# to the dedicated toolchain, port5041 and approved emulator serials.
with socket.create_connection(("127.0.0.1", 5041), timeout=2):
    pass
u = runpy.run_path(str(Path(__file__).resolve().parent / "root-ui-tools.py"))
results = []
for serial in ["emulator-5570", "emulator-5572"]:
    assert u["shell"](serial, "am", "get-current-user").decode().strip() == "10"
    before = u["adb"](serial, "reverse", "--list").decode().splitlines()
    targets = [parts[2] for line in before if len(parts := line.split()) == 3 and parts[1] == "tcp:4182"]
    assert not targets or targets == ["tcp:4192"], "Different default mapping preserved; refusing override"
    assert any(line.split()[1:] == ["tcp:4192", "tcp:4192"] for line in before if len(line.split()) == 3), "Owned4192 mapping must exist first"
    if not targets:
        u["adb"](serial, "reverse", "--no-rebind", "tcp:4182", "tcp:4192")
    after = u["adb"](serial, "reverse", "--list").decode().splitlines()
    assert any(line.split()[1:] == ["tcp:4182", "tcp:4192"] for line in after if len(line.split()) == 3)
    results.append({"serial": serial, "default4182TargetsOwned4192": True, "existing4192Preserved": True, "newMapping": not targets})
print(json.dumps({"atUtc": datetime.datetime.now(datetime.timezone.utc).isoformat(), "results": results, "note": "Transport-scoped mapping; rerun after AVD boot. No app data, room identity or archived evidence is written."}, indent=2))
