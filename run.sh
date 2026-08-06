#!/usr/bin/env bash
# json_drc launcher.  Self-locating: works wherever the bundle is unpacked.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Tell RapidWright where its data lives.  Avoids polluting the user's
# ~/.local/share/RapidWright.
export RAPIDWRIGHT_PATH="$HERE/data"

# Tell json2dcp where the V7 wire oracle lives (only consulted when the
# target part is xc7vx485tffg1761-2).
export XRAY_WIRE_ORACLE="$HERE/oracle/xc7vx485tffg1761-2.oracle.txt.gz"

exec java -cp "$HERE/lib/rapidwright_json_drc.jar:$HERE/lib/rapidwright-2025.2.1-standalone-lin64.jar:$HERE/lib/gson-2.10.1.jar" \
          dev.fpga.rapidwright.json_drc "$@"
