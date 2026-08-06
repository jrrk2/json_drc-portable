package dev.fpga.rapidwright;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.Cell;

public class probe_site {
    public static void main(String[] args) throws Exception {
        Design des = Design.readCheckpoint(args[0]);
        for (int i = 2; i < args.length; i++) {
            SiteInst si = des.getSiteInstFromSiteName(args[i]);
            if (si == null) { System.out.println(args[i] + ": no SiteInst"); continue; }
            System.out.println("== " + args[i] + " tile=" + si.getTile().getName());
            for (Cell c : si.getCells()) {
                System.out.println("  cell " + c.getBELName() + " type=" + c.getType()
                    + " rt=" + c.isRoutethru() + " p2l=" + c.getPinMappingsP2L());
            }
            for (com.xilinx.rapidwright.device.SitePIP p : si.getUsedSitePIPs()) {
                System.out.println("  sitepip " + p.getBELName() + "." + p.getInputPinName());
            }
            System.out.println("  vccwires=" + si.getSiteWiresFromNet(des.getVccNet()));
            System.out.println("  gndwires=" + si.getSiteWiresFromNet(des.getGndNet()));
            for (com.xilinx.rapidwright.design.SitePinInst spi : si.getSitePinInsts()) {
                System.out.println("  sitepin " + spi.getName() + " net=" +
                    (spi.getNet() == null ? "null" : spi.getNet().getName()));
            }
        }
    }
}
