/* Print every FF_ROUTETHRU cell and the relevant site PIPs / cell
 * params, so we can see what Vivado actually configures for a CARRY4
 * dual-output slot.  Targeted at the carry_test golden DCP. */
package dev.fpga.rapidwright;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.device.BEL;
import com.xilinx.rapidwright.device.BELPin;
import com.xilinx.rapidwright.device.SitePIP;

public class inspect_carry_routethru {
    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("usage: inspect_carry_routethru <in.dcp>");
            System.exit(2);
        }
        Design des = Design.readCheckpoint(args[0]);
        System.out.println("=== SiteInsts ===");
        for (SiteInst si : des.getSiteInsts()) {
            System.out.printf("SI %s type=%s%n", si.getSiteName(), si.getSiteTypeEnum());
            for (Cell c : si.getCells()) {
                System.out.printf("    cell %-22s type=%-12s bel=%-8s rt=%b%n",
                                  c.getName(), c.getType(), c.getBELName(),
                                  c.isRoutethru());
            }
            // Site PIPs in use
            for (BEL bel : si.getBELs()) {
                SitePIP pip = si.getUsedSitePIP(bel.getName());
                if (pip == null) continue;
                System.out.printf("    sitePIP %s.%s -> %s%n",
                                  pip.getBELName(),
                                  pip.getInputPin().getName(),
                                  pip.getOutputPin().getName());
            }
        }
        System.out.println();
        System.out.println("=== Design.getCells() ===");
        for (Cell c : des.getCells()) {
            String t = c.getType();
            if (!t.equals("FDRE") && !t.equals("FDCE") && !t.equals("FDPE")
                && !t.equals("FDSE") && !t.equals("LDCE") && !t.equals("LDPE")
                && !t.equals("LATCH") && !t.equals("CARRY4")) continue;
            SiteInst si = c.getSiteInst();
            String belName = c.getBELName();
            boolean routeThru = c.isRoutethru();
            System.out.printf("CELL %-22s type=%-8s rt=%b site=%s/%s%n",
                              c.getName(), t, routeThru,
                              si != null ? si.getSiteName() : "(null)",
                              belName);
            if (si != null && c.getBEL() != null) {
                for (BELPin pin : c.getBEL().getPins()) {
                    SitePIP pip = si.getUsedSitePIP(pin);
                    if (pip != null) {
                        System.out.printf("    pin %-8s -> SitePIP %s.%s%n",
                                          pin.getName(),
                                          pip.getBELName(),
                                          pip.getInputPin().getName());
                    }
                }
            }
        }
    }
}
