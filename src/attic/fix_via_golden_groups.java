/* Group-based golden-placement applicator.
 *
 * Refined version of fix_via_golden.java that addresses the "broken
 * intra-site routing" failure mode by treating each destination SLICE
 * as an atomic unit:
 *
 *   1. Group broken-design cells by the SiteInst they should land in
 *      according to the golden DCP (cell-name-normalised matching).
 *   2. Move all cells in a group to the destination SiteInst at their
 *      golden BELs.
 *   3. Copy every SitePIP from golden's SiteInst-at-that-site into the
 *      new SiteInst in our design — this lifts Vivado's already-proven
 *      intra-site routing (CLKINV/CEUSEDMUX/SRUSEDMUX/FFMUX) wholesale
 *      instead of trying to reinvent it cell-by-cell.
 *   4. Leave unmatched cells (nextpnr's $intcell$ / $LUT$ wrappers) at
 *      their original nextpnr positions, untouched.
 *   5. Router.routeDesign() for tile-level routing through INT fabric.
 *
 * Usage: fix_via_golden_groups <broken.dcp> <golden.dcp> <out.dcp>
 */
package dev.fpga.rapidwright;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.DesignTools;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.BEL;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.SitePIP;
import com.xilinx.rapidwright.router.Router;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class fix_via_golden_groups {

    static String norm(String name) {
        if (name == null) return null;
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isLetterOrDigit(c)) sb.append(c);
        }
        return sb.toString();
    }

    /** Holds the planned move for one cell. */
    static class Move {
        Cell brokenCell;
        Site destSite;
        BEL  destBEL;
        Move(Cell c, Site s, BEL b) { brokenCell = c; destSite = s; destBEL = b; }
    }

    public static void main(String[] args) {
        if (args.length < 3) {
            System.err.println("usage: fix_via_golden_groups "
                + "<broken.dcp> <golden.dcp> <out.dcp>");
            System.exit(2);
        }
        Design broken = Design.readCheckpoint(args[0]);
        Design golden = Design.readCheckpoint(args[1]);

        // 1. Oracle: norm(name) -> golden-side {Cell, siteName, BELName}.
        Map<String, String[]> oracle = new HashMap<>();
        Map<String, Cell> goldenByNorm = new HashMap<>();
        for (Cell gc : golden.getCells()) {
            if (gc.getSiteInst() == null) continue;
            String k = norm(gc.getName());
            oracle.put(k, new String[]{
                gc.getSiteName(), gc.getBELName(), gc.getType()
            });
            goldenByNorm.put(k, gc);
        }
        System.out.println("[oracle] " + oracle.size() + " golden cell placements");

        // 2. Plan moves and group by destination site.
        Map<String, List<Move>> groupsByDestSite = new HashMap<>();
        int planned = 0, alreadyOk = 0, noMatch = 0, typeMismatch = 0;
        for (Cell c : broken.getCells()) {
            if (c.getSiteInst() == null) continue;
            String k = norm(c.getName());
            String[] target = oracle.get(k);
            if (target == null) { noMatch++; continue; }
            String targetSiteName = target[0];
            String targetBELName  = target[1];
            String goldenType     = target[2];
            if (!goldenType.equals(c.getType())) { typeMismatch++; continue; }
            if (targetSiteName.equals(c.getSiteName())
                && targetBELName.equals(c.getBELName())) {
                alreadyOk++;
                continue;
            }
            Site dest = broken.getDevice().getSite(targetSiteName);
            if (dest == null) continue;
            BEL destBEL = dest.getBEL(targetBELName);
            if (destBEL == null) continue;
            groupsByDestSite.computeIfAbsent(targetSiteName, x -> new ArrayList<>())
                .add(new Move(c, dest, destBEL));
            planned++;
        }
        System.out.printf("[plan] %d cells in %d destination SLICEs (already_ok=%d, "
            + "no_match=%d, type_mismatch=%d)%n",
            planned, groupsByDestSite.size(), alreadyOk, noMatch, typeMismatch);

        // 3. For each destination SLICE: unplace all in the group, then
        //    place them all at the golden BELs.  Then copy golden's
        //    SitePIPs at that site into our new SiteInst.
        int moved = 0, placeFailed = 0, sitepipsCopied = 0;
        for (Map.Entry<String, List<Move>> e : groupsByDestSite.entrySet()) {
            String destSiteName = e.getKey();
            List<Move> group = e.getValue();

            // Unplace.
            for (Move m : group) {
                try {
                    Map<Net, Set<SitePinInst>> cap = new HashMap<>();
                    DesignTools.fullyUnplaceCell(m.brokenCell, cap);
                } catch (Throwable t) {
                    m.brokenCell.unplace();
                }
            }
            // Place.
            for (Move m : group) {
                if (!broken.placeCell(m.brokenCell, m.destSite, m.destBEL)) {
                    placeFailed++;
                } else {
                    moved++;
                }
            }

            // Copy golden SitePIPs.
            SiteInst goldSi = golden.getSiteInstFromSiteName(destSiteName);
            SiteInst ourSi  = broken.getSiteInstFromSiteName(destSiteName);
            if (goldSi == null || ourSi == null) continue;
            for (SitePIP sp : goldSi.getSitePIPs()) {
                // SitePIP objects belong to the device, not the design,
                // so they can be added to either SiteInst directly.
                ourSi.addSitePIP(sp);
                sitepipsCopied++;
            }
        }
        System.out.printf("[apply] moved=%d, place_failed=%d, sitepips_copied=%d%n",
            moved, placeFailed, sitepipsCopied);

        // 4. Router.
        System.out.println("[Router.routeDesign()] ...");
        try {
            new Router(broken).routeDesign();
            System.out.println("[Router] complete");
        } catch (Throwable t) {
            System.out.println("[Router] failed: " + t);
        }

        System.out.println("[fix_via_golden_groups] writing " + args[2]);
        broken.writeCheckpoint(args[2]);
    }
}
