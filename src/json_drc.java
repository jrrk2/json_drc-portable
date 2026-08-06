/* Vivado-free physical DRC pass over a RapidWright Design.
 *
 * Two invocation modes:
 *
 *   (A) json_drc <in.dcp>
 *         Run checks against an existing DCP.
 *
 *   (B) json_drc --from-json [--keep] <device> <design.json>
 *         Build a DCP from nextpnr's routed JSON via json2dcp into a
 *         temporary file under /tmp, run checks against it, delete
 *         the temp file (or keep it for post-mortem if --keep is set).
 *
 * Each check covers one of the failure modes we have hit end-to-end
 * on the V7 open flow:
 *
 *   D1 UNPLACED          Cell with no SiteInst (synth packer cells
 *                        beginning '$' are skipped).
 *   D2 NO-SOURCE         Connected non-static net with sinks but no
 *                        source SitePinInst.  Catches "8 OBUFs floating"
 *                        / "rst_IBUF no source".
 *   D3 BUFG-FABRIC       BUFG/BUFGCTRL.I0 sourced from a fabric
 *                        IMUX/GFAN rather than CK_MUXED0 / CK_BUFG_CASC.
 *                        Mirrors check_bufg_clock.
 *   D4 PRECYINIT-*       CARRY4 PRECYINIT routing BEL unbound, or its
 *                        selection disagrees with the CI net: chain root
 *                        (CI = GND/VCC) wants C0/C1; chain follower
 *                        wants CIN.  The FasmInconsistentBits class.
 *   D5 C4-OUT-UNROUTED   CARRY4 with a logical O[i]/CO[i] sink elsewhere
 *                        in the design but no source SitePinInst on its
 *                        SLICE — xOUTMUX / COUT site PIP never bound.
 *   D6 SPI-ORPHAN        SitePinInst not attached to any Net — left
 *                        behind by cell unplace/move passes.
 *   D7 STATIC-UNROUTED   VCC/GND sink site pin with no PIP feeding its
 *                        tile wire (approximate; some sinks are tied
 *                        purely via intra-site SitePIPs).
 *
 * Exit:  0 = no errors    1 = errors    2 = bad CLI / unreadable input
 */
package dev.fpga.rapidwright;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.SitePIP;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFHierNet;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFPortInst;

import java.io.File;
import java.io.PrintStream;
import java.nio.file.Files;

public class json_drc {

    int errors   = 0;
    int warnings = 0;
    final PrintStream out = System.out;

    void err (String tag, String msg) { out.println("DRC-ERR  [" + tag + "] " + msg); errors++;   }
    void warn(String tag, String msg) { out.println("DRC-WARN [" + tag + "] " + msg); warnings++; }
    void info(String msg)             { out.println("DRC-INFO " + msg); }

