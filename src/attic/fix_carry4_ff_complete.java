/* CARRY4 sum-output FF placement + intra-site SitePIP + SitePinInst
 * migration + Router-driven re-routing fixer (Vivado-free).
 *
 * The earlier attempt (#94 step 2) called cell.unplace() + des.placeCell()
 * which moves the binding but leaves SitePinInsts on the OLD SiteInst.
 * Router then doesn't see anything to do because the Nets still appear
 * terminated at the (now-orphaned) old location.
 *
 * This version uses the correct API:
 *   1. DesignTools.fullyUnplaceCell(ff, capturedMap) — capture the
 *      old SitePinInsts into a map AND remove them from their Nets so
 *      the Nets become "missing a sink" again.
 *   2. des.placeCell(ff, newSite, newBEL) — bind to new SLICE/BEL.
 *   3. For each captured (Net, pinInst) pair, call Net.connect(cell,
 *      logicalPin) which resolves the new SiteInst's site-pin name
 *      automatically and attaches a fresh SitePinInst there.
 *   4. injectFFMUXSitePIPs(des) — set up the intra-site CARRY4_XOR
 *      paths for the relocated FFs (so step 3 can call
 *      getCorrespondingSitePinName successfully — chicken+egg note).
 *   5. Router.routeDesign() — Router now sees the Nets as having
 *      pending sinks at the new SitePinInsts and builds tile-level
 *      routes.
 *
 * Usage: fix_carry4_ff_complete <in.dcp> <out.dcp>
 */
