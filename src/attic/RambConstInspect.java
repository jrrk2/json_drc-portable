package dev.fpga.rapidwright;
import com.xilinx.rapidwright.design.*;
import com.xilinx.rapidwright.device.*;
import java.util.*;
public class RambConstInspect {
  public static void main(String[] a) throws Exception {
    Design d = Design.readCheckpoint(a[0]);
    int shown=0;
    for (SiteInst si : d.getSiteInsts()) {
      if (!si.getSiteTypeEnum().toString().contains("RAMB18")) continue;
      System.out.println("SITE "+si.getSiteName()+" type="+si.getSiteTypeEnum());
      for (Cell c : si.getCells()) {
        if (c.getType()==null || !c.getType().contains("RAMB18")) continue;
        System.out.println(" CELL "+c.getName()+" type="+c.getType());
        for (String pin : new String[]{"ENARDEN","ENBWREN","CLKBWRCLK","REGCEAREGCE","RSTRAMARSTRAM","RSTRAMB","REGCEB"}) {
          BEL bel = c.getBEL();
          BELPin bp = (bel==null)?null:bel.getPin(pin);
          if (bp==null){System.out.println("   "+pin+": no belpin on "+ (bel==null?"null":bel.getName())); continue;}
          String sw = bp.getSiteWireName();
          Net bn = (sw==null)?null:si.getNetFromSiteWire(sw);
          System.out.println("   belpin "+pin+" sitewire="+sw+" net="+(bn==null?"NULL":bn.getName()));
        }
      }
      System.out.println(" usedSitePIPs="+si.getUsedSitePIPs());
      // which static-net site pins does this site carry?
      System.out.println(" sitePinInsts:");
      for (SitePinInst sp : si.getSitePinInsts())
        System.out.println("    "+sp.getName()+" net="+(sp.getNet()==null?"null":sp.getNet().getName()));
      if (++shown>=1) break;
    }
  }
}
