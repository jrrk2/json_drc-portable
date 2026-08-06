package dev.fpga.rapidwright;
import com.xilinx.rapidwright.design.*;
import com.xilinx.rapidwright.device.*;
import com.xilinx.rapidwright.edif.*;
import java.util.*;
public class CK4 {
  public static void main(String[] a) throws Exception {
    Design d = Design.readCheckpoint(a[0]);
    String[] cases = {"CLBLM_R_X35Y104:D","CLBLM_R_X37Y101:B","CLBLM_R_X37Y101:C"};
    for (String cs : cases) {
      String tile=cs.split(":")[0]; char L=cs.split(":")[1].charAt(0);
      SiteInst si=null;
      for (SiteInst s : d.getSiteInsts()) {
        if (s.getTile().getName().equals(tile) && s.getSiteName().contains("SLICE")) {
          // X1 = the SLICEL; pick the one whose site X is odd (X1)
          Cell c6=s.getCell(L+"6LUT");
          if (c6!=null) { si=s; 
            EDIFCellInst inst=c6.getEDIFCellInst();
            EDIFPortInst po=inst==null?null:inst.getPortInst("O");
            EDIFNet en=po==null?null:po.getNet();
            System.out.println(tile+" "+L+" site="+s.getSiteName()+" cell="+c6.getName()+" type="+c6.getType()+" Onet="+(en==null?"null":en.getName()));
            // resolve physical net
            EDIFHierCellInst ehci=c6.getEDIFHierCellInst();
            String parent = (ehci!=null&&ehci.getParent()!=null)?ehci.getParent().getFullHierarchicalInstName():"";
            Net pn = en==null?null:d.getNet((parent.isEmpty()?"":parent+"/")+en.getName());
            if (pn==null&&en!=null) pn=d.getNet(en.getName());
            System.out.println("   parent="+parent+" net="+(pn==null?"NULL":pn.getName())+" pips="+(pn==null?0:pn.getPIPs().size()));
            if (pn!=null) for (PIP p:pn.getPIPs()) if (p.getTile().getName().equals(tile)) System.out.println("      PIP "+p.getStartWireName()+" -> "+p.getEndWireName());
          }
        }
      }
    }
  }
}
