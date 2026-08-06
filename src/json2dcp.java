package dev.fpga.rapidwright;

import com.google.gson.stream.JsonReader;
import com.xilinx.rapidwright.design.*;
import com.xilinx.rapidwright.device.PartNameTools;
import com.xilinx.rapidwright.device.*;
import com.google.gson.*;
import com.xilinx.rapidwright.edif.*;
// Dropped: com.xilinx.rapidwright.util.RapidWright (no such class, only mentioned in a comment)
// Dropped: org.python.antlr.ast.Str (Jython artefact, unused)

import java.io.FileNotFoundException;
import java.io.FileReader;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

public class json2dcp {
    static int skipConst = 0;

    // Set in main() once at startup.  Null if the oracle file is missing —
    // the routing-import path then falls back to the legacy node-graph walk.
    static WireOracle wireOracle = null;

    static class NextpnrNet {

        public NextpnrNet(String name) {
            this.name = name;
            this.params = new HashMap<>();
            this.attrs = new HashMap<>();
            this.driver = null;
            this.users = new ArrayList<>();
            this.rwNet = null;
        }

        public String name;
        public HashMap<String, String> params;
        public HashMap<String, String> attrs;
        public NextpnrCellPort driver;
        public ArrayList<NextpnrCellPort> users;
        public Net rwNet;
    }

    enum PortDirection {
        PORT_IN,
        PORT_OUT,
        PORT_INOUT
    }

    public static PortDirection parsePortDir(String s) {
        if (s.equals("input"))
            return PortDirection.PORT_IN;
        else if (s.equals("output"))
            return PortDirection.PORT_OUT;
        else if (s.equals("inout"))
            return PortDirection.PORT_INOUT;
        else
            throw new RuntimeException("bad port direction " + s);
    }

    static class NextpnrCellPort {

        public NextpnrCellPort(NextpnrCell cell, String name, PortDirection type) {
            this.cell = cell;
            this.name = name;
            this.type = type;
            this.net = null;
        }

        public NextpnrCell cell;
        public String name;
        public PortDirection type;
        public NextpnrNet net;
    }

    static class NextpnrCell {

        public NextpnrCell(String name, String type) {
            this.name = name;
            this.type = type;
            this.ports = new HashMap<>();
            this.params = new HashMap<>();
            this.attrs = new HashMap<>();
            this.rwCell = null;
        }

        public String name, type;
        public HashMap<String, NextpnrCellPort> ports;
        public HashMap<String, String> params;
        public HashMap<String, String> attrs;

        public Cell rwCell;
    }

    /**
     * SHARED LUT INPUT PINS.
     *
     * nextpnr's fixupRouting() merges two logical LUT inputs carrying the SAME
     * net onto one physical pin and records both names in X_ORIG_PORT_&lt;phys&gt;
     * ("I3 I4").  RapidWright's pinMappingsP2L is 1:1 physical-&gt;logical, so the
     * second addPinMapping OVERWRITES the first and a logical pin is silently
     * dropped -- leaving e.g. a LUT6 with a 64-bit INIT but only 5 connected
     * inputs and one bel pin "Not assigned".  Vivado reports
     *
     *   [Designutils 20-756] Invalid physical equation for the B6LUT bel ...
     *   the bit width of the INIT value does not match the number of used
     *   input pins '5'
     *
     * and then SEGFAULTS in phys_opt_design / report_route_status /
     * report_timing, dereferencing the null logical net on the unassigned pin
     * (HDLHNet::getSigType &lt;- HDPYNetProxy &lt;- HDPYRoutedSiteBuilder::newRoutedSite).
     *
     * Since the shared pins carry ONE net, the function restricted to Ia==Ib is
     * a genuine (k-1)-input function: reduce the logical LUT to the pins
     * physically present rather than invent routing to a second pin.
     *
     * This logic existed only in ethsoc/routedjson2dcp.sh's python preprocessor,
     * so callers that go straight to json2dcp (json_drc --from-json, and the
     * Makefile's ethmin_open-eval) produced crashing checkpoints.  It belongs
     * here, where every caller gets it.
     */
    /** "64'hf7" (or a raw bit string) -> an MSB-first bit string of exactly
     *  `want` bits, or null if it cannot be read that way. */
    static String init_to_bits(String s, int want) {
        if (s == null) return null;
        String bits;
        int q = s.indexOf("'h");
        if (q >= 0) {
            try {
                bits = new java.math.BigInteger(s.substring(q + 2).trim(), 16).toString(2);
            } catch (NumberFormatException e) { return null; }
        } else {
            for (int i = 0; i < s.length(); i++)
                if (s.charAt(i) != '0' && s.charAt(i) != '1') return null;
            bits = s;
        }
        if (bits.length() > want) return null;          // wider than the LUT: not ours
        StringBuilder b = new StringBuilder();
        for (int i = bits.length(); i < want; i++) b.append('0');
        return b.append(bits).toString();
    }

    /** MSB-first bit string -> "<width>'h<hex>", the form parseParam emits. */
    static String bits_to_init(String bits) {
        return bits.length() + "'h" + new java.math.BigInteger(bits, 2).toString(16);
    }

    static void reduce_shared_lut_pins(NextpnrDesign ndes) {
        int nlut = 0;
        boolean dbg = System.getenv("J2D_SHARED_LUT_DBG") != null;
        for (NextpnrCell nc : ndes.cells.values()) {
            // Is this cell shared at all?  Computed first so a rejection can be
            // reported: a silent skip here is how the defect survived.
            boolean anyShared = false;
            for (Map.Entry<String, String> e0 : nc.attrs.entrySet())
                if (e0.getKey().startsWith("X_ORIG_PORT_")
                        && e0.getValue().trim().split("\\s+").length > 1) anyShared = true;

            String oty = nc.attrs.get("X_ORIG_TYPE");
            if (oty == null || !oty.startsWith("LUT") || oty.length() != 4) {
                if (anyShared && dbg)
                    System.out.println("[shared-lut] SKIP " + nc.name + ": X_ORIG_TYPE=" + oty);
                continue;
            }
            int k = oty.charAt(3) - '0';
            if (k < 1 || k > 6) continue;
            // parseParam() has ALREADY rewritten a binary INIT into Verilog
            // sized-hex ("64'hf7"), so this runs on that form, not on the raw
            // bit string the JSON carried.  Comparing against the bit-string
            // length silently skipped 4 of the 5 shared LUTs here.
            String initRaw = nc.params.get("INIT");
            String init = init_to_bits(initRaw, 1 << k);
            if (init == null) {
                if (anyShared && dbg)
                    System.out.println("[shared-lut] SKIP " + nc.name + ": " + oty
                            + " INIT=" + initRaw + " (cannot read as " + (1 << k) + " bits)");
                continue;
            }

            // physical pin -> logical pins on it
            java.util.TreeMap<String, java.util.List<Integer>> phys = new java.util.TreeMap<>();
            boolean shared = false;
            for (Map.Entry<String, String> e : nc.attrs.entrySet()) {
                if (!e.getKey().startsWith("X_ORIG_PORT_")) continue;
                java.util.List<Integer> ins = new java.util.ArrayList<>();
                for (String tok : e.getValue().trim().split("\\s+")) {
                    if (tok.length() == 2 && tok.charAt(0) == 'I'
                            && tok.charAt(1) >= '0' && tok.charAt(1) <= '9')
                        ins.add(tok.charAt(1) - '0');
                }
                if (!ins.isEmpty()) {
                    phys.put(e.getKey().substring("X_ORIG_PORT_".length()), ins);
                    if (ins.size() > 1) shared = true;
                }
            }
            // NB: do NOT bail out on !shared here -- a constant-tied input is
            // the other way a LUT arrives with fewer pins than its INIT width,
            // and it is detected below.  The decision to skip is at the
            // "nmiss == 0 && !shared" test, once both causes are known.

            // old logical index -> physical pin
            String[] where = new String[k];
            boolean ok = true;
            for (Map.Entry<String, java.util.List<Integer>> e : phys.entrySet())
                for (int i : e.getValue()) {
                    if (i < 0 || i >= k) { ok = false; break; }
                    where[i] = e.getKey();
                }
            if (!ok) continue;

            // CONSTANT-TIED INPUTS.  A logical input with no X_ORIG_PORT is one
            // yosys tied to a constant ("I5": ["0"]); nextpnr keeps the physical
            // pin on $PACKER_GND_NET/$PACKER_VCC_NET but drops the logical
            // mapping, so the cell arrives as 5 used pins with a 64-bit INIT --
            // the same 20-756 Vivado rejects, for a different reason.  The fix
            // is to COFACTOR: with I5 tied low, the 5-input function is the
            // INIT's low half (ffffffffbf000000 -> 32'hbf000000).
            int[] constVal = new int[k];
            java.util.Arrays.fill(constVal, -1);
            int nmiss = 0;
            for (int i = 0; i < k; i++) if (where[i] == null) nmiss++;
            if (nmiss > 0) {
                // physical input pins carrying a constant and mapped to no logical pin
                java.util.List<Integer> consts = new java.util.ArrayList<>();
                for (NextpnrCellPort p : nc.ports.values()) {
                    if (p.net == null || p.name.startsWith("O")) continue;
                    if (nc.attrs.containsKey("X_ORIG_PORT_" + p.name)) continue;
                    if (p.net.name.contains("PACKER_GND")) consts.add(0);
                    else if (p.net.name.contains("PACKER_VCC")) consts.add(1);
                }
                // Only act when the tie is unambiguous: as many constant pins as
                // missing inputs, all the same value.  Anything else is reported
                // rather than guessed -- a wrong cofactor is a silent miscompile.
                boolean uniform = consts.size() == nmiss && !consts.isEmpty();
                if (uniform)
                    for (int cv : consts) if (cv != consts.get(0)) uniform = false;
                if (!uniform) {
                    if (dbg)
                        System.out.println("[shared-lut] SKIP " + nc.name + ": " + oty + " has "
                                + nmiss + " unmapped logical input(s) but " + consts.size()
                                + " constant pin(s) -- not reducing");
                    continue;
                }
                for (int i = 0; i < k; i++) if (where[i] == null) constVal[i] = consts.get(0);
            }
            if (nmiss == 0 && !shared) continue;

            // physical pin -> lowest old index on it; those survive, in order.
            // constant-tied inputs have no physical pin and simply vanish.
            java.util.TreeMap<String, Integer> rep = new java.util.TreeMap<>();
            for (int i = 0; i < k; i++)
                if (where[i] != null && !rep.containsKey(where[i])) rep.put(where[i], i);
            if (rep.isEmpty()) continue;
            java.util.List<Integer> kept = new java.util.ArrayList<>(rep.values());
            java.util.Collections.sort(kept);
            java.util.HashMap<Integer, Integer> slot = new java.util.HashMap<>();
            for (int j = 0; j < kept.size(); j++) slot.put(kept.get(j), j);
            int nm = kept.size();

            // Rebuild the truth table over the reduced inputs.  INIT is
            // MSB-first, so bit for minterm idx is init[len-1-idx].
            StringBuilder out = new StringBuilder();
            for (int v = 0; v < (1 << nm); v++) {
                int o = 0;
                for (int i = 0; i < k; i++) {
                    int b = (where[i] == null) ? constVal[i]
                                               : ((v >> slot.get(rep.get(where[i]))) & 1);
                    if (b != 0) o |= 1 << i;
                }
                out.append(init.charAt(init.length() - 1 - o));
            }
            // back out in the same sized-hex form parseParam produced
            nc.params.put("INIT", bits_to_init(out.reverse().toString()));
            nc.attrs.put("X_ORIG_TYPE", "LUT" + nm);
            for (String pin : phys.keySet())
                nc.attrs.put("X_ORIG_PORT_" + pin, "I" + slot.get(rep.get(pin)));
            nlut++;
        }
        if (nlut > 0)
            System.out.println("[shared-lut] reduced " + nlut
                    + " LUT(s) whose logical inputs share a physical pin");
    }

    static class NextpnrDesign {
        // json index --> net
        public HashMap<Integer, NextpnrNet> nets;
        // name --> cell
        public HashMap<String, NextpnrCell> cells;
        public Design rwd;

        public NextpnrDesign() {
            nets = new HashMap<>();
            cells = new HashMap<>();
            rwd = null;
        }

        String parseParam(JsonElement val) {
            JsonPrimitive prim = val.getAsJsonPrimitive();
            if (prim.isNumber()) {
                int p = prim.getAsInt();
                int size = 1;
                if (p < 0) {
                    size = 32;
                } else {
                    while (p >= (1L << size))
                        ++size;
                }
                return "32'h" + Integer.toHexString(p);
            } else {
                String s = prim.getAsString();
                int state = 0;
                for (char c : s.toCharArray()) {
                    if (state == 0) {
                        if (c == ' ') {
                            state = 1;
                        } else if (c != '0' && c != '1' && c != 'x') {
                            state = 2;
                        }
                    } else if (state == 1) {
                        if (c != ' ')
                            state = 2;
                    }
                }
                if (state == 0) {
                    s = s.replace('x', '0');
                    BigInteger bi = new BigInteger(s, 2);
                    return s.length() + "'h" + bi.toString(16);
                } else if (state == 1) {
                    return s.substring(0, s.length() - 1);
                } else {
                    return s;
                }
            }
        }

        void Import(JsonObject des) {
            JsonObject top = des.getAsJsonObject("modules").getAsJsonObject(des.getAsJsonObject("modules").keySet().toArray()[0].toString());
            JsonObject netJson = top.getAsJsonObject("netnames");
            for(Map.Entry<String, JsonElement> entry : netJson.entrySet()) {
                NextpnrNet net = new NextpnrNet(entry.getKey());
                JsonObject data = entry.getValue().getAsJsonObject();
                int index = data.getAsJsonArray("bits").get(0).getAsInt();

                if (data.has("attributes")) {
                    JsonObject attrs = data.getAsJsonObject("attributes");
                    for (Map.Entry<String, JsonElement> attr : attrs.entrySet())
                        net.attrs.put(attr.getKey(), attr.getValue().getAsString());
                }

                nets.put(index, net);
            }

            JsonObject cellJson = top.getAsJsonObject("cells");
            for(Map.Entry<String, JsonElement> entry : cellJson.entrySet()) {
                JsonObject data = entry.getValue().getAsJsonObject();
                NextpnrCell cell = new NextpnrCell(entry.getKey(), data.get("type").getAsString());

                JsonObject pdirs = data.getAsJsonObject("port_directions");
                for (Map.Entry<String, JsonElement> pdir : pdirs.entrySet())
                    cell.ports.put(pdir.getKey(), new NextpnrCellPort(cell, pdir.getKey(), parsePortDir(pdir.getValue().getAsString())));

                JsonObject pconns = data.getAsJsonObject("connections");
                for (Map.Entry<String, JsonElement> pc : pconns.entrySet()) {
                    NextpnrCellPort port = cell.ports.get(pc.getKey());
                    JsonArray conn = pc.getValue().getAsJsonArray();
                    if (conn.size() > 0) {
                        NextpnrNet net = nets.get(conn.get(0).getAsInt());
                        port.net = net;
                        if (port.type == PortDirection.PORT_OUT) {
                            net.driver = port;
                        } else {
                            net.users.add(port);
                        }
                    }

                }

                JsonObject attrs = data.getAsJsonObject("attributes");
                for (Map.Entry<String, JsonElement> attr : attrs.entrySet())
                    cell.attrs.put(attr.getKey(), attr.getValue().getAsString());

                // FIXME: parse numerical params correctly
                JsonObject params = data.getAsJsonObject("parameters");
                for (Map.Entry<String, JsonElement> param : params.entrySet())
                    cell.params.put(param.getKey(), parseParam(param.getValue()));

                cells.put(entry.getKey(), cell);
            }
        }
    }

    public static String escape_name(String name) {
        return name.replace("\\", "__").replace("/", "_").replace("$subnet$", "/");
    }

