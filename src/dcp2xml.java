package dev.fpga.rapidwright;

import com.xilinx.rapidwright.design.*;
import com.xilinx.rapidwright.device.*;
import com.xilinx.rapidwright.edif.*;
import java.io.*;
import java.util.*;

/**
 * dcp2xml — serialize a RapidWright Design (read from a Vivado DCP) into the
 * open, human-readable "opendcp" XML interchange format, plus a sidecar EDIF
 * holding the logical netlist verbatim.  Pairs with xml2dcp for a lossless
 * DCP -> XML -> DCP round-trip: the XML captures the COMPLETE physical state
 * (cell placement, pin mappings, native routethrus, intra-site SitePIPs, and
 * full inter-tile routing PIPs) that a logical-EDIF + placement reconstruction
 * (the json2dcp path) cannot carry.
 */
public class dcp2xml {

    static String esc(String s) {
        if (s == null) return "";
        return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;")
                .replace("\"","&quot;");
    }

    public static void main(String[] a) throws Exception {
        if (a.length < 2) {
            System.err.println("usage: dcp2xml <in.dcp> <out.opendcp.xml>  (writes <out>.edif sidecar)");
            System.exit(2);
        }
        Design des = Design.readCheckpoint(a[0]);
        String xmlPath = a[1];
        String edifPath = xmlPath.replaceFirst("(\\.xml)?$", "") + ".edif";

        // logical netlist verbatim
        des.getNetlist().exportEDIF(edifPath);

        PrintWriter o = new PrintWriter(new BufferedWriter(new FileWriter(xmlPath)));
        o.println("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        o.println("<opendcp part=\"" + esc(des.getPartName())
                + "\" top=\"" + esc(des.getTopEDIFCell().getName())
                + "\" edif=\"" + esc(new File(edifPath).getName()) + "\">");

        // ---- physical: per-SiteInst cells + used SitePIPs ----
        o.println("  <physical>");
        int nCells = 0, nRT = 0, nSP = 0, nSR = 0;
        for (SiteInst si : des.getSiteInsts()) {
            o.println("    <siteinst name=\"" + esc(si.getName())
                    + "\" site=\"" + esc(si.getSiteName())
                    + "\" type=\"" + esc(si.getSiteTypeEnum().toString()) + "\">");
            for (Cell c : si.getCells()) {
                if (c.getBELName() == null) continue;
                StringBuilder sb = new StringBuilder();
                sb.append("      <cell name=\"").append(esc(c.getName()))
                  .append("\" bel=\"").append(esc(c.getBELName()))
                  .append("\" type=\"").append(esc(c.getType()))
                  .append("\"");
                if (c.isRoutethru())       sb.append(" routethru=\"true\"");
                if (c.isFFRoutethruCell()) sb.append(" ffroutethru=\"true\"");
                Map<String,String> p2l = c.getPinMappingsP2L();
                Map<String,com.xilinx.rapidwright.edif.EDIFPropertyValue> props = c.getProperties();
                boolean empty = (p2l == null || p2l.isEmpty()) && (props == null || props.isEmpty());
                sb.append(empty ? "/>" : ">");
                o.println(sb.toString());
                if (!empty) {
                    if (p2l != null) for (Map.Entry<String,String> e : p2l.entrySet())
                        o.println("        <pin bel=\"" + esc(e.getKey())
                                + "\" log=\"" + esc(e.getValue()) + "\"/>");
                    if (props != null) for (var e : props.entrySet())
                        o.println("        <prop key=\"" + esc(e.getKey())
                                + "\" val=\"" + esc(e.getValue().getValue()) + "\"/>");
                    o.println("      </cell>");
                }
                nCells++;
                if (c.isRoutethru()) nRT++;
            }
            List<SitePIP> sps = si.getUsedSitePIPs();
            if (sps != null) for (SitePIP sp : sps) {
                o.println("      <sitepip bel=\"" + esc(sp.getBELName())
                        + "\" in=\"" + esc(sp.getInputPinName())
                        + "\" out=\"" + esc(sp.getOutputPinName()) + "\"/>");
                nSP++;
            }
            // EXPLICIT intra-site routing: per net, its driver BEL pin and sink
            // BEL pins (over the net's occupied site wires).  Restored verbatim
            // via routeIntraSiteNet so the bypass/mux/const paths are bit-exact
            // (no routeSite approximation).
            for (Map.Entry<Net,List<String>> e : si.getNetToSiteWiresMap().entrySet()) {
                Net net = e.getKey();
                List<BELPin> outs = new ArrayList<>(), ins = new ArrayList<>();
                for (String wire : e.getValue()) {
                    BELPin[] bps = si.getSiteWirePins(wire);
                    if (bps != null) for (BELPin bp : bps) {
                        // Only ROOT drivers / LEAF loads: skip RBEL (routing-mux)
                        // pins -- the mux path between root and leaf is carried by
                        // the SitePIPs, so routeIntraSiteNet reconstructs it.
                        if (bp.getBEL() != null
                                && bp.getBEL().getBELClass() == BELClass.RBEL) continue;
                        if (bp.isOutput()) outs.add(bp); else ins.add(bp);
                    }
                }
                if (ins.isEmpty() || outs.isEmpty()) continue;
                for (BELPin src : outs) {
                    o.println("      <siteroute net=\"" + esc(net.getName())
                            + "\" srcbel=\"" + esc(src.getBELName())
                            + "\" srcpin=\"" + esc(src.getName()) + "\">");
                    for (BELPin sk : ins)
                        o.println("        <sink bel=\"" + esc(sk.getBELName())
                                + "\" pin=\"" + esc(sk.getName()) + "\"/>");
                    o.println("      </siteroute>");
                    nSR++;
                }
            }
            o.println("    </siteinst>");
        }
        o.println("  </physical>");

        // ---- routing: per-Net source/sink site pins + inter-tile PIPs ----
        o.println("  <routing>");
        int nNets = 0, nPins = 0, nPips = 0;
        for (Net n : des.getNets()) {
            o.println("    <net name=\"" + esc(n.getName())
                    + "\" type=\"" + (n.isStaticNet() ? esc(n.getType().toString()) : "SIGNAL") + "\">");
            for (SitePinInst spi : n.getPins()) {
                o.println("      <sitepin site=\"" + esc(spi.getSiteInst()==null?"":spi.getSiteInst().getSiteName())
                        + "\" pin=\"" + esc(spi.getName())
                        + "\" dir=\"" + (spi.isOutPin()?"out":"in") + "\"/>");
                nPins++;
            }
            for (PIP p : n.getPIPs()) {
                o.println("      <pip tile=\"" + esc(p.getTile().getName())
                        + "\" src=\"" + esc(p.getStartWireName())
                        + "\" dst=\"" + esc(p.getEndWireName())
                        + "\"" + (p.isBidirectional() ? " bidir=\"true\"" : "")
                        + (p.isReversed() ? " rev=\"true\"" : "") + "/>");
                nPips++;
            }
            o.println("    </net>");
            nNets++;
        }
        o.println("  </routing>");

        // ---- constraints (XDC) ----
        o.println("  <constraints>");
        for (ConstraintGroup g : ConstraintGroup.values()) {
            List<String> xs = des.getXDCConstraints(g);
            if (xs != null) for (String x : xs)
                o.println("    <xdc group=\"" + g + "\">" + esc(x) + "</xdc>");
        }
        o.println("  </constraints>");

        o.println("</opendcp>");
        o.close();
        System.out.println("dcp2xml: wrote " + xmlPath + " + " + edifPath);
        System.out.println("  cells=" + nCells + " routethru=" + nRT + " sitepips=" + nSP
                + " nets=" + nNets + " sitepins=" + nPins + " pips=" + nPips + " siteroutes=" + nSR);
    }
}
