/* CARRY4 sum-output intra-site routing fixer (Vivado-free).
 *
 * Follows fix_carry4_ff_place.java in the auto-fix sequence.  Assumes
 * every CARRY4 sum-output's sink FF is now in the same SLICE at the
 * matching slot (i=0..3 -> AFF/BFF/CFF/DFF).  Adds:
 *   - the physical Net for the CARRY4.O[i] -> FF.D connection (if not
 *     already present, since nextpnr emitted no physical Net for these);
 *   - the intra-site SitePIP configuring slot-i FFMUX to source from
 *     CARRY4_XOR (the carry-sum path).
 *
 * Pattern copied from json2dcp.java's [carry-sum-output] block
 * (around line 1410) -- the same code that closes task #56 for
 * nextpnr-routed JSON.
 *
 * Usage: fix_carry4_ff_route <in.dcp> <out.dcp>
 */
package dev.fpga.rapidwright;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.device.BEL;
import com.xilinx.rapidwright.device.BELClass;
import com.xilinx.rapidwright.device.BELPin;
import com.xilinx.rapidwright.device.SitePIP;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFPortInst;

public class fix_carry4_ff_route {

    static final String[] FF_BEL_AT_SLOT = {"AFF", "BFF", "CFF", "DFF"};
    static final char[]   SLOT_LETTER     = {'A', 'B', 'C', 'D'};

    static boolean isFFType(String t) {
        return t.equals("FDRE") || t.equals("FDCE") || t.equals("FDSE") || t.equals("FDPE")
            || t.equals("FDRE_1") || t.equals("FDCE_1") || t.equals("FDSE_1") || t.equals("FDPE_1");
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("usage: fix_carry4_ff_route <in.dcp> <out.dcp>");
            System.exit(2);
        }
        Design des = Design.readCheckpoint(args[0]);

        int injected = 0, skipNoFF = 0, skipNotColo = 0, skipNoSlotMatch = 0,
            skipNoSitePIP = 0, skipNoStart = 0;

        for (Cell c : des.getCells()) {
            if (!c.getType().equals("CARRY4")) continue;
            SiteInst csi = c.getSiteInst();
            if (csi == null) continue;
            String carrySite = csi.getSite().getName();
            EDIFCellInst eci = c.getEDIFCellInst();
            if (eci == null) continue;

            for (int i = 0; i < 4; i++) {
                EDIFPortInst pi = eci.getPortInst("O[" + i + "]");
                if (pi == null || pi.getNet() == null) continue;
                EDIFNet logNet = pi.getNet();

                // Sink FF (D pin only).
                Cell ff = null;
                for (EDIFPortInst sinkPi : logNet.getPortInsts()) {
                    if (sinkPi == pi) continue;
                    if (sinkPi.getCellInst() == null) continue;
                    if (!isFFType(sinkPi.getCellInst().getCellType().getName())) continue;
                    if (!"D".equals(sinkPi.getName())) continue;
                    Cell maybeFf = des.getCell(sinkPi.getCellInst().getName());
                    if (maybeFf != null) { ff = maybeFf; break; }
                }
                if (ff == null) { skipNoFF++; continue; }
                if (ff.getSiteInst() == null) { skipNotColo++; continue; }

                // Same SLICE + correct slot?
                boolean sameSite = ff.getSiteName().equals(carrySite);
                boolean rightBel = FF_BEL_AT_SLOT[i].equals(ff.getBELName());
                if (!sameSite || !rightBel) {
                    skipNotColo++;
                    continue;
                }

                // Locate the FFMUX SitePIP whose output is the FF.D
                // sitewire AND whose CARRY4_XOR input pin selects the
                // carry-sum path.
                BEL ffBel = ff.getBEL();
                BELPin ffDPin = ffBel.getPin("D");
                if (ffDPin == null) { skipNoSlotMatch++; continue; }
                String fmuxOutWire = ffDPin.getSiteWireName();
                if (fmuxOutWire == null) { skipNoSlotMatch++; continue; }
                char slotLetter = SLOT_LETTER[i];

                SitePIP bound = null;
                for (BEL bel : csi.getSite().getBELs()) {
                    if (bel.getBELClass() != BELClass.RBEL) continue;
                    if (bel.getName().charAt(0) != slotLetter) continue;
                    for (BELPin bp : bel.getPins()) {
                        if (!bp.isInput()) continue;
                        if (!"CARRY4_XOR".equals(bp.getName())) continue;
                        for (SitePIP cand : bp.getSitePIPs()) {
                            BELPin out = cand.getOutputPin();
                            if (out == null) continue;
                            String oswn = out.getSiteWireName();
                            if (oswn != null && oswn.equals(fmuxOutWire)) {
                                bound = cand;
                                break;
                            }
                        }
                        if (bound != null) break;
                    }
                    if (bound != null) break;
                }
                if (bound == null) {
                    System.out.printf("  [skip] %s.O[%d]/slot %c: no CARRY4_XOR SitePIP "
                        + "for D sitewire %s%n",
                        c.getName(), i, slotLetter, fmuxOutWire);
                    skipNoSitePIP++;
                    continue;
                }

                // Get/create physical Net.  RapidWright's Net has no
                // setLogicalNet(EDIFNet) — only setLogicalHierNet.  For
                // intra-site routing we don't strictly need the back-link,
                // since the net is created by name only.  Skip linking.
                Net physNet = des.getNet(logNet.getName());
                if (physNet == null) {
                    physNet = des.createNet(logNet.getName());
                }

                // Stitch intra-site BELs end-to-end via the SitePIP.
                BELPin startPin = null;
                for (BEL other : csi.getSite().getBELs())
                    for (BELPin p : other.getPins())
                        if (p.isOutput()) {
                            String swn = p.getSiteWireName();
                            if (swn != null
                                && swn.equals(bound.getInputPin().getSiteWireName()))
                                startPin = p;
                        }
                if (startPin == null) { skipNoStart++; continue; }
                BELPin endPin = null;
                for (BEL other : csi.getSite().getBELs())
                    for (BELPin p : other.getPins())
                        if (p.isInput()) {
                            String swn = p.getSiteWireName();
                            if (swn != null && swn.equals(fmuxOutWire))
                                endPin = p;
                        }
                if (endPin != null) {
                    csi.routeIntraSiteNet(physNet, startPin, endPin);
                }
                csi.addSitePIP(bound);
                injected++;
                System.out.printf("  [inject] %s.O[%d] -> %s.D @ %s  via SitePIP %s%n",
                    c.getName(), i, ff.getName(), carrySite, bound);
            }
        }

        System.out.printf("%n[fix_carry4_ff_route] injected=%d, skip_no_ff=%d, "
            + "skip_not_colocated=%d, skip_no_slot_match=%d, "
            + "skip_no_sitepip=%d, skip_no_start=%d%n",
            injected, skipNoFF, skipNotColo, skipNoSlotMatch, skipNoSitePIP, skipNoStart);

        System.out.println("[fix_carry4_ff_route] writing " + args[1]);
        des.writeCheckpoint(args[1]);
    }
}