    /** cells whose RapidWright default pin map was kept (hard blocks: no X_ORIG_PORT). */
    static int keptDefaultPinmap = 0, keptDefaultPins = 0, keptDroppedPins = 0;
    static int hardSinkLogical = 0, hardSinkFailed = 0, hardSinkNoPort = 0;

    static String[] DEBUG_NETS = null;
    /** True when J2D_DEBUG_NET names this net (comma-separated list). */
    public static boolean debug_net(String name) {
        if (DEBUG_NETS == null) {
            String e = System.getenv("J2D_DEBUG_NET");
            DEBUG_NETS = (e == null || e.isEmpty()) ? new String[0] : e.split(",");
        }
        for (String d : DEBUG_NETS)
            if (d.equals(name)) return true;
        return false;
    }

    public static String fixup_init(String orig, int bits) {
        // Vivado seems *very* fussy here
        String hex = orig.split("'h")[1];
        int digits = Math.max(bits / 4, 1);
        while (hex.length() < digits)
            hex = "0" + hex;
        return bits + "'h" + hex;
    }


    /** Map a nextpnr FLAT port name onto the EDIF cell's actual port.
     *
     * nextpnr names hard-block bus pins without brackets -- RXDATA13,
     * RXCHARISK0 -- but the EDIF GTXE2_CHANNEL declares RXDATA[15:0], so
     * createPortInst("RXDATA13") finds nothing and the whole bus arrives
     * undriven.  Vivado then refuses to run phys_opt_design at all:
     *   [DRC NDRV-1] ... Bus Net ...rxdata_rec[15:0] has undriven bits 0:15.
     *
     * Not a blind de-suffix: GTREFCLK0, RXOUTCLK and friends are genuine
     * SCALAR ports that end in a digit, so the cell's own port list decides.
     * Exact match wins; only if there is no such port do we split the trailing
     * digits and check for a bus of that base name.
     */
    public static String resolve_edif_pin(Cell cell, String name) {
        try {
            EDIFCellInst inst = cell.getEDIFCellInst();
            if (inst == null || name.endsWith("]"))
                return name;
            EDIFCell type = inst.getCellType();
            if (type == null || type.getPort(name) != null)
                return name;                       // genuine scalar, e.g. GTREFCLK0
            // Split flat "<BUS><index>" into bus + index.  Stripping ALL trailing
            // digits is WRONG when a cell has both <BUS> and <BUS><digit> ports:
            // a GTXE2_CHANNEL declares PCSRSVDIN[15:0] AND PCSRSVDIN2[4:0], so
            // nextpnr's "PCSRSVDIN20" (= PCSRSVDIN2 bit 0) got read as
            // PCSRSVDIN[20].  Index 20 on a 16-wide bus then produced a NEGATIVE
            // EDIF member -- (portref (member PCSRSVDIN -5) ...) -- and Vivado
            // rejected the whole netlist with
            //   [EDIF 20-86] Cannot find port 'PCSRSVDIN' ... of cell 'GTXE2_CHANNEL'
            // naming the bus, which sent me looking at the bus machinery rather
            // than at the index.  Indices 20..24 were exactly PCSRSVDIN2[4:0].
            //
            // So try the split points from the RIGHT -- fewest index digits first,
            // i.e. longest base first -- and take the first that names a real bus
            // with the index IN RANGE.  PCSRSVDIN2+"0" wins over PCSRSVDIN+"20";
            // RXDATA13 still resolves to RXDATA[13] because there is no RXDATA1.
            for (int cut = name.length() - 1; cut > 0; cut--) {
                if (!Character.isDigit(name.charAt(cut)))
                    break;                         // ran past the trailing digits
                EDIFPort p = type.getPort(name.substring(0, cut));
                if (p == null || !p.isBus())
                    continue;
                int idx;
                try { idx = Integer.parseInt(name.substring(cut)); }
                catch (NumberFormatException nfe) { continue; }
                if (idx >= 0 && idx < p.getWidth())
                    return name.substring(0, cut) + "[" + idx + "]";
            }
            return name;
        } catch (RuntimeException ex) {
            return name;
        }
    }

    /** Set a Unisim parameter only if the netlist did not supply one. */
    public static void default_param(NextpnrCell nc, String key, String value) {
        if (!nc.params.containsKey(key))
            nc.rwCell.addProperty(key, value);
    }

    /** Does this PIP actually carry the hop nextpnr described?
     *
     * Wire NAMES repeat in every INT tile, so a hop that spans tiles --
     * "INT_R_X31Y83/SR1BEG_S0 -> INT_R_X31Y65/LV0" -- can be "found" in the
     * destination tile as INT_R_X31Y65/SR1BEG_S0->LV0.  That is a real pip, so
     * it imports without error, and the net looks connected if you compare
     * names; but SR1 is a single-length wire, so SR1BEG_S0@Y65 is a DIFFERENT
     * node from the SR1BEG_S0@Y83 our route drives.  Vivado then reports the
     * whole downstream branch as an antenna.  Compare NODES, not names.
     * Either orientation is accepted: bidirectional pips are imported reversed.
     */
    public static boolean pip_matches_nodes(PIP p, Tile srcTile, String srcWire,
                                            Tile dstTile, String dstWire) {
        try {
            // EXCEPTION: the per-tile constant pseudo-sources.  nextpnr writes
            // a canonical placeholder tile for them -- "INT_L_X0Y138/GND_WIRE
            // -> INT_L_X30Y138/GFAN1" -- where the real constant node is the
            // DESTINATION tile's GND_WIRE (every INT tile has its own, driven
            // by that tile's TIEOFF).  Validating the source node here rejected
            // 10 of the GND net's pips and left it unroutable, so for these
            // wires only the destination node is meaningful.
            boolean constSrc = "GND_WIRE".equals(srcWire) || "VCC_WIRE".equals(srcWire);
            Node expS = (!constSrc && srcTile != null && srcTile.getWireIndex(srcWire) != null)
                    ? new Wire(srcTile, srcWire).getNode() : null;
            Node expD = (dstTile != null && dstTile.getWireIndex(dstWire) != null)
                    ? new Wire(dstTile, dstWire).getNode() : null;
            if (expS == null && expD == null) return true;
            Node gs = p.getStartNode(), gd = p.getEndNode();
            boolean fwd = (expS == null || expS.equals(gs)) && (expD == null || expD.equals(gd));
            boolean bwd = (expS == null || expS.equals(gd)) && (expD == null || expD.equals(gs));
            return fwd || bwd;
        } catch (RuntimeException ex) {
            return true;   // never let the check itself drop a pip
        }
    }

    public static void connect_log_and_phys(Net net, Cell cell, String logical_pin) {
        // Best-effort wrapper: the SVS all-LUT netlist exposes several const/
        // logical-pin edge cases RapidWright can't map.  Skip the unmappable
        // connection (counted) so the DCP builds for STA rather than aborting.
        try { connect_log_and_phys_impl(net, cell, logical_pin); }
        catch (RuntimeException ex) {
            skipConst++;
            if (net != null && debug_net(net.getName()))
                System.out.println("[dbg] connect FAILED net=" + net.getName()
                        + " cell=" + cell.getName() + " pin=" + logical_pin
                        + " site=" + cell.getSiteName() + " : " + ex);
        }
    }
    public static void connect_log_and_phys_impl(Net net, Cell cell, String logical_pin) {
        // Similar to RapidWright's net.connect; but handles some special cases correctly
        if (cell.getName().contains("/") || net.getLogicalNet() == null) {
            var phys_pins = cell.getAllPhysicalPinMappings(logical_pin);
            for (String belpin : phys_pins) {
                if (cell.getBEL().getPin(belpin).getConnectedSitePinName() != null) {
                    String pin = cell.getBEL().getPin(belpin).getConnectedSitePinName();
                    // Several logical pins (e.g. a RAMB18 WEA byte-write bus, or
                    // shared LUT address inputs) can map to the same physical
                    // site pin; RapidWright throws "already has a pin named X"
                    // on the second add.  Reuse the existing one.
                    if (cell.getSiteInst().getSitePinInst(pin) == null)
                        net.addPin(new SitePinInst(cell.getBEL().getPin(belpin).isOutput(), pin, cell.getSiteInst()));
                }
            }
        } else if (cell.getPhysicalPinMapping(logical_pin) == null || cell.getBEL().getPin(cell.getPhysicalPinMapping(logical_pin)).getConnectedSitePinName() == null || logical_pin.endsWith("]") ||
                    cell.getType().equals("RAMB36E2") || cell.getType().equals("IBUFCTRL") || cell.getType().equals("OUTBUF") || cell.getType().equals("INBUF")) {
            // Create logical connection only
            EDIFPortInst epi;
            if (logical_pin.endsWith("]")) {
                int open_pos = logical_pin.lastIndexOf('[');
                String log_bus = logical_pin.substring(0, open_pos);
                int port_index = Integer.parseInt(logical_pin.substring(open_pos + 1, logical_pin.length() - 1));
                int bus_width = cell.getEDIFCellInst().getPort(log_bus).getWidth();
                // An out-of-range index silently becomes a NEGATIVE EDIF member,
                // which Vivado only rejects when it READS the checkpoint -- and it
                // blames the bus, not the index.  Refuse here instead.
                if (port_index < 0 || port_index >= bus_width)
                    throw new RuntimeException("bus index out of range: " + logical_pin
                            + " on " + log_bus + "[" + (bus_width - 1) + ":0] of "
                            + cell.getName());
                epi = net.getLogicalNet().createPortInst(log_bus, (bus_width - 1) - port_index, cell.getEDIFCellInst());
                //System.out.println(net.getName() + " -L-> " + epi.getName());

            } else {
                epi = net.getLogicalNet().createPortInst(logical_pin, cell.getEDIFCellInst());
            }
            // If there is a physical pin connect it too.  getAllPhysicalPinMappings
            // returns null for a logical pin with no physical mapping (e.g. a
            // constant-generator LUT input), so guard against it.
            var phys_pins = cell.getAllPhysicalPinMappings(logical_pin);
            if (phys_pins != null) {
                for (String belpin : phys_pins) {
                    if (cell.getBEL().getPin(belpin).getConnectedSitePinName() != null) {
                        String pin = cell.getBEL().getPin(belpin).getConnectedSitePinName();
                        // See note above: multiple logical pins (RAMB WEA bus,
                        // shared LUT inputs) may map to one site pin; reuse it.
                        if (cell.getSiteInst().getSitePinInst(pin) == null)
                            net.addPin(new SitePinInst(epi.getDirection() == EDIFDirection.OUTPUT, pin, cell.getSiteInst()));
                    }
                }
            }

        } else {
            boolean isConst = net.getName() != null && net.getName().startsWith("GLOBAL_LOGIC");
            try {
                net.connect(cell, logical_pin);
            } catch (RuntimeException ex) {
                // nextpnr can name a LUT input that one net drives on several
                // physical inputs as a concatenation, e.g. "I1I0" = I1+I0 or
                // "I2I3" = I2+I3.  RapidWright has no such port; connect the net
                // to each constituent I<n> pin instead.
                java.util.regex.Matcher m =
                    java.util.regex.Pattern.compile("I\\d").matcher(logical_pin);
                boolean any = false;
                while (m.find()) {
                    try { net.connect(cell, m.group()); any = true; }
                    catch (RuntimeException ex2) { any = true; skipConst++; }
                }
                // Best-effort: an unresolvable const tie-off / already-claimed
                // LUT input (the SVS all-LUT netlist has many nextpnr VCC/GND
                // ties RapidWright can't route intra-site).  Static -> timing-
                // irrelevant.  Skip so the DCP builds for STA.
                if (!any) skipConst++;
            }
        }
    }

    public static Cell create_cell_custom(Design d, NextpnrCell nc) {
        Unisim unitype = Unisim.valueOf(nc.attrs.get("X_ORIG_TYPE"));
        String legal_name = nc.name.replace("/", "__");
        String fullname = legal_name.replace("$subcell$", "/");
        String basename = legal_name.split("\\$subcell\\$")[0];

        if (d.getTopEDIFCell().getCellInst(basename) == null) {
            String macrotype = nc.attrs.get("X_ORIG_MACRO_PRIM");
            if (d.getNetlist().getCell(macrotype) == null)
                d.getNetlist().getHDIPrimitivesLibrary().addCell(Design.getUnisimCell(Unisim.valueOf(macrotype)));
            Design.getUnisimCell(Unisim.valueOf(macrotype)).createCellInst(basename, d.getTopEDIFCell());
        }


        String[] bel = nc.attrs.get("NEXTPNR_BEL").split("/");
        // BEL attrs may be SITE/BEL or SITE/SITETYPE/BEL (IOB pads:
        // "IOB_X0Y77/IOB18/INBUF_DCIEN") -- use first + LAST components.
        com.xilinx.rapidwright.device.Site site = d.getDevice().getSite(bel[0]);
        com.xilinx.rapidwright.device.BEL rwbel = (site == null) ? null : site.getBEL(bel[bel.length - 1]);
        if (site == null || rwbel == null) {
            System.err.println("WARN: cannot resolve BEL '" + nc.attrs.get("NEXTPNR_BEL")
                    + "' for subcell " + nc.name + " (type " + nc.attrs.get("X_ORIG_TYPE")
                    + ") -- cell left unplaced");
            return null;
        }
        Cell c = d.createAndPlaceCell(null, fullname, unitype, site, rwbel);
        c.setBELFixed(true);
        c.setSiteFixed(true);
        // createAndPlaceCell(null, ...) makes a PHYSICAL-ONLY cell: no EDIF
        // backing, so Cell.getType() returns null.  RapidWright's (closed)
        // DCP writer looks every placed cell's type string up in its XDEF
        // string table — a null type NPEs the writer mid-write and TRUNCATES
        // the checkpoint (the "347KB DCP" failure).  Stamp the Unisim name.
        c.setType(unitype.name());
        return c;
   }

