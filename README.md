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
