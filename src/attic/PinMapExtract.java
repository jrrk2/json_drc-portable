package dev.fpga.rapidwright;
import com.xilinx.rapidwright.design.*;
import java.io.*;
import java.util.*;
public class PinMapExtract {
  public static void main(String[] a) throws Exception {
    Design d = Design.readCheckpoint(a[0]);
    PrintWriter pw = new PrintWriter(new FileWriter(a[1]));
    int n=0;
    for (Cell c : d.getCells()) {
      if (c.getBELName()==null || c.getSiteName()==null) continue;
      if (c.isRoutethru()) continue;
      Map<String,String> p2l = c.getPinMappingsP2L();  // belPin -> logicalPin
      if (p2l==null || p2l.isEmpty()) continue;
      StringBuilder sb = new StringBuilder();
      String stype = (c.getSiteInst()!=null) ? c.getSiteInst().getSiteTypeEnum().toString() : "?";
      sb.append(c.getName()).append('\t')
        .append(c.getSiteName()).append('/').append(c.getBELName()).append('\t')
        .append(c.getType()).append('\t')
        .append(stype).append('\t');
      boolean first=true;
      for (Map.Entry<String,String> e : p2l.entrySet()) {
        if (!first) sb.append(',');
        sb.append(e.getKey()).append('=').append(e.getValue());
        first=false;
      }
      pw.println(sb.toString());
      n++;
    }
    pw.close();
    System.out.println("wrote pin maps for "+n+" cells");
  }
}