    public static void main(String[] args) throws FileNotFoundException {

        if (args.length < 3) {
            System.err.println("Usage: json2dcp <device> <design.json> <design.dcp>");
            System.err.println("   e.g json2dcp xczu2cg-sbva484-1-e top_routed.json top_routed.dcp");
            System.exit(1);
        }

        NextpnrDesign ndes = new NextpnrDesign();
        ndes.Import(new JsonParser().parse(new FileReader(args[1])).getAsJsonObject());
        reduce_shared_lut_pins(ndes);

        Design des = new Design("top", args[0]);

        // Load the static wire-name oracle.  Hard-fail if it is missing —
        // we have one and only one source of truth for tile/wire/PIP names.
        // Silent fallback to runtime node-search produced bitstreams that
        // loaded but didn't work on hardware (see [[feedback_clock_buffer_neuter]]).
        // Search order: (1) explicit env var XRAY_WIRE_ORACLE, (2) sibling
        // file ./oracle/<part>.oracle.txt.gz beside the JSON input.
        String oraclePath = System.getenv("XRAY_WIRE_ORACLE");
        if (oraclePath == null) {
            java.io.File jsonFile = new java.io.File(args[1]).getAbsoluteFile();
            String oracleLeaf = args[0] + ".oracle.txt.gz";
            // Walk up from the JSON's directory looking for an "oracle/" sibling.
            // Stop at filesystem root.  This catches both flat layouts
            // (JSON next to oracle/) and per-build subdirs (JSON in pass/,
            // oracle/ in the project dir).
            java.io.File cur = jsonFile.getParentFile();
            while (cur != null) {
                java.io.File cand = new java.io.File(new java.io.File(cur, "oracle"), oracleLeaf);
                if (cand.isFile()) { oraclePath = cand.getAbsolutePath(); break; }
                cur = cur.getParentFile();
            }
        }
        if (oraclePath == null) {
            System.err.println("ERROR: wire-name oracle not found.");
            System.err.println("       Set XRAY_WIRE_ORACLE or place "
                    + args[0] + ".oracle.txt.gz under ./oracle/ next to the JSON input.");
            System.err.println("       Generate with: java dev.fpga.rapidwright.BuildWireOracle "
                    + args[0] + " <out.oracle.txt.gz>");
            System.exit(1);
        }
        wireOracle = WireOracle.load(oraclePath);
        if (wireOracle == null) {
            System.err.println("ERROR: failed to load wire-name oracle from " + oraclePath);
            System.exit(1);
        }
        System.out.println("[wire-oracle] loaded " + oraclePath
                + " types=" + wireOracle.numTypes()
                + " tiles=" + wireOracle.numTiles()
                + " pips=" + wireOracle.numCatalogPIPs());

        HashMap<String, String> siteToPin = new HashMap<>();
        for (PackagePin p : des.getDevice().getActivePackage().getPackagePinMap().values())
            if (p != null && p.getSite() != null)
                siteToPin.put(p.getSite().getName(), p.getName());

        for (NextpnrCell nc : ndes.cells.values()) {
            // A cell that survived packing without X_ORIG_TYPE is a primitive
            // nextpnr handles internally but never told us how to re-create on
            // the RapidWright side — silently skipping it (the old behaviour)
            // produced DCPs that loaded cleanly but had core cells missing
            // (BUFG, etc.), leaving downstream nets driverless.  Better to
            // fail loudly and add the missing X_ORIG_TYPE upstream in nextpnr
            // than to ship a broken bitstream.  Constants are the only
            // legitimate skip — those route via Net.GLOBAL_LOGIC0/1.
            if (!nc.attrs.containsKey("X_ORIG_TYPE")) {
                if (nc.type.equals("PSEUDO_GND") || nc.type.equals("PSEUDO_VCC")
                    || nc.type.equals("$PACKER_GND_DRV") || nc.type.equals("$PACKER_VCC_DRV")) {
                    continue;
                }
                throw new RuntimeException("json2dcp: cell '" + nc.name + "' of type '"
                    + nc.type + "' has no X_ORIG_TYPE attribute — nextpnr's packer "
                    + "needs to tag this cell so RapidWright can re-create it.  "
                    + "Aborting rather than silently dropping the cell.");
            }
            // nextpnr emits "IOB_PAD" on UltraScale but plain "PAD" on 7-series
            // for the package-pin BEL — neither maps to a Unisim primitive, so
            // we skip them (the PAD bel is implied by the IBUF/OBUF placement).
            if (nc.type.equals("IOB_PAD") || nc.type.equals("PAD")) {
                nc.rwCell = null;
            } else {
                String origType = nc.attrs.get("X_ORIG_TYPE");
                Unisim unitype = Unisim.valueOf(origType);

                if (unitype == Unisim.FDRE_1)
                    unitype= Unisim.FDRE;
                else if (unitype == Unisim.FDSE_1)
                    unitype = Unisim.FDSE;
                else if (unitype == Unisim.FDPE_1)
                    unitype = Unisim.FDPE;
                else if (unitype == Unisim.FDCE_1)
                    unitype = Unisim.FDCE;

                if (nc.name.contains("$subcell$")) {
                    nc.rwCell = create_cell_custom(des, nc);
                    if (nc.rwCell == null)
                        continue;
                } else {
                    // nextpnr's IO packer wraps user-instantiated IBUFs / OBUFs
                    // / IBUFDS in synthetic "$intcell$XXX" cells.  Strip the
                    // suffix so the EDIF instance name matches what Vivado
                    // would emit (and what the source RTL named); otherwise
                    // every IO cell carries the wrapper name through to the
                    // emitted EDIF and downstream constraint matching fails.
                    String edifName = nc.name.replace("/", "__")
                                             .replaceFirst("\\$intcell\\$[A-Z]+$", "");
                    // nextpnr emits NEXTPNR_BEL as "SITE/BEL" on most architectures
                    // but as "SITE/SITETYPE/BEL" on 7-series IOB sites where the
                    // site has multiple type personalities (IOB18 vs IOB33, or
                    // IOB18M vs IOB18S for LVDS diff-pair masters/slaves).
                    String nb = nc.attrs.get("NEXTPNR_BEL");
                    // BUFG fix: nextpnr packs BUFG → BUFGCTRL and emits
                    // BEL=BUFGCTRL_X0Y16/BUFGCTRL.  Vivado puts a logical BUFG
                    // on the BUFG sub-BEL of the BUFGCTRL site instead — so
                    // when X_ORIG_TYPE=BUFG, retarget the BEL string to the
                    // BUFG sub-BEL.  Vivado's reference DCP shows
                    // "BEL=BUFG.BUFG" for a logical BUFG; matching that here
                    // makes the json2dcp DCP and the Vivado DCP agree on
                    // both the physical site AND the sub-BEL.
                    if (unitype == Unisim.BUFG && nb.endsWith("/BUFGCTRL")) {
                        nb = nb.substring(0, nb.length() - "/BUFGCTRL".length()) + "/BUFG";
                    }
                    String[] belParts = nb.split("/");
                    if (belParts.length == 3) {
                        Site s = des.getDevice().getSite(belParts[0]);
                        if (s == null) {
                            throw new RuntimeException("cannot find site " + belParts[0]
                                + " (cell " + nc.name + " type " + origType + ")");
                        }
                        BEL b = s.getBEL(belParts[2]);
                        if (b == null) {
                            throw new RuntimeException("cannot resolve BEL for " + nb
                                + " (cell " + nc.name + " type " + origType + ")");
                        }
                        SiteTypeEnum variant = SiteTypeEnum.valueOf(belParts[1]);
                        if (variant != s.getSiteTypeEnum()
                                && des.getSiteInstFromSite(s) == null) {
                            // Pre-allocate the SiteInst with the named variant
                            // so subsequent operations on this site see the
                            // right type.
                            des.createSiteInst(s.getName(), variant, s);
                        }
                        // RapidWright's createAndPlaceCell rejects IBUFDS on
                        // 7-series IOB18M sites (its Unisim.IBUFDS BEL-fit table
                        // is UltraScale+ HPIOB only).  In Vivado, an IBUFDS on
                        // xc7 lives physically as an INBUF_DCIEN BEL configured
                        // for differential reception — same BEL footprint as a
                        // single-ended IBUF.  Place as Unisim.IBUF and reattach
                        // the IBUFDS EDIF cell type after-the-fact so the EDIF
                        // half carries the right primitive name and the physical
                        // half lands on the diff-receiver BEL.
                        Unisim placeAs = unitype;
                        boolean restoreType = false;
                        if (unitype == Unisim.IBUFDS && variant == SiteTypeEnum.IOB18M) {
                            placeAs = Unisim.IBUF;
                            restoreType = true;
                        }
                        try {
                            nc.rwCell = des.createAndPlaceCell(des.getTopEDIFCell(),
                                    edifName, placeAs, s, b);
                        } catch (RuntimeException re) {
                            throw new RuntimeException("createAndPlaceCell failed: name="
                                + nc.name + " origType=" + origType + " unitype=" + placeAs
                                + " site=" + s.getName() + " siteType=" + s.getSiteTypeEnum()
                                + " variant=" + variant + " bel=" + b.getName(), re);
                        }
                        if (restoreType) {
                            // Re-stamp the EDIF cell's reference so the emitted
                            // EDIF says IBUFDS — but pull the IBUFDS definition
                            // from the design's HDI primitives library, not the
                            // raw unisim master.  Otherwise the cellref lands in
                            // library 'work' and Vivado's read_edif throws
                            // "Cannot find cell 'IBUFDS' view 'netlist' in
                            // library 'work'".
                            EDIFLibrary hdiLib = des.getNetlist().getHDIPrimitivesLibrary();
                            EDIFCell ibufdsCell = hdiLib.getCell("IBUFDS");
                            if (ibufdsCell == null) {
                                // Copy in the unisim template so it lives in the
                                // primitives library going forward.
                                EDIFCell unisimRef = Design.getUnisimCell(Unisim.IBUFDS);
                                ibufdsCell = new EDIFCell(hdiLib, unisimRef, unisimRef.getName());
                            }
                            nc.rwCell.getEDIFCellInst().setCellType(ibufdsCell);
                        }
                    } else {
                        // 2-segment BEL ("SITE/BEL").  Direct placement — the
                        // BUFG retarget happened above (BEL string was rewritten
                        // BUFGCTRL → BUFG so this call lands on the sub-BEL
                        // Vivado uses), so no Unisim substitution is needed.
                        try {
                            nc.rwCell = des.createAndPlaceCell(edifName, unitype, nb);
                        } catch (RuntimeException re) {
                            throw new RuntimeException("createAndPlaceCell (2-seg) failed: name="
                                + nc.name + " origType=" + origType + " unitype=" + unitype
                                + " bel=" + nb, re);
                        }
                    }
                }


                Map<String, String> map = nc.rwCell.getPinMappingsP2L();
                Object[] pins = map.keySet().toArray();

                // X_ORIG_PORT_<phys> records nextpnr's REPACK mapping, and this
                // rebuilds the cell's physical->logical map from it.  But a cell
                // nextpnr never repacked carries NO X_ORIG_PORT at all -- its type
                // already IS the Unisim.  That is every hard block: GTXE2_CHANNEL,
                // GTXE2_COMMON, IBUFDS_GTE2.  Clearing the defaults and then
                // finding nothing to re-add left them with ZERO pin mappings:
                // measured on ethmin, 539 connected CHANNEL ports and 23 COMMON
                // ports became 0 and 0.  Vivado then reports ~22 of
                //   [DRC PDCN-*] <pin>_connects: On GTXE2_CHANNEL_X1Y1, pin <pin>
                //                                must be connected
                // plus [DRC RTSTAT-4] 32 nets with no routable drivers (the GT's
                // own outputs -- rxdata_rec[15:0], gtxe2_i_n_*, rxoutclk, txoutclk),
                // and a placed hard block with no pin mappings is exactly the null
                // logical net that segfaults HDPYRoutedSiteBuilder::newRoutedSite.
                //
                // For such a cell RapidWright's DEFAULT map is already right --
                // physical pin name equals logical pin name, bus indices included --
                // so the correct action is to leave it alone, not to rebuild it.
                boolean hasRepack = false;
                for (String k : nc.attrs.keySet())
                    if (k.startsWith("X_ORIG_PORT_")) { hasRepack = true; break; }

                if (unitype != Unisim.PS8 && hasRepack) {
                    for (Object p : pins)
                        nc.rwCell.removePinMapping(p.toString());
                    for (NextpnrCellPort p : nc.ports.values()) {
                        if (!nc.attrs.containsKey("X_ORIG_PORT_" + p.name))
                            continue;
                        String[] orig_ports = nc.attrs.get("X_ORIG_PORT_" + p.name).split(" ");

                        for (String orig : orig_ports)
                            if (!orig.trim().isEmpty()) {
                                nc.rwCell.addPinMapping(p.name, orig.trim());

                            }
                    }

                } else if (unitype != Unisim.PS8) {
                    // Hard block (no X_ORIG_PORT): keep RapidWright's default map
                    // for now.  It cannot be filtered HERE -- nets are connected
                    // later in main(), so the test that matters ("does this
                    // logical port actually have a net?") has no answer yet.
                    // prune_dangling_pinmaps() below does it once the EDIF is
                    // complete.
                    keptDefaultPinmap++;
                    keptDefaultPins += pins.length;
                }

                for (Map.Entry<String, String> param : nc.params.entrySet()) {
                    String value = param.getValue();
                    if (param.getKey().equals("INIT")) {
                        switch(unitype) {
                            case LUT1:
                                value = fixup_init(value, 1<<1);
                                break;
                            case LUT2:
                                value = fixup_init(value, 1<<2);
                                break;
                            case LUT3:
                                value = fixup_init(value, 1<<3);
                                break;
                            case LUT4:
                                value = fixup_init(value, 1<<4);
                                break;
                            case LUT5:
                                value = fixup_init(value, 1<<5);
                                break;
                            case LUT6:
                                value = fixup_init(value, 1<<6);
                                break;
                            case FDRE:
                            case FDSE:
                            case FDCE:
                            case FDPE:
                                value = "1'b" + value.substring(value.length() - 1);
                                break;
                        }
                    } else if (unitype == Unisim.RAMB18E2 || unitype == Unisim.RAMB36E2) {
                        if (param.getKey().equals("INIT_A") || param.getKey().equals("INIT_B") || param.getKey().startsWith("SRVAL_")) {
                            value = fixup_init(value, unitype == Unisim.RAMB36E2 ? 36 : 18);
                        } else if (param.getKey().startsWith("INIT_") || param.getKey().startsWith("INITP_")) {
                            value = fixup_init(value, 256);
                        }
                    }
                    if (param.getKey().startsWith("IS_") && param.getKey().endsWith("_INVERTED")) {
                        value = fixup_init(value, 1).replace("h", "b");
                    }
                    nc.rwCell.addProperty(param.getKey(), value);
                    //System.out.println(param.getKey() + " = " + param.getValue());
                }

                if (nc.rwCell.getType() != null && nc.rwCell.getType().equals("RAMD64E")) {
                    // FIXME: move to nextpnr
                    nc.rwCell.addProperty("RAM_ADDRESS_MASK", "2'b11");
                    nc.rwCell.addProperty("RAM_ADDRESS_SPACE", "2'b11");
                }

                // Unisim parameter defaults that nextpnr's JSON does not carry.
                // Absent from the netlist because they were never set in the
                // source and yosys emits only what it was given -- but Vivado
                // demands them for a BITSTREAM, not merely for analysis:
                //   [DRC ADEF-499] Cell ... has no value for attribute
                //   SS_MOD_PERIOD. For proper hardware operation, ...
                // On ibex that was the ONLY thing left between a repaired
                // checkpoint and a bitstream: 9 errors over 5 attributes.
                // Filled in only when absent, so a real value always wins.
                // RapidWright exposes no parameter-default table (only
                // CellPinStaticDefaults, which is about pins), hence the list.
                String oty = nc.attrs.getOrDefault("X_ORIG_TYPE", nc.type);
                if (oty != null) {
                    if (oty.equals("MMCME2_ADV") || oty.equals("PLLE2_ADV")
                            || oty.equals("PLLE2_BASE")) {
                        default_param(nc, "SS_MOD_PERIOD", "10000");
                        // A PLLE2_BASE in the source names only the outputs it
                        // uses, and nextpnr's JSON carries only what was named.
                        // Vivado demands the rest explicitly for a bitstream:
                        //   [DRC ADEF-841..845] no value for CLKOUT1..5_DIVIDE
                        //   [DRC AVAL-79,82..87] CLKIN2_PERIOD / CLKOUTn_
                        //       DUTY_CYCLE / PHASE have unexpected values
                        // 14 errors on the SERV SoC, all from one PLL.
                        default_param(nc, "CLKIN2_PERIOD", "0.000");
                        for (int k = 0; k <= 5; k++) {
                            if (k > 0) default_param(nc, "CLKOUT" + k + "_DIVIDE", "1");
                            default_param(nc, "CLKOUT" + k + "_DUTY_CYCLE", "0.500");
                            default_param(nc, "CLKOUT" + k + "_PHASE", "0.000");
                        }
                    } else if (oty.startsWith("RAMB18")) {
                        for (String p : new String[]{"INIT_A", "INIT_B", "SRVAL_A", "SRVAL_B"})
                            default_param(nc, p, "18'h00000");
                    } else if (oty.startsWith("RAMB36")) {
                        for (String p : new String[]{"INIT_A", "INIT_B", "SRVAL_A", "SRVAL_B"})
                            default_param(nc, p, "36'h000000000");
                    }
                }

                if (nc.type.startsWith("IOB_"))
                    nc.rwCell.setSiteFixed(true);
            }

        }

        EDIFCell top = des.getNetlist().getTopCell();
        EDIFNet edif_gnd = EDIFTools.getStaticNet(NetType.GND, top, des.getNetlist());
        EDIFNet edif_vcc = EDIFTools.getStaticNet(NetType.VCC, top, des.getNetlist());
        // RapidWright >= 2022 dropped Net(String, EDIFNet) in favour of
        // Net(String, EDIFHierNet). Wrap each EDIFNet under the top hier
        // cell instance to bridge the API change.
        EDIFHierCellInst topInst = des.getNetlist().getTopHierCellInst();

        for (NextpnrNet nn : ndes.nets.values()) {
            //System.out.println("create net " + nn.name);
            Net n;
            if (nn.name.equals("$PACKER_VCC_NET")) {
                n = new Net("GLOBAL_LOGIC1", new EDIFHierNet(topInst, edif_vcc));
                des.addNet(n);
            } else if (nn.name.equals("$PACKER_GND_NET")) {
                n = new Net("GLOBAL_LOGIC0", new EDIFHierNet(topInst, edif_gnd));
                des.addNet(n);
            } else if (nn.name.endsWith("$const") && nn.driver == null) {
                // nextpnr leaves hard-block inputs it never drove as their own
                // undriven nets (RAMB address bits above the used width, unused
                // data inputs) -- 96 of them on SERV, 90 on ethmin, and Vivado
                // rejects the design for it: [DRC NDRV-1] Driverless Nets.
                // They are constants by construction, so make the net BE the
                // GND net, exactly as $PACKER_GND_NET is handled above.  Tying
                // the sink PINS to GND instead was tried and left the
                // checkpoint unopenable: connect_log_and_phys creates physical
                // site pins that collide with the hard block's site routing.
                n = des.getGndNet();
            } else if (nn.name.contains("$subnet$")) {
                n = new Net(escape_name(nn.name), (EDIFHierNet)null);
                des.addNet(n);
            } else {
                EDIFNet en = new EDIFNet(escape_name(nn.name), des.getTopEDIFCell());
                n = new Net(escape_name(nn.name), new EDIFHierNet(topInst, en));
                des.addNet(n);
                // escape_name() rewrites '/' to '_' because RapidWright uses '/'
                // as its hierarchy separator, so a flat net name containing one
                // would be misread as hierarchical.  That is necessary but
                // DESTRUCTIVE: eth...pcs_pma_block_i/transceiver_inst/rxdata_rec[13]
                // arrives in Vivado as ..._transceiver_inst_rxdata_rec[13], and
                // nothing in the DCP records which underscores used to be
                // slashes.  Every downstream join back to the nextpnr netlist
                // (timing calibration, database diffs) then has to canonicalise
                // by stripping ALL separators, which is lossy and can collide.
                // Carry the real name so the mapping stays exact.
                if (!escape_name(nn.name).equals(nn.name))
                    en.addProperty("NEXTPNR_NAME", nn.name);
            }
            nn.rwNet = n;
            // A net with a driver but NO users has nothing to route to, and
            // Vivado's own database does not carry a pin for it: golden ethmin's
            // CARRY4 cells have logical pins [CI,CYINIT,DI,O,S] where ours have
            // those plus CO[0..3], because yosys leaves every unused carry-out
            // connected and we faithfully made a source pin for each.  That is
            // where the NOLOADS nets come from -- 226 on SERV, 534 on ibex, 903
            // on picosoc, 1264 on ethmin -- so skip the driver connection and
            // the net stays as inert in the DCP as it is in the design.
            if (nn.driver != null && nn.driver.cell.rwCell != null && !nn.users.isEmpty()) {
                if (nn.driver.cell.attrs.containsKey("X_ORIG_PORT_" + nn.driver.name)) {
                    //System.out.println("connect " + n.getName() + " <- " + nn.driver.cell.name + "." + nn.driver.name);
                    connect_log_and_phys(n, nn.driver.cell.rwCell, nn.driver.cell.attrs.get("X_ORIG_PORT_" + nn.driver.name));
                } else {
                    // Hard blocks that nextpnr never repacks (GTXE2_CHANNEL,
                    // GTXE2_COMMON, IBUFDS_GTE2) carry NO X_ORIG_PORT at all --
                    // their type already IS the Unisim.  This used to `continue`,
                    // which skipped not only the driver but every USER of the net
                    // below, leaving 154 GT-driven nets (incl. the TXOUTCLK that
                    // gt_txoutclk is created on) with a physical source pin but a
                    // logical net with no driver.  Vivado's delay estimator then
                    // derefs that null logical net and segfaults in
                    // HDPYRoutedSiteBuilder::newRoutedSite on report_timing.
                    // Same fallback the user branch below already uses, but the
                    // name has to be resolved against the EDIF cell first: the
                    // GT's bus pins arrive as RXDATA13, not RXDATA[13].
                    connect_log_and_phys(n, nn.driver.cell.rwCell,
                            resolve_edif_pin(nn.driver.cell.rwCell, nn.driver.name));
                }
            }
            if (debug_net(nn.name)) {
                System.out.println("[dbg] net " + nn.name
                    + "  driver=" + (nn.driver == null ? "NONE"
                        : nn.driver.cell.name + "." + nn.driver.name
                          + " rwCell=" + (nn.driver.cell.rwCell == null ? "NULL" : "ok")
                          + " xorig=" + nn.driver.cell.attrs.containsKey("X_ORIG_PORT_" + nn.driver.name))
                    + "  users=" + nn.users.size());
                for (NextpnrCellPort u : nn.users)
                    System.out.println("[dbg]    user " + u.cell.name + "." + u.name
                        + " type=" + u.cell.type
                        + " rwCell=" + (u.cell.rwCell == null ? "NULL" : u.cell.rwCell.getSiteName())
                        + " xorig=" + u.cell.attrs.get("X_ORIG_PORT_" + u.name));
            }
            for (NextpnrCellPort usr : nn.users) {
                if (usr.cell.rwCell != null) {
                    if (usr.cell.attrs.containsKey("X_ORIG_PORT_" + usr.name)) {
                        String[] orig_ports = usr.cell.attrs.get("X_ORIG_PORT_" + usr.name).split(" ");
                        for (String orig : orig_ports) {
                            connect_log_and_phys(n, usr.cell.rwCell, orig);
                            //n.connect(usr.cell.rwCell, orig);
                        }
                    } else {
                        // NEVER-REPACKED CELLS (hard blocks) NEED THE LOGICAL EDGE TOO.
                        //
                        // A cell nextpnr never repacked carries no X_ORIG_PORT at
                        // all -- its type already IS the Unisim.  This branch used
                        // to give such a sink ONLY a physical site pin, so the GT's
                        // inputs were wired in the physical netlist and nowhere in
                        // the EDIF: measured on ethmin, just 34 of 744 GT pin
                        // mappings had a live logical net.  Vivado then walks the
                        // pin map, hits the 710 with none, and segfaults in
                        // HDPYRoutedSiteBuilder::newRoutedSite -- and reports ~22
                        // PDCN "pin must be connected" for good measure.
                        //
                        // The driver branch above already learned this (it used to
                        // `continue` and orphan the whole net); this is the same
                        // fix for SINKS.  Gate on the cell having NO X_ORIG_PORT_*
                        // anywhere, which is exactly "never repacked" -- a packed
                        // LUT has them for its other pins, so the fractured-LUT
                        // A6-tie case below still behaves as before.
                        boolean neverRepacked = true;
                        for (String k : usr.cell.attrs.keySet())
                            if (k.startsWith("X_ORIG_PORT_")) { neverRepacked = false; break; }
                        // Only when the EDIF really has that port.  resolve_edif_pin
                        // passes a name through unchanged when it cannot improve it,
                        // and connect_log_and_phys accepts it silently -- the bad
                        // name then surfaces only when Vivado READS the checkpoint:
                        //   [EDIF 20-86] Cannot find port 'PCSRSVDIN' on instance
                        //   ... of cell 'GTXE2_CHANNEL'
                        // PCSRSVDIN is a BUS, and a bus referenced without an index
                        // is not a port.  Check before connecting, not after.
                        if (neverRepacked) {
                            String edifPin = resolve_edif_pin(usr.cell.rwCell, usr.name);
                            EDIFCellInst ci = usr.cell.rwCell.getEDIFCellInst();
                            boolean portOk = false;
                            if (ci != null && ci.getCellType() != null && edifPin != null) {
                                int br = edifPin.indexOf('[');
                                String base = (br > 0 && edifPin.endsWith("]"))
                                        ? edifPin.substring(0, br) : edifPin;
                                EDIFPort ep = ci.getCellType().getPort(base);
                                // a bus needs an index; a scalar must not have one
                                portOk = ep != null && (ep.isBus() == (br > 0));
                                // BUS MEMBERS ARE OFF BY DEFAULT.  Connecting them
                                // makes Vivado reject the EDIF outright at read time:
                                //   [EDIF 20-86] Cannot find port 'PCSRSVDIN' on
                                //   instance ... of cell 'GTXE2_CHANNEL'
                                // even though the cell DOES declare PCSRSVDIN[15:0]
                                // and connect_log_and_phys_impl takes the
                                // createPortInst(bus, index, inst) path for names
                                // ending in ']'.  So the member reference is being
                                // emitted in a form Vivado will not read, and that is
                                // a separate bug from the missing connections.
                                // Scalars alone already cover most of the PDCN
                                // complaints (CPLLRESET, DRPCLK, GTRXRESET, RXUSRCLK,
                                // TXUSRCLK...).  J2D_HARD_SINK_BUS=1 to include buses.
                                if (br > 0 && System.getenv("J2D_HARD_SINK_BUS") == null)
                                    portOk = false;
                            }
                            if (portOk) {
                                try {
                                    connect_log_and_phys(n, usr.cell.rwCell, edifPin);
                                    hardSinkLogical++;
                                    continue;
                                } catch (RuntimeException ex) {
                                    hardSinkFailed++;   // fall through to physical-only
                                }
                            } else {
                                hardSinkNoPort++;
                            }
                        }
                        // Special case where no logical pin exists, mostly where we tie A6 high for a fractured LUT
                        BELPin belPin = usr.cell.rwCell.getBEL().getPin(usr.name);
                        if (belPin != null && belPin.getConnectedSitePinName() != null) {
                            // RapidWright >= 2022 dropped the boolean isOutput arg from
                            // Net.createPin — direction is inferred from the site pin.
                            String sitePinName = belPin.getConnectedSitePinName();
                            SiteInst siteInst = usr.cell.rwCell.getSiteInst();
                            // Virtex-7 SLICE packing puts both halves of a LUT pair
                            // (e.g. A5LUT and A6LUT) into the same slot; their lower
                            // address inputs are the same physical wire, so nextpnr
                            // can legitimately list the same site-pin twice — once
                            // per LUT-cell user.  RapidWright's Net.createPin throws
                            // "already has a pin named X" if the SiteInst already
                            // has that pin attached; skip the second add since both
                            // halves drive the same net by construction.
                            if (siteInst.getSitePinInst(sitePinName) == null) {
                                n.createPin(sitePinName, siteInst);
                            }
                        }
                    }

                }
            }
        }

        for (NextpnrNet nn : ndes.nets.values()) {
            Net n = nn.rwNet;

            HashSet<String> inverted_wires = new HashSet<>();
            for (NextpnrCellPort sink : nn.users) {
                if (sink.cell.attrs.containsKey("X_ORIG_PORT_" + sink.name)) {
                    String[] orig_ports = sink.cell.attrs.get("X_ORIG_PORT_" + sink.name).split(" ");
                    if (sink.cell.rwCell == null)
                        continue;
                    for (String orig : orig_ports) {
                        if (!sink.cell.params.getOrDefault("IS_" + orig + "_INVERTED", "0").endsWith("1"))
                            continue;
                        BELPin sinkpin = sink.cell.rwCell.getBEL().getPin(sink.name);
                        if (sinkpin == null)
                            continue;
                        inverted_wires.add(sink.cell.rwCell.getSiteName() + "/" + sinkpin.getSiteWireName());
                        //System.out.println(sink.cell.rwCell.getSiteName() + "/" + sinkpin.getSiteWireName());
                    }
                }
            }

            String[] routing = nn.attrs.get("ROUTING").split(";");
            // A net must never contain the same PIP twice.  nextpnr emits the
            // carry cascade as four hops -- CARRY4_CO3 -> COUT -> tile COUT ->
            // tile CIN -> CIN -- two of which resolve to the SAME physical pip
            // (CLBLM_M_COUT -> CLBLM_M_COUT_N), so we added it twice.  Vivado
            // calls that a "partial route conflict" and refuses bitgen: 104 on
            // picosoc, 71 on ibex, every one of them a CARRY4 CO[3].
            HashSet<PIP> seenPips = new HashSet<>();
            int pipImported = 0, pipDroppedSiteWire = 0, pipDroppedSitePip = 0,
                pipDroppedLookup = 0, pipDroppedParse = 0,
                pipNodeTraversal = 0, pipOracleHit = 0, pipOracleReverseBidir = 0,
                pipOracleNodeReject = 0, pipDroppedDup = 0;
            for (int i = 0; i < (routing.length-2); i+=3) {
                String wire = routing[i];
                String pip = routing[i+1];

                if (pip.isEmpty() || pip.trim().isEmpty())
                    continue;
                if (pip.startsWith("SITEPIP")) { pipDroppedSitePip++; continue; }
                if (pip.contains("SITEWIRE")) {
                    // The simplest and highest-value SITEWIRE case is
                    //   tile-wire->SITEWIRE/<site>/<sitePinName>
                    // which means the inter-tile route arrives at a SITE
                    // PIN — without registering it, Vivado sees the route
                    // terminate at the tile-edge wire and reports ANTENNAS
                    // ("doesn't reach the BEL pin").  Other shapes
                    // (intra-site SITEWIRE->SITEWIRE, or SITEWIRE->tile-wire
                    // for output paths) need RapidWright SitePIP / site-net
                    // configuration; defer those for now and just count.
                    int arrow = pip.indexOf("->");
                    if (arrow > 0) {
                        String left = pip.substring(0, arrow);
                        String right = pip.substring(arrow + 2);
                        boolean leftIsSite  = left.startsWith("SITEWIRE/");
                        boolean rightIsSite = right.startsWith("SITEWIRE/");
                        // tile-wire -> SITEWIRE/site/pin
                        if (!leftIsSite && rightIsSite) {
                            String[] parts = right.split("/", 3);
                            String[] leftParts = left.split("/", 2);
                            if (parts.length == 3) {
                                SiteInst si = des.getSiteInstFromSiteName(parts[1]);
                                if (si != null) {
                                    // nextpnr's chipdb has known wire->pin
                                    // ordering bugs for SLICE input pins
                                    // (e.g. it claims CLBLM_M_A2 feeds A1
                                    // when RapidWright's authoritative
                                    // mapping says A2).  When the left side
                                    // is a real tile wire, ask RapidWright
                                    // which site pin actually receives it
                                    // and override nextpnr's claim if they
                                    // disagree.  Without this the
                                    // SitePinInst lands on the wrong pin
                                    // and Vivado reports an antenna.
                                    String pinName = parts[2];
                                    if (leftParts.length == 2) {
                                        Site rwSite = si.getSite();
                                        Tile srcTile = des.getDevice().getTile(leftParts[0]);
                                        if (rwSite != null && srcTile != null) {
                                            for (int pi = 0; pi < rwSite.getSitePinCount(); pi++) {
                                                String pname = rwSite.getPinName(pi);
                                                String tw = rwSite.getTileWireNameFromPinName(pname);
                                                if (tw != null && tw.equals(leftParts[1])
                                                        && srcTile.equals(rwSite.getTile())) {
                                                    if (!pname.equals(parts[2])) {
                                                        // Override.  Quiet log
                                                        // for first few so we
                                                        // can audit.
                                                        if (pipImported < 32)
                                                            System.out.println("  [pin-fix] "
                                                                + parts[1] + ": nextpnr said "
                                                                + parts[2] + " but RW says "
                                                                + pname + " for tile-wire "
                                                                + leftParts[1]);
                                                    }
                                                    pinName = pname;
                                                    break;
                                                }
                                            }
                                        }
                                    }
                                    if (si.getSitePinInst(pinName) == null) {
                                        try {
                                            n.createPin(pinName, si);
                                            pipImported++;
                                            continue;
                                        } catch (RuntimeException ex) {
                                            // pin name not legal on this
                                            // SiteInst type
                                        }
                                    } else {
                                        // pin already exists on this net
                                        pipImported++;
                                        continue;
                                    }
                                }
                            }
                        }
                        // Intra-site SITEWIRE->SITEWIRE is the slice output
                        // path.  The OUTMUX/USED SitePIPs it implies are ALREADY
                        // bound elsewhere in this importer (verified: binding
                        // them here changed the DCP's sitepip count not at all,
                        // 6929 before and after), so nothing is missing.
                        //
                        // The 5 nets Vivado still rejects on picosoc are not an
                        // import defect: nextpnr routes a LUT6's O6 out through
                        // the slice's xMUX
                        //   SITEWIRE/SLICE_X0Y66/D6LUT_O6 -> .../DMUX
                        // and Vivado will not have it -- route_design -preserve
                        // moves the source to the DIRECT pin (DMUX -> D) and
                        // reroutes.  Its own exemplar (build/exemplar) shows the
                        // convention: xOUTMUX carries O5, xUSED carries O6.
                        // Fixing this belongs in nextpnr's router, not here.
                        // NOTE: other intra-site SITEWIRE->SITEWIRE shapes -- "SITEWIRE/SLICE_X0Y66/
                        // D6LUT_O6 -> .../DMUX" means DOUTMUX selects O6 -- and
                        // leaving it unbound is why a net that leaves its slice
                        // via xMUX shows up as a partial route / antenna
                        // (4 nets on picosoc).  Binding the SitePIP whose input
                        // and output sitewires match was TRIED and makes Vivado
                        // SEGFAULT on open_checkpoint: matching on sitewire
                        // names alone picks a mux on the wrong BEL, or one that
                        // conflicts with a cell already placed there.  It needs
                        // the BEL identified from the driving cell, not a name
                        // search.  Counted, not bound, until then.
                        // SITEWIRE/site/pin -> tile-wire  (output, e.g. IOB.O)
                        if (leftIsSite && !rightIsSite) {
                            String[] parts = left.split("/", 3);
                            if (parts.length == 3) {
                                SiteInst si = des.getSiteInstFromSiteName(parts[1]);
                                if (si != null && si.getSitePinInst(parts[2]) == null) {
                                    try {
                                        n.createPin(parts[2], si);
                                        pipImported++;
                                        continue;
                                    } catch (RuntimeException ex) {
                                        // pin name not legal on this SiteInst type
                                    }
                                }
                            }
                        }
                        // SITEWIRE/site/src -> SITEWIRE/site/dst  (intra-site
                        // SitePIP selection — e.g. AOUTMUX:A5Q selects FF.Q as
                        // the AMUX site-pin source).  Without binding these,
                        // the cell pin and the site pin are disconnected and
                        // Vivado reports ANTENNAS post-route_design even
                        // though every inter-site PIP is correct.  Mirror the
                        // existing SITEPIP handler: addSitePIP plus a
                        // routeIntraSiteNet that stitches the BEL output
                        // driving the input sitewire to the BEL input receiving
                        // the output sitewire.
                        if (leftIsSite && rightIsSite) {
                            String[] L = left.split("/", 3);
                            String[] R = right.split("/", 3);
                            if (L.length == 3 && R.length == 3 && L[1].equals(R[1])) {
                                SiteInst si = des.getSiteInstFromSiteName(L[1]);
                                if (si != null) {
                                    SitePIP bound = null;
                                    for (BEL bel : si.getBELs()) {
                                        if (bel.getBELClass() != BELClass.RBEL) continue;
                                        for (BELPin bp : bel.getPins()) {
                                            if (!bp.isInput()) continue;
                                            String swn = bp.getSiteWireName();
                                            if (swn == null || !swn.equals(L[2])) continue;
                                            for (SitePIP cand : bp.getSitePIPs()) {
                                                BELPin out = cand.getOutputPin();
                                                if (out == null) continue;
                                                String oswn = out.getSiteWireName();
                                                if (oswn != null && oswn.equals(R[2])) {
                                                    bound = cand;
                                                    break;
                                                }
                                            }
                                            if (bound != null) break;
                                        }
                                        if (bound != null) break;
                                    }
                                    if (bound != null) {
                                        // Stitch the intra-site net from the
                                        // BEL output driving src sitewire to
                                        // the BEL input on dst sitewire — the
                                        // existing SITEPIP handler does the
                                        // same, and without it Vivado sees the
                                        // SitePIP on but the data path between
                                        // it and the cell pin not bound.
                                        BELPin startPin = null;
                                        for (BEL other : si.getBELs())
                                            for (BELPin p : other.getPins())
                                                if (p.isOutput()) {
                                                    String swn = p.getSiteWireName();
                                                    if (swn != null && swn.equals(L[2]))
                                                        startPin = p;
                                                }
                                        if (startPin != null) {
                                            for (BEL other : si.getBELs())
                                                for (BELPin p : other.getPins())
                                                    if (p.isInput()) {
                                                        String swn = p.getSiteWireName();
                                                        if (swn != null && swn.equals(R[2]))
                                                            si.routeIntraSiteNet(n, startPin, p);
                                                    }
                                        }
                                        si.addSitePIP(bound);
                                        pipImported++;
                                        continue;
                                    }
                                }
                            }
                        }
                    }
                    pipDroppedSiteWire++;
                    continue;
                }
                // Two pip encodings exist in nextpnr-xilinx output:
                //   old: "TILE/SRC_IDX.DST_IDX"           (integer indices)
                //   new: "SRC_TILE/SRC_WIRE->DST_TILE/DST_WIRE"  (named wires)
                try {
                    if (pip.contains("->")) {
                        // Named-wire form.  nextpnr models multi-tile node
                        // traversals as PIPs (because its router thinks in
                        // wire-pip-wire steps); RapidWright models the same
                        // physical signal as a single Node spanning multiple
                        // tiles with no PIP between them.  When PIP lookup
                        // fails on (srcTile/srcWire → dstTile/dstWire), check
                        // whether srcWire and dstWire resolve to the SAME
                        // node — if so, the route is implicitly connected
                        // through the node and we count it as success
                        // without adding anything.
                        String[] sides = pip.split("->", 2);
                        String[] srcS = sides[0].split("/", 2);
                        String[] dstS = sides[1].split("/", 2);
                        Device dev = des.getDevice();
                        Tile srcTile = (srcS.length == 2) ? dev.getTile(srcS[0]) : null;
                        Tile dstTile = (dstS.length == 2) ? dev.getTile(dstS[0]) : null;
                        // First try inside the destination tile (legacy path).
                        Tile dt = dstTile;
                        Integer dstIdx = (dt != null) ? dt.getWireIndex(dstS[1]) : null;
                        Integer srcIdx = (dt != null) ? dt.getWireIndex(srcS[1]) : null;
                        if (dt == null || dstIdx == null || srcIdx == null) {
                            // Try the source tile.
                            if (srcTile != null) {
                                srcIdx = srcTile.getWireIndex(srcS[1]);
                                dstIdx = srcTile.getWireIndex(dstS[1]);
                                if (srcIdx != null && dstIdx != null) {
                                    dt = srcTile;
                                }
                            }
                        }
                        PIP p = (dt != null && srcIdx != null && dstIdx != null)
                                ? dt.getPIP(srcIdx, dstIdx) : null;
                        // This legacy path looks BOTH wire names up in the
                        // destination tile.  Wire names repeat in every INT
                        // tile, so for a hop that spans tiles it can return a
                        // real pip that is not the one nextpnr meant -- see
                        // pip_matches_nodes() for the case that cost a day.
                        if (p != null && !pip_matches_nodes(p, srcTile, srcS[1], dstTile, dstS[1])) {
                            pipOracleNodeReject++;
                            p = null;
                        }
                        if (p != null) {
                            if (seenPips.add(p)) { n.addPIP(p); pipImported++; }
                            else pipDroppedDup++;
                            continue;
                        }
                        // Oracle short-circuit: ask the static catalogue for
                        // either endpoint tile before the slow node-graph
                        // walk.  Catches the bidir-direction case (#50) and
                        // the cross-tile-name case in O(1) per candidate tile.
                        if (wireOracle != null) {
                            String[] candTiles = (srcTile != null && dstTile != null
                                                  && srcTile != dstTile)
                                ? new String[]{ dstS[0], srcS[0] }
                                : new String[]{ (dstTile != null) ? dstS[0] : srcS[0] };
                            PIP got = null;
                            boolean isRev = false;
                            Tile gotTile = null;
                            WireOracle.PipMatch m = null;
                            for (String candName : candTiles) {
                                if (candName == null) continue;
                                m = wireOracle.lookupPipAtTile(candName, srcS[1], dstS[1]);
                                if (m == null) continue;
                                gotTile = dev.getTile(candName);
                                if (gotTile == null) continue;
                                // Oracle's srcIdx/dstIdx are in the order
                                // the caller asked.  For REVERSE_BIDIR the
                                // catalogue entry that exists is (dstIdx,
                                // srcIdx) — fetch with that arrangement and
                                // flip the PIP's direction flag for use.
                                if (m.direction == WireOracle.Direction.FORWARD) {
                                    got = gotTile.getPIP(m.srcIdx, m.dstIdx);
                                } else if (m.direction == WireOracle.Direction.REVERSE_BIDIR) {
                                    PIP base = gotTile.getPIP(m.dstIdx, m.srcIdx);
                                    if (base != null) {
                                        PIP copy = new PIP(base);
                                        copy.setIsReversed(true);
                                        got = copy;
                                        isRev = true;
                                    }
                                }
                                if (got != null) break;
                            }
                            // VALIDATE the name-based match against NODE identity.
                            // The oracle is keyed by wire NAME, and names like
                            // SR1BEG_S0 exist in every INT tile, so a hop that
                            // spans tiles can match a real-but-WRONG pip in the
                            // destination tile.  That is exactly what killed
                            // johnson's led_int[4]: the hop
                            //   INT_R_X31Y83/SR1BEG_S0 -> INT_R_X31Y65/LV0
                            // matched INT_R_X31Y65/SR1BEG_S0->LV0, a valid pip
                            // whose start node (SR1BEG_S0@Y65) our route never
                            // drives -- SR1 is a single-length wire, so Y83 and
                            // Y65 are different nodes.  The pip imported without
                            // error, the net looked connected by name, and
                            // Vivado reported an antenna.  Long lines are where
                            // this shows because they are the hops whose source
                            // and destination tiles differ.
                            if (got != null && !pip_matches_nodes(got, srcTile, srcS[1], dstTile, dstS[1])) {
                                pipOracleNodeReject++;
                                got = null;          // fall through to the node search
                            }
                            if (got != null) {
                                if (seenPips.add(got)) { n.addPIP(got); pipImported++; }
                                else pipDroppedDup++;
                                pipOracleHit++;
                                if (isRev) pipOracleReverseBidir++;
                                continue;
                            }
                        }
                        // Neither dt.getPIP() nor the oracle catalogue found
                        // this PIP.  Use RapidWright's authoritative node
                        // APIs for the two remaining classes:
                        //   * sn == dn      -> node traversal, no PIP needed
                        //   * sn != dn      -> find the bridge PIP via
                        //                      Node.getAllDownhillPIPs() —
                        //                      O(node-degree), not the old
                        //                      O(all PIPs in all member
                        //                      tiles) walk.
                        // This is not a fallback; these are direct queries
                        // to the same RapidWright data the oracle is built
                        // from, just answering questions the per-type
                        // catalogue doesn't carry (node membership).
                        if (srcTile != null && dstTile != null
                                && srcTile.getWireIndex(srcS[1]) != null
                                && dstTile.getWireIndex(dstS[1]) != null) {
                            Node sn = new Wire(srcTile, srcS[1]).getNode();
                            Node dn = new Wire(dstTile, dstS[1]).getNode();
                            if (sn != null && dn != null) {
                                if (sn.equals(dn)) {
                                    pipNodeTraversal++;
                                    continue;
                                }
                                PIP bridge = null;
                                boolean bridgeReversed = false;
                                for (PIP cand : sn.getAllDownhillPIPs()) {
                                    if (cand.getEndWire().getNode().equals(dn)) {
                                        bridge = cand;
                                        break;
                                    }
                                }
                                if (bridge == null) {
                                    // Try the reverse-bidir direction.
                                    for (PIP cand : sn.getAllUphillPIPs()) {
                                        if (!cand.isBidirectional()) continue;
                                        if (cand.getStartWire().getNode().equals(dn)) {
                                            PIP copy = new PIP(cand);
                                            copy.setIsReversed(true);
                                            bridge = copy;
                                            bridgeReversed = true;
                                            break;
                                        }
                                    }
                                }
                                if (bridge != null) {
                                    if (seenPips.add(bridge)) { n.addPIP(bridge); pipImported++; }
                                    else pipDroppedDup++;
                                    pipOracleHit++;
                                    if (bridgeReversed) pipOracleReverseBidir++;
                                    continue;
                                }
                            }
                        }
                        pipDroppedLookup++;
                        if (debug_net(nn.name) && pipDroppedLookup < 8) {
                            String srcType = (srcTile != null)
                                    ? srcTile.getTileTypeEnum().toString() : "?";
                            String dstType = (dstTile != null)
                                    ? dstTile.getTileTypeEnum().toString() : "?";
                            System.out.println("  oracle-miss: " + pip
                                    + "  srcType=" + srcType + " dstType=" + dstType);
                        }
                    } else {
                        String[] sp = pip.split("/");
                        String tile = sp[0];
                        String[] wires = sp[1].split("\\.");
                        Tile t = des.getDevice().getTile(tile);
                        if (t == null) { pipDroppedLookup++; continue; }
                        int src = Integer.parseInt(wires[0]);
                        int dst = Integer.parseInt(wires[1]);
                        if (src < t.getWireCount() && dst < t.getWireCount()) {
                            PIP idxPip = t.getPIP(src, dst);
                            if (seenPips.add(idxPip)) { n.addPIP(idxPip); pipImported++; }
                            else pipDroppedDup++;
                        } else pipDroppedLookup++;
                    }
                } catch (NumberFormatException | ArrayIndexOutOfBoundsException ex) {
                    pipDroppedParse++;
                }
            }
            // Per-net routing-import summary on a few key nets so we know
            // where the gaps are without overwhelming the build output.
            // J2D_DEBUG_NET=<name>[,<name>...] traces exactly where one net's
            // routing goes, which is the only way to tell "the importer dropped
            // it" from "nextpnr never routed it".  Replaces a hardcoded list of
            // net names from some long-gone debug session.
            if (debug_net(nn.name)) {
                System.out.println("json2dcp ROUTING net=" + nn.name
                    + ": imported=" + pipImported
                    + " (oracle=" + pipOracleHit
                    + ", oracle_rev_bidir=" + pipOracleReverseBidir + ")"
                    + " node_traversal=" + pipNodeTraversal
                    + " sitewire_skipped=" + pipDroppedSiteWire
                    + " sitepip_skipped=" + pipDroppedSitePip
                    + " lookup_failed=" + pipDroppedLookup
                    + " parse_failed=" + pipDroppedParse
                    + " oracle_node_reject=" + pipOracleNodeReject
                    + " dup_skipped=" + pipDroppedDup);
            }

            for (int i = 0; i < (routing.length-2); i+=3) {
                String wire = routing[i];
                String pip = routing[i + 1];

                if (!pip.isEmpty() && pip.startsWith("SITEPIP") && (!nn.name.equals("$PACKER_GND_NET") || !pip.contains("OUTMUXA"))) {
                    String[] sp = pip.split("/");
                    SiteInst si = des.getSiteInstFromSiteName(sp[1]);
                    if (si == null)
                        si = des.createSiteInst(des.getDevice().getSite(sp[1]));
                    BEL b = si.getBEL(sp[2]);

                    if (b == null)
                        continue;

                    for (BELPin bp : b.getPins()) {
                        for (SitePIP sitePIP : bp.getSitePIPs()) {
                            if (sitePIP.getInputPin().getSiteWireName().equals(sp[3])) {

                                // Don't route through when inverting

                                if (inverted_wires.contains(si.getSiteName() + "/" + sitePIP.getOutputPin().getSiteWireName()))
                                    continue;

                                BELPin startPin = null;
                                for (BEL other : si.getBELs())
                                    for (BELPin p : other.getPins())
                                        if(p.isOutput() && p.getSiteWireName().equals(sitePIP.getInputPin().getSiteWireName()))
                                            startPin = p;
                                if (startPin != null) {
                                    for (BEL other : si.getBELs())
                                        for (BELPin p : other.getPins())
                                            if (p.isInput() && p.getSiteWireName().equals(sitePIP.getOutputPin().getSiteWireName()))
                                                si.routeIntraSiteNet(n, startPin, p);
                                }


                                si.addSitePIP(sitePIP);

                                // FIXME: when does/n't site PIP insertion work?

                            }
                        }
                    }

                }

                if (wire.startsWith("SITEWIRE") && !nn.name.equals("$PACKER_GND_NET")) {
                    String[] sw = wire.split("/");
                    SiteInst si = des.getSiteInstFromSiteName(sw[1]);
                    if (si == null)
                        si = des.createSiteInst(des.getDevice().getSite(sw[1]));
                    // FIXME: when does/n't site Wire insertion work?
                    // NOTE: binding the IOB's RBEL routing muxes (IUSED ->
                    // sitewire "I") was TRIED here and made no difference to
                    // the 18-4866 "sitetype net ... overwritten" warnings or to
                    // the report_route_status segfault, so it was reverted.
                    // The real cause is one site carrying two top-level ports:
                    // a differential input has a single INBUF on the MASTER
                    // site but two PAD cells (P and N), and the N pad is
                    // attached to the master instead of the slave site.
                    BELPin startPin = null;
                    for (BEL other : si.getBELs()) {
                        if (other.getBELClass() == BELClass.RBEL
                                || (other.getBELClass() == BELClass.PORT &&
                                si.getSiteTypeEnum() != SiteTypeEnum.HDIOB_M &&
                                si.getSiteTypeEnum() != SiteTypeEnum.HDIOB_S &&
                                si.getSiteTypeEnum() != SiteTypeEnum.HPIOB &&
                                si.getSiteTypeEnum() != SiteTypeEnum.HPIOB_M &&
                                si.getSiteTypeEnum() != SiteTypeEnum.HPIOB_S))
                            continue;
                        for (BELPin p : other.getPins()) {
                            String pwn = p.getSiteWireName();
                            if (pwn != null && p.isOutput() && pwn.equals(sw[2]))
                                startPin = p;
                        }
                    }

                    if (startPin != null) {
                        for (BEL other : si.getBELs()) {
                            //if (other.getBELClass() == BELClass.RBEL || other.getBELClass() == BELClass.PORT)
                            //    continue;
                            for (BELPin p : other.getPins()) {
                                // Some BEL pins (e.g. internal IOB18 ground/static pins)
                                // have a null sitewire on 7-series — skip those rather
                                // than NPE on the equals().
                                String pwn = p.getSiteWireName();
                                if (pwn != null && p.isInput() && pwn.equals(sw[2])) {
                                    si.routeIntraSiteNet(n, startPin, p);
                                    //System.out.println(si.getSiteName() + ": " + startPin.getBEL().getName() + "." + startPin.getName() + " -> " + p.getBEL().getName() + "." + p.getName());
                                }
                            }
                        }

                    }
                }
            }
        }


        HashMap<String, HashSet<String>> created_ports = new HashMap<>();

        for (NextpnrCell nc : ndes.cells.values()) {
            if (nc.name.contains("$subcell$")) {
                String basename = nc.name.replace("/", "__").split("\\$subcell\\$")[0];
                created_ports.putIfAbsent(basename, new HashSet<>());
                EDIFCellInst macro = des.getTopEDIFCell().getCellInst(basename);

                for (Map.Entry<String, String> param : nc.params.entrySet()) {
                    String value = param.getValue();
                    if (param.getKey().startsWith("IS_") && param.getKey().endsWith("_INVERTED")) {
                        value = fixup_init(value, 1).replace("h", "b");
                    }
                    macro.addProperty(param.getKey(), value);
                }


                for (NextpnrCellPort p : nc.ports.values()) {
                    if (p.net == null)
                        continue;
                    Net physNet = p.net.rwNet;
                    if (physNet == null)
                        continue;
                    if (!nc.attrs.containsKey("X_ORIG_PORT_" + p.name))
                        continue;
                    String[] orig_ports = nc.attrs.get("X_ORIG_PORT_" + p.name).split(" ");
                    for (String o : orig_ports) {
                        if (nc.attrs.containsKey("X_MACRO_PORTS_" + o)) {
                            String[] mps = nc.attrs.get("X_MACRO_PORTS_" + o).split(";");
                            for (String mp : mps) {
                                String [] nt = mp.split(",");
                                if (created_ports.get(basename).contains(nt[0]))
                                    continue;
                                if (physNet.getLogicalNet() == null)
                                    continue;
                                String logical_pin = nt[0];
                                if (logical_pin.endsWith("]")) {
                                    int open_pos = logical_pin.lastIndexOf('[');
                                    String log_bus = logical_pin.substring(0, open_pos);
                                    int port_index = Integer.parseInt(logical_pin.substring(open_pos + 1, logical_pin.length() - 1));
                                    int bus_width = macro.getPort(log_bus).getWidth();
                                    physNet.getLogicalNet().createPortInst(log_bus, (bus_width - 1) - port_index, macro);
                                } else {
                                    physNet.getLogicalNet().createPortInst(logical_pin, macro);
                                }
                                created_ports.get(basename).add(nt[0]);
                            }
                        }
                    }

                }
            }
        }

        for (NextpnrCell nc : ndes.cells.values()) {
            // nextpnr emits "IOB_PAD" on UltraScale and plain "PAD" on
            // 7-series.  Both shapes need their PAD net stitched to the
            // top-level port in the EDIF so Vivado doesn't think the
            // IBUF/OBUF behind it is "redundant" (which would prune
            // them and leave the design's clk/rst/led nets driverless).
            if (nc.type.equals("IOB_PAD") || nc.type.equals("PAD")) {
                // Process top level IO
                EDIFPortInst epi = EDIFTools.createTopLevelPortInst(des, nc.name, PinType.valueOf(nc.attrs.get("X_IO_DIR")));
                Net pad_net = nc.ports.get("PAD").net.rwNet;

                // --- physical <PORT> cell on the PAD BEL -------------------
                // Vivado's own DCP places a <PORT> cell on the PAD BEL of every
                // IOB it uses (11 of them in the golden johnson); ours placed
                // none, so nothing anchored the port to its site.  nextpnr
                // gives us the site in NEXTPNR_BEL ("IOB_X0Y49/IOB18/PAD").
                Site padSite = null;
                String nbel = nc.attrs.get("NEXTPNR_BEL");
                if (nbel != null && nbel.indexOf('/') > 0)
                    padSite = des.getDevice().getSite(nbel.substring(0, nbel.indexOf('/')));
                SiteInst padSi = null;
                if (padSite != null) {
                    padSi = des.getSiteInstFromSite(padSite);
                    if (padSi == null)
                        padSi = des.createSiteInst(padSite);
                    BEL padBel = padSi.getBEL("PAD");
                    if (padBel != null && padSi.getCell(padBel) == null) {
                        Cell pcell = new Cell(nc.name, padSi, padBel);
                        pcell.setType("<PORT>");
                        padSi.addCell(pcell);
                    }
                }

                // --- differential input: the N half ------------------------
                // The golden routes it as ONE pip, slave PADOUT -> master
                // DIFFI_IN, and reserves the slave's input buffer with a
                // <LOCKED> cell.  We emitted no physical net at all, so Vivado
                // called sysclk_n unrouted and refused bitgen.  Detect the N
                // pad by its net feeding somebody's DIFFI_IN.
                NextpnrCellPort diffUser = null;
                if (nc.ports.get("PAD").net != null)
                    for (NextpnrCellPort u : nc.ports.get("PAD").net.users)
                        if (u.name.equals("DIFFI_IN") && u.cell.rwCell != null)
                            diffUser = u;
                if (diffUser != null && padSi != null) {
                    SiteInst master = diffUser.cell.rwCell.getSiteInst();
                    BEL inbuf = padSi.getBEL("INBUF_DCIEN");
                    if (inbuf != null && padSi.getCell(inbuf) == null) {
                        Cell lk = new Cell("<LOCKED>", padSi, inbuf);
                        lk.setType("<LOCKED>");
                        padSi.addCell(lk);
                    }
                    try {
                        SitePinInst sp = pad_net.createPin("PADOUT", padSi);
                        SitePinInst sk = pad_net.createPin("DIFFI_IN", master);
                        Node a = sp.getConnectedNode(), b = sk.getConnectedNode();
                        if (a != null && b != null)
                            for (PIP pp : a.getAllDownhillPIPs())
                                if (b.equals(pp.getEndNode())) { pad_net.addPIP(pp); break; }
                    } catch (RuntimeException ex) {
                        System.out.println("WARNING: diff-pair N stitch failed for "
                                + nc.name + ": " + ex);
                    }
                }
                pad_net.getLogicalNet().addPortInst(epi);
                for (var attr : nc.attrs.entrySet()) {
                    pad_net.getLogicalNet().addProperty(attr.getKey(), attr.getValue());
                }
                // UCIO-1 fires on write_bitstream when LOC was applied at
                // runtime (e.g. from a DCP-embedded XDC) rather than from
                // source XDC.  All four ports are correctly LOC'd here, but
                // Vivado's DRC is pedantic.  Demote UCIO-1 once per design
                // so the workaround lives with the artefact, not in every
                // caller's TCL.  We re-add this line per port; addXDCConstraint
                // is idempotent on identical strings so it's harmless.
                des.addXDCConstraint(ConstraintGroup.NORMAL,
                    "set_property SEVERITY {Warning} [get_drc_checks UCIO-1]");
                // Emit set_property PACKAGE_PIN / IOSTANDARD as real XDC
                // constraints — RapidWright persists addXDCConstraint() into
                // the DCP's top.xdc.  Without this Vivado's open_checkpoint
                // sees no LOC on the top-level ports and DRC UCIO-1 blocks
                // write_bitstream ("4 logical ports have no LOC").  The same
                // info already lives as EDIF net properties above, but Vivado
                // doesn't pull LOC from EDIF — only from XDC.
                String pin = nc.attrs.get("PACKAGE_PIN");
                String iostd = nc.attrs.get("IOSTANDARD");
                String portName = nc.name;
                // Brace the port name so a bus-indexed port like "led[3]"
                // stays literal under Tcl evaluation.
                if (pin != null) {
                    des.addXDCConstraint(ConstraintGroup.NORMAL,
                        "set_property PACKAGE_PIN " + pin + " [get_ports {" + portName + "}]");
                }
                if (iostd != null) {
                    des.addXDCConstraint(ConstraintGroup.NORMAL,
                        "set_property IOSTANDARD " + iostd + " [get_ports {" + portName + "}]");
                }
            }
        }

        // ------------------------------------------------------------------
        // Carry the user's source XDC into the bundled DCP XDC verbatim.
        //
        // The per-PAD loop above only emits PACKAGE_PIN + IOSTANDARD per port,
        // because that's all nextpnr propagated through the JSON.  Anything
        // net-scope or design-scope (CLOCK_DEDICATED_ROUTE, MARK_DEBUG,
        // DONT_TOUCH, create_clock -waveform, set_clock_groups, ...) is
        // dropped and every downstream Vivado run has to re-apply it.
        //
        // Find top.xdc by walking up from the JSON input (same convention as
        // the wire-name oracle's auto-locate).  Append the file's contents to
        // the bundled XDC string.  Skip the PACKAGE_PIN / IOSTANDARD lines we
        // already emit, and skip set_property SEVERITY on UCIO-1 (already
        // emitted).  Everything else carries through unchanged.
        // ------------------------------------------------------------------
        {
            java.io.File xdcSrc = null;
            String envXdc = System.getenv("XRAY_SOURCE_XDC");
            if (envXdc != null) {
                java.io.File cand = new java.io.File(envXdc);
                if (cand.isFile()) xdcSrc = cand;
            }
            if (xdcSrc == null) {
                java.io.File jsonFile = new java.io.File(args[1]).getAbsoluteFile();
                java.io.File cur = jsonFile.getParentFile();
                while (cur != null && xdcSrc == null) {
                    java.io.File cand = new java.io.File(cur, "top.xdc");
                    if (cand.isFile()) xdcSrc = cand;
                    cur = cur.getParentFile();
                }
            }
            if (xdcSrc != null) {
                int copied = 0, skipped = 0;
                try (java.io.BufferedReader r =
                        new java.io.BufferedReader(new java.io.FileReader(xdcSrc))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        String stripped = line.trim();
                        if (stripped.isEmpty() || stripped.startsWith("#")) {
                            continue;
                        }
                        // Drop the duplicates we already emit per-port.
                        if (stripped.startsWith("set_property PACKAGE_PIN")
                                || stripped.startsWith("set_property IOSTANDARD")
                                || (stripped.startsWith("set_property SEVERITY")
                                    && stripped.contains("UCIO-1"))) {
                            skipped++;
                            continue;
                        }
                        des.addXDCConstraint(ConstraintGroup.NORMAL, line);
                        copied++;
                    }
                } catch (java.io.IOException ex) {
                    System.err.println("[source-xdc] failed to read " + xdcSrc
                            + ": " + ex.getMessage());
                }
                System.out.println("[source-xdc] " + xdcSrc.getAbsolutePath()
                        + ": copied=" + copied + " skipped-duplicates=" + skipped);
            } else {
                System.out.println("[source-xdc] no top.xdc found (set "
                        + "XRAY_SOURCE_XDC=<path> or place top.xdc next to the JSON)");
            }
        }

