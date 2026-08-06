package dev.fpga.rapidwright;

import com.xilinx.rapidwright.design.*;
import com.xilinx.rapidwright.edif.*;
import com.google.gson.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/**
 * xml2json -- convert a placed opendcp XML into a fully-PLACED nextpnr JSON,
 * so nextpnr only ROUTES (no re-pack -> no unplaceable CARRY4 feed-through LUTs).
 *
 * The golden placement carries each CARRY4 S[i] input through an x6LUT used as a
 * route-through (physical-only, no logical EDIF cell).  nextpnr's packer, given
 * a bare S net, mints a feed_through_lut it then cannot place inside the full
 * stamped slice.  We instead emit each route-through as an EXPLICIT, BEL-pinned
 * LUT1 buffer driving S[i]; nextpnr then ADOPTS it (pinned LUT -> carry) and
 * never creates a feed-through.
 *
 * Logical netlist + non-routethru placement come from the RapidWright Design
 * (xml2dcp.buildDesign).  The route-through bels are read DIRECTLY from the XML
 * (buildDesign collapses the 4 same-named routethrus per slice into 1).
 */
public class xml2json {

    static final Pattern BIT = Pattern.compile("^(.*)\\[(\\d+)\\]$");
    static int nextBit = 2;                 // 0=const0(GND), 1=const1(VCC) lanes
    static Map<EDIFNet,Integer> netBit = new HashMap<>();
    static int bitOf(EDIFNet n) { return netBit.computeIfAbsent(n, k -> nextBit++); }
    static boolean isConst(EDIFNet n) { String nm=n.getName(); return nm.equals("<const0>")||nm.equals("<const1>"); }
    static int freshBit() { return nextBit++; }
    static Map<Integer,String> freshNames = new java.util.LinkedHashMap<>();  // bit -> trace name

    // route-through record: CARRY4 inst name + S-pin (e.g. "S[3]") -> full bel
    static class RT { String carry, spin, bel; }      // bel = "SITE/x6LUT"
    static Map<String,String> placement = new HashMap<>();  // cellName -> "SITE/BEL" (non-routethru)

    // Single pass over the XML: build the non-routethru placement map AND the
    // route-through S-LUT list, both keyed off the enclosing <siteinst site=>.
    static List<RT> parseXml(String xmlPath) throws IOException {
        List<RT> out = new ArrayList<>();
        Pattern pinP = Pattern.compile("<pin bel=\"[^\"]+\" log=\"(S\\[\\d\\])\"/>");
        String curSite = null;
        RT cur = null;            // open routethru cell
        for (String ln : Files.readAllLines(Paths.get(xmlPath))) {
            String t = ln.trim();
            if (t.startsWith("<siteinst ")) { curSite = attr(t, "site"); continue; }
            if (t.startsWith("<cell ")) {
                String name = attr(t, "name"), bel = attr(t, "bel");
                boolean rt = t.contains("routethru=\"true\"");
                if (rt) {
                    cur = new RT(); cur.carry = name; cur.spin = null;
                    cur.bel = (bel != null && bel.endsWith("LUT")) ? curSite + "/" + bel : null;
                    if (t.endsWith("/>")) cur = null;   // routethru with no pin block (skip)
                } else {
                    if (name != null && bel != null) placement.put(name, curSite + "/" + bel);
                    cur = null;
                }
                continue;
            }
            if (t.startsWith("<pin ") && cur != null) {
                Matcher pm = pinP.matcher(t);
                if (pm.find()) cur.spin = pm.group(1);
                continue;
            }
            if (t.startsWith("</cell>") && cur != null) {
                if (cur.spin != null && cur.bel != null) out.add(cur);
                cur = null;
            }
        }
        return out;
    }
    static String attr(String s, String k) {
        Matcher m = Pattern.compile(k+"=\"([^\"]*)\"").matcher(s);
        return m.find() ? m.group(1) : null;
    }

