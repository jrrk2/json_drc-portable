package dev.fpga.rapidwright;
import com.xilinx.rapidwright.design.*;
import com.xilinx.rapidwright.device.*;
import java.util.*;
public class MapTest {
  public static void main(String[] a) throws Exception {
    Design d = Design.readCheckpoint(a[0]);
    SiteInst si = d.getSiteInsts().iterator().next();
    Map<String,Net> m = si.getSiteWireToNetMap();
    // is it live? find a wire with a net, note it, try to clear via put, re-read
    String wire=null; for (var e : m.entrySet()) if (e.getValue()!=null){wire=e.getKey();break;}
    System.out.println("site="+si.getSiteName()+" wire="+wire+" net="+m.get(wire));
    try {
      m.put(wire, null);
      Net after = si.getNetFromSiteWire(wire);
      System.out.println("after put(null): getNetFromSiteWire="+after+" -> LIVE="+(after==null));
    } catch (Throwable e) { System.out.println("put threw: "+e); }
  }
}
