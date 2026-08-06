/* CARRY4 sum-output FF placement-relocation fixer (Vivado-free).
 *
 * Diagnosis: nextpnr's packer leaves 20 of 25 CARRY4 sum-output FFs at
 * SLICEs different from their driving CARRY4 — the FFMUX -> FF.D path
 * is intra-site only, so cross-SLICE placement makes the connection
 * unrouteable without crossing the INT_R/INT_L fabric.  Even Vivado
 * route_design can't fix this because it's a placement defect, not a
 * routing one.
 *
 * Fix: walk every CARRY4, find each O[i]'s sink FF, and if the FF is in
 * a different SLICE, relocate it into the CARRY4's SLICE at slot
 * {A,B,C,D}FF (i = 0,1,2,3).  Skip cases where the target BEL is
 * already occupied by some other cell — we never displace, only fill
 * empty slots.
 *
 * Intra-site routing (FFMUX = XORCY) is NOT set up in this fixer — that
 * comes in a follow-up pass (fix_carry4_ff_route.java).  The point of
 * keeping placement and routing as separate fixers is so the
 * relocation result can be inspected (via dcp2fasm) before we commit
 * to the routing change.
 *
 * Usage: fix_carry4_ff_place <in.dcp> <out.dcp>
 */
package dev.fpga.rapidwright;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.device.BEL;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFPortInst;

public class fix_carry4_ff_place {

    /** FF BEL name for slot i (0..3 -> AFF, BFF, CFF, DFF) — the BEL_FF
     *  slot that the carry-sum path lands on (NOT BEL_FF2 / A5FF). */
    static final String[] FF_BEL_AT_SLOT = {"AFF", "BFF", "CFF", "DFF"};

    static boolean isFFType(String t) {
        return t.equals("FDRE") || t.equals("FDCE") || t.equals("FDSE") || t.equals("FDPE")
            || t.equals("FDRE_1") || t.equals("FDCE_1") || t.equals("FDSE_1") || t.equals("FDPE_1");
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("usage: fix_carry4_ff_place <in.dcp> <out.dcp>");
            System.exit(2);
        }
        Design des = Design.readCheckpoint(args[0]);

        int moved = 0, alreadyOk = 0, skipBelOccupied = 0, skipNoFf = 0, skipUnplaced = 0;

        for (Cell c : des.getCells()) {
            if (!c.getType().equals("CARRY4")) continue;
            SiteInst csi = c.getSiteInst();
            if (csi == null) continue;
            Site carrySite = csi.getSite();
            String carrySiteName = carrySite.getName();

            EDIFCellInst eci = c.getEDIFCellInst();
            if (eci == null) continue;

            for (int i = 0; i < 4; i++) {
                EDIFPortInst pi = eci.getPortInst("O[" + i + "]");
                if (pi == null || pi.getNet() == null) continue;
                EDIFNet logNet = pi.getNet();

                // Find the unique FF.D sink in this net.
                Cell ff = null;
                EDIFPortInst ffSinkPi = null;
                for (EDIFPortInst sinkPi : logNet.getPortInsts()) {
                    if (sinkPi == pi) continue;
                    if (sinkPi.getCellInst() == null) continue;
                    EDIFCellInst sinkInst = sinkPi.getCellInst();
                    if (!isFFType(sinkInst.getCellType().getName())) continue;
                    if (!"D".equals(sinkPi.getName())) continue;
                    Cell maybeFf = des.getCell(sinkInst.getName());
                    if (maybeFf == null) continue;
                    ff = maybeFf;
                    ffSinkPi = sinkPi;
                    break; // first FF-D sink wins
                }
                if (ff == null) { skipNoFf++; continue; }
                if (ff.getSiteInst() == null) { skipUnplaced++; continue; }

                String targetBELName = FF_BEL_AT_SLOT[i];
                BEL targetBEL = carrySite.getBEL(targetBELName);
                if (targetBEL == null) {
                    System.out.printf("  [WARN] %s missing BEL %s%n",
                        carrySiteName, targetBELName);
                    continue;
                }

                String ffSite = ff.getSiteName();
                String ffBel = ff.getBELName();
                if (carrySiteName.equals(ffSite) && targetBELName.equals(ffBel)) {
                    alreadyOk++;
                    continue;
                }

                // Check target BEL is empty in the CARRY4's SiteInst.
                Cell occupant = csi.getCell(targetBELName);
                if (occupant != null && occupant != ff) {
                    System.out.printf("  [skip] %s slot %d -> %s/%s: occupied by %s%n",
                        c.getName(), i, carrySiteName, targetBELName, occupant.getName());
                    skipBelOccupied++;
                    continue;
                }

                // Unplace + replace.
                System.out.printf("  [move] %s.O[%d] sink %s: %s/%s -> %s/%s%n",
                    c.getName(), i, ff.getName(),
                    ffSite, ffBel,
                    carrySiteName, targetBELName);
                ff.unplace();
                boolean ok = des.placeCell(ff, carrySite, targetBEL);
                if (!ok) {
                    System.out.printf("    placeCell failed; cell now unplaced%n");
                    continue;
                }
                moved++;
            }
        }

        System.out.printf("%n[fix_carry4_ff_place] moved=%d, already_ok=%d, "
            + "skip_bel_occupied=%d, skip_no_ff=%d, skip_unplaced=%d%n",
            moved, alreadyOk, skipBelOccupied, skipNoFf, skipUnplaced);

        System.out.println("[fix_carry4_ff_place] writing " + args[1]);
        des.writeCheckpoint(args[1]);
    }
}
