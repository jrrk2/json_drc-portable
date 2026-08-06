package dev.fpga.rapidwright;
import com.xilinx.rapidwright.design.*;
import com.xilinx.rapidwright.device.*;
public class SP {
  public static void main(String[] a) throws Exception {
    Design d = Design.readCheckpoint(a[0]);
    SiteInst si = d.getSiteInstFromSiteName("SLICE_X55Y100");
    System.out.println("BMUX sitepin: "+si.getSitePinInst("BMUX"));
    System.out.println("CMUX sitepin: "+si.getSitePinInst("CMUX"));
    System.out.println("BOUTMUX usedSitePIP: "+si.getUsedSitePIP("BOUTMUX"));
    Cell b6=si.getCell("B6LUT");
    System.out.println("B6LUT: "+b6+" type="+(b6==null?"-":b6.getType()));
    System.out.println("all OUTMUX usedSitePIPs:");
    for (SitePIP sp : si.getUsedSitePIPs()) if (sp.getBELName().contains("OUTMUX")) System.out.println("  "+sp.getBELName()+" in="+sp.getInputPinName());
    // which net is on the BMUX sitewire?
    System.out.println("net on BMUX sitewire: "+si.getNetFromSiteWire("BMUX"));
    System.out.println("net on BOUTMUX_OUT sitewire: "+si.getNetFromSiteWire("BOUTMUX_OUT"));
  }
}