        // ------------------------------------------------------------------
        // Inject CARRY4 -> CARRY4 carry-chain routing.
        //
        // nextpnr-xilinx treats the COUT -> CIN connection between vertically
        // adjacent CARRY4 cells as dedicated / implicit and emits NO routing
        // tokens for it in the JSON.  Vivado / RapidWright still need the
        // canonical PIP + SitePinInsts to consider the net routed:
        //
        //   <lower-SLICE>.COUT   site-pin (output)
        //   lower-tile PIP:      CLBLM_M_COUT -> CLBLM_M_COUT_N
        //                        (or CLBLL_LL_COUT -> CLBLL_LL_COUT_N
        //                        depending on tile + slice column letter)
        //   <upper-SLICE>.CIN    site-pin (input)
        //
        // Detect carry-chain nets as: driver = CARRY4 cell CO3 port,
        // sink = CARRY4 cell CIN port, both cells placed at same X, Y+1
        // SLICEs.  Then create the SitePinInsts, look up the PIP via the
        // wire oracle / Tile.getPIP(), and addPIP to the net.
        // ------------------------------------------------------------------
        {
            int chainsInjected = 0, chainsFailed = 0;
            for (NextpnrNet nn : ndes.nets.values()) {
                if (nn.rwNet == null) continue;
                if (nn.driver == null || nn.driver.cell == null) continue;
                if (!nn.driver.cell.type.equals("CARRY4")
                    && !nn.driver.cell.attrs.getOrDefault("X_ORIG_TYPE", "").equals("CARRY4"))
                    continue;
                if (!nn.driver.name.equals("CO3")) continue;
                Cell driverCell = nn.driver.cell.rwCell;
                if (driverCell == null) continue;
                Site lowerSite = driverCell.getSite();
                if (lowerSite == null) continue;
                // Find a CARRY4 sink whose port is CIN and SLICE is X==lowerX, Y==lowerY+1.
                NextpnrCellPort cinSink = null;
                for (NextpnrCellPort sink : nn.users) {
                    if (sink.cell == null) continue;
                    if (!sink.cell.type.equals("CARRY4")
                        && !sink.cell.attrs.getOrDefault("X_ORIG_TYPE", "").equals("CARRY4"))
                        continue;
                    if (!sink.name.equals("CIN")) continue;
                    Cell sinkCell = sink.cell.rwCell;
                    if (sinkCell == null) continue;
                    Site upperSite = sinkCell.getSite();
                    if (upperSite == null) continue;
                    if (upperSite.getInstanceX() != lowerSite.getInstanceX()) continue;
                    if (upperSite.getInstanceY() != lowerSite.getInstanceY() + 1) continue;
                    cinSink = sink;
                    break;
                }
                if (cinSink == null) continue;
                Site upperSite = cinSink.cell.rwCell.getSite();

                Tile lowerTile = lowerSite.getTile();
                String coutTileWire = lowerSite.getTileWireNameFromPinName("COUT");
                String cinTileWire  = upperSite.getTileWireNameFromPinName("CIN");
                if (coutTileWire == null || cinTileWire == null) {
                    chainsFailed++;
                    continue;
                }
                // The PIP we need: lower-tile COUT -> COUT_N (the suffix is
                // appended in the canonical wire-name convention).
                String coutNWire = coutTileWire + "_N";
                Integer srcIdx = lowerTile.getWireIndex(coutTileWire);
                Integer dstIdx = lowerTile.getWireIndex(coutNWire);
                if (srcIdx == null || dstIdx == null) {
                    chainsFailed++;
                    continue;
                }
                PIP carryPip = lowerTile.getPIP(srcIdx, dstIdx);
                if (carryPip == null) {
                    chainsFailed++;
                    continue;
                }

                Net rw = nn.rwNet;
                SiteInst lowerSi = des.getSiteInstFromSiteName(lowerSite.getName());
                SiteInst upperSi = des.getSiteInstFromSiteName(upperSite.getName());
                if (lowerSi == null) lowerSi = des.createSiteInst(lowerSite);
                if (upperSi == null) upperSi = des.createSiteInst(upperSite);

                if (lowerSi.getSitePinInst("COUT") == null) {
                    try { rw.createPin("COUT", lowerSi); }
                    catch (RuntimeException ex) { chainsFailed++; continue; }
                }
                if (upperSi.getSitePinInst("CIN") == null) {
                    try { rw.createPin("CIN", upperSi); }
                    catch (RuntimeException ex) { chainsFailed++; continue; }
                }
                // Only if the routing loop did not already import it.  nextpnr
                // DOES emit the cascade hop (CLBLM_M_COUT -> CLBLM_M_CIN), so
                // injecting unconditionally put the same pip on the net twice --
                // which Vivado calls a "partial route conflict" and refuses to
                // bitgen: 104 such nets on picosoc, 71 on ibex, every one a
                // CARRY4 CO[3].  This pass predates the routing loop learning
                // to import the hop.
                if (!rw.getPIPs().contains(carryPip))
                    rw.addPIP(carryPip);
                // Select the CIN cascade on the follower's PRECYINIT mux.
                // nextpnr leaves it implicit; without this the mux defaults to
                // AX, an invalid carry config for a cell-driven CI (DRC
                // PRECYINIT-FOLLOWER) that also mis-encodes the carry-in.
                try { upperSi.addSitePIP("PRECYINIT", "CIN"); }
                catch (RuntimeException ex) { /* leave to DRC */ }
                chainsInjected++;
            }
            if (chainsInjected > 0 || chainsFailed > 0)
                System.out.println("[carry-chain] injected=" + chainsInjected
                        + " failed=" + chainsFailed);
        }

