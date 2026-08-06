package dev.fpga.rapidwright;
import com.xilinx.rapidwright.design.*;
import com.xilinx.rapidwright.device.*;
import java.util.*;
public class RouteThruInspect {
  public static void main(String[] args) throws Exception {
    Design d = Design.readCheckpoint(args[0]);
    int rtLut=0, rtFf=0, sitePips=0, siWith=0;
    Map<String,Integer> rtByType = new TreeMap<>();
    int shown=0;
    for (SiteInst si : d.getSiteInsts()) {
      List<SitePIP> sps = si.getUsedSitePIPs();
      if (sps != null && !sps.isEmpty()) { sitePips += sps.size(); siWith++; }
      for (Cell c : si.getCells()) {
        if (c.isRoutethru()) {
          rtLut++;
          String key = (c.isFFRoutethruCell()?"FFthru ":"LUTthru ") + si.getSiteTypeEnum();
          rtByType.merge(key,1,Integer::sum);
          if (c.isFFRoutethruCell()) rtFf++;
          if (shown < 14) {
            shown++;
            System.out.println("RT cell="+c.getName()+" type="+c.getType()
              +" bel="+c.getBELName()+" site="+si.getSiteName()
              +" ff="+c.isFFRoutethruCell()
              +" usedPhysPins="+c.getUsedPhysicalPins());
          }
        }
      }
    }
    System.out.println("=== routethru cells: "+rtLut+" (FF="+rtFf+") ; sitePIPs used="+sitePips+" across "+siWith+" siteInsts");
    System.out.println("=== routethru by type ==="); 
    for (Map.Entry<String,Integer> e: rtByType.entrySet()) System.out.println("   "+e.getValue()+"  "+e.getKey());
    // dump a sample SiteInst's full used-SitePIP list
    for (SiteInst si : d.getSiteInsts()) {
      if (si.getUsedSitePIPs()!=null && si.getUsedSitePIPs().size()>=3) {
        System.out.println("=== sample SiteInst "+si.getSiteName()+" ("+si.getSiteTypeEnum()+") used SitePIPs ===");
        for (SitePIP sp : si.getUsedSitePIPs())
          System.out.println("   SitePIP "+sp.getBELName()+" in="+sp.getInputPinName()+" out="+sp.getOutputPinName());
        break;
      }
    }
  }
}
