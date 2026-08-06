package dev.fpga.rapidwright;
import com.xilinx.rapidwright.design.*;
import com.xilinx.rapidwright.device.*;
import java.util.regex.*;
public class CK {
  public static void main(String[] a) throws Exception {
    Design d = Design.readCheckpoint(a[0]);
    Device dev = d.getDevice();
    // find a net with a GCLK pip
    Pattern P = Pattern.compile("CLK_HROW_([RL])_CK_GCLK(\\d+)");
    for (Net n : d.getNets()) {
      int g=-1; char side=0;
      for (PIP p : n.getPIPs()) {
        Matcher m = P.matcher(p.getStartWireName()); if(!m.find()) m=P.matcher(p.getEndWireName());
        if (m.find(0)||m.find()) {}
      }
      for (PIP p : n.getPIPs()) {
        for (String w : new String[]{p.getStartWireName(),p.getEndWireName()}) {
          Matcher m=P.matcher(w); if(m.matches()){side=m.group(1).charAt(0);g=Integer.parseInt(m.group(2));}
        }
      }
      if (g<0) continue;
      System.out.println("net="+n.getName()+" GCLK"+g+" side="+side+" loads="+n.getPins().size());
      int shown=0;
      for (SitePinInst spi : n.getPins()) {
        if (spi.isOutPin()) continue;
        Tile lt = spi.getTile(); ClockRegion cr = lt.getClockRegion();
        if (shown++<4) System.out.println("  load "+spi.getName()+" tile="+lt.getName()+" region="+(cr==null?"-":cr.getName()));
      }
      break;
    }
    // map CLK_HROW_BOT_R region -> tile
    int cnt=0;
    for (Tile t : dev.getAllTiles()) {
      if (t.getName().startsWith("CLK_HROW_BOT_R")) {
        ClockRegion cr=t.getClockRegion();
        if (cnt++<5) System.out.println("HROW "+t.getName()+" region="+(cr==null?"-":cr.getName()));
      }
    }
  }
}
