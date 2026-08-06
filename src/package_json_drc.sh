#!/usr/bin/env bash
# Package json_drc as a portable, self-contained archive.
#
# The bundle includes:
#   - RapidWright 2025.2.1 standalone jar (Linux x86_64 — see README)
#   - rapidwright_json_drc.jar (our tool)
#   - gson-2.10.1.jar
#   - RapidWright runtime data for V7 (xc7vx485t + global tables)
#   - V7 wire oracle for the xc7vx485tffg1761-2 part
#   - run.sh launcher (sets RAPIDWRIGHT_PATH and invokes java)
#
# Target machine needs: java 11+ on PATH.  No other prerequisites.
#
# Usage:  bash package_json_drc.sh   ->   /tmp/json_drc-portable-<ts>.tar.gz
set -euo pipefail

cd "$(dirname "$0")"
bash build.sh > /dev/null         # ensure jars are fresh

# Stamp the archive without using Date.now() — we use the most recent git
# commit timestamp on the source files so the script is reproducible.
STAMP=$(git -C "$HOME/rapidwright" log -1 --format=%cd --date=format:%Y%m%d 2>/dev/null \
         || echo "snapshot")
STAGE=/tmp/json_drc-portable-staging
PKG=json_drc-portable
OUT=/tmp/${PKG}-${STAMP}.tar.gz

rm -rf "$STAGE"
mkdir -p "$STAGE/$PKG/lib" \
         "$STAGE/$PKG/data" \
         "$STAGE/$PKG/data/devices/virtex7" \
         "$STAGE/$PKG/data/routeThrus" \
         "$STAGE/$PKG/oracle" \
         "$STAGE/$PKG/src"

# --- jars ---
cp ~/rapidwright/rapidwright-2025.2.1-standalone-lin64.jar  "$STAGE/$PKG/lib/"
cp ~/rapidwright/build/rapidwright_json_drc.jar              "$STAGE/$PKG/lib/"
cp ~/rapidwright/jars/jars/gson-2.10.1.jar                   "$STAGE/$PKG/lib/"

