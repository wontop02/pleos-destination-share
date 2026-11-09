#!/bin/zsh
# Resolve this entry's real location, including project-parent symlinks and spaces.
exec /usr/bin/python3 - "$0" "$@" <<'PY'
from pathlib import Path
import runpy
import sys
entry = Path(sys.argv[1]).resolve()
launcher = entry.parent / 'prototype' / 'vehicle' / 'demo' / 'launcher.py'
sys.argv = [str(launcher)] + sys.argv[2:]
runpy.run_path(str(launcher), run_name='__main__')
PY