    // Convert a Verilog property literal to the plain bit string nextpnr wants:
    //   N'bXXXX -> XXXX ; N'hHEX -> N-bit binary ; else unchanged (enum strings).
    static final Pattern VBIN = Pattern.compile("^(\\d+)'[bB]([01xzXZ]+)$");
    static final Pattern VHEX = Pattern.compile("^(\\d+)'[hH]([0-9a-fA-F]+)$");
    static String normProp(String v) {
        if (v == null) return null;
        Matcher m = VBIN.matcher(v);
        if (m.matches()) return m.group(2);
        m = VHEX.matcher(v);
        if (m.matches()) {
            int w = Integer.parseInt(m.group(1));
            String b = new java.math.BigInteger(m.group(2), 16).toString(2);
            if (b.length() < w) { StringBuilder sb = new StringBuilder(); for (int i=b.length();i<w;i++) sb.append('0'); b = sb + b; }
            else if (b.length() > w) b = b.substring(b.length() - w);
            return b;
        }
        return v;
    }

    static String netName(EDIFNet n) {
        String nm = n.getName();
        // Do NOT reuse $PACKER_GND_NET/$PACKER_VCC_NET -- those names collide with
        // the nets nextpnr's pack_constants creates (pack.cc:578), so it OVERWRITES
        // our net and orphans its loads.  Use distinct names; the GND/VCC cell
        // driving them lets pack_constants rewire the loads onto its own const nets.
        if (nm.equals("<const0>")) return "xml2json_gnd_src";
        if (nm.equals("<const1>")) return "xml2json_vcc_src";
        return nm;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) { System.err.println("usage: xml2json <in.opendcp.xml> <out.json>"); System.exit(2); }
        Design des = xml2dcp.buildDesign(args[0], false);
        EDIFNetlist nl = des.getNetlist();
        EDIFCell top = nl.getTopCell();
        List<RT> rts = parseXml(args[0]);
        System.out.println("xml2json: routethru S-LUTs parsed = " + rts.size());

        // index routethrus by (carry,spin)
        Map<String,String> rtBel = new HashMap<>();   // "carry|S[3]" -> bel
        for (RT r : rts) rtBel.put(r.carry + "|" + r.spin, r.bel);

        JsonObject cells = new JsonObject();
        JsonObject netnames = new JsonObject();
        JsonObject ports = new JsonObject();

        // ---- top-level ports ----
        for (EDIFPort p : top.getPorts()) {
            JsonObject po = new JsonObject();
            po.addProperty("direction", p.getDirection()==EDIFDirection.INPUT?"input":(p.getDirection()==EDIFDirection.OUTPUT?"output":"inout"));
            JsonArray bits = new JsonArray();
            int w = p.isBus()? p.getWidth():1;
            for (int i=0;i<w;i++){
                String pn = p.isBus()? p.getBusName()+"["+(p.getLeft()>=p.getRight()? p.getRight()+i : p.getLeft()+i)+"]" : p.getName();
                EDIFNet n = top.getNet(pn);
                if (n==null) n = top.getNet(p.getName());
                bits.add(n==null? freshBit() : bitOf(n));
            }
            po.add("bits", bits);
            ports.add(p.isBus()? p.getBusName() : p.getName(), po);
        }

