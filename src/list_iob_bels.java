/* Quick diagnostic: list BELs in an IOB site for V7. */
package dev.fpga.rapidwright;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.device.BEL;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Site;

public class list_iob_bels {
    public static void main(String[] args) {
        Design des = new Design("d", "xc7vx485tffg1761-2");
        Device dev = des.getDevice();
        for (String name : new String[]{
                "IOB_X0Y124", "IOB_X0Y138", "IOB_X1Y276" }) {
            Site s = dev.getSite(name);
            System.out.println(name + " type=" + s.getSiteTypeEnum()
                + " alt-types=" + java.util.Arrays.toString(s.getAlternateSiteTypeEnums()));
            for (BEL b : s.getBELs()) {
                System.out.println("  BEL " + b.getName()
                    + " class=" + b.getBELClass());
            }
        }
    }
}
