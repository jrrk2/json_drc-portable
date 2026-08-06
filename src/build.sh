#!/usr/bin/env bash
# Build script for rapidwright_json2dcp.jar
#
# Layout this script expects:
#   ~/rapidwright/
#     rapidwright-2025.2.1-standalone-lin64.jar       (~95 MB, runtime)
#     jars/jars/*.jar                                 (deps incl. gson-2.10.1.jar)
#     build/json2dcp.java                             (patched source)
#     build/manifest.mf                               (Main-Class + Class-Path)
#
# RapidWright device data must be installed at ~/.local/share/RapidWright/data/
# (the rapidwright_data.zip + rapidwright_data2.zip extract there).
set -euo pipefail
cd "$(dirname "$0")"
SA=$HOME/rapidwright/rapidwright-2025.2.1-standalone-lin64.jar
GSON=$HOME/rapidwright/jars/jars/gson-2.10.1.jar
test -f "$SA"   || { echo "missing $SA";   exit 1; }
test -f "$GSON" || { echo "missing $GSON"; exit 1; }
rm -rf dev
javac -d . -cp "$SA:$GSON" WireOracle.java json2dcp.java dcp2fasm.java json_drc.java
jar cfm rapidwright_json2dcp.jar manifest.mf -C . dev
# Sibling jar for the DCP -> FASM prototype.
cp manifest.mf manifest_dcp2fasm.mf
sed -i 's/json2dcp/dcp2fasm/' manifest_dcp2fasm.mf
jar cfm rapidwright_dcp2fasm.jar manifest_dcp2fasm.mf -C . dev
# Sibling jar for the JSON/DCP physical-DRC pass.
cp manifest.mf manifest_json_drc.mf
sed -i 's/json2dcp/json_drc/' manifest_json_drc.mf
jar cfm rapidwright_json_drc.jar manifest_json_drc.mf -C . dev
ls -la rapidwright_dcp2fasm.jar
ls -la rapidwright_json2dcp.jar
ls -la rapidwright_json_drc.jar
echo
echo "usage: java -jar rapidwright_json2dcp.jar <part> <routed.json> <out.dcp>"
echo "  e.g. java -jar $PWD/rapidwright_json2dcp.jar \\"
echo "         xc7vx485tffg1761-2 \\"
echo "         ~/vc707_rst_to_led/rst_to_led_routed.json \\"
echo "         /tmp/rst_to_led.dcp"
