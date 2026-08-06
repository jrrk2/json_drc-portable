package dev.fpga.rapidwright;
import com.xilinx.rapidwright.design.*; import com.xilinx.rapidwright.edif.*;
public class Pr2 { public static void main(String[] a) throws Exception {
  Design des = xml2dcp.buildDesign(a[0], false);
  for (EDIFCellInst ci : des.getNetlist().getTopCell().getCellInsts())
    if (ci.getName().equals("cnt_reg[32]_i_1"))
      for (EDIFPortInst pi : ci.getPortInsts())
        if (pi.getName().startsWith("S")||pi.getName().startsWith("DI"))
          System.out.println(pi.getName()+" = "+(pi.getNet()==null?"NULL":pi.getNet().getName()));
}}