        // ------------------------------------------------------------------
        // Inject CARRY4 sum-output routing (#56).
        //
        // Same gap as #55 but for the CARRY4 cell's O0..O3 outputs (sum
        // outputs) driving the FF.D inputs that hold the counter bits.
        // nextpnr-xilinx emits empty ROUTING for these nets because the
        // packer assumes they're intra-site (CARRY4 in slot S and FF at
        // SFF / S5FF in the same SLICE).  When the FF is in the same SLICE
        // we just need to bind the SitePIP that selects CARRY4_XOR as the
        // FFMUX input — the BEL output siteWire is whatever feeds the FF.D
        // pin's siteWire.  When the FF lands in a different SLICE (the
        // packer's CONSTR_CHILDREN doesn't include FFs, so they spread),
        // the route needs general fabric routing — skip those with a
        // diagnostic for follow-up.
        // ------------------------------------------------------------------
        {
            int sumsInjected = 0, sumsSkippedCross = 0, sumsFailed = 0;
            int sumsCrossSlot = 0;
            for (NextpnrNet nn : ndes.nets.values()) {
                if (nn.rwNet == null) continue;
                if (nn.driver == null || nn.driver.cell == null) continue;
                if (!nn.driver.cell.type.equals("CARRY4")
                    && !nn.driver.cell.attrs.getOrDefault("X_ORIG_TYPE", "").equals("CARRY4"))
                    continue;
                // Driver port must be O0, O1, O2, or O3 (the sum outputs).
                String portName = nn.driver.name;
                if (!portName.startsWith("O") || portName.length() != 2) continue;
                char slot = portName.charAt(1);
                if (slot < '0' || slot > '3') continue;
                Cell driverCell = nn.driver.cell.rwCell;
                if (driverCell == null) continue;
                Site driverSite = driverCell.getSite();
                if (driverSite == null) continue;

                // Slot letter from O index: O0 -> A, O1 -> B, O2 -> C, O3 -> D.
                char slotLetter = (char) ('A' + (slot - '0'));

                // Find FF sinks of this net.  Most counters have ONE FF.D
                // sink per O port; multi-sink (e.g. a sum output used as
                // both a register input AND a downstream LUT input) needs
                // the LUT-input side to go via general routing too, but
                // here we focus on the FF.D leg which is the one the
                // packer assumed implicit.
                for (NextpnrCellPort sink : nn.users) {
                    if (sink.cell == null) continue;
                    Cell sinkCell = sink.cell.rwCell;
                    if (sinkCell == null) continue;
                    // FFs land at *FF or *5FF BELs; their cell port for
                    // D input is "D" in nextpnr's view.
                    if (!"D".equals(sink.name)) continue;
                    Site sinkSite = sinkCell.getSite();
                    if (sinkSite == null) continue;
                    if (!sinkSite.getName().equals(driverSite.getName())) {
                        // Cross-SLICE — the packer let the FF drift away
                        // from its CARRY4.  Inject-via-fabric is the real
                        // fix; for now log and let the bitgen flag it.
                        sumsSkippedCross++;
                        continue;
                    }
                    SiteInst si = des.getSiteInstFromSiteName(sinkSite.getName());
                    if (si == null) {
                        si = des.createSiteInst(sinkSite);
                    }
                    // Find the routing BEL that drives this FF.D pin.
                    // sinkBel.getName() is e.g. "AFF" or "A5FF" — its D
                    // BEL-pin lives on a siteWire that the *FFMUX RBEL's
                    // output drives.
                    BEL sinkBel = sinkCell.getBEL();
                    if (sinkBel == null) { sumsFailed++; continue; }
                    // Slot consistency: each CARRY4 sum-output O<idx>
                    // wires to that slot's XOR sitewire (O0 -> A's
                    // CARRY4_AXOR_O, O1 -> B's CARRY4_BXOR_O, ...).
                    // The FFMUX:CARRY4_XOR intra-site path only exists
                    // within the matching slot.  If the FF landed in a
                    // different slot (a packer fault -- task #75), the
                    // path needs fabric routing, not a SitePIP.
                    char sinkSlot = sinkBel.getName().charAt(0);
                    if (sinkSlot != slotLetter) {
                        sumsCrossSlot++;
                        continue;
                    }
                    BELPin sinkDPin = sinkBel.getPin("D");
                    if (sinkDPin == null) { sumsFailed++; continue; }
                    String fmuxOutWire = sinkDPin.getSiteWireName();
                    if (fmuxOutWire == null) { sumsFailed++; continue; }

                    SitePIP bound = null;
                    for (BEL bel : si.getBELs()) {
                        // The FFMUX whose output drives the FF.D sitewire
                        // is the routing BEL we want.  Only RBELs have
                        // SitePIPs.
                        if (bel.getBELClass() != BELClass.RBEL) continue;
                        // Cheap pre-filter: name should start with slot
                        // letter (e.g. AFFMUX, A5FFMUX).
                        if (bel.getName().charAt(0) != slotLetter) continue;
                        for (BELPin bp : bel.getPins()) {
                            if (!bp.isInput()) continue;
                            String swn = bp.getSiteWireName();
                            // Looking for the "CARRY4_XOR" input.
                            if (swn == null) continue;
                            // The input pin name we want is one whose
                            // SitePIP output siteWire equals the FF.D
                            // sitewire AND whose own name signals "from
                            // CARRY4".  Walk pin SitePIPs and check.
                            for (SitePIP cand : bp.getSitePIPs()) {
                                BELPin out = cand.getOutputPin();
                                if (out == null) continue;
                                String oswn = out.getSiteWireName();
                                if (oswn == null || !oswn.equals(fmuxOutWire)) continue;
                                // Input pin name carries the source-kind
                                // identifier: "CARRY4_XOR" for the sum
                                // path.  Anything else here is the
                                // wrong selection.
                                if ("CARRY4_XOR".equals(bp.getName())) {
                                    bound = cand;
                                    break;
                                }
                            }
                            if (bound != null) break;
                        }
                        if (bound != null) break;
                    }
                    if (bound == null) {
                        sumsFailed++;
                        continue;
                    }

                    // Same stitching pattern as #54 / #55: routeIntraSiteNet
                    // hooks the BEL that drives the SitePIP's input
                    // sitewire (here the CARRY4's BEL) to the sink FF
                    // BEL pin, then we bind the SitePIP itself.
                    BELPin startPin = null;
                    for (BEL other : si.getBELs())
                        for (BELPin p : other.getPins())
                            if (p.isOutput()) {
                                String swn = p.getSiteWireName();
                                if (swn != null && swn.equals(bound.getInputPin().getSiteWireName()))
                                    startPin = p;
                            }
                    if (startPin != null) {
                        for (BEL other : si.getBELs())
                            for (BELPin p : other.getPins())
                                if (p.isInput()) {
                                    String swn = p.getSiteWireName();
                                    if (swn != null && swn.equals(fmuxOutWire))
                                        si.routeIntraSiteNet(nn.rwNet, startPin, p);
                                }
                    }
                    si.addSitePIP(bound);
                    sumsInjected++;
                }
            }
            if (sumsInjected > 0 || sumsSkippedCross > 0
                    || sumsCrossSlot > 0 || sumsFailed > 0)
                System.out.println("[carry-sum-output] injected=" + sumsInjected
                        + " skipped-cross-slice=" + sumsSkippedCross
                        + " skipped-cross-slot=" + sumsCrossSlot
                        + " failed=" + sumsFailed);
        }

