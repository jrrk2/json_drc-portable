package dev.fpga.rapidwright;

import com.xilinx.rapidwright.design.*;
import com.xilinx.rapidwright.device.*;
import com.xilinx.rapidwright.edif.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/**
 * xml2dcp — restore a Vivado DCP from the open "opendcp" XML + sidecar EDIF
 * produced by dcp2xml.  Rebuilds the COMPLETE RapidWright Design (cell
 * placement, native routethrus, pin mappings, properties, intra-site SitePIPs,
 * full inter-tile routing PIPs, site pins, constraints) so the round-trip
 * DCP -> XML -> DCP is lossless.
 */
public class xml2dcp {

    static String un(String s) {
        if (s == null) return null;
        return s.replace("&quot;","\"").replace("&gt;",">").replace("&lt;","<").replace("&amp;","&");
    }
    static final Pattern ATTR = Pattern.compile("(\\w+)=\"([^\"]*)\"");
    static Map<String,String> attrs(String line) {
        Map<String,String> m = new HashMap<>();
        Matcher mm = ATTR.matcher(line);
        while (mm.find()) m.put(mm.group(1), un(mm.group(2)));
        return m;
    }

    /**
     * Intra-site stitching, with the robustness logic ported from json2dcp.
     *
     * The naive version walked the dumped &lt;siteroute&gt; records and called
     * routeIntraSiteNet(net, srcBelPin, sinkBelPin) on the literal BEL pins,
     * with no guards and a single failure counter.  json2dcp does three things
     * it did not:
     *
     *  1. It refuses to stitch THROUGH AN INVERSION.  If a cell has
     *     IS_&lt;port&gt;_INVERTED=1, the site wire arriving at that pin is
     *     inverted, and routing a net across it asserts that both ends carry
     *     the same value when they do not.  json2dcp collects those wires up
     *     front and skips them.  This is the direct counterpart of the Vivado
     *     code that crashes on round-tripped checkpoints --
     *     HDPYFinalizeReqVal::invertConstant, reached from optionalInversions --
     *     which is Vivado resolving exactly this question.  IMPORTED.
     *
     *  2. It guards null site wires.  Some 7-series BEL pins (IOB18 internal
     *     static pins) have none, and an unguarded equals() NPEs on them.
     *     IMPORTED.
     *
     *  3. It resolves endpoints by scanning for a BEL pin on the right SITE
     *     WIRE rather than by name.  IMPORTED ONLY AS A FALLBACK, deliberately:
     *     json2dcp scans because a nextpnr routing string gives it a wire and
     *     nothing else, whereas here dcp2xml names the pins and picks them with
     *     intent -- ROOT drivers and LEAF loads only, RBEL routing-mux pins
     *     excluded, because the mux path between root and leaf is carried by
     *     the SitePIPs.  Scanning unconditionally re-admits those mux pins:
     *     measured on golden ethmin it gives 5.4x more successful stitches
     *     (566345 against 105344) and ONE MORE unrouted net at the far end,
     *     1104 against 1094.  More stitching is not more routing.  So the dump
     *     wins and the scan fires only when the named pin has the wrong
     *     direction -- 24 times out of 55292 records here.
     *
     * The dump emits, per net per site, the cross product of root drivers and
     * leaf loads over all the wires that net occupies, so most (src,sink) pairs
     * are not real paths and are expected to be refused.  A bare failure count
     * over that cross product says nothing, hence the breakdown.
     */
    static void applySiteRoutes(Design des, Device dev, List<String[]> siteRoutes) {
        // --- 1. wires that arrive at an inverted pin -----------------------
        // json2dcp reads IS_*_INVERTED off the nextpnr cell params; here the
        // same parameters are cell properties restored from the XML.
        HashSet<String> invertedWires = new HashSet<>();
        int invPropsSeen = 0;
        for (SiteInst si : des.getSiteInsts()) {
            for (Cell c : si.getCells()) {
                if (c.getBEL() == null || c.getProperties() == null) continue;
                for (Map.Entry<String,EDIFPropertyValue> e : c.getProperties().entrySet()) {
                    String k = e.getKey();
                    if (!k.startsWith("IS_") || !k.endsWith("_INVERTED")) continue;
                    invPropsSeen++;
                    String v = e.getValue() == null ? null : e.getValue().getValue();
                    if (v == null || !v.endsWith("1")) continue;
                    String logical = k.substring(3, k.length() - "_INVERTED".length());
                    // logical port -> physical BEL pin -> the wire it sits on
                    String phys = c.getPhysicalPinMapping(logical);
                    BELPin bp = (phys == null) ? c.getBEL().getPin(logical)
                                               : c.getBEL().getPin(phys);
                    if (bp == null) continue;
                    String swn = bp.getSiteWireName();
                    if (swn != null) invertedWires.add(si.getSiteName() + "/" + swn);
                }
            }
        }

        int ok=0, noSite=0, noNet=0, noSrc=0, noSink=0, inverted=0, refused=0, threw=0, srcScanned=0, sinkScanned=0;
        for (String[] rec : siteRoutes) {
            Site s = dev.getSite(rec[0]);
            SiteInst si = (s==null)?null:des.getSiteInstFromSite(s);
            if (si == null) { noSite++; continue; }
            Net net = des.getNet(rec[1]);
            if (net == null) net = des.getGndNet().getName().equals(rec[1]) ? des.getGndNet()
                                 : des.getVccNet().getName().equals(rec[1]) ? des.getVccNet() : null;
            if (net == null) { noNet++; continue; }

            // --- 2. source: the OUTPUT pin on the source wire --------------
            BEL sbel = si.getBEL(rec[2]);
            BELPin named = (sbel==null)?null:sbel.getPin(rec[3]);
            String srcWire = (named==null)?null:named.getSiteWireName();
            // json2dcp scans for "an output pin on this wire" because the nextpnr
            // routing string gives it a wire and nothing else.  Here the dump
            // names the pin, and dcp2xml picked it deliberately: it records ROOT
            // drivers only, skipping RBEL routing-mux pins, because the mux path
            // between root and leaf is carried by the SitePIPs.  Scanning would
            // happily replace that root with a mux output on the same wire and
            // stitch from the middle of the path.  So trust the dump, and fall
            // back to json2dcp's scan only when it does not resolve to an output.
            BELPin src = (named != null && named.isOutput()) ? named : null;
            if (src == null && srcWire != null) {
                for (BEL other : si.getBELs())
                    for (BELPin p : other.getPins()) {
                        String pwn = p.getSiteWireName();
                        if (p.isOutput() && pwn != null && pwn.equals(srcWire)) src = p;
                    }
                if (src != null) srcScanned++;
            }
            if (src == null) { noSrc++; continue; }

            for (int i = 4; i + 1 < rec.length; i += 2) {
                BEL kbel = si.getBEL(rec[i]);
                BELPin sk = (kbel==null)?null:kbel.getPin(rec[i+1]);
                if (sk == null) { noSink++; continue; }
                String dstWire = sk.getSiteWireName();
                if (dstWire == null) { noSink++; continue; }
                if (invertedWires.contains(si.getSiteName() + "/" + dstWire)) { inverted++; continue; }
                // Sinks, same reasoning as the source: the dump lists the actual
                // LEAF loads, so use them.  Expanding to every input pin on the
                // wire (json2dcp's approach, which is all it can do) was tried
                // and pulls in the RBEL mux inputs dcp2xml excludes on purpose:
                // 5.4x more successful stitches, and ONE MORE unrouted net at
                // the far end -- 1104 against 1094.  More stitching is not more
                // routing.
                if (!sk.isInput()) {
                    BELPin scan = null;
                    for (BEL other : si.getBELs())
                        for (BELPin p : other.getPins()) {
                            String pwn = p.getSiteWireName();
                            if (p.isInput() && pwn != null && pwn.equals(dstWire)) scan = p;
                        }
                    if (scan == null) { noSink++; continue; }
                    sk = scan; sinkScanned++;
                }
                try { if (si.routeIntraSiteNet(net, src, sk)) ok++; else refused++; }
                catch (Throwable e) { threw++; }
            }
        }
        System.out.println("xml2dcp: siteroutes ok=" + ok + " refused=" + refused
                + " inverted-skip=" + inverted + " no-site=" + noSite + " no-net=" + noNet
                + " no-src=" + noSrc + " no-sink=" + noSink + " scanned(src/sink)=" + srcScanned + "/" + sinkScanned + " threw=" + threw);
        // Report the scan, not just the hits: on a design where nothing is
        // inverted the guard is correctly inert, and "0 wires" alone cannot be
        // told apart from a lookup that silently found no properties at all.
        System.out.println("xml2dcp: IS_*_INVERTED props scanned=" + invPropsSeen
                + " -> inverted site wires=" + invertedWires.size());
    }