        // ---- cells ----
        for (EDIFCellInst ci : top.getCellInsts()) {
            String type = ci.getCellType().getName();
            // EMIT GND/VCC cells: nextpnr's pack_constants only rewires const-tied
            // loads onto its own $PACKER_GND_NET/$PACKER_VCC_NET when the source net
            // is driven by a GND/VCC *cell* (pack.cc:620).  That rewire is what lets
            // pack_carry disconnect a GND DI (pack.cc:186).  Without the cell,
            // pack_constants OVERWRITES the same-named net and orphans the loads.
            JsonObject c = new JsonObject();
            c.addProperty("hide_name", 1);
            c.addProperty("type", type);
            // parameters (INIT etc.) -- normalize Verilog literals (1'b0, 33'h0)
            // to plain bit strings; nextpnr's fasm.cc asserts bit-strings are 0/1/x/z.
            JsonObject params = new JsonObject();
            for (Map.Entry<String,EDIFPropertyValue> e : ci.getPropertiesMap().entrySet())
                params.addProperty(e.getKey(), normProp(e.getValue().getValue()));
            c.add("parameters", params);
            // attributes: BEL placement.  Pin SLICE fabric AND IOB cells
            // (IBUFDS/OBUF) to golden's bels -- I/O placement is critical and
            // must match the XDC pins exactly; leaving the IBUFDS unpinned left
            // it UNPLACED -> no clock entered the chip -> dead design.  ONLY the
            // BUFG is left unpinned (BUFGCTRL site, /BUFG bel that nextpnr types
            // BUFG_BUFG -> a pin there is a bel-type ontology mismatch; it
            // self-constrains via dedicated routing instead).
            JsonObject attrs = new JsonObject();
            String bel = placement.get(ci.getName());
            boolean isBufg = type.equals("BUFG") || type.equals("BUFGCTRL")
                          || (bel != null && bel.contains("BUFG"));
            // Option (a): pin IO cells + ONLY the carry-chain ROOT (a CARRY4 whose
            // CI is a CONST, i.e. the bottom of the chain).  Drop absolute pins on
            // the rest of the carry cluster (non-root CARRY4s, the S-LUTs, the carry
            // FFs) so nextpnr's ATOMIC carry packer places them RELATIVE to the
            // pinned root -- which forms the dedicated COUT->CIN chain.  Absolute
            // pins on EVERY carry broke the cluster (constraint-satisfaction error)
            // -> the chain was routed through slow FABRIC -> timing fail @200MHz.
            // The relative placement still lands at golden's slices.
            if (bel != null && !isBufg && (bel.startsWith("SLICE") || bel.startsWith("IOB")))
                attrs.addProperty("BEL", bel);
            c.add("attributes", attrs);
            // connections + port_directions
            JsonObject conns = new JsonObject();
            JsonObject pdir = new JsonObject();
            // group port insts into buses
            Map<String,TreeMap<Integer,EDIFNet>> buses = new TreeMap<>();
            Map<String,EDIFDirection> dirs = new HashMap<>();
            for (EDIFPortInst pi : ci.getPortInsts()) {
                // Use the port-inst NAME (e.g. "S[3]") for the LOGICAL bit number;
                // getIndex() returns declaration position, which is reversed for
                // MSB-first buses and would misalign the routethru S-pin lookup.
                String pname = pi.getName();
                String base; int idx;
                Matcher bm = BIT.matcher(pname);
                if (bm.matches()) { base = bm.group(1); idx = Integer.parseInt(bm.group(2)); }
                else { base = pname; idx = 0; }
                buses.computeIfAbsent(base, k->new TreeMap<>()).put(idx, pi.getNet());
                dirs.put(base, pi.getPort().getDirection());
            }
            boolean isCarry = type.equals("CARRY4");
            // MMCM/PLL const DRP/control pins (DADDR, DI, DCLK, DEN, DWE, PSx,
            // CLKIN2, PWRDWN, RST, CLKINSEL...) are tied to GND/VCC in golden.
            // nextpnr disconnects these itself ("Vivado leaves them unrouted") --
            // but only during CLOCK packing, AFTER pack_constants has already
            // built GND-distribution feed-through LUTs for GND's swollen fanout
            // (here: 59 loads, 32 of them MMCM).  Those orphaned LUTs then can't
            // place on the pinned layout -> "Found unbound cell $PACKER_GND_NET$LUT".
            // Leave the const pins disconnected up front so GND fanout stays low.
            boolean isPllMmcm = type.startsWith("MMCM") || type.startsWith("PLL");
            // A CARRY4 with a DYNAMIC CYINIT (carry-chain input) cannot route a
            // const DI[0] through the AX bypass (AX carries CYINIT), so nextpnr
            // wants an O5 feed-through LUT for it.  Detect that here so the DI
            // emission can synth a const LUT at the slice's A5LUT.
            boolean dynCyinit = false;
            if (isCarry && buses.containsKey("CYINIT")) {
                EDIFNet cyi = buses.get("CYINIT").get(0);
                dynCyinit = cyi != null && !cyi.getName().startsWith("<const");
            }
            for (Map.Entry<String,TreeMap<Integer,EDIFNet>> b : buses.entrySet()) {
                String base = b.getKey();
                JsonArray arr = new JsonArray();
                // bus bits in index order 0..n
                int max = b.getValue().lastKey();
                for (int i=0;i<=max;i++){
                    EDIFNet n = b.getValue().get(i);
                    int bit;
                    boolean inPin = dirs.get(base)==EDIFDirection.INPUT;
                    boolean constNet = n!=null && n.getName().startsWith("<const");
                    if (n==null) bit = freshBit();
                    else if (isPllMmcm && inPin && constNet) bit = freshBit(); // leave unrouted
                    else if (isCarry && base.equals("S") && rtBel.containsKey(ci.getName()+"|S["+i+"]")) {
                        // golden route-through: explicit BEL-pinned buffer LUT driving
                        // S[i] -> nextpnr ADOPTS it (no feed-through).  const S and
                        // real-LUT S are left to nextpnr (golden puts no LUT there).
                        bit = freshBit();
                        emitRtLut(cells, ci.getName(), i, rtBel.get(ci.getName()+"|S["+i+"]"), bitOf(n), bit);
                    } else bit = bitOf(n);
                    arr.add(bit);
                }
                conns.add(base, arr);
                EDIFDirection d = dirs.get(base);
                pdir.addProperty(base, d==EDIFDirection.INPUT?"input":(d==EDIFDirection.OUTPUT?"output":"inout"));
            }
            c.add("port_directions", pdir);
            c.add("connections", conns);
            cells.add(ci.getName(), c);
        }

