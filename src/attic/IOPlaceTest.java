package dev.fpga.rapidwright;
import com.xilinx.rapidwright.design.*;
import com.xilinx.rapidwright.device.*;
public class IOPlaceTest {
  static void tryPlace(Design d, String nm, Unisim u, String belStr) {
    try {
      Cell c = d.createAndPlaceCell(nm, u, belStr);
      System.out.println("  createAndPlaceCell("+u+","+belStr+") -> cell="+(c==null?"NULL":c.getName())
        +" placed="+(c!=null && c.getBEL()!=null)+" bel="+(c!=null && c.getBEL()!=null ? c.getBEL().getName():"-"));
    } catch (Throwable e) {
      System.out.println("  createAndPlaceCell("+u+","+belStr+") THREW: "+e.getMessage());
    }
  }
  public static void main(String[] a) throws Exception {
    Design d = new Design("iotest","xc7vx485tffg1761-2");
    Device dev = d.getDevice();
    Site s = dev.getSite("IOB_X0Y138");
    System.out.println("IOB_X0Y138 type="+s.getSiteTypeEnum()+" bels:");
    for (BEL b : s.getBELs()) System.out.println("   "+b.getName()+" ("+b.getBELType()+")");
    System.out.println("place OBUF attempts:");
    tryPlace(d,"o1",Unisim.OBUF,"IOB_X0Y138/OUTBUF_DCIEN");
    tryPlace(d,"o2",Unisim.OBUF,"IOB_X0Y138/OUTBUF");
    // what bels does Unisim.OBUF fit? try via EDIFCell placement
    Site s2 = dev.getSite("IOB_X0Y124");
    System.out.println("place IBUF attempts:");
    tryPlace(d,"i1",Unisim.IBUF,"IOB_X0Y124/INBUF_DCIEN");
    tryPlace(d,"i2",Unisim.IBUF,"IOB_X0Y124/INBUF_EN");
    tryPlace(d,"i3",Unisim.IBUF,"IOB_X0Y124/INBUF");
  }
}
