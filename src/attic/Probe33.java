package dev.fpga.rapidwright;
import com.xilinx.rapidwright.design.*;
import com.xilinx.rapidwright.edif.*;
import java.util.*;
public class Probe33 {
  public static void main(String[] a) throws Exception {
    Design des = xml2dcp.buildDesign(a[0], false);
    EDIFCell top = des.getNetlist().getTopCell();
    System.out.println("leaf insts: "+top.getCellInsts().size()+"  nets: "+top.getNets().size()+"  ports: "+top.getPorts().size());
    // one CARRY4 logical inst: its port insts + driving nets
    for (EDIFCellInst ci : top.getCellInsts()) {
      if (ci.getCellType().getName().equals("CARRY4")) {
        System.out.println("CARRY4 inst="+ci.getName());
        for (EDIFPortInst pi : ci.getPortInsts()) {
          if (pi.getName().startsWith("S")||pi.getName().startsWith("DI")||pi.getName().startsWith("CYINIT"))
            System.out.println("  "+pi.getName()+" dir="+pi.getDirection()+" net="+(pi.getNet()==null?"-":pi.getNet().getName()));
        }
        break;
      }
    }
    // routethru cells: pin mapping + bel + name
    int rt=0;
    for (Cell c : des.getCells()) {
      if (c.isRoutethru()) {
        if (rt++<3) System.out.println("ROUTETHRU name="+c.getName()+" bel="+c.getSiteName()+"/"+c.getBELName()+" type="+c.getType()+" P2L="+c.getPinMappingsP2L());
      }
    }
    System.out.println("total routethru="+rt);
    // GND/VCC representation
    for (EDIFCellInst ci : top.getCellInsts())
      if (ci.getCellType().getName().equals("GND")||ci.getCellType().getName().equals("VCC"))
        { System.out.println("CONST inst="+ci.getName()+" type="+ci.getCellType().getName()+" outnet="+ci.getPortInsts().iterator().next().getNet().getName()); }
  }
}
