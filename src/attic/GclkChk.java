package dev.fpga.rapidwright;
import com.xilinx.rapidwright.device.*;
public class GclkChk { public static void main(String[] a) throws Exception {
  Device d=Device.getDevice("xc7vx485tffg1761-2");
  Tile t=d.getTile("INT_R_X31Y127");
  for (String w : new String[]{"GCLK_B0_EAST","GCLK_B0","CLK0"}) {
    Node n=t.getNode(w);
    System.out.println(w+" node="+(n==null?"NULL":n.toString()));
  }
}}
