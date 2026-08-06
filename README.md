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
      Makefile                               — fetch, build, verify, dist
      run.sh                                 — launcher
      lib/
        rapidwright_json_drc.jar             — the DRC tool
        rapidwright_json2dcp.jar             — routed JSON -> DCP
        rapidwright_dcp2fasm.jar             — DCP -> FASM
        rapidwright_dcp2xml.jar              — DCP -> opendcp XML
        rapidwright_xml2dcp.jar              — opendcp XML -> DCP
        rapidwright_xml2json.jar             — opendcp XML -> nextpnr JSON
        rapidwright_xml2fasm.jar             — opendcp XML -> FASM
        rapidwright_dcp2routes.jar           — DCP -> per-net routes
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
        the ten .java files above, one manifest per tool, SOURCES.md
        attic/                                — not compiled; see its README

## The opendcp XML round trip

`dcp2xml` writes a checkpoint out as readable XML plus an EDIF sidecar;
`xml2dcp` reads that pair back into a DCP:

    dcp2xml  in.dcp   out.xml        # also writes out.edif
    xml2dcp  out.xml  rebuilt.dcp    # sidecar found by name

Running it on a checkpoint Vivado itself produced is the control that
separates a defect in a *design* from a defect in the *DCP writer* — if
the rebuilt copy of a known-good checkpoint fails a check the original
passes, the writer lost something.  `dcp2xml`'s summary line reports
`routethru=`, which is also the tell for provenance: Vivado's own place
and route emits route-throughs, RapidWright-written DCPs have none.

## Source / rebuild

Every `.java` file we authored is in `src/` in the preferred form for
modification; `make jars` rebuilds them.  See `src/SOURCES.md` for the
tool-by-tool breakdown and pointers to the upstream sources for
RapidWright, gson, and the device data.

## Adding more parts

Drop additional `<part>_db.dat` files into `data/devices/<family>/`
from a host that already has RapidWright installed
(`~/.local/share/RapidWright/data/devices/`).  No other change needed.
