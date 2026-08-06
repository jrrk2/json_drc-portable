package dev.fpga.rapidwright;
import com.xilinx.rapidwright.design.*;
import com.xilinx.rapidwright.device.*;
import java.util.*;
public class LED {
  public static void main(String[] a) throws Exception {
    Design d = Design.readCheckpoint(a[0]);
    for (Cell c : d.getCells()) {
      String n=c.getName();
      if (c.getType().contains("OBUF") && n.contains("led")) {
        Site s=c.getSite(); 
        System.out.println(n+" bel="+c.getBELName()+" site="+(s==null?"-":s.getName())+" tile="+(s==null?"-":s.getTile().getName()));
      }
    }
  }
}
