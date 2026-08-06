package dev.fpga.rapidwright;
import com.xilinx.rapidwright.design.*;
import com.xilinx.rapidwright.device.*;
import java.io.*;
import java.util.*;
public class FFRouteThruExtract {
  public static void main(String[] a) throws Exception {
    Design d = Design.readCheckpoint(a[0]);
    PrintWriter pw = new PrintWriter(new FileWriter(a[1]));
    int n=0, shown=0;
    for (SiteInst si : d.getSiteInsts()) {
      for (Cell c : si.getCells()) {
        if (!c.isRoutethru()) continue;
        String inpins = c.getUsedPhysicalPins()==null ? "" : c.getUsedPhysicalPins().toString();
        // find the net carried: the input bel pin's site wire net
        String net="?";
        try {
          for (String pp : c.getUsedPhysicalPins()) {
            BELPin bp = c.getBEL().getPin(pp);
            if (bp!=null) { Net nn = si.getNetFromSiteWire(bp.getSiteWireName()); if (nn!=null){net=nn.getName(); break;} }
          }
        } catch (Exception e){}
        pw.println(c.getName()+"\t"+si.getSiteName()+"/"+c.getBELName()+"\t"+c.getType()+"\t"+inpins+"\t"+net);
        n++;
        if (shown<14){shown++; System.out.println("RT "+c.getName()+" site="+si.getSiteName()+" rtBEL="+c.getBELName()+" type="+c.getType()+" usedPins="+inpins+" net="+net);}
      }
    }
    pw.close();
    System.out.println("=== total routethru cells: "+n);
  }
}