        // ---- netnames ----
        for (EDIFNet n : top.getNets()) {
            JsonObject nn = new JsonObject();
            nn.addProperty("hide_name", 1);
            JsonArray bits = new JsonArray(); bits.add(bitOf(n));
            nn.add("bits", bits);
            nn.add("attributes", new JsonObject());
            netnames.add(netName(n), nn);
        }
        // name the synthesized RT-LUT output nets so any feed-through on them is
        // traceable (otherwise nextpnr renames them "$legal$N").
        for (Map.Entry<Integer,String> e : freshNames.entrySet()) {
            JsonObject nn = new JsonObject(); nn.addProperty("hide_name", 0);
            JsonArray bits = new JsonArray(); bits.add(e.getKey()); nn.add("bits", bits);
            nn.add("attributes", new JsonObject());
            netnames.add(e.getValue(), nn);
        }

        // ---- assemble ----
        JsonObject mod = new JsonObject();
        JsonObject modAttrs = new JsonObject(); modAttrs.addProperty("top","00000000000000000000000000000001");
        mod.add("attributes", modAttrs);
        mod.add("ports", ports);
        mod.add("cells", cells);
        mod.add("netnames", netnames);
        JsonObject modules = new JsonObject(); modules.add(top.getName(), mod);
        JsonObject root = new JsonObject();
        root.addProperty("creator","xml2json");
        root.add("modules", modules);

        try (PrintWriter w = new PrintWriter(new FileWriter(args[1]))) {
            new GsonBuilder().setPrettyPrinting().create().toJson(root, w);
        }
        System.out.println("xml2json: wrote " + args[1] + "  cells=" + cells.size() + " nets=" + netnames.size());
    }

    // Synthesize a BEL-pinned LUT1 buffer (O = I0) at the route-through bel,
    // driving CARRY4.S[i].  inBit = original S source net; outBit = the fresh
    // net the CARRY4 now sees on S[i].
    static void emitRtLut(JsonObject cells, String carry, int idx, String bel, int inBit, int outBit) {
        JsonObject c = new JsonObject();
        c.addProperty("hide_name", 1);
        c.addProperty("type", "LUT1");
        // LUT1 buffer O=I0: INIT is a 2-bit BINARY string, bit[i0]=output.
        // "10" => I0=1->1, I0=0->0 (buffer).  Must be binary, not decimal "2",
        // or nextpnr's LUT timing model breaks ("negative fanin count").
        JsonObject params = new JsonObject(); params.addProperty("INIT","10"); c.add("parameters", params);
        JsonObject attrs = new JsonObject(); attrs.addProperty("BEL", bel); c.add("attributes", attrs);
        JsonObject pdir = new JsonObject(); pdir.addProperty("I0","input"); pdir.addProperty("O","output");
        c.add("port_directions", pdir);
        JsonObject conns = new JsonObject();
        JsonArray i0 = new JsonArray(); i0.add(inBit); conns.add("I0", i0);
        JsonArray oo = new JsonArray(); oo.add(outBit); conns.add("O", oo);
        c.add("connections", conns);
        cells.add(carry + "$RT$S" + idx, c);
        freshNames.put(outBit, carry + "$RTOUT$S" + idx);
    }
}