        // ------------------------------------------------------------------
        // Tie the two connectivity gaps Vivado insists on.
        //
        // CLKINSEL: a PLLE2_BASE in source has no such port, but the PLLE2_ADV
        // it maps to does, and Vivado rejects it unconnected --
        //   [DRC REQP-159] the PLLE2_ADV input pins CLKINSEL and at least one
        //   of CLKIN1 or CLKIN2 must have a connection
        // The BASE flavour always uses CLKIN1, which is CLKINSEL high.
        //
        // <net>$const: nextpnr leaves hard-block inputs it never drove as
        // undriven nets (RAMB address bits above the used width, unused data
        // inputs) -- [DRC NDRV-1].  They have no driver by construction, so any
        // value is as good; GND is what Vivado's own opt_design ties them to.
        // ------------------------------------------------------------------
        // ------------------------------------------------------------------
        // GT REFERENCE CLOCK.  nextpnr DELIBERATELY disconnects GTREFCLK0/1 in
        // pack_gt_xc7.cc: on 7-series GTREFCLK0 is HARDWIRED to the lower
        // IBUFDS_GTE2 of the quad and GTREFCLK1 to the upper one, so there is
        // nothing to route -- it records the choice as _GTREFCLK0_USED /
        // _GTREFCLK1_USED and the FASM backend sets the config bit.  Correct for
        // the open flow, and NOT a defect.
        //
        // Vivado's netlist DRC does not know that, and rejects the checkpoint:
        //   [DRC REQP-51] must_use_ref_clock: an input reference clock pin
        //   (GTREFCLK0, ...) or GTXE2_COMMON clock input (QPLLCLK) must be used
        // along with PDCN-730/735 on the COMMON.  So put the LOGICAL connection
        // back from the flag nextpnr left behind: no site pin, no routing, just
        // the netlist edge Vivado's rule is looking for.
        // ------------------------------------------------------------------
        {
            // the IBUFDS_GTE2 output net, by buffer position (_REL_BUF_Y)
            java.util.HashMap<Integer, NextpnrNet> refbuf = new java.util.HashMap<>();
            for (NextpnrCell nc : ndes.cells.values()) {
                String oty = nc.attrs.getOrDefault("X_ORIG_TYPE", nc.type);
                if (oty == null || !oty.startsWith("IBUFDS_GTE2")) continue;
                NextpnrCellPort o = nc.ports.get("O");
                if (o == null || o.net == null) continue;
                int relY = 0;
                String r = nc.attrs.get("_REL_BUF_Y");
                if (r != null) try {
                    r = r.trim();
                    relY = r.length() > 1 ? Integer.parseInt(r, 2) : Integer.parseInt(r);
                } catch (NumberFormatException e) { relY = 0; }
                refbuf.put(relY, o.net);
            }
            int tiedRef = 0;
            for (NextpnrCell nc : ndes.cells.values()) {
                if (nc.rwCell == null) continue;
                String oty = nc.attrs.getOrDefault("X_ORIG_TYPE", nc.type);
                if (oty == null || !oty.startsWith("GTXE2")) continue;
                EDIFCellInst inst = nc.rwCell.getEDIFCellInst();
                if (inst == null) continue;
                for (int idx = 0; idx <= 1; idx++) {
                    String flag = nc.params.get("_GTREFCLK" + idx + "_USED");
                    if (flag == null || flag.replace("'", "").endsWith("0")) continue;
                    NextpnrNet rn = refbuf.get(idx);
                    if (rn == null) rn = refbuf.get(0);          // single-buffer designs
                    if (rn == null || rn.rwNet == null || rn.rwNet.getLogicalNet() == null) continue;
                    String pin = "GTREFCLK" + idx;
                    if (inst.getPortInst(pin) != null) continue;
                    try {
                        rn.rwNet.getLogicalNet().createPortInst(pin, inst);
                        tiedRef++;
                    } catch (RuntimeException ex) { /* no such port on this flavour */ }
                }
            }
        if (hardSinkLogical > 0 || hardSinkFailed > 0)
            System.out.println("[hard-sink] logically connected " + hardSinkLogical
                    + " sink pin(s) on never-repacked cells (" + hardSinkFailed + " failed, "
                    + hardSinkNoPort + " with no matching EDIF port)");

        // ------------------------------------------------------------------
        // PRUNE DANGLING PIN MAPPINGS on never-repacked hard blocks.
        //
        // Vivado builds a routed-site model by walking each placed cell's
        // physical->logical pin map and taking the LOGICAL NET behind every
        // entry.  A mapping whose logical port has no net gives it a null and it
        // dies:  HDLHNet::getSigType <- HDPYNetProxy
        //                            <- HDPYRoutedSiteBuilder::newRoutedSite
        //
        // RapidWright's default map covers EVERY bel pin of the block (628 on a
        // GTXE2_CHANNEL), so most entries are danglers.  Two filters were tried
        // and BOTH still crashed, identically: keeping all defaults, and keeping
        // those whose nextpnr port had a net.  The second was still wrong,
        // because a nextpnr-side net does not guarantee the connection SURVIVED
        // into the EDIF -- resolve_edif_pin can fail to find the port, and the
        // macro path makes physical-only Nets whose getLogicalNet() is null by
        // construction.
        //
        // So test the real thing, after the netlist is complete: keep a mapping
        // only if the EDIF cell instance actually has a PortInst for that logical
        // pin.  That is exactly what Vivado dereferences.
        // ------------------------------------------------------------------
        {
            int pruned = 0, kept = 0;
            for (NextpnrCell nc : ndes.cells.values()) {
                if (nc.rwCell == null) continue;
                boolean hasRepack = false;
                for (String k : nc.attrs.keySet())
                    if (k.startsWith("X_ORIG_PORT_")) { hasRepack = true; break; }
                if (hasRepack) continue;                 // repacked cells were rebuilt already
                EDIFCellInst inst = nc.rwCell.getEDIFCellInst();
                if (inst == null) continue;
                for (Object po : nc.rwCell.getPinMappingsP2L().keySet().toArray()) {
                    String phys = po.toString();
                    String logical = nc.rwCell.getPinMappingsP2L().get(phys);
                    boolean live = false;
                    if (logical != null) {
                        EDIFPortInst pi = inst.getPortInst(logical);
                        live = pi != null && pi.getNet() != null;
                    }
                    if (live) kept++;
                    else { nc.rwCell.removePinMapping(phys); pruned++; }
                }
            }
            if (pruned > 0 || kept > 0)
                System.out.println("[pinmap] hard blocks: kept " + kept
                        + " pin mapping(s) with a live logical net, pruned " + pruned + " dangling");
        }

            if (keptDefaultPinmap > 0)
                System.out.println("[pinmap] kept RapidWright default pin map on " + keptDefaultPinmap
                        + " never-repacked cell(s): " + keptDefaultPins + " connected pin(s) kept, "
                        + keptDroppedPins + " unconnected dropped -- hard blocks carry no X_ORIG_PORT");
            if (tiedRef > 0)
                System.out.println("[gt-refclk] reconnected " + tiedRef
                        + " GTREFCLK pin(s) that nextpnr left implicit");
        }

