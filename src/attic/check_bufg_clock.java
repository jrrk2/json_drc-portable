/* BUFG clock-input DRC detector (Vivado-free).
 *
 * Walks the given DCP, locates every BUFG/BUFGCTRL cell, examines the
 * PIP that drives the BUFGCTRL site's I0 input, and reports:
 *   GOOD  if I0 is sourced from the dedicated CK_MUXED0 backbone, or
 *   BROKEN if I0 is sourced from CLK_BUFG_IMUX* / general fabric.
 *
 * This is the first of the auto-correct detectors planned for task #94.
 * The same walk pattern (cell -> site pin -> incoming PIP -> source wire)
 * is the template for the other defect detectors:
 *   - SR-mux unrouteable on SLICEs
 *   - CARRY4 PRECYINIT blocked
 *   - GNDNet partial route
 *   - Missing XDC LOC on top-level ports
 *
 * Usage: check_bufg_clock <in.dcp>
 * Exit 0 = clean, 1 = at least one BROKEN, 2 = no BUFG cells found
 * (likely DCP parse problem worth flagging).
 */
package dev.fpga.rapidwright;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.PIP;

public class check_bufg_clock {

    /** Returns true if the PIP source wire is a fabric IMUX feeder rather than
     *  the dedicated CK_MUXED0 backbone. */
    static boolean isFabricRouted(String srcWire) {
        if (srcWire == null) return false;
        return srcWire.startsWith("CLK_BUFG_IMUX")
            || srcWire.contains("GFAN")
            || srcWire.startsWith("CLK_BUFG_R_BUFGCTRL"); // misc fabric-shape
    }

    /** Returns true if the PIP source wire is the dedicated clock backbone. */
    static boolean isDedicatedRouted(String srcWire) {
        if (srcWire == null) return false;
        return srcWire.contains("CK_MUXED")
            || srcWire.contains("CK_BUFG_CASC");
    }

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("usage: check_bufg_clock <in.dcp>");
            System.exit(2);
        }
        Design des = Design.readCheckpoint(args[0]);

        int bufgCount = 0, brokenCount = 0, cleanCount = 0;
        for (Cell c : des.getCells()) {
            String type = c.getType();
            // Both flavours.  Vivado-style "BUFG" gets packed to a BUFGCTRL
            // BEL by nextpnr; either way the BEL is the same physical site.
            if (!type.equals("BUFG") && !type.equals("BUFGCTRL")) continue;
            bufgCount++;

            SiteInst si = c.getSiteInst();
            if (si == null) {
                System.out.println("  [SKIP] " + c.getName()
                    + " is not placed at a site");
                continue;
            }

            // Find the SitePinInst driving I0 on this BUFGCTRL site.  For
            // BUFGCTRL_X0Y0 .. _X0Y31, the site-pin name is "I0".
            SitePinInst i0 = si.getSitePinInst("I0");
            if (i0 == null) {
                System.out.println("  [SKIP] " + c.getName()
                    + " has no I0 SitePinInst (clock not yet routed?)");
                continue;
            }

            // Walk the Net feeding I0 and find the PIP whose end-wire is I0.
            // RapidWright models the I0 pin as a wire on the tile; the
            // incoming PIP's destination matches it.
            Net clkNet = i0.getNet();
            if (clkNet == null) {
                System.out.println("  [SKIP] " + c.getName()
                    + " I0 pin has no Net");
                continue;
            }

            // Resolve I0's tile-level wire name via its tile + connected wire index.
            String i0TileName = i0.getTile().getName();
            int i0WireIdx = i0.getConnectedWireIndex();
            String i0WireName = i0.getTile().getWireName(i0WireIdx);
            PIP feedingPip = null;
            for (PIP p : clkNet.getPIPs()) {
                if (!p.getTile().getName().equals(i0TileName)) continue;
                String endName = p.getEndWireName();
                if (endName.equals(i0WireName)
                    || endName.endsWith("." + i0WireName)
                    // PIP end wires on V7 sometimes carry a tile-relative
                    // prefix.  Match by site-pin substring as a fallback.
                    || endName.contains("BUFGCTRL")) {
                    feedingPip = p;
                    break;
                }
            }
            if (feedingPip == null) {
                System.out.println("  [WARN] " + c.getName()
                    + " I0 wire=" + i0WireName + " tile=" + i0TileName
                    + " — no incoming PIP found; net has "
                    + clkNet.getPIPs().size() + " PIPs total");
                continue;
            }

            String srcWire = feedingPip.getStartWireName();
            String pipStr = feedingPip.toString();

            boolean broken = isFabricRouted(srcWire);
            boolean dedicated = isDedicatedRouted(srcWire);
            String verdict;
            if (dedicated)        verdict = "GOOD   ";
            else if (broken)      verdict = "BROKEN ";
            else                  verdict = "UNKNOWN";

            System.out.printf("  [%s] %s (site %s)  I0 <- %s   (PIP: %s)%n",
                verdict, c.getName(), si.getSiteName(), srcWire, pipStr);

            if (broken) brokenCount++;
            else if (dedicated) cleanCount++;
        }

        System.out.printf("%n[check_bufg_clock] %d BUFG cell(s) examined: "
            + "%d good / %d broken / %d skipped-or-unknown%n",
            bufgCount, cleanCount, brokenCount,
            bufgCount - cleanCount - brokenCount);

        if (bufgCount == 0) System.exit(2);
        System.exit(brokenCount == 0 ? 0 : 1);
    }
}