# --- global RapidWright runtime data ---
cp ~/.local/share/RapidWright/data/cell_pin_defaults.dat{,.md5} "$STAGE/$PKG/data/"
cp ~/.local/share/RapidWright/data/partdump.csv{,.md5}          "$STAGE/$PKG/data/"
cp ~/.local/share/RapidWright/data/parts.db{,.md5}              "$STAGE/$PKG/data/"
cp ~/.local/share/RapidWright/data/unisim_data.dat{,.md5}       "$STAGE/$PKG/data/"
cp ~/.local/share/RapidWright/data/versal_vdistr_trees.dat{,.md5} "$STAGE/$PKG/data/"
# routeThrus dir — small text files; copy all (no part-specific subset cost)
cp ~/.local/share/RapidWright/data/routeThrus/*.rt              "$STAGE/$PKG/data/routeThrus/"

# --- V7 device data (xc7vx485t only — sized for the VC707 part) ---
cp ~/.local/share/RapidWright/data/devices/virtex7/xc7vx485t_db.dat{,.md5} \
   "$STAGE/$PKG/data/devices/virtex7/"
# The _cache file is regenerated on first use if missing; ship it to save
# ~ 60s on the target machine.
cp ~/.local/share/RapidWright/data/devices/virtex7/xc7vx485t_db_cache.dat \
   "$STAGE/$PKG/data/devices/virtex7/"

# --- V7 wire oracle ---
cp /home/jonathan/min_ibufds_ff_led/oracle/xc7vx485tffg1761-2.oracle.txt.gz \
   "$STAGE/$PKG/oracle/"

# --- source in preferred form for modification ---
# Everything we wrote ourselves: the DRC checker, the json2dcp builder,
# the dcp2fasm emitter, the wire-oracle helpers, the build glue, and
# this packaging script.  RapidWright and gson are upstream libraries
# (see SOURCES.md); their source lives at their own repositories.
cp ~/rapidwright/build/json_drc.java          "$STAGE/$PKG/src/"
cp ~/rapidwright/build/json2dcp.java          "$STAGE/$PKG/src/"
cp ~/rapidwright/build/dcp2fasm.java          "$STAGE/$PKG/src/"
cp ~/rapidwright/build/WireOracle.java        "$STAGE/$PKG/src/"
cp ~/rapidwright/build/BuildWireOracle.java   "$STAGE/$PKG/src/"
cp ~/rapidwright/build/DumpTileWires.java     "$STAGE/$PKG/src/"
cp ~/rapidwright/build/list_iob_bels.java     "$STAGE/$PKG/src/"
cp ~/rapidwright/build/manifest.mf            "$STAGE/$PKG/src/"
cp ~/rapidwright/build/build.sh               "$STAGE/$PKG/src/"
cp ~/rapidwright/build/package_json_drc.sh    "$STAGE/$PKG/src/"

cat > "$STAGE/$PKG/src/SOURCES.md" <<'SRC'
# Source — preferred form for modification

The `.java` files and the two shell scripts in this directory are the
preferred form for modification of every component of this tool that
we authored.  Together they reproduce the bundle byte-for-byte (modulo
the timestamps embedded in the tar header).

## Rebuild from inside the bundle

The bundle's `lib/` already contains all the jars that `javac` needs:

    cd src
    javac -d ../lib/classes \
          -cp ../lib/rapidwright-2025.2.1-standalone-lin64.jar:../lib/gson-2.10.1.jar \
          WireOracle.java json2dcp.java dcp2fasm.java json_drc.java
    jar -cfm ../lib/rapidwright_json_drc.jar manifest.mf -C ../lib/classes dev

After that, `../run.sh` picks up the new jar.  Edit any of the `.java`
files first to change behaviour.

## Repackaging

`package_json_drc.sh` is the script that produced this tarball.  It
expects the original development layout (`~/rapidwright/build/`,
`~/.local/share/RapidWright/data/`, the wire-oracle path) and will not
work unmodified from the unpacked bundle — it is included here as the
authoritative record of how the archive was assembled.

## Upstream sources

These libraries are bundled as binaries; their source lives upstream:

| Component                                | Upstream                                                                |
|------------------------------------------|-------------------------------------------------------------------------|
| `rapidwright-2025.2.1-standalone-lin64.jar` | <https://github.com/Xilinx/RapidWright> (release tag 2025.2.1)        |
| `gson-2.10.1.jar`                        | <https://github.com/google/gson> (release tag gson-parent-2.10.1)       |
| `data/cell_pin_defaults.dat` etc.        | RapidWright `data/` zip — fetched by RapidWright from its release URL  |
| `data/devices/virtex7/xc7vx485t_db.dat`  | Same as above                                                          |
| `oracle/*.oracle.txt.gz`                 | Built by `BuildWireOracle.java` against RapidWright's DeviceResources  |

The oracle file is reproducible by running:

    javac -cp ../lib/rapidwright-2025.2.1-standalone-lin64.jar \
          BuildWireOracle.java
    java  -cp .:../lib/rapidwright-2025.2.1-standalone-lin64.jar \
          BuildWireOracle xc7vx485tffg1761-2 \
          ../oracle/xc7vx485tffg1761-2.oracle.txt.gz
SRC

# --- launcher ---
cat > "$STAGE/$PKG/run.sh" <<'SCRIPT'
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
SCRIPT
chmod +x "$STAGE/$PKG/run.sh"

# --- README ---
cat > "$STAGE/$PKG/README.md" <<'DOC'
# json_drc — portable Vivado-free physical DRC

Self-contained bundle of the `json_drc` tool plus RapidWright runtime
data and the V7 wire oracle.  Drop on any Linux x86_64 host with a
Java 11+ JRE on PATH, no install needed.

## Quick start

    tar -xzf json_drc-portable-*.tar.gz
    cd json_drc-portable
    ./run.sh --help                              # usage
    ./run.sh some_existing_design.dcp            # DRC an existing DCP
    ./run.sh --from-json xc7vx485tffg1761-2 \
              routed.json                        # build a tmp DCP and DRC it
    ./run.sh --from-json --keep xc7vx485tffg1761-2 \
              routed.json                        # keep the tmp DCP in /tmp
    ./run.sh --from-json --dump-fasm out.fasm \
              xc7vx485tffg1761-2 routed.json    # also emit FASM (via dcp2fasm)
    ./run.sh --from-json --dump-dcp out.dcp \
              --dump-fasm out.fasm \
              xc7vx485tffg1761-2 routed.json    # both intermediates

Exit code 0 = clean, 1 = DRC errors, 2 = bad input.

## Dumping artifacts

`--dump-dcp <path>`   copies the (temp or input) DCP to `<path>`.
`--dump-fasm <path>`  runs the bundled `dcp2fasm` and writes FASM to
`<path>`.  From there, feed it to `prjxray`'s `fasm2frames.py` +
`xc7frames2bit` on a host that has the prjxray database installed to
produce a loadable `.bit`.  The bundle does **not** ship prjxray.

## What gets checked

Each check is one of the V7 open-flow failure modes hit end-to-end:

| Tag                  | Failure                                                       |
|----------------------|---------------------------------------------------------------|
| `UNPLACED`           | Cell with no SiteInst                                         |
| `NO-SOURCE`          | Connected net has sinks but no source SitePinInst             |
| `BUFG-FABRIC`        | BUFG.I0 routed via fabric IMUX, not CK_MUXED0 / CK_BUFG_CASC  |
| `PRECYINIT-*`        | CARRY4 PRECYINIT unbound or disagrees with CI net             |
| `C4-OUT-UNROUTED`    | CARRY4 output has sinks but no source SitePinInst             |
| `C4-NO-PHYS-NET`     | CARRY4 output net entirely absent from the physical Design    |
| `SPI-ORPHAN`         | SitePinInst not attached to any Net (cell-move leftover)      |
| `STATIC-UNROUTED`    | VCC/GND sink with no PIP feeding its tile wire (warning)      |

## Platform

Bundle ships the **Linux x86_64** RapidWright standalone jar.  For
macOS or Windows targets, swap
`lib/rapidwright-2025.2.1-standalone-lin64.jar` for the matching
RapidWright release jar from
<https://github.com/Xilinx/RapidWright/releases>.

## Layout

    json_drc-portable/
      run.sh                                 — launcher
      lib/
        rapidwright_json_drc.jar             — the DRC tool (+ json2dcp)
        rapidwright-2025.2.1-standalone-lin64.jar
        gson-2.10.1.jar
      data/
        cell_pin_defaults.dat, partdump.csv, parts.db, unisim_data.dat,
        versal_vdistr_trees.dat               — RapidWright global tables
        routeThrus/                           — per-part routethru hints
        devices/virtex7/xc7vx485t_db.dat      — V7 device DB (VC707 part)
        devices/virtex7/xc7vx485t_db_cache.dat — speeds up first run
      oracle/
        xc7vx485tffg1761-2.oracle.txt.gz      — V7 wire-name oracle
      src/                                    — source in preferred form
        json_drc.java, json2dcp.java, dcp2fasm.java, WireOracle.java,
        BuildWireOracle.java, DumpTileWires.java, list_iob_bels.java,
        manifest.mf, build.sh, package_json_drc.sh, SOURCES.md

## Source / rebuild

Every `.java` file we authored, plus the build glue, is included in
`src/` in the preferred form for modification.  See `src/SOURCES.md`
for the in-place rebuild recipe (no extra downloads — the bundle's
`lib/` already has every jar `javac` needs) and pointers to the
upstream sources for RapidWright, gson, and the device data.

## Adding more parts

Drop additional `<part>_db.dat` files into `data/devices/<family>/`
from a host that already has RapidWright installed
(`~/.local/share/RapidWright/data/devices/`).  No other change needed.
DOC

# --- archive ---
tar -C "$STAGE" -czf "$OUT" "$PKG"

ls -lh "$OUT"
echo
echo "Archive: $OUT"
echo "Unpack:  tar -xzf $(basename "$OUT")  &&  cd $PKG  &&  ./run.sh --help"
