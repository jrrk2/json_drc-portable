package dev.fpga.rapidwright;
import com.xilinx.rapidwright.design.*;
import com.xilinx.rapidwright.device.*;
import java.util.*; import java.util.regex.*;
public class CK2 {
  public static void main(String[] a) throws Exception {
    Design d = Design.readCheckpoint(a[0]);
    Pattern P = Pattern.compile("CLK_HROW_R_CK_GCLK(\\d+)");
    for (Net n : d.getNets()) {
      int g=-1;
      for (PIP p : n.getPIPs()){ Matcher m=P.matcher(p.getStartWireName()); if(!m.matches())m=P.matcher(p.getEndWireName()); if(m.matches()){g=Integer.parseInt(m.group(1));break;} }
      if(g<0) continue;
      TreeSet<Integer> loadRows=new TreeSet<>(), srcRows=new TreeSet<>();
      String srcInfo="";
      for (SitePinInst spi : n.getPins()) {
        Tile lt=spi.getTile(); if(lt==null)continue; ClockRegion cr=lt.getClockRegion(); if(cr==null)continue;
        if (spi.isOutPin()){ srcRows.add(cr.getInstanceY()); srcInfo+=" "+spi.getName()+"@"+lt.getName()+"/"+cr.getName(); }
        else loadRows.add(cr.getInstanceY());
      }
      System.out.println("GCLK"+g+" net="+n.getName()+" srcRows="+srcRows+" loadRows="+loadRows+" src="+srcInfo);
    }
  }
}