package dev.fpga.rapidwright;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.DesignTools;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.BEL;
import com.xilinx.rapidwright.device.BELClass;
import com.xilinx.rapidwright.device.BELPin;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.SitePIP;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFPortInst;
import com.xilinx.rapidwright.router.Router;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class fix_carry4_ff_complete {

    static final String[] FF_BEL_AT_SLOT = {"AFF", "BFF", "CFF", "DFF"};
    static final char[]   SLOT_LETTER     = {'A', 'B', 'C', 'D'};

    /** Logical pins on a FDRE / FDCE / FDSE / FDPE cell whose physical
     *  SitePinInsts we need to migrate when the cell moves SLICE. */
    static final String[] FF_LOGICAL_PINS = {"C", "CE", "D", "R", "S", "Q",
                                              "CLR", "PRE"};

    static boolean isFFType(String t) {
        return t.equals("FDRE") || t.equals("FDCE") || t.equals("FDSE") || t.equals("FDPE")
            || t.equals("FDRE_1") || t.equals("FDCE_1") || t.equals("FDSE_1") || t.equals("FDPE_1");
    }

    /** Pair holding a Cell to relocate + its captured SitePinInsts and
     *  the (logical pin -> Net) mapping we need to re-attach at the
     *  destination SLICE. */
    static class Move {
        Cell ff;
        Site targetSite;
        BEL  targetBEL;
        // Logical-pin-name -> Net.  Recorded from the Cell's EDIF
        // connections before unplace so we can re-attach after place.
        Map<String, Net> logicalPinNets = new HashMap<>();
    }

    static void plan(Design des, List<Move> moves) {
        int alreadyOk = 0, skipBelOccupied = 0, skipNoFf = 0;
        // Use a "claimed targets" set so the planning pass doesn't double-book a BEL.
        Set<String> claimedTargets = new HashSet<>();
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
                Cell ff = null;
                for (EDIFPortInst sinkPi : logNet.getPortInsts()) {
                    if (sinkPi == pi) continue;
                    if (sinkPi.getCellInst() == null) continue;
                    if (!isFFType(sinkPi.getCellInst().getCellType().getName())) continue;
                    if (!"D".equals(sinkPi.getName())) continue;
                    Cell maybeFf = des.getCell(sinkPi.getCellInst().getName());
                    if (maybeFf != null) { ff = maybeFf; break; }
                }
                if (ff == null) { skipNoFf++; continue; }
                if (ff.getSiteInst() == null) continue;

                String targetBELName = FF_BEL_AT_SLOT[i];
                BEL targetBEL = carrySite.getBEL(targetBELName);
                if (targetBEL == null) continue;

                String ffSite = ff.getSiteName();
                String ffBel = ff.getBELName();
                if (carrySiteName.equals(ffSite) && targetBELName.equals(ffBel)) {
                    alreadyOk++;
                    continue;
                }
                String claim = carrySiteName + "/" + targetBELName;
                if (claimedTargets.contains(claim)) {
                    skipBelOccupied++;
                    continue;
                }
                Cell occupant = csi.getCell(targetBELName);
                if (occupant != null && occupant != ff) {
                    skipBelOccupied++;
                    continue;
                }
                claimedTargets.add(claim);

                Move m = new Move();
                m.ff = ff;
                m.targetSite = carrySite;
                m.targetBEL = targetBEL;

                // Capture the (logicalPin -> Net) map BEFORE unplace, so
                // we know which logical pins to reconnect after place.
                EDIFCellInst ffEci = ff.getEDIFCellInst();
                if (ffEci != null) {
                    for (String lp : FF_LOGICAL_PINS) {
                        EDIFPortInst lpPi = ffEci.getPortInst(lp);
                        if (lpPi == null || lpPi.getNet() == null) continue;
                        Net physNet = des.getNet(lpPi.getNet().getName());
                        if (physNet == null) {
                            // For D specifically — handled by the
                            // CARRY4 sum-output route fixer, not by us.
                            if ("D".equals(lp)) continue;
                            // Create a placeholder if not present.
                            physNet = des.createNet(lpPi.getNet().getName());
                        }
                        m.logicalPinNets.put(lp, physNet);
                    }
                }
                moves.add(m);
            }
        }
        System.out.printf("[plan] %d moves; already_ok=%d, skip_bel_occupied=%d, "
            + "skip_no_ff=%d%n", moves.size(), alreadyOk, skipBelOccupied, skipNoFf);
    }

    /** Step 2 — the carry-sum SitePIPs. Must run AFTER moves and BEFORE
     *  trying Net.connect for the FF (which needs an intra-site path to
     *  resolve site pins via getCorrespondingSitePinName). */
    static void injectFFMUXSitePIPs(Design des) {
        int injected = 0, skipped = 0;
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
                Cell ff = null;
                for (EDIFPortInst sinkPi : logNet.getPortInsts()) {
                    if (sinkPi == pi) continue;
                    if (sinkPi.getCellInst() == null) continue;
                    if (!isFFType(sinkPi.getCellInst().getCellType().getName())) continue;
                    if (!"D".equals(sinkPi.getName())) continue;
                    Cell maybeFf = des.getCell(sinkPi.getCellInst().getName());
                    if (maybeFf != null) { ff = maybeFf; break; }
                }
                if (ff == null || ff.getSiteInst() == null) { skipped++; continue; }
                if (!ff.getSiteName().equals(carrySite)) { skipped++; continue; }
                if (!FF_BEL_AT_SLOT[i].equals(ff.getBELName())) { skipped++; continue; }

                BEL ffBel = ff.getBEL();
                BELPin ffDPin = ffBel.getPin("D");
                if (ffDPin == null) { skipped++; continue; }
                String fmuxOutWire = ffDPin.getSiteWireName();
                if (fmuxOutWire == null) { skipped++; continue; }
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
                            if (out != null && fmuxOutWire.equals(out.getSiteWireName())) {
                                bound = cand;
                                break;
                            }
                        }
                        if (bound != null) break;
                    }
                    if (bound != null) break;
                }
                if (bound == null) { skipped++; continue; }
                Net physNet = des.getNet(logNet.getName());
                if (physNet == null) physNet = des.createNet(logNet.getName());

                BELPin startPin = null;
                for (BEL other : csi.getSite().getBELs())
                    for (BELPin p : other.getPins())
                        if (p.isOutput()
                            && p.getSiteWireName() != null
                            && p.getSiteWireName().equals(bound.getInputPin().getSiteWireName()))
                            startPin = p;
                BELPin endPin = null;
                for (BEL other : csi.getSite().getBELs())
                    for (BELPin p : other.getPins())
                        if (p.isInput()
                            && p.getSiteWireName() != null
                            && p.getSiteWireName().equals(fmuxOutWire))
                            endPin = p;
                if (startPin != null && endPin != null)
                    csi.routeIntraSiteNet(physNet, startPin, endPin);
                csi.addSitePIP(bound);
                injected++;
            }
        }
        System.out.printf("[step 3: SitePIPs] injected=%d, skipped=%d%n",
            injected, skipped);
    }

    /** Bind a SitePIP on a named RBEL whose input pin name matches
     *  inputPinName, then route the intra-site path so getCorresponding-
     *  SitePinName can later resolve through it.  Returns true on
     *  success, false if no matching SitePIP exists in this site. */
    static boolean bindRbelSitePIP(SiteInst si, String rbelName,
                                   String inputPinName, Net netForRoute) {
        BEL rbel = si.getSite().getBEL(rbelName);
        if (rbel == null) return false;
        SitePIP chosen = null;
        for (BELPin bp : rbel.getPins()) {
            if (!bp.isInput()) continue;
            if (!bp.getName().equals(inputPinName)) continue;
            for (SitePIP cand : bp.getSitePIPs()) {
                if (cand.getInputPin() == bp) { chosen = cand; break; }
            }
            if (chosen != null) break;
        }
        if (chosen == null) return false;

        // Stitch: find an output pin on any BEL in the site whose sitewire
        // matches the SitePIP's input sitewire (the upstream source), and
        // an input pin whose sitewire matches the SitePIP's output (the
        // downstream sinks).  routeIntraSiteNet binds the path so future
        // getCorrespondingSitePinName calls can walk it.
        BELPin startPin = null;
        String inWire = chosen.getInputPin().getSiteWireName();
        for (BEL other : si.getSite().getBELs())
            for (BELPin p : other.getPins())
                if (p.isOutput() && inWire != null && inWire.equals(p.getSiteWireName()))
                    startPin = p;
        BELPin endPin = null;
        String outWire = chosen.getOutputPin().getSiteWireName();
        for (BEL other : si.getSite().getBELs())
            for (BELPin p : other.getPins())
                if (p.isInput() && outWire != null && outWire.equals(p.getSiteWireName()))
                    endPin = p;
        if (startPin != null && endPin != null && netForRoute != null)
            si.routeIntraSiteNet(netForRoute, startPin, endPin);
        si.addSitePIP(chosen);
        return true;
    }

    /** For each FF-bearing SiteInst that we relocated FFs into, configure
     *  CLKINV / CEUSEDMUX / SRUSEDMUX RBEL SitePIPs so that the clock,
     *  enable and set/reset inputs can reach the FF BEL pins.  Without
     *  this, getCorrespondingSitePinName returns null on CE/R/C and
     *  Net.connect fails. */
    static int bindFFRoutingMuxes(Design des, List<Move> moves) {
        int siteCount = 0, pipsAdded = 0;
        Set<String> done = new HashSet<>();
        for (Move m : moves) {
            Cell ff = m.ff;
            SiteInst si = ff.getSiteInst();
            if (si == null) continue;
            String siteName = si.getSite().getName();
            if (!done.add(siteName)) continue;
            siteCount++;

            // Decide which input each RBEL selects.  Read CE / R from the FF's
            // EDIF connections.
            EDIFCellInst eci = ff.getEDIFCellInst();
            EDIFPortInst cePi = eci == null ? null : eci.getPortInst("CE");
            EDIFPortInst rPi  = eci == null ? null : eci.getPortInst("R");
            if (rPi == null && eci != null) rPi = eci.getPortInst("S");
            if (rPi == null && eci != null) rPi = eci.getPortInst("CLR");
            if (rPi == null && eci != null) rPi = eci.getPortInst("PRE");

            String ceNetName = (cePi != null && cePi.getNet() != null)
                ? cePi.getNet().getName() : null;
            String rNetName  = (rPi  != null && rPi.getNet() != null)
                ?  rPi.getNet().getName() : null;

            // CLKINV: route SLICE.CLK -> CK_OUT_SW (non-inverted).
            Net clkNet = (m.logicalPinNets != null) ? m.logicalPinNets.get("C") : null;
            if (bindRbelSitePIP(si, "CLKINV", "CLK", clkNet)) pipsAdded++;

            // CEUSEDMUX: 1->OUT if CE is hard-wired high; otherwise IN->OUT.
            boolean ceHigh = "<const1>".equals(ceNetName);
            String ceInput = ceHigh ? "1" : "IN";
            Net ceNet = (m.logicalPinNets != null) ? m.logicalPinNets.get("CE") : null;
            if (bindRbelSitePIP(si, "CEUSEDMUX", ceInput, ceNet)) pipsAdded++;

            // SRUSEDMUX: 0->OUT if R/S is hard-wired low; otherwise IN->OUT.
            boolean rLow = "<const0>".equals(rNetName);
            String srInput = rLow ? "0" : "IN";
            Net rNet = (m.logicalPinNets != null) ? m.logicalPinNets.get("R") : null;
            if (rNet == null && m.logicalPinNets != null) rNet = m.logicalPinNets.get("S");
            if (bindRbelSitePIP(si, "SRUSEDMUX", srInput, rNet)) pipsAdded++;
        }
        System.out.printf("[step 3.5: FF routing muxes] sites=%d, pips_added=%d%n",
            siteCount, pipsAdded);
        return pipsAdded;
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("usage: fix_carry4_ff_complete <in.dcp> <out.dcp>");
            System.exit(2);
        }
        Design des = Design.readCheckpoint(args[0]);

        // 1. Plan.
        List<Move> moves = new ArrayList<>();
        plan(des, moves);

        // 2. Unplace (with SitePinInst capture) + place at new site.
        int relocated = 0;
        int placeFailed = 0;
        for (Move m : moves) {
            // Capture the cell's SitePinInsts BEFORE unplacing so we can
            // reattach matching SitePinInsts at the new site.
            // DesignTools.fullyUnplaceCell needs a Map<Net, Set<SitePinInst>>
            // to record what got removed.
            Map<Net, Set<SitePinInst>> captured = new HashMap<>();
            DesignTools.fullyUnplaceCell(m.ff, captured);
            if (!des.placeCell(m.ff, m.targetSite, m.targetBEL)) {
                placeFailed++;
                continue;
            }
            relocated++;
        }
        System.out.printf("[step 2: relocate] relocated=%d, place_failed=%d%n",
            relocated, placeFailed);

        // 3. Set up intra-site SitePIPs (FFMUX = CARRY4_XOR) — needed
        //    before Net.connect can resolve the FF.D site pin via
        //    getCorrespondingSitePinName.
        injectFFMUXSitePIPs(des);

        // 3.5. Bind CLKINV / CEUSEDMUX / SRUSEDMUX SitePIPs at every
        //      relocated FF's new SiteInst.  Same chicken-and-egg as
        //      step 3: Net.connect for C/CE/R fails until these RBELs
        //      have SitePIPs bound.
        bindFFRoutingMuxes(des, moves);

        // 4. Reattach SitePinInsts at the new sites for each captured
        //    (logical pin -> Net) pair.
        int attached = 0, skipNoMap = 0, attachErr = 0;
        for (Move m : moves) {
            Cell ff = m.ff;
            if (ff.getSiteInst() == null) { skipNoMap++; continue; }
            for (Map.Entry<String, Net> e : m.logicalPinNets.entrySet()) {
                String logicalPin = e.getKey();
                Net net = e.getValue();
                try {
                    SitePinInst spi = net.connect(ff, logicalPin);
                    if (spi != null) attached++;
                } catch (Throwable t) {
                    attachErr++;
                    // Continue — Router can sometimes recover.
                }
            }
        }
        System.out.printf("[step 4: reattach] attached=%d, errors=%d%n",
            attached, attachErr);

        // 5. Router.routeDesign() — completes tile-level routes to the
        //    new SitePinInsts.
        System.out.println("[step 5: Router.routeDesign()] ...");
        try {
            new Router(des).routeDesign();
            System.out.println("[step 5] complete");
        } catch (Throwable t) {
            System.out.println("[step 5] Router failed: " + t);
        }

        System.out.println("[fix_carry4_ff_complete] writing " + args[1]);
        des.writeCheckpoint(args[1]);
    }
}