    public static void main(String[] a) throws Exception {
        if (a.length < 2) {
            System.err.println("usage: xml2dcp <in.opendcp.xml> <out.dcp>");
            System.exit(2);
        }
        Design des = buildDesign(a[0], true);
        des.writeCheckpoint(a[1]);
        System.out.println("xml2dcp: wrote " + a[1]);
    }

    /** Parse opendcp XML + sidecar EDIF into a fully-built RapidWright Design
     *  (cells, native routethrus, pin maps, properties, SitePIPs, all routing
     *  PIPs, site pins).  reconstructSiteRouting=true runs routeSite() to
     *  rebuild intra-site nets (needed for writeCheckpoint); false skips it
     *  (FASM emission is feature-based and needs only the SitePIPs/PIPs). */
    public static Design buildDesign(String xmlPath, boolean reconstructSiteRouting) throws Exception {
        List<String> L = Files.readAllLines(Paths.get(xmlPath));
        // header
        String part = null, top = null, edif = null;
        for (String s : L) { String t = s.trim();
            if (t.startsWith("<opendcp ")) { var h = attrs(t); part=h.get("part"); top=h.get("top"); edif=h.get("edif"); break; } }
        File edifFile = new File(new File(xmlPath).getParentFile(), edif);
        System.out.println("xml2dcp: part=" + part + " top=" + top + " edif=" + edifFile);

        EDIFNetlist nl = EDIFTools.readEdifFile(edifFile.toPath());
        Design des = new Design(top, part);
        des.setNetlist(nl);
        Device dev = des.getDevice();

        int cells=0, rts=0, sps=0, nets=0, pins=0, pips=0, pipMiss=0, cellFail=0, pinFail=0, macroNets=0;
        SiteInst curSi = null;
        Cell curCell = null;
        Net curNet = null;
        boolean inRouting = false;
        // siteroutes collected during parse, applied after nets exist:
        // {siteName, netName, srcBel, srcPin, [sinkBel,sinkPin,...]}
        List<String[]> siteRoutes = new ArrayList<>();
        String[] curSR = null;
        List<String> curSRsinks = null;

        for (String raw : L) {
            String t = raw.trim();
            if (t.startsWith("<routing")) { inRouting = true; continue; }
            if (t.startsWith("</routing")) { inRouting = false; continue; }

            if (t.startsWith("<siteinst ")) {
                var h = attrs(t);
                Site s = dev.getSite(h.get("site"));
                SiteTypeEnum type = SiteTypeEnum.valueOf(h.get("type"));
                curSi = des.getSiteInstFromSite(s);
                if (curSi == null) curSi = des.createSiteInst(h.get("name"), type, s);
                continue;
            }
            if (t.startsWith("<cell ")) {
                var h = attrs(t);
                String name = h.get("name"), belnm = h.get("bel");
                BEL bel = curSi.getBEL(belnm);
                curCell = null;
                boolean isRT = "true".equals(h.get("routethru"));
                try {
                    EDIFHierCellInst ehci = nl.getHierCellInstFromName(name);
                    if (bel != null) {
                        Cell c;
                        if (isRT) {
                            // A routethru is a SECOND physical placement of an
                            // already-placed logical cell (e.g. an FDRE on a LUT
                            // bel buffering its D net).  createCell() rejects the
                            // duplicate ehci, so build it directly and link the
                            // same logical inst.
                            c = new Cell(name, curSi, bel);
                            c.setType(h.get("type"));
                            c.setRoutethru(true);
                            if (ehci != null) c.setEDIFHierCellInst(ehci);
                            curSi.addCell(c);
                            rts++;
                        } else if (ehci != null) {
                            c = curSi.createCell(ehci, bel);
                        } else { cellFail++; continue; }
                        // clear default pin mappings; XML carries the authoritative set
                        for (Object p : c.getPinMappingsP2L().keySet().toArray())
                            c.removePinMapping(p.toString());
                        curCell = c;
                        cells++;
                    } else cellFail++;
                } catch (Throwable e) { cellFail++; }
                if (t.endsWith("/>")) curCell = null;
                continue;
            }
            if (t.startsWith("<pin ") && curCell != null) {
                var h = attrs(t);
                try { curCell.addPinMapping(h.get("bel"), h.get("log")); } catch (Throwable e) {}
                continue;
            }
            if (t.startsWith("<prop ") && curCell != null) {
                var h = attrs(t);
                try { curCell.addProperty(h.get("key"), h.get("val")); } catch (Throwable e) {}
                continue;
            }
            if (t.startsWith("</cell>")) { curCell = null; continue; }

            if (t.startsWith("<sitepip ") && curSi != null) {
                var h = attrs(t);
                try { curSi.addSitePIP(h.get("bel"), h.get("in")); sps++; } catch (Throwable e) {}
                continue;
            }
            if (t.startsWith("<siteroute ") && curSi != null) {
                var h = attrs(t);
                curSR = new String[]{ curSi.getSiteName(), h.get("net"), h.get("srcbel"), h.get("srcpin") };
                curSRsinks = new ArrayList<>();
                continue;
            }
            if (t.startsWith("<sink ") && curSR != null) {
                var h = attrs(t);
                curSRsinks.add(h.get("bel")); curSRsinks.add(h.get("pin"));
                continue;
            }
            if (t.startsWith("</siteroute>") && curSR != null) {
                String[] rec = new String[4 + curSRsinks.size()];
                System.arraycopy(curSR, 0, rec, 0, 4);
                for (int i = 0; i < curSRsinks.size(); i++) rec[4+i] = curSRsinks.get(i);
                siteRoutes.add(rec);
                curSR = null; curSRsinks = null;
                continue;
            }

            if (t.startsWith("<net ")) {
                var h = attrs(t);
                String name = h.get("name"), ntype = h.get("type");
                if ("GND".equals(ntype))      curNet = des.getGndNet();
                else if ("VCC".equals(ntype)) curNet = des.getVccNet();
                else {
                    curNet = des.getNet(name);
                    if (curNet == null) {
                        EDIFHierNet ehn = nl.getHierNetFromName(name);
                        if (ehn != null) {
                            curNet = des.createNet(ehn);
                        } else {
                            try {
                                curNet = des.createNet(name);
                            } catch (RuntimeException ex) {
                                // MACRO-INTERNAL net.  RapidWright expands a
                                // RAM64M into four RAMD64Es and creates DOA..DOD
                                // INSIDE the macro cell, so createNet() on a path
                                // like ".../ram_reg_0_63_3_5/DOA" resolves into
                                // that cell and collides with the net already
                                // there:
                                //   ERROR: Name collision inside EDIFCell RAM64M,
                                //   trying to add net DOA which already exists
                                // 40 such nets in a Vivado-routed ethmin, and it
                                // aborted the whole round-trip -- which is what
                                // blocked the one clean control available for the
                                // DCP-writer question (same design, same routing,
                                // only the writer differs).
                                //
                                // The logical net already exists inside the macro;
                                // what the round-trip needs is somewhere to hang
                                // the PHYSICAL routing.  Make a physical-only Net
                                // and leave the EDIF alone.
                                curNet = des.getNet(name);
                                if (curNet == null) {
                                    curNet = new Net(name, (EDIFHierNet) null);
                                    des.addNet(curNet);
                                    macroNets++;
                                }
                            }
                        }
                    }
                }
                nets++;
                continue;
            }
            if (t.startsWith("<sitepin ") && curNet != null) {
                var h = attrs(t);
                Site s = dev.getSite(h.get("site"));
                SiteInst si = (s==null)?null:des.getSiteInstFromSite(s);
                if (si != null) {
                    SitePinInst spi = si.getSitePinInst(h.get("pin"));
                    if (spi == null) {
                        try { curNet.createPin(h.get("pin"), si); pins++; }
                        catch (Throwable e) {
                            pinFail++;
                            if (pinFail <= 8) System.out.println("[pin-fail] net=" + curNet.getName()
                                + " site=" + h.get("site") + " pin=" + h.get("pin") + " : " + e);
                        }
                    } else if (spi.getNet() != curNet) {
                        // pin already created (e.g. by a routethru cell) but not
                        // attached to this net -> attach so the routing tree has
                        // its endpoint (else Vivado reports an antenna).
                        try { curNet.addPin(spi); pins++; } catch (Throwable e) {}
                    }
                }
                continue;
            }
            if (t.startsWith("<pip ") && curNet != null) {
                var h = attrs(t);
                Tile tile = dev.getTile(h.get("tile"));
                if (tile == null) { pipMiss++; continue; }
                Integer si = tile.getWireIndex(h.get("src"));
                Integer di = tile.getWireIndex(h.get("dst"));
                if (si == null || di == null) { pipMiss++; continue; }
                PIP p = tile.getPIP(si, di);
                if (p == null) p = tile.getPIP(di, si);
                if (p == null) { pipMiss++; continue; }
                if ("true".equals(h.get("rev"))) { p = new PIP(p); p.setIsReversed(true); }
                curNet.addPIP(p);
                pips++;
                continue;
            }
            if (t.startsWith("<xdc ")) {
                int gs = t.indexOf('>'), ge = t.lastIndexOf("</xdc>");
                if (gs >= 0 && ge > gs) {
                    var h = attrs(t);
                    ConstraintGroup g = ConstraintGroup.valueOf(h.getOrDefault("group","NORMAL"));
                    des.addXDCConstraint(g, un(t.substring(gs+1, ge)));
                }
                continue;
            }
        }
        System.out.println("xml2dcp: cells=" + cells + " (routethru=" + rts + " fail=" + cellFail
                + ") sitepips=" + sps + " nets=" + nets + " macro-internal=" + macroNets + " sitepins=" + pins
                + " pips=" + pips + " pipMiss=" + pipMiss);
        // Reconstruct intra-site routing.  Two strategies:
        //   default       : per-SiteInst routeSite() — reconstructs the bypass/
        //                    mux/const site-wire paths from cells + SitePIPs.
        //                    Best practical fidelity (RapidWright exposes no
        //                    public site-wire->net SETTER, so the exact dumped
        //                    site routing cannot be written back directly).
        //   _SITEROUTE=1  : apply the explicit dumped <siteroute> via
        //                    routeIntraSiteNet (root driver -> leaf loads).
        if (reconstructSiteRouting && System.getenv("XML2DCP_SITEROUTE") != null) {
            applySiteRoutes(des, dev, siteRoutes);
        } else if (reconstructSiteRouting) {
            int ok=0, fail=0;
            // A swallowed count is not a diagnosis.  Vivado's crash on a
            // round-tripped checkpoint lands in HDPYFinalizeReqVal::
            // invertConstant / optionalInversions -- i.e. resolving a CONSTANT
            // on an invertible site pin -- so which site types fail to
            // reconstruct, and why, is exactly the evidence needed.
            java.util.Map<String,Integer> failByType = new java.util.TreeMap<>();
            java.util.List<String> failExamples = new java.util.ArrayList<>();
            for (SiteInst si : des.getSiteInsts()) {
                try { si.routeSite(); ok++; }
                catch (Throwable e) {
                    fail++;
                    String ty = String.valueOf(si.getSiteTypeEnum());
                    failByType.merge(ty, 1, Integer::sum);
                    if (failExamples.size() < 20)
                        failExamples.add(si.getSiteName() + " (" + ty + "): "
                                + e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }
            System.out.println("xml2dcp: routeSite ok=" + ok + " fail=" + fail);
            if (fail > 0) {
                System.out.println("xml2dcp: routeSite failures by site type: " + failByType);
                for (String x : failExamples) System.out.println("xml2dcp:   " + x);
            }
        }
        return des;
    }
}
