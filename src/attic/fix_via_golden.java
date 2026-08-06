/* Apply Vivado-golden placement to the nextpnr-broken design, then
 * run our intra-site routing fixers, then dcp2fasm + load.
 *
 * Instead of inventing a placement heuristic (the 4-FFs-per-CARRY4
 * rule + slot-occupancy planning), use Vivado's known-good placement
 * decisions as a lookup table.  For each cell name (normalised to
 * absorb the Vivado-vs-nextpnr naming differences like
 * "prescaler_reg[3]" vs "prescaler_reg_3_"), find Vivado's chosen
 * Site + BEL and move the nextpnr cell there.
 *
 * Then apply the same SitePinInst migration + RBEL SitePIP binding +
 * Router workflow as fix_carry4_ff_complete.java.  Finally dcp2fasm
 * and diff vs the golden's FASM to see what's still off.
 *
 * Usage: fix_via_golden <broken.dcp> <golden.dcp> <out.dcp>
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
import com.xilinx.rapidwright.router.Router;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class fix_via_golden {

    /** Normalise a cell name so Vivado's "prescaler_reg[3]_i_1" matches
     *  nextpnr's "prescaler_reg_3__i_1".  Strip all non-alphanumeric
     *  characters — both styles collapse to e.g. "prescalerreg3i1". */
    static String norm(String name) {
        if (name == null) return null;
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isLetterOrDigit(c)) sb.append(c);
        }
        return sb.toString();
    }

    public static void main(String[] args) {
        if (args.length < 3) {
            System.err.println("usage: fix_via_golden <broken.dcp> "
                + "<golden.dcp> <out.dcp>");
            System.exit(2);
        }
        Design broken = Design.readCheckpoint(args[0]);
        Design golden = Design.readCheckpoint(args[1]);

        // Build the placement oracle from golden: norm(cellName) -> {site, bel}.
        Map<String, String[]> oracle = new HashMap<>();
        for (Cell gc : golden.getCells()) {
            if (gc.getSiteInst() == null) continue;
            String k = norm(gc.getName());
            oracle.put(k, new String[]{
                gc.getSiteName(), gc.getBELName(), gc.getType()
            });
        }
        System.out.println("[oracle] " + oracle.size() + " golden cell placements");

        // For each broken cell, look up the oracle and relocate.
        int moved = 0, alreadyOk = 0, noMatch = 0, typeMismatch = 0,
            belOccupied = 0, placeFailed = 0;
        Set<String> claimedTargets = new HashSet<>();

        for (Cell c : broken.getCells()) {
            if (c.getSiteInst() == null) continue;
            String k = norm(c.getName());
            String[] target = oracle.get(k);
            if (target == null) { noMatch++; continue; }

            String targetSiteName = target[0];
            String targetBELName  = target[1];
            String goldenType     = target[2];

            // Don't try to retype cells; only relocate.
            if (!goldenType.equals(c.getType())) {
                typeMismatch++;
                continue;
            }
            if (targetSiteName.equals(c.getSiteName())
                && targetBELName.equals(c.getBELName())) {
                alreadyOk++;
                continue;
            }
            String claim = targetSiteName + "/" + targetBELName;
            if (claimedTargets.contains(claim)) {
                belOccupied++;
                continue;
            }

            Site newSite = broken.getDevice().getSite(targetSiteName);
            if (newSite == null) { noMatch++; continue; }
            BEL newBEL = newSite.getBEL(targetBELName);
            if (newBEL == null) { noMatch++; continue; }

            SiteInst newSi = broken.getSiteInstFromSiteName(targetSiteName);
            if (newSi != null && newSi.getCell(targetBELName) != null
                    && newSi.getCell(targetBELName) != c) {
                belOccupied++;
                continue;
            }

            // Use fullyUnplaceCell when possible (correctly migrates
            // SitePinInsts).  RapidWright has a known NPE on some
            // cell shapes ("a6Spi is null"); fall back to plain
            // unplace() so the run completes.
            try {
                Map<Net, Set<SitePinInst>> captured = new HashMap<>();
                DesignTools.fullyUnplaceCell(c, captured);
            } catch (Throwable t) {
                c.unplace();
            }
            if (!broken.placeCell(c, newSite, newBEL)) {
                placeFailed++;
                continue;
            }
            claimedTargets.add(claim);
            moved++;
        }
        System.out.printf("[apply] moved=%d, already_ok=%d, no_match=%d, "
            + "type_mismatch=%d, bel_occupied=%d, place_failed=%d%n",
            moved, alreadyOk, noMatch, typeMismatch, belOccupied, placeFailed);

        // Hand off to Router to redo tile-level routing.
        System.out.println("[Router.routeDesign()] ...");
        try {
            new Router(broken).routeDesign();
            System.out.println("[Router] complete");
        } catch (Throwable t) {
            System.out.println("[Router] failed: " + t);
        }

        System.out.println("[fix_via_golden] writing " + args[2]);
        broken.writeCheckpoint(args[2]);
    }
}