    public static void main(String[] args) throws Exception {
        // Mode parsing -----------------------------------------------------
        boolean fromJson  = false;
        boolean keep      = false;
        String  dumpDcp   = null;        // path or null
        String  dumpFasm  = null;        // path or null
        int argi = 0;
        while (argi < args.length && args[argi].startsWith("--")) {
            switch (args[argi]) {
                case "--from-json": fromJson = true; break;
                case "--keep":      keep     = true; break;
                case "--dump-dcp":
                    if (++argi >= args.length) {
                        System.err.println("--dump-dcp needs a path"); System.exit(2);
                    }
                    dumpDcp = args[argi];
                    break;
                case "--dump-fasm":
                    if (++argi >= args.length) {
                        System.err.println("--dump-fasm needs a path"); System.exit(2);
                    }
                    dumpFasm = args[argi];
                    break;
                default:
                    System.err.println("unknown flag: " + args[argi]);
                    usage();
                    System.exit(2);
            }
            argi++;
        }

        File   tmpDcp = null;
        String dcpPath;

        if (fromJson) {
            if (args.length - argi < 2) { usage(); System.exit(2); }
            String device   = args[argi];
            String jsonPath = args[argi + 1];

            File jsonFile = new File(jsonPath);
            if (!jsonFile.isFile()) {
                System.err.println("error: JSON file not found: " + jsonPath
                                     + " (cwd: " + new File("").getAbsolutePath() + ")");
                System.exit(2);
            }

            tmpDcp = File.createTempFile("jsondrc-", ".dcp", new File("/tmp"));
            tmpDcp.deleteOnExit();
            dcpPath = tmpDcp.getAbsolutePath();

            System.out.println("DRC-INFO building DCP " + dcpPath
                                + (keep ? "  (will be kept)" : "  (will be deleted)"));
            // json2dcp prints its own progress, then writes the .dcp file.
            json2dcp.main(new String[] { device, jsonPath, dcpPath });
        } else {
            if (args.length - argi < 1) { usage(); System.exit(2); }
            dcpPath = args[argi];
            if (!new File(dcpPath).isFile()) {
                System.err.println("error: DCP file not found: " + dcpPath);
                System.exit(2);
            }
        }

        int rc;
        try {
            rc = new json_drc().runChecks(dcpPath);

            if (dumpDcp != null) {
                Files.copy(new File(dcpPath).toPath(),
                           new File(dumpDcp).toPath(),
                           java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                System.out.println("DRC-INFO wrote DCP: " + dumpDcp);
            }
            if (dumpFasm != null) {
                System.out.println("DRC-INFO emitting FASM via dcp2fasm");
                dcp2fasm.main(new String[] { dcpPath, dumpFasm });
                System.out.println("DRC-INFO wrote FASM: " + dumpFasm);
            }
        } finally {
            if (tmpDcp != null && !keep && tmpDcp.exists()) {
                try { Files.deleteIfExists(tmpDcp.toPath()); } catch (Exception ignored) {}
            }
        }
        System.exit(rc);
    }

    static void usage() {
        System.err.println("usage:");
        System.err.println("  json_drc [--dump-dcp <out.dcp>] [--dump-fasm <out.fasm>] <in.dcp>");
        System.err.println("  json_drc --from-json [--keep] \\");
        System.err.println("           [--dump-dcp <out.dcp>] [--dump-fasm <out.fasm>] \\");
        System.err.println("           <device> <design.json>");
        System.err.println();
        System.err.println("  --dump-dcp   copy the (temp or input) DCP to <path>");
        System.err.println("  --dump-fasm  run dcp2fasm and write FASM to <path>");
        System.err.println("  --keep       keep the temp DCP under /tmp (only with --from-json)");
    }

    /** Run all checks against the DCP at {@code dcpPath}; return 0 if clean,
     *  1 if any DRC-ERR was emitted. */
    int runChecks(String dcpPath) {
        Design des;
        try {
            des = Design.readCheckpoint(dcpPath);
        } catch (Throwable t) {
            System.err.println("error reading DCP: " + t.getMessage());
            return 2;
        }

        info("loaded " + dcpPath
              + "  cells="     + des.getCells().size()
              + "  nets="      + des.getNets().size()
              + "  siteinsts=" + des.getSiteInsts().size());

        checkCellsPlaced(des);
        checkNetsHaveSource(des);
        checkBufgDedicatedRoute(des);
        checkPrecyinitConsistent(des);
        checkCarry4OutputEgress(des);
        checkOrphanSitePinInsts(des);
        checkStaticNetReach(des);

        out.println();
        out.println("DRC summary: errors=" + errors + "  warnings=" + warnings);
        return errors == 0 ? 0 : 1;
    }

    /* ---------------- D1: placement coverage ---------------- */

    void checkCellsPlaced(Design des) {
        for (Cell c : des.getCells()) {
            if (c.getSiteInst() != null) continue;
            String t = c.getType();
            if (t == null || t.startsWith("$")) continue;
            err("UNPLACED", "cell '" + c.getName() + "' (type " + t + ") not placed");
        }
    }

    /* ---------------- D2: every connected net has a source ---------------- */

    void checkNetsHaveSource(Design des) {
        for (Net n : des.getNets()) {
            if (n.isStaticNet())            continue;
            if (n.getSinkPins().isEmpty())  continue;
            if (n.getSource() != null)      continue;

            String driverDesc = "";
            try {
                EDIFHierNet ehn = des.getNetlist().getHierNetFromName(n.getName());
                if (ehn != null) {
                    for (EDIFPortInst pi : ehn.getNet().getSourcePortInsts(false)) {
                        driverDesc = "  logical driver: "
                                       + (pi.getCellInst() != null
                                            ? pi.getCellInst().getName() : "(top)")
                                       + "/" + pi.getName();
                        break;
                    }
                }
            } catch (Throwable t) { /* logical view may be incomplete; ignore */ }

            err("NO-SOURCE", "net '" + n.getName() + "' has "
                  + n.getSinkPins().size() + " sink(s) but no source SitePinInst."
                  + driverDesc);
        }
    }

    /* ---------------- D3: BUFG must use the dedicated clock backbone ---------------- */

    void checkBufgDedicatedRoute(Design des) {
        for (Cell c : des.getCells()) {
            String t = c.getType();
            if (!"BUFG".equals(t) && !"BUFGCTRL".equals(t)) continue;
            SiteInst si = c.getSiteInst();
            if (si == null) continue;

            SitePinInst i0 = si.getSitePinInst("I0");
            if (i0 == null) {
                warn("BUFG-NOSPI", "BUFG '" + c.getName() + "' at "
                       + si.getSiteName() + " has no I0 SitePinInst");
                continue;
            }
            Net n = i0.getNet();
            if (n == null) {
                err("BUFG-NONET", "BUFG '" + c.getName() + "' I0 is unattached");
                continue;
            }
            String pinTileWire = i0.getTile().getWireName(i0.getConnectedWireIndex());
            PIP incoming = null;
            for (PIP p : n.getPIPs()) {
                if (pinTileWire.equals(p.getEndWireName())) { incoming = p; break; }
            }
            if (incoming == null) {
                err("BUFG-UNROUTED", "BUFG '" + c.getName() + "' I0 has no PIP feeding "
                       + pinTileWire);
                continue;
            }
            String src = incoming.getStartWireName();
            boolean dedicated = src.contains("CK_MUXED") || src.contains("CK_BUFG_CASC");
            boolean fabric    = src.startsWith("CLK_BUFG_IMUX") || src.contains("GFAN");
            if (fabric || !dedicated) {
                err("BUFG-FABRIC", "BUFG '" + c.getName() + "' I0 routed via '"
                       + src + "' (expected CK_MUXED0 / CK_BUFG_CASC)");
            }
        }
    }

    /* ---------------- D4: CARRY4 PRECYINIT bound and consistent ---------------- */

    void checkPrecyinitConsistent(Design des) {
        for (Cell c : des.getCells()) {
            if (!"CARRY4".equals(c.getType())) continue;
            SiteInst si = c.getSiteInst();
            if (si == null) continue;

            SitePIP pip      = si.getUsedSitePIP("PRECYINIT");
            boolean rootByCI = isCarry4ChainRoot(c);

            if (pip == null) {
                err("PRECYINIT-UNBOUND", "CARRY4 '" + c.getName() + "' at "
                       + si.getSiteName() + " has unbound PRECYINIT (root wants C0/C1, "
                       + "follower wants CIN)");
                continue;
            }
            String sel = pip.getInputPin().getName();
            if (rootByCI && "CIN".equals(sel)) {
                err("PRECYINIT-ROOT-CIN", "CARRY4 '" + c.getName() + "' at "
                       + si.getSiteName() + " is chain root (CI=const) but PRECYINIT=CIN");
            } else if (!rootByCI && !"CIN".equals(sel)) {
                err("PRECYINIT-FOLLOWER", "CARRY4 '" + c.getName() + "' at "
                       + si.getSiteName() + " has cell-driven CI but PRECYINIT=" + sel
                       + " (expected CIN)");
            }
        }
    }

    boolean isCarry4ChainRoot(Cell c) {
        EDIFCellInst inst = c.getEDIFCellInst();
        if (inst == null) return true;
        EDIFPortInst ci = inst.getPortInst("CI");
        if (ci == null) return true;
        EDIFNet en = ci.getNet();
        if (en == null) return true;
        for (EDIFPortInst src : en.getSourcePortInsts(false)) {
            if (src.getCellInst() == null) continue;
            String dvType = src.getCellInst().getCellType().getName();
            if ("GND".equals(dvType) || "VCC".equals(dvType)) return true;
        }
        return false;
    }

    /* ---------------- D5: CARRY4 used output has an egress SitePinInst ---------------- */

    void checkCarry4OutputEgress(Design des) {
        for (Cell c : des.getCells()) {
            if (!"CARRY4".equals(c.getType())) continue;
            SiteInst si = c.getSiteInst();
            if (si == null) continue;
            EDIFCellInst inst = c.getEDIFCellInst();
            if (inst == null) continue;

            for (String base : new String[]{"O", "CO"}) {
                for (int i = 0; i < 4; i++) {
                    EDIFPortInst pi = inst.getPortInst(base + "[" + i + "]");
                    if (pi == null) continue;
                    EDIFNet en = pi.getNet();
                    if (en == null) continue;

                    boolean externalSink = false;
                    for (EDIFPortInst sp : en.getPortInsts()) {
                        if (sp == pi) continue;
                        if (sp.isInput()) { externalSink = true; break; }
                    }
                    if (!externalSink) continue;

                    Net pn = null;
                    try {
                        EDIFHierNet ehn = des.getNetlist().getHierNetFromName(en.getName());
                        if (ehn != null) pn = des.getNet(ehn.getHierarchicalNetName());
                    } catch (Throwable t) { /* fall through */ }
                    if (pn == null) pn = des.getNet(en.getName());

                    if (pn == null) {
                        err("C4-NO-PHYS-NET", "CARRY4 '" + c.getName() + "' "
                              + base + "[" + i + "] logical net '" + en.getName()
                              + "' has no physical Net");
                        continue;
                    }
                    if (pn.getSource() == null) {
                        err("C4-OUT-UNROUTED", "CARRY4 '" + c.getName() + "' "
                              + base + "[" + i + "] at " + si.getSiteName()
                              + " has sinks but no source SitePinInst (xOUTMUX/COUT unbound)");
                    }
                }
            }
        }
    }

    /* ---------------- D6: orphaned SitePinInsts ---------------- */

    void checkOrphanSitePinInsts(Design des) {
        for (SiteInst si : des.getSiteInsts()) {
            for (SitePinInst spi : si.getSitePinInsts()) {
                if (spi.getNet() == null) {
                    err("SPI-ORPHAN", "SitePinInst " + si.getSiteName() + "/"
                          + spi.getName() + " has no Net (left over from cell move?)");
                }
            }
        }
    }

    /* ---------------- D7: static-net sinks reached ---------------- */

    void checkStaticNetReach(Design des) {
        Net[] nets = new Net[] { des.getGndNet(), des.getVccNet() };
        for (Net n : nets) {
            if (n == null) continue;
            for (SitePinInst sink : n.getSinkPins()) {
                if (sink.isOutPin()) continue;
                String wire = sink.getTile() == null
                                ? null
                                : sink.getTile().getWireName(sink.getConnectedWireIndex());
                if (wire == null) continue;
                boolean reached = false;
                for (PIP p : n.getPIPs()) {
                    if (wire.equals(p.getEndWireName())) { reached = true; break; }
                }
                if (!reached) {
                    warn("STATIC-UNROUTED", "static net '" + n.getName() + "' sink "
                            + sink.getSiteInst().getSiteName() + "/" + sink.getName()
                            + " has no PIP terminating at " + wire
                            + " (may be SitePIP-tied)");
                }
            }
        }
    }
}