        {
            int tiedSel = 0;
            for (NextpnrCell nc : ndes.cells.values()) {
                if (nc.rwCell == null) continue;
                String oty = nc.attrs.getOrDefault("X_ORIG_TYPE", nc.type);
                if (oty == null) continue;
                if (!(oty.startsWith("PLLE2") || oty.startsWith("MMCME2"))) continue;
                EDIFCellInst inst = nc.rwCell.getEDIFCellInst();
                if (inst == null || inst.getPortInst("CLKINSEL") != null) continue;
                try {
                    des.getVccNet().getLogicalNet().createPortInst("CLKINSEL", inst);
                    tiedSel++;
                } catch (RuntimeException ex) { /* no such port on this flavour */ }
            }
            // NOTE: tying the <net>$const pins to GND was TRIED here and makes
            // the checkpoint UNOPENABLE -- connect_log_and_phys creates physical
            // site pins, not just a logical tie, and those collide with the IOB
            // and hard-block site routing (Vivado: 18-4866 "sitetype net ...
            // overwritten", then open_checkpoint fails).  A logical-only tie via
            // EDIFNet.createPortInst is the shape to try, not this.
            if (tiedSel > 0)
                System.out.println("[tie] CLKINSEL=" + tiedSel);
        }

        // ------------------------------------------------------------------
        // Anchor the constant nets to their TIEOFFs.
        //
        // nextpnr sources GND/VCC from the per-INT-tile pseudo-constant wires
        // ("INT_L_X0Y138/GND_WIRE -> INT_L_X30Y138/GFAN1"), so we import PIPs
        // for them but never create a SOURCE site pin -- Vivado then reports
        // GLOBAL_LOGIC0/1 as partial antennas (routing with nothing driving it)
        // and refuses bitgen.  Vivado's own DCP of the same design drives VCC
        // from TIEOFF/HARD1, and every INT tile has a co-located TIEOFF whose
        // HARD0/HARD1 pin IS that GND_WIRE/VCC_WIRE node.  So the fix keeps
        // nextpnr's routing verbatim -- non-optimal though it is, it is what
        // the bitstream actually does -- and just adds the anchor Vivado needs.
        // ------------------------------------------------------------------
        {
            int tieAdded = 0;
            for (Net net : des.getNets()) {
                if (!net.isStaticNet()) continue;
                boolean isGnd = net.getName().contains("LOGIC0") || net.getName().contains("GND");
                String pinName = isGnd ? "HARD0" : "HARD1";
                String wantWire = isGnd ? "GND_WIRE" : "VCC_WIRE";
                HashSet<String> done = new HashSet<>();
                for (PIP p : new ArrayList<>(net.getPIPs())) {
                    Node sn = p.getStartNode();
                    if (sn == null) continue;
                    if (!wantWire.equals(sn.getWireName())) continue;
                    Tile t = sn.getTile();
                    if (t == null) continue;
                    for (Site site : t.getSites()) {
                        if (site.getSiteTypeEnum() != SiteTypeEnum.TIEOFF) continue;
                        if (!done.add(site.getName())) continue;
                        SiteInst si = des.getSiteInstFromSite(site);
                        if (si == null) si = des.createSiteInst(site);
                        if (si.getSitePinInst(pinName) == null) {
                            try { net.createPin(pinName, si); tieAdded++; }
                            catch (RuntimeException ex) { /* already attached */ }
                        }
                    }
                }
            }
            if (tieAdded > 0)
                System.out.println("[tieoff] anchored " + tieAdded
                        + " constant-net source pin(s) to TIEOFF sites");
        }

