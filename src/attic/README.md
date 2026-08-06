# src/attic — not compiled

Everything here came from the `~/rapidwright/build/` development tree when it
was folded into this repo.  None of it is on the `make jars` path; it is kept
because it is cheap to keep and expensive to rediscover.

## The parked json2dcp fork

`json2dcp.rw-build-jun20.java` is **not** a stale copy of `../json2dcp.java` —
it is a second line of development on the same file, and it was not merged.

Both descend from `~/rapidwright/build` commit `b2a35f4`, then diverged:

| | live (`../json2dcp.java`) | parked (this file) |
|---|---|---|
| last worked on | Aug 6 | Jun 20 |
| provenance | the DCP-import campaign | the Phase-B / fixed-routes campaign |
| evidence | johnson + xmux verified **on silicon**; servmin/picosoc/ibex at Vivado DRC+TIMING PASS | FASM/bit-splice work |
| env prefix | `J2D_*` | `JSON2DCP_*` |

A three-way merge is *textually* easy — 8 conflict hunks, most of them the two
branches fixing the same defect in slightly different words.  It was not taken,
because the clean-merging remainder is the dangerous part: the parked branch's
`suppressPhys` grafts itself into `connect_log_and_phys` for every RAMB cell,
which silently changes the behaviour of the exact code path behind the
silicon-verified results.  Merging that on a "it compiled" basis would trade a
proven tool for an unproven one.

What is parked here, and would have to be re-applied deliberately, with the
signoff matrix re-run afterwards:

- **`suppressPhys`** — suppress RAMB18/36 physical pin mappings, then route the
  const-tied control inputs (`ENARDEN`/`ENBWREN`/`RST*`/`REGCE*`/`CLK*`)
  intra-site the way golden does: a site pin on the const net plus a SitePIP
  through the `<pin>INV` routing BEL.  Aimed at Vivado DRC `PDIL-1`.
  Debug with `JSON2DCP_RAMB_DBG`.
- **`$svs_unconn` skip** — treat unused primitive ports as genuinely
  unconnected (golden leaves the bel pin NULL) rather than making nets for
  them.  The live branch reaches a similar end by a different route.
- **`JSON2DCP_OMIT_ROUTING_FILE`** — leave a named set of nets unrouted, for
  bisecting which net breaks a checkpoint.
- **orphan-prune diagnostics** — `swDropShapes` / `swDropExamples`, shape
  histograms of dropped sitewire hops.
- misc knobs: `JSON2DCP_NO_CARRY_INJECT`, `JSON2DCP_NO_RAMB_ROUTESITE`,
  `JSON2DCP_NO_SLICE_ROUTESITE`, `JSON2DCP_SKIP_CELLS`, `JSON2DCP_VALIDATE`,
  `JSON2DCP_CONFLICT_REPORT`, `JSON2DCP_DUMP_SITES`, `JSON2DCP_SERIAL`.

`dcp2fasm.java` needed no such care: the copy this repo had was byte-identical
to `b2a35f4`, so the development tree's version was a pure descendant and was
fast-forwarded straight into `src/`.  That recovered ~890 lines of Phase-B work
(MMCM, distributed RAM, `REBUF`, `OUTMUX`) that this repo had never carried —
`src/dcp2fasm.java` had been a Jun 4 snapshot.

## One-off probes and fixups

Small programs written to answer one question against a specific design, each
superseded by something in `src/` or by a settled conclusion:

- `CK*.java`, `ClkProbe.java`, `GclkChk.java`, `check_bufg_clock.java`,
  `fix_bufg_clock.java` — BUFG / clock-routing investigations.
- `fix_carry4_ff_*.java`, `inspect_carry_routethru.java` — CARRY4+FF packing
  and route-through repairs.
- `fix_via_golden.java`, `fix_via_golden_groups.java`, `greedy_min.java` —
  bisecting a broken checkpoint against a golden one by copying pieces across.
- `RambConstInspect.java`, `RouteThruInspect.java`, `FFRouteThruExtract.java`,
  `PinMapExtract.java`, `NodeCheck.java`, `probe_site.java`, `SP.java`,
  `MapTest.java`, `Pr2.java`, `Probe33.java`, `IOPlaceTest.java`, `LED.java` —
  point queries against the RapidWright device model.

To run one, compile it against the same classpath `make jars` uses:

    javac -d /tmp/attic -cp lib/rapidwright-2025.2.1-standalone-lin64.jar:lib/gson-2.10.1.jar \
          src/attic/<name>.java
    RAPIDWRIGHT_PATH=$PWD/data java -cp /tmp/attic:lib/rapidwright-2025.2.1-standalone-lin64.jar \
          dev.fpga.rapidwright.<name> ...
