/* Minimum viable test of "Vivado-free P&R on V7 via RapidWright":
 * a single input pin -> a single output pin, no clock, no logic.
 *
 * If this routes cleanly through Router.routeDesign() and produces a
 * DCP that dcp2fasm + prjxray can render into a working bit, the
 * approach scales up to counter25.  If Router fails or RapidWright
 * rejects the V7 part, we know to pivot.
 *
 * Design:
 *   rst (input)  -> IBUF -> OBUF -> led (output)
 * Pin constraints (from counter25's top.xdc, VC707 board):
 *   rst : PACKAGE_PIN AV40 (IOB_X0Y124), IOSTANDARD LVCMOS18
 *   led : PACKAGE_PIN AM39 (IOB_X0Y138), IOSTANDARD LVCMOS18
 */
package dev.fpga.rapidwright;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.design.Unisim;
import com.xilinx.rapidwright.device.BEL;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.SiteTypeEnum;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFDirection;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFPort;
import com.xilinx.rapidwright.edif.EDIFPortInst;
import com.xilinx.rapidwright.edif.EDIFTools;
import com.xilinx.rapidwright.router.Router;

public class greedy_min {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: greedy_min <out.dcp>");
            System.exit(2);
        }
        String outDcp = args[0];

        String part = "xc7vx485tffg1761-2";
        Design des = new Design("top", part);
        Device dev = des.getDevice();
        EDIFNetlist nl = des.getNetlist();
        EDIFCell topCell = nl.getTopCell();
        EDIFCell workLib = nl.getWorkLibrary().getCell("top");
        if (workLib == null) workLib = topCell;

        // === 1. Top-level ports ===
        EDIFPort rstPort = topCell.createPort("rst", EDIFDirection.INPUT, 1);
        EDIFPort ledPort = topCell.createPort("led", EDIFDirection.OUTPUT, 1);

        // === 2. Create IBUF and OBUF cells, place at the requested IOBs ===
        // IBUF for `rst` at IOB_X0Y124 (PACKAGE_PIN AV40).
        // V7 IOB sites carry INBUF_DCIEN as the input-buffer BEL (no plain INBUF).
        Cell ibuf = des.createAndPlaceCell(
            "rst_IBUF", Unisim.IBUF,
            "IOB_X0Y124/INBUF_DCIEN");
        if (ibuf == null) {
            throw new RuntimeException(
                "createAndPlaceCell for IBUF at IOB_X0Y124 failed");
        }

        // OBUF for `led` at IOB_X0Y138 (PACKAGE_PIN AM39).
        Cell obuf = des.createAndPlaceCell(
            "led_OBUF", Unisim.OBUF,
            "IOB_X0Y138/OUTBUF_DCIEN");
        if (obuf == null) {
            throw new RuntimeException(
                "createAndPlaceCell for OBUF at IOB_X0Y138 failed");
        }

        // === 3. Create three logical nets and wire them up ===
        // Net "rst" : top port -> IBUF.I
        EDIFNet rstNet = topCell.createNet("rst");
        rstNet.createPortInst(rstPort);
        rstNet.createPortInst("I", ibuf.getEDIFCellInst());

        // Net "rst_IBUF" : IBUF.O -> OBUF.I
        EDIFNet midNet = topCell.createNet("rst_IBUF");
        midNet.createPortInst("O", ibuf.getEDIFCellInst());
        midNet.createPortInst("I", obuf.getEDIFCellInst());

        // Net "led" : OBUF.O -> top port
        EDIFNet ledNet = topCell.createNet("led");
        ledNet.createPortInst("O", obuf.getEDIFCellInst());
        ledNet.createPortInst(ledPort);

        // === 4. Manual SitePinInst plumbing.
        // Net.connect() needs the IOB site's intra-site routing (IUSED/OUSED
        // RBELs etc.) configured already.  Bypass it by creating
        // SitePinInsts explicitly the way json2dcp.java does.

        // Helper: attach a Cell's port to a Net as either source or sink, by
        // walking the cell's BEL pin connections to the SitePinName.
        // Map logical pin (as named on the Unisim cell) to the BEL pin via
        // Cell.getPhysicalPinMapping, then walk to the site pin via
        // BEL.getPin(belPin).getConnectedSitePinName().  Pattern lifted from
        // json2dcp.java's createNetSink helper.
        java.util.function.BiConsumer<Net, Object[]> attach = (n, parms) -> {
            Cell c = (Cell) parms[0];
            String logicalPin = (String) parms[1];
            boolean isOutput = (boolean) parms[2];
            String belPin = c.getPhysicalPinMapping(logicalPin);
            if (belPin == null) {
                throw new RuntimeException("no physical-pin mapping for "
                    + c.getName() + "." + logicalPin
                    + " (BEL=" + c.getBEL().getName() + ")");
            }
            String sitePin = c.getBEL().getPin(belPin).getConnectedSitePinName();
            if (sitePin == null) {
                throw new RuntimeException("BEL pin " + c.getBEL().getName()
                    + "." + belPin + " has no connected site pin");
            }
            SitePinInst spi = new SitePinInst(isOutput, sitePin, c.getSiteInst());
            n.addPin(spi);
        };

        // Print pin mapping for debug
        System.out.println("[greedy_min] IBUF physical-pin map: "
            + "I -> " + ibuf.getPhysicalPinMapping("I")
            + ", O -> " + ibuf.getPhysicalPinMapping("O"));
        System.out.println("[greedy_min] OBUF physical-pin map: "
            + "I -> " + obuf.getPhysicalPinMapping("I")
            + ", O -> " + obuf.getPhysicalPinMapping("O"));

        Net physMid = des.createNet("rst_IBUF");
        attach.accept(physMid, new Object[]{ibuf, "O", true});   // source
        attach.accept(physMid, new Object[]{obuf, "I", false});  // sink

        Net physRst = des.createNet("rst");
        attach.accept(physRst, new Object[]{ibuf, "I", false});

        Net physLed = des.createNet("led");
        attach.accept(physLed, new Object[]{obuf, "O", true});

        // === 5. Set basic IO properties ===
        ibuf.addProperty("IOSTANDARD", "LVCMOS18");
        obuf.addProperty("IOSTANDARD", "LVCMOS18");

        // === 6. Hand the design to Router ===
        System.out.println("[greedy_min] Routing " + des.getNets().size()
            + " physical nets; net details:");
        for (Net n : des.getNets()) {
            System.out.println("  " + n.getName()
                + " src=" + n.getSource()
                + " #pins=" + n.getPins().size());
        }
        Router router = new Router(des);
        router.routeDesign();
        System.out.println("[greedy_min] After routing, PIP counts per net:");
        for (Net n : des.getNets()) {
            System.out.println("  " + n.getName() + " #pips=" + n.getPIPs().size());
        }

        // === 7. Write DCP ===
        System.out.println("[greedy_min] Writing " + outDcp);
        des.writeCheckpoint(outDcp);
        System.out.println("[greedy_min] Done.");
    }
}
