package dev.fpga.rapidwright;
import com.xilinx.rapidwright.device.*;
import java.util.*;
public class ClkProbe {
  public static void main(String[] a) throws Exception {
    Device dev = Device.getDevice("xc7vx485tffg1761-2");
    Site s = dev.getSite("SLICE_X47Y127");
    System.out.println("SLICE_X47Y127 tile="+s.getTile().getName());
    Node start = s.getConnectedNode("CLK");
    System.out.println("CLK node: "+start+" tile="+(start==null?"-":start.getTile().getName()));
    if (start==null) return;
    Set<String> seen=new HashSet<>(); Deque<Node> q=new ArrayDeque<>(); q.add(start); seen.add(start.toString());
    Set<String> hclkSrc=new TreeSet<>(); int steps=0;
    while(!q.isEmpty() && steps<500000){ Node n=q.poll(); steps++;
      String w=n.getTile().getName()+"/"+n.getWireName();
      if (w.contains("BUFHCLK")||w.contains("HCLK_LEAF")||(n.getTile().getName().startsWith("HCLK_") && w.contains("CK_")))
        hclkSrc.add(w);
      for (Node up : n.getAllUphillNodes()){ if(seen.add(up.toString())) q.add(up); }
    }
    System.out.println("uphill HCLK BUFHCLK/leaf wires (steps="+steps+", nodes="+seen.size()+"):");
    for (String h: hclkSrc) System.out.println("  "+h);
  }
}
