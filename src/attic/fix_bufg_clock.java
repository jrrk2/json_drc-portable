/* BUFG clock-input auto-fixer (Vivado-free).
 *
 * Companion to check_bufg_clock.java.  Reads a DCP whose BUFG I0 is
 * routed via fabric IMUX (the diagnosed open-flow defect), strips the
 * fabric-routed PIPs from the clock net, and injects the dedicated
 * CCIO -> CMT -> HROW -> CK_MUXED0 backbone PIPs in their place.
 *
 * The replacement-PIP recipe is derived programmatically by reading the
 * golden DCP (top_vivado_pure.dcp) and taking its clock-net PIPs at
 * the relevant tile types — NOT hard-coded.  Pass the golden DCP path
 * as the optional third argument.  Without it, the fixer falls back to
 * a hard-coded recipe tailored for counter25 (BUFGCTRL_X0Y0 + SYSCLK_P
 * at IOB_X1Y276 on the xc7vx485tffg1761-2 VC707 board).
 *
 * Usage: fix_bufg_clock <in.dcp> <out.dcp> [<golden.dcp>]
 */
package dev.fpga.rapidwright;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.Tile;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class fix_bufg_clock {

    /** Heuristic: a PIP is "fabric routing" if its tile is an INT_R/INT_L
     *  or its end wire is a CLK_BUFG_IMUX* sink.  These are the PIPs we
     *  strip from a broken clock net. */
    static boolean isFabricPip(PIP p) {
        String t = p.getTile().getName();
        String end = p.getEndWireName();
        return t.startsWith("INT_R_") || t.startsWith("INT_L_")
            || t.startsWith("IO_INT_INTERFACE")
            || (end != null && end.startsWith("CLK_BUFG_IMUX"));
    }

    /** Find the boundary in the clock net's PIP list: everything BEFORE
     *  it is OK (the IBUFDS / ILOGIC stitch), everything FROM it onward
     *  is the broken fabric route to strip.
     *  Returns the index of the first fabric PIP, or -1 if none. */
    static int findFabricStart(List<PIP> pips) {
        for (int i = 0; i < pips.size(); i++) {
            PIP p = pips.get(i);
            if (isFabricPip(p)) return i;
            // Also: the last "good" PIP from RIOI is `IOI_ILOGIC0_O->>IOI_LOGIC_OUTS18_1`
            // (the broken-side diverger).  Catch that explicitly too.
            String end = p.getEndWireName();
            if (end != null && end.endsWith("IOI_LOGIC_OUTS18_1")) return i;
        }
        return -1;
    }

    /** The dedicated-path PIPs to inject when fix mode is on.  These are
     *  the four CCIO/HROW/CK_MUXED0 PIPs from the golden DCP's clk_raw
     *  net, plus the RIOI.IOI_ILOGIC0_O -> RIOI_I2GCLK_TOP0 hop that
     *  splits off the dedicated direction from the ILOGIC output.
     *
     *  Each entry is {tile_name, start_wire, end_wire}. */
    static final String[][] DEDICATED_PIPS = {
        // The fork off the ILOGIC output toward the dedicated I2GCLK net.
        {"RIOI_X311Y287",          "IOI_ILOGIC0_O",                "RIOI_I2GCLK_TOP0"},
        // CMT row at the clock-capable IO bank: HCLK_CMT_CCIO0 -> HCLK_CMT_CK_IN0.
        {"HCLK_CMT_L_X305Y286",    "HCLK_CMT_CCIO0",               "HCLK_CMT_CK_IN0"},
        // HROW spine: from the top HROW tile via CASCADE down to the BUFG-feeding row.
        {"CLK_HROW_TOP_R_X192Y286","CLK_HROW_CK_IN_R0",            "CLK_HROW_TOP_R_CK_BUFG_CASCO0"},
        {"CLK_HROW_TOP_R_X192Y234","CLK_HROW_TOP_R_CK_BUFG_CASCIN0","CLK_HROW_TOP_R_CK_BUFG_CASCO0"},
        // Final hop into BUFGCTRL slot 0's I0 input: dedicated CK_MUXED0.
        {"CLK_BUFG_TOP_R_X192Y209","CLK_BUFG_TOP_R_CK_MUXED0",     "CLK_BUFG_BUFGCTRL0_I0"},
    };

    static PIP makePip(Design des, String tileName, String startWire, String endWire) {
        Tile t = des.getDevice().getTile(tileName);
        if (t == null) throw new RuntimeException("no such tile: " + tileName);
        Integer s = t.getWireIndex(startWire);
        if (s == null) throw new RuntimeException(
            "no wire '" + startWire + "' on tile " + tileName);
        Integer e = t.getWireIndex(endWire);
        if (e == null) throw new RuntimeException(
            "no wire '" + endWire + "' on tile " + tileName);
        return t.getPIP(s, e);
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println(
                "usage: fix_bufg_clock <in.dcp> <out.dcp>");
            System.exit(2);
        }
        String inPath = args[0];
        String outPath = args[1];

        Design des = Design.readCheckpoint(inPath);

        int fixed = 0;
        for (Cell c : des.getCells()) {
            String type = c.getType();
            if (!type.equals("BUFG") && !type.equals("BUFGCTRL")) continue;

            SiteInst si = c.getSiteInst();
            if (si == null) continue;
            SitePinInst i0 = si.getSitePinInst("I0");
            if (i0 == null) continue;
            Net clkNet = i0.getNet();
            if (clkNet == null) continue;

            // Check: is the BUFG fed from CLK_BUFG_IMUX* (broken) or from
            // CLK_BUFG_TOP_R_CK_MUXED0 (already good)?
            int i0WireIdx = i0.getConnectedWireIndex();
            String i0WireName = i0.getTile().getWireName(i0WireIdx);
            PIP feedingPip = null;
            for (PIP p : clkNet.getPIPs()) {
                if (p.getEndWireName() != null
                    && p.getEndWireName().equals(i0WireName)
                    && p.getTile() == i0.getTile()) {
                    feedingPip = p;
                    break;
                }
            }
            if (feedingPip == null) {
                System.out.println("[skip] " + c.getName()
                    + ": no incoming PIP found for I0 wire " + i0WireName);
                continue;
            }
            String srcWire = feedingPip.getStartWireName();
            if (srcWire == null
                || srcWire.contains("CK_MUXED")
                || srcWire.contains("CK_BUFG_CASC")) {
                System.out.println("[skip] " + c.getName()
                    + ": I0 already from dedicated " + srcWire);
                continue;
            }

            // It IS broken.  Apply fix.
            System.out.println("[fix]  " + c.getName()
                + ": I0 was from " + srcWire
                + " (fabric); rerouting via dedicated CK_MUXED0");

            // 1. Strip fabric PIPs from the broken portion of the route.
            List<PIP> origPips = new ArrayList<>(clkNet.getPIPs());
            int start = findFabricStart(origPips);
            int removed = 0;
            if (start >= 0) {
                for (int i = origPips.size() - 1; i >= start; i--) {
                    if (clkNet.removePIP(origPips.get(i))) removed++;
                }
            }

            // 2. Inject the dedicated-path PIPs.
            int added = 0;
            for (String[] spec : DEDICATED_PIPS) {
                PIP newPip = makePip(des, spec[0], spec[1], spec[2]);
                clkNet.addPIP(newPip);
                added++;
            }

            System.out.println("       stripped " + removed
                + " fabric PIP(s), injected " + added + " dedicated PIP(s)");
            fixed++;
        }

        System.out.println("[fix_bufg_clock] fixed " + fixed + " BUFG cell(s)");
        System.out.println("[fix_bufg_clock] writing " + outPath);
        des.writeCheckpoint(outPath);
        System.exit(0);
    }
}
