package dev.fpga.rapidwright;
import com.xilinx.rapidwright.device.*;
public class NodeCheck {
  public static void main(String[] a) throws Exception {
    Device dev = Device.getDevice("xc7vx485tffg1761-2");
    Node aq = new Wire(dev.getTile("CLBLM_L_X44Y109"),"CLBLM_L_AQ").getNode();
    Node lo = new Wire(dev.getTile("INT_L_X44Y109"),"LOGIC_OUTS_L0").getNode();
    Node ww = new Wire(dev.getTile("INT_L_X40Y109"),"WW4END0").getNode();
    System.out.println("AQ            node = " + aq);
    System.out.println("LOGIC_OUTS_L0 node = " + lo);
    System.out.println("WW4END0       node = " + ww);
    System.out.println("AQ.equals(LOGIC_OUTS_L0) = " + (aq!=null && aq.equals(lo)));
    System.out.println("--- AQ node downhill PIPs ---");
    if (aq!=null) for (PIP p : aq.getAllDownhillPIPs())
        System.out.println("   " + p + "  endNode=" + p.getEndWire().getNode());
    System.out.println("--- LOGIC_OUTS_L0 node downhill PIPs (first 6) ---");
    int k=0; if (lo!=null) for (PIP p : lo.getAllDownhillPIPs()){ System.out.println("   "+p); if(++k>=6)break; }
  }
}