        // ------------------------------------------------------------------
        // Pre-write routing sanity (#island).  RapidWright's (closed,
        // obfuscated) DCP writer walks each net's PIPs from the graph
        // roots (start nodes that are never any PIP's end node); any PIP
        // left unreached — a detached island or a cycle — makes it print
        // "ERROR: island/loop discovered in net: <name>" and then NPE,
        // TRUNCATING the checkpoint (Vivado: "failed integrity check").
        // Replicate the reachability check here and DROP the routing of
        // any offending net (leave it unrouted, warn) so the checkpoint
        // always writes whole.
        // ------------------------------------------------------------------
        {
            int droppedNets = 0;
            for (Net net : des.getNets()) {
                if (net.isStaticNet()) continue;  // multi-source tieoff trees
                java.util.List<PIP> pips = net.getPIPs();
                if (pips == null || pips.isEmpty()) continue;

                // Adjacency over the net's PIPs.  Directional PIPs give a
                // directed edge start->end; bidirectional PIPs (LV/LH long
                // lines, "<<->>") are traversable either way — the writer
                // resolves their direction from context, so treating them
                // as undirected avoids false islands.
                HashMap<Node, ArrayList<Node>> adj = new HashMap<>();
                HashSet<Node> starts = new HashSet<>();
                HashSet<Node> ends = new HashSet<>();
                for (PIP p : pips) {
                    Node s = p.getStartNode();
                    Node e = p.getEndNode();
                    if (s == null || e == null) continue;
                    ArrayList<Node> lst = adj.get(s);
                    if (lst == null) { lst = new ArrayList<>(); adj.put(s, lst); }
                    lst.add(e);
                    if (p.isBidirectional()) {
                        ArrayList<Node> rl = adj.get(e);
                        if (rl == null) { rl = new ArrayList<>(); adj.put(e, rl); }
                        rl.add(s);
                    }
                    starts.add(s);
                    ends.add(e);
                }

                // Seeds: the net's source site pin node(s) — the writer
                // walks branches from the logical source.  Fall back to
                // graph roots when no source pin exists.
                java.util.ArrayDeque<Node> queue = new java.util.ArrayDeque<>();
                HashSet<Node> reached = new HashSet<>();
                for (SitePinInst spi : net.getPins()) {
                    if (!spi.isOutPin()) continue;
                    Node n0 = spi.getConnectedNode();
                    if (n0 != null && reached.add(n0)) queue.add(n0);
                }
                // Seed from the GRAPH ROOTS as well, not just as a fallback.
                // The writer's own walk starts at roots (per the comment above),
                // so seeding only from the source site pin made this replica
                // STRICTER than the thing it replicates, and it threw away the
                // routing of perfectly good nets.  On the johnson counter it
                // killed led_int[4] -- 8 of 25 pips "unreachable" -- where the
                // unreached chain hung off long lines (LV0/LVB12/LVB0 at
                // different tiles) and the SING-tile IOI's OLOGIC route-through,
                // whose nodes do not chain from the source in RapidWright's
                // model.  Dropping a net's routing is the worst possible
                // outcome for a check-only signoff: Vivado then reports it as
                // unrouted and refuses bitgen, blaming our router for the
                // importer's mistake.
                for (Node s : starts)
                    if (!ends.contains(s) && reached.add(s)) queue.add(s);
                while (!queue.isEmpty()) {
                    Node n = queue.poll();
                    ArrayList<Node> cs = adj.get(n);
                    if (cs == null) continue;
                    for (Node c : cs)
                        if (reached.add(c)) queue.add(c);
                }

                ArrayList<PIP> orphans = new ArrayList<>();
                for (PIP p : pips) {
                    Node s = p.getStartNode();
                    Node e = p.getEndNode();
                    boolean ok = (s != null && reached.contains(s))
                            || (p.isBidirectional() && e != null && reached.contains(e));
                    if (!ok) orphans.add(p);
                }
                if (!orphans.isEmpty()) {
                    System.out.println("WARNING: island/loop in net '" + net.getName()
                            + "' (" + orphans.size() + "/" + pips.size()
                            + " pips unreachable from the source) — dropping the"
                            + " net's routing so the checkpoint can write");
                    int shown = 0;
                    for (PIP p : orphans) {
                        System.out.println("         orphan pip: " + p);
                        if (++shown >= 12) {
                            System.out.println("         ... ("
                                    + (orphans.size() - shown) + " more)");
                            break;
                        }
                    }
                    net.unroute();
                    droppedNets++;
                }
            }
            if (droppedNets > 0)
                System.out.println("[route-sanity] dropped routing of "
                        + droppedNets + " net(s) with islands/loops");
        }

        // Debug bisect aid: J2D_KEEP_ROUTED="lo:hi" keeps routing ONLY on the
        // nets whose (name-sorted) index falls in [lo,hi); every other net is
        // unrouted before the write.  Used to binary-search writer crashes.
        String keepRange = System.getenv("J2D_KEEP_ROUTED");
        if (keepRange != null) {
            String[] lohi = keepRange.split(":");
            int lo = Integer.parseInt(lohi[0]), hi = Integer.parseInt(lohi[1]);
            ArrayList<Net> routed = new ArrayList<>();
            for (Net net : des.getNets())
                if (!net.getPIPs().isEmpty()) routed.add(net);
            routed.sort((x, y) -> x.getName().compareTo(y.getName()));
            int kept = 0, cut = 0;
            for (int i = 0; i < routed.size(); i++) {
                if (i >= lo && i < hi) { kept++; continue; }
                routed.get(i).unroute();
                cut++;
            }
            System.out.println("[bisect] kept " + kept + " routed nets [" + lo
                    + ":" + hi + ") of " + routed.size() + ", unrouted " + cut);
            if (kept > 0 && kept <= 8)
                for (int i = Math.max(lo, 0); i < Math.min(hi, routed.size()); i++)
                    System.out.println("[bisect]   keeping: " + routed.get(i).getName());
        }

        // J2D_DROP_SITES_RE=<regex>: remove SiteInsts (and their cells) whose
        // site name matches.  Used to produce STA-only DCPs: the IOB sites'
        // imported intra-site bindings tag input-port nets onto OUTBUF
        // sitewires (Vivado 18-4866 "sitetype net overwritten"), and Vivado
        // 2020.1's delay estimator then derefs a null logical net in
        // HDPYRoutedSiteBuilder::newRoutedSite and SEGFAULTS on any
        // report_timing.  Dropping the IOB SiteInsts leaves the IO cells
        // unplaced (core timing unaffected).  Default OFF.
        String dropSitesRe = System.getenv("J2D_DROP_SITES_RE");
        if (dropSitesRe != null) {
            java.util.regex.Pattern dp = java.util.regex.Pattern.compile(dropSitesRe);
            int cut = 0;
            for (SiteInst si : new ArrayList<>(des.getSiteInsts())) {
                if (!dp.matcher(si.getName()).find()) continue;
                for (Cell c : new ArrayList<>(si.getCells()))
                    des.removeCell(c);
                des.removeSiteInst(si);
                cut++;
            }
            System.out.println("[drop-sites] removed " + cut
                    + " SiteInsts matching " + dropSitesRe);
        }

        // Debug bisect aid #2: J2D_KEEP_SITES="lo:hi" keeps only the SiteInsts
        // whose (name-sorted) index falls in [lo,hi); the rest are removed
        // before the write.  Used to binary-search per-site writer crashes.
        String keepSites = System.getenv("J2D_KEEP_SITES");
        if (keepSites != null) {
            String[] lohi = keepSites.split(":");
            int lo = Integer.parseInt(lohi[0]), hi = Integer.parseInt(lohi[1]);
            ArrayList<SiteInst> sis = new ArrayList<>(des.getSiteInsts());
            sis.sort((x, y) -> x.getName().compareTo(y.getName()));
            int cut = 0;
            for (int i = 0; i < sis.size(); i++) {
                if (i >= lo && i < hi) continue;
                SiteInst si = sis.get(i);
                for (Cell c : new ArrayList<>(si.getCells()))
                    des.removeCell(c);
                des.removeSiteInst(si);
                cut++;
            }
            System.out.println("[bisect-sites] kept [" + lo + ":" + hi + ") of "
                    + sis.size() + ", removed " + cut);
            for (int i = Math.max(lo, 0); i < Math.min(hi, sis.size()) && i < lo + 8; i++) {
                SiteInst si = sis.get(i);
                System.out.println("[bisect-sites]   keeping: " + si.getName()
                        + " type=" + si.getSiteTypeEnum());
                for (Cell c : si.getCells()) {
                    System.out.println("[bisect-sites]     cell " + c.getName()
                            + " type=" + c.getType() + " bel=" + c.getBELName()
                            + " edif=" + (c.getEDIFCellInst() == null ? "NULL"
                                : c.getEDIFCellInst().getCellType().getName())
                            + " pinmap=" + c.getPinMappingsP2L());
                }
                for (SitePIP sp : si.getUsedSitePIPs())
                    System.out.println("[bisect-sites]     sitepip " + sp);
                for (Net nn2 : des.getNets()) {
                    java.util.List<String> sw = si.getSiteWiresFromNet(nn2);
                    if (sw != null && !sw.isEmpty())
                        System.out.println("[bisect-sites]     net " + nn2.getName()
                                + " sitewires=" + sw);
                }
            }
        }

        for (Net dn : des.getNets()) {
            if (!debug_net(dn.getName())) continue;
            System.out.println("[dbg] FINAL net " + dn.getName()
                + " pips=" + dn.getPIPs().size()
                + " pins=" + dn.getPins().size()
                + " src=" + (dn.getSource() == null ? "NULL" : dn.getSource().toString())
                + " logical=" + (dn.getLogicalNet() == null ? "NULL" : "ok"));
        }
        des.writeCheckpoint(args[2]);
    }

}
