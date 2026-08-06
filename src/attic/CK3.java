package dev.fpga.rapidwright;
import com.xilinx.rapidwright.design.*;
import com.xilinx.rapidwright.device.*;
import java.util.*; import java.util.regex.*;
public class CK3 {
  public static void main(String[] a) throws Exception {
    Design d = Design.readCheckpoint(a[0]);
    Device dev=d.getDevice();
    TreeMap<Integer,String> byY=new TreeMap<>();
    for(Tile t:dev.getAllTiles()) if(t.getName().startsWith("CLK_HROW_BOT_R")) byY.put(t.getTileYCoordinate(),t.getName());
    Integer[] HY=byY.keySet().toArray(new Integer[0]);
    System.out.println("HROW rows(Y): "+byY.keySet());
    Pattern P = Pattern.compile("CLK_HROW_R_CK_GCLK(\\d+)");
    for (Net n : d.getNets()) {
      int g=-1;
      for (PIP p : n.getPIPs()){ Matcher m=P.matcher(p.getStartWireName()); if(!m.matches())m=P.matcher(p.getEndWireName()); if(m.matches()){g=Integer.parseInt(m.group(1));break;} }
      if(g<0) continue;
      // source out-pin tiles
      for (SitePinInst spi : n.getPins()) {
        if(!spi.isOutPin())continue;
        Tile lt=spi.getTile();
        int y=lt.getTileYCoordinate();
        int best=0; for(int i=1;i<HY.length;i++) if(Math.abs(HY[i]-y)<Math.abs(HY[best]-y)) best=i;
        System.out.println("GCLK"+g+" SRC pin="+spi.getName()+" tile="+lt.getName()+" Y="+y+" -> nearestHROWrow="+best+"(Y"+HY[best]+")");
      }
    }
  }
}
