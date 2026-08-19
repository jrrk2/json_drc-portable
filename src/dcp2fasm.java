package dev.fpga.rapidwright;

// Prototype DCP -> FASM emitter (cell-config half only).
//
// Reads a routed DCP via RapidWright and writes the FASM features that
// describe the placed cell configuration -- LUT INITs, FF init/reset
// polarity, and per-slice summary bits.  Routing and IOB configuration
// are deliberately out of scope for the prototype; both get tallied to
// stderr instead so the diff against nextpnr's FASM tells us how big
// each gap is.
//
// Goal: see how close the cell-config half gets us before sinking
// effort into the routing half (#63).

import java.io.PrintStream;
import java.io.FileOutputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.device.BEL;
import com.xilinx.rapidwright.device.BELPin;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.edif.EDIFPropertyValue;

public class dcp2fasm {

    static long lutCount = 0, ffCount = 0, iobCount = 0, pipCount = 0;
    // skipped["IOB"] => count of IOB cells we punted on, etc.
    static Map<String,Integer> skipped = new TreeMap<>();

    // XDC IOSTANDARD/SLEW/DRIVE/PULLTYPE/IN_TERM by top-level port name,
    // pulled from the bundled top.xdc inside the DCP zip.  Vivado /
    // RapidWright stores these on the port; the FASM emitter looks them
    // up by walking the IOB cell back to its top-level port via the
    // padding net.
    static Map<String, Map<String,String>> xdcByPort = new HashMap<>();

    // After the IO pass, per-HCLK ioconfig defaults that fasm.cc emits
    // at HCLK_IOI level for the bank (VREF for SSTL, STEPDOWN, etc.).
    // Only populated -- not yet emitted -- in the cell-config half.
    static Map<String,Boolean> hclkVref = new HashMap<>();
    static Map<String,Boolean> hclkStepdown = new HashMap<>();

    static void bump(String key) {
        skipped.merge(key, 1, Integer::sum);
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: dcp2fasm <in.dcp> <out.fasm>");
            System.exit(2);
        }
        Design des = Design.readCheckpoint(args[0]);
        parseBundledXdc(args[0]);
        PrintStream out = new PrintStream(new FileOutputStream(args[1]));
        emitDesign(des, out, args.length > 2 ? args[2] : null);
    }

    /** Emit FASM from an already-built Design (used by dcp2fasm and xml2fasm).
     *  Walks SiteInsts for cell/site config and Nets for routing PIPs -- needs
     *  only the physical state (cells, SitePIPs, PIPs), NOT the intra-site net
     *  occupancy, so it works on the Design xml2dcp reconstructs from opendcp
     *  XML even though writeCheckpoint can't (the RapidWright site-wire-setter
     *  gap is irrelevant to feature-based FASM). */
    public static void emitDesign(Design des, PrintStream out, String sitepipsFile) throws Exception {
        // Group everything by tile so the output is stable and groupable
        // by-tile (matches nextpnr's FASM grouping convention).
        Map<String, List<String>> byTile = new TreeMap<>();

        // Harvest IOSTANDARD/DRIVE/SLEW/PULLTYPE from the Design's constraint
        // list -- covers both the DCP path (RapidWright loads the bundled XDC)
        // and the xml2fasm path (xml2dcp loaded the opendcp <constraints>).
        parseDesignXdc(des);

        for (SiteInst si : des.getSiteInsts()) {
            String stype = si.getSiteTypeEnum().name();
            if (stype.startsWith("SLICE")) {
                emitSlice(si, byTile);
            } else if (stype.startsWith("IOB")) {
                emitIob(si, byTile);
            } else if (stype.startsWith("BUFG")) {
                emitBufg(si, byTile);
            } else if (stype.startsWith("BUFR") || stype.startsWith("BUFIO")) {
                bump("BUF:" + stype);
            } else if (stype.startsWith("RAMB") || stype.startsWith("FIFO")) {
                emitBram(si, byTile);
            } else if (stype.startsWith("MMCM")) {
                emitMmcm(si, byTile);
            } else if (stype.startsWith("PLL")) {
                bump("CLOCK:" + stype);
            } else if (stype.startsWith("ILOGICE") || stype.startsWith("OLOGICE")) {
                emitIolConfig(si, byTile);
            } else {
                bump("other:" + stype);
            }
        }

        // Optional Vivado sitepips side-file (dump_sitepips.tcl).
        // RapidWright drops dangling OUTMUX site pips (mux programmed
        // but output pin unrouted) from getUsedSitePIPs(); Vivado's own
        // IS_USED dump is ground truth -- emit any OUTMUX selections we
        // missed.  Duplicates are removed at output time.
        if (sitepipsFile != null) {
            BufferedReader br = new BufferedReader(
                new java.io.FileReader(sitepipsFile));
            String ln;
            int extra = 0;
            while ((ln = br.readLine()) != null) {
                ln = ln.trim();
                int slash = ln.indexOf('/'), colon = ln.indexOf(':');
                if (slash < 0 || colon < slash) continue;
                String siteName = ln.substring(0, slash);
                String belName = ln.substring(slash + 1, colon);
                String pinName = ln.substring(colon + 1);
                if (!belName.endsWith("OUTMUX")) continue;
                SiteInst si = des.getSiteInstFromSiteName(siteName);
                if (si == null) continue;
                String feat = si.getTile().getName() + "." + getHalfTag(si)
                            + "." + belName + "." + pinName;
                List<String> tl = byTile.get(si.getTile().getName());
                if (tl == null || !tl.contains(feat)) {
                    addLine(byTile, si.getTile().getName(), feat);
                    extra++;
                }
            }
            br.close();
            System.err.println("[dcp2fasm] sitepips side-file: " + extra
                + " OUTMUX features recovered");
        }

        // Routing-PIP emission -- walk every Net's PIPs and emit the
        // tile-prefixed dst.src line plus any pseudo-pip substitutions.
        emitRouting(des, byTile);

        emitGclkActive(des, byTile);
        emitIobColBankActive(des, byTile);
        emitHclkIoConfig(byTile);
        emitNoClkInvDefaults(des, byTile);

        // BRAM address-cascade activity: Vivado sets CASCOUT_*_ACTIVE
        // whenever a tile's BRAM_CASCOUT_ADDR* wires carry a routed
        // signal (fasm.cc does the same from used wires).  Detect from
        // the pip features already emitted for the tile.
        for (Map.Entry<String, List<String>> e : byTile.entrySet()) {
            String tile = e.getKey();
            if (!tile.startsWith("BRAM_L") && !tile.startsWith("BRAM_R")) continue;
            boolean ard = false, bwr = false;
            for (String l : e.getValue()) {
                if (l.contains("BRAM_CASCOUT_ADDRARDADDR")) ard = true;
                if (l.contains("BRAM_CASCOUT_ADDRBWRADDR")) bwr = true;
            }
            if (ard) e.getValue().add(tile + ".CASCOUT_ARD_ACTIVE");
            if (bwr) e.getValue().add(tile + ".CASCOUT_BWR_ACTIVE");
        }

        for (Map.Entry<String, List<String>> e : byTile.entrySet()) {
            Collections.sort(e.getValue());
            String prev = null;
            for (String line : e.getValue()) {
                if (!line.equals(prev)) out.println(line);
                prev = line;
            }
            out.println();
        }
        out.close();

        System.err.println("[dcp2fasm] luts=" + lutCount + " ffs=" + ffCount
            + " iobs=" + iobCount + " bufgs=" + bufgCount
            + " iol=" + iolCount + " brams=" + bramCount + " pips=" + pipCount);
        if (!skipped.isEmpty()) {
            System.err.println("[dcp2fasm] skipped categories (not yet emitted):");
            for (Map.Entry<String,Integer> e : skipped.entrySet()) {
                System.err.println("    " + e.getKey() + " : " + e.getValue());
            }
        }
    }

    // Within a CLBL{M,L}_{L,R} tile, the lower-instanceX site gets the
    // FASM tag X0, higher gets X1.  CLBLM tiles have one SLICEM (the
    // M-half) and one SLICEL.  Determine the half by checking the
    // companion site in the same tile.
    static String getHalfTag(SiteInst si) {
        Site s = si.getSite();
        Tile t = s.getTile();
        boolean tileIsM = t.getName().startsWith("CLBLM");
        boolean iAmM = si.getSiteTypeEnum().name().equals("SLICEM");
        // Find the other slice in this tile; we're X0 if our instanceX
        // is the lower of the two.
        int myX = s.getInstanceX();
        int otherX = Integer.MAX_VALUE;
        for (Site cand : t.getSites()) {
            if (cand == s) continue;
            if (cand.getName().startsWith("SLICE"))
                otherX = Math.min(otherX, cand.getInstanceX());
        }
        boolean iAmHi = otherX < myX;   // there's a lower-X SLICE -> I'm X1
        if (tileIsM) {
            return iAmHi ? "SLICEL_X1" : "SLICEM_X0";
        } else {
            return iAmHi ? "SLICEL_X1" : "SLICEL_X0";
        }
    }

    static void addLine(Map<String,List<String>> byTile, String tile, String line) {
        byTile.computeIfAbsent(tile, k -> new ArrayList<>()).add(line);
    }

    static void emitSlice(SiteInst si, Map<String,List<String>> byTile) {
        Tile t = si.getTile();
        String tn = t.getName();
        String half = getHalfTag(si);
        String prefix = tn + "." + half + ".";
        // Whether anything in this slice has logical content -- LUT,
        // FF, or even a single placed cell (CARRY4, MUXF7/F8).  nextpnr
        // emits the NOCLKINV slice default for every slice it touches
        // (even routethru-only slices); track it the same way.
        boolean sliceTouched = !si.getCells().isEmpty();

        // CARRY4 PRECYINIT.  fasm.cc emits CIN when the CYINIT input is
        // GND (or routed from below).  Detect by looking at the CARRY4
        // BEL's CYINIT site-pin source.  For CARRY chains > 1 high,
        // PRECYINIT.CIN selects the previous carry-out as the carry-in.
        // Site wires carrying the global VCC net: an empty LUT slot
        // whose O6 wire is VCC is a static logic-1 source (Vivado
        // programs INIT = all-ones with no logical cell present).
        Set<String> vccWires = new HashSet<>();
        try {
            List<String> vw = si.getSiteWiresFromNet(si.getDesign().getVccNet());
            if (vw != null) vccWires.addAll(vw);
        } catch (Exception ex) { /* no vcc net */ }

        // -- per-letter LUT INIT + FF config --------------------------
        boolean ffAny = false;
        if (si.getBEL("WEMUX") != null) {
            com.xilinx.rapidwright.device.SitePIP wePip = si.getUsedSitePIP("WEMUX");
            if (wePip != null)
                addLine(byTile, tn, prefix + "WEMUX." + wePip.getInputPinName());
        }
        // For the slice summary bits.
        boolean anyFFSync = false;     // FDRE/FDSE family
        boolean anyClkInv = false;
        boolean anyClkSeen = false;
        boolean anySRUsed = false;
        boolean anyCEUsed = false;
        boolean anyLatch = false;
        boolean wa7Used = false;       // dist-RAM high write-address pins
        boolean wa8Used = false;

        for (char L : new char[]{'A','B','C','D'}) {
            // OUTMUX SitePIP -- selects which intra-site signal drives
            // the slice's <L>MUX output pin.  fasm.cc emits these as
            // routing-bel features alongside the LUT INIT.  When the
            // SitePIP isn't explicitly bound but the slot has a LUT6
            // cell, Vivado defaults the OUTMUX to O6 (the LUT6 output).
            String outmuxBel = L + "OUTMUX";
            com.xilinx.rapidwright.device.SitePIP omuxPip = si.getUsedSitePIP(outmuxBel);
            if (omuxPip != null) {
                addLine(byTile, tn, prefix + outmuxBel + "." + omuxPip.getInputPinName());
            } else if (si.getCell(L + "6LUT") != null
                       && isLutType(si.getCell(L + "6LUT").getType())
                       && lutOutputUsesOutmux(si, si.getCell(L + "6LUT"), L, half)) {
                // Vivado/RapidWright DROPS the xMUX output site pin AND the
                // <L>OUTMUX SitePIP on load, so neither omuxPip nor the
                // <L>MUX SitePinInst is visible.  Detect OUTMUX.O6 from ROUTING:
                // the LUT6 output net routes INTO this slice's <L>MUX wire
                // (the OUTMUX output).  A LUT output exiting via the DIRECT <L>
                // pin instead does NOT use the OUTMUX, so only the xMUX-egress
                // case sets the bit -- matching golden.
                addLine(byTile, tn, prefix + outmuxBel + ".O6");
            }
            // DI1MUX (SLICEM-only): selects the carry-chain data-in
            // input.  Emit as <slot>LUT.DI1MUX.<inputpin>.  The BEL
            // doesn't exist on SLICEL sites -- guard accordingly.
            String di1muxBel = L + "DI1MUX";
            if (si.getBEL(di1muxBel) != null) {
                com.xilinx.rapidwright.device.SitePIP di1Pip = si.getUsedSitePIP(di1muxBel);
                if (di1Pip != null) {
                    String ip = di1Pip.getInputPinName();
                    // DB names the cascade options structurally
                    if (L == 'A' && !ip.equals("AI")) ip = "BDI1_BMC31";
                    if (L == 'B' && !ip.equals("BI")) ip = "DI_CMC31";
                    if (L == 'C' && !ip.equals("CI")) ip = "DI_DMC31";
                    addLine(byTile, tn,
                        prefix + L + "LUT.DI1MUX." + ip);
                }
            }
            Cell lut6 = si.getCell(L + "6LUT");
            Cell lut5 = si.getCell(L + "5LUT");
            // Build the 64-bit physical-pin-order INIT.  Both halves of
            // the LUT pair (LUT6 + LUT5) contribute to the same FASM
            // line `<slot>LUT.INIT[63:0]`; when only one half is used the
            // other is treated as "always 0" via the per-cell expansion.
            boolean isSrl = false, isSmallSrl = false;
            boolean isRam = false, isSmallRam = false;
            Cell srlCell = null;
            for (Cell lc : new Cell[]{lut6, lut5}) {
                if (lc == null) continue;
                String ty = lc.getType();
                if ("SRLC32E".equals(ty)) { isSrl = true; srlCell = lc; }
                if ("SRL16E".equals(ty)) { isSrl = true; isSmallSrl = true; srlCell = lc; }
                if ("RAMD64E".equals(ty) || "RAMS64E".equals(ty)) isRam = true;
                if ("RAMD32".equals(ty) || "RAMS32".equals(ty)) { isRam = true; isSmallRam = true; }
                // WA7/WA8 only count as used when the mapped logical
                // pin carries a real signal (RAM128/RAM256 deep RAMs);
                // a bare physical pin mapping on RAM32/RAM64 cells does
                // not set the bit in golden bitstreams.
                Map<String,String> p2l = lc.getPinMappingsP2L();
                String wl7 = p2l.get("WA7"), wl8 = p2l.get("WA8");
                if (wl7 != null && cellPinDrivenByLogic(lc, wl7)) wa7Used = true;
                if (wl8 != null && cellPinDrivenByLogic(lc, wl8)) wa8Used = true;
            }
            if (isSrl) {
                addLine(byTile, tn, prefix + L + "LUT.SRL");
                if (isSmallSrl) addLine(byTile, tn, prefix + L + "LUT.SMALL");
                // SRL INIT bit k occupies LUT INIT bits 2k and 2k+1
                int srlBits = isSmallSrl ? 16 : 32;
                String sb = hexPropToBits(srlCell, "INIT", srlBits);
                char[] init = new char[64];
                Arrays.fill(init, '0');
                if (sb != null) {
                    for (int k = 0; k < srlBits; k++) {
                        if (sb.charAt(srlBits - 1 - k) == '1') {
                            init[63 - (2 * k)] = '1';
                            init[63 - (2 * k + 1)] = '1';
                        }
                    }
                }
                addLine(byTile, tn, prefix + L + "LUT.INIT[63:0] = 64'b" + new String(init));
                lutCount++;
            } else if (lut6 != null || lut5 != null) {
                // When the slot's A6 site wire is tied to VCC the LUT is
                // fractured (O5 in use, e.g. CARRY4 xCY0.O5): the 6LUT
                // cell owns only INIT[63:32] even with no 5LUT cell --
                // the low half belongs to the (possibly static) O5.
                boolean frac6 = vccWires.contains(L + "6");
                String bits = lutSlotInitBits(L, lut6, lut5, frac6);
                // Static VCC on the O6 half alongside a real LUT5 cell
                if (lut6 == null && vccWires.contains(L + "6LUT_O6"))
                    bits = repeat('1', 32) + bits.substring(32);
                // Static VCC on the O5 half (no 5LUT cell)
                if (lut5 == null && vccWires.contains(L + "5LUT_O5"))
                    bits = bits.substring(0, 32) + repeat('1', 32);
                addLine(byTile, tn, prefix + L + "LUT.INIT[63:0] = 64'b" + bits);
                if (isRam) {
                    addLine(byTile, tn, prefix + L + "LUT.RAM");
                    if (isSmallRam) addLine(byTile, tn, prefix + L + "LUT.SMALL");
                }
                if (lut6 != null) lutCount++;
                if (lut5 != null) lutCount++;
            } else if (vccWires.contains(L + "6LUT_O6")) {
                // Cell-less static logic-1 LUT
                addLine(byTile, tn, prefix + L + "LUT.INIT[63:0] = 64'b" + repeat('1', 64));
                lutCount++;
            }

            // FFs at *FF and *5FF
            for (String suffix : new String[]{"FF","5FF"}) {
                String belName = L + suffix;
                Cell ff = si.getCell(belName);
                if (ff == null) continue;
                String type = ff.getType();
                if (type == null) continue;
                Boolean[] zinitZrst = ffZinitZrst(ff, type);
                if (zinitZrst == null) {
                    bump("FF-type:" + type);
                    continue;
                }
                ffCount++; ffAny = true;
                if (zinitZrst[0]) addLine(byTile, tn, prefix + belName + ".ZINI");
                if (zinitZrst[1]) addLine(byTile, tn, prefix + belName + ".ZRST");

                // Per-FF input mux (AFFMUX.O6, AFFMUX.O5, A5FFMUX.IN_A, ...).
                // Skip CARRY4_XOR input: nextpnr's FASM treats intra-site
                // CARRY4-sum -> FF.D as implicit; emitting it produces
                // a feature that prjxray's segbit DB doesn't recognise.
                String muxLine = ffMuxLine(si, belName, L, suffix);
                if (muxLine != null && !muxLine.endsWith(".CARRY4_XOR"))
                    addLine(byTile, tn, prefix + muxLine);

                // Sync vs async, clk inversion: derived from FF type
                boolean sync = type.startsWith("FDRE") || type.startsWith("FDSE");
                anyFFSync |= sync;
                boolean negedge = type.endsWith("_1");
                boolean clkInvProp = getBoolParam(ff, "IS_CLK_INVERTED");
                boolean clkInv = negedge || clkInvProp;
                if (anyClkSeen && (clkInv != anyClkInv)) {
                    // Inconsistent per-FF clk polarity within a half --
                    // shouldn't happen on real designs; flag.
                    bump("FF-clk-inconsistent:" + tn);
                }
                anyClkInv = clkInv; anyClkSeen = true;

                if (type.startsWith("LD")) anyLatch = true;

                // SR/CE used: derive from the cell's net connections.
                if (hasSRNet(ff)) anySRUsed = true;
                if (hasCENet(ff)) anyCEUsed = true;
            }
        }

        // Per-slice summary bits.  Match nextpnr's fasm.cc:
        //   - NOCLKINV / CLKINV is emitted unconditionally for every
        //     SLICE that gets touched (nextpnr emits it even on slices
        //     with no FFs -- it's a polarity default the assembler
        //     reads from the segbits DB).
        //   - FFSYNC / SRUSEDMUX / CEUSEDMUX / LATCH only when an FF
        //     in this half asks for them.
        if (sliceTouched || ffAny) {
            addLine(byTile, tn, prefix + (anyClkInv ? "CLKINV" : "NOCLKINV"));
            // PRECYINIT default selector.  When a CARRY4 sits in this
            // slice the CARRY4 cell's SitePIP picks CIN / AX / C0 / C1.
            // Otherwise the bit defaults to C0 and Vivado's bitgen emits
            // PRECYINIT.C0 on every touched slice.
            Cell carry4 = si.getCell("CARRY4");
            if (carry4 != null) {
                // Per-row DI source: <L>CY0 sitepip on O5 selects the
                // 5LUT O5 output as the carry DI (bit set); on the
                // bypass pin (AX/BX/CX/DX) the bit stays clear.
                for (char L : new char[]{'A','B','C','D'}) {
                    com.xilinx.rapidwright.device.SitePIP cy0 = si.getUsedSitePIP(L + "CY0");
                    if (cy0 != null && "O5".equals(cy0.getInputPinName()))
                        addLine(byTile, tn, prefix + "CARRY4." + L + "CY0");
                }
                com.xilinx.rapidwright.device.SitePIP precy = si.getUsedSitePIP("PRECYINIT");
                if (precy != null) {
                    String pin = precy.getInputPinName();
                    String featPin = pin.equals("0") ? "C0"
                                   : pin.equals("1") ? "C1"
                                   : pin;
                    addLine(byTile, tn, prefix + "PRECYINIT." + featPin);
                } else {
                    // No PRECYINIT SitePIP means the mux passes the dedicated
                    // carry-in, so the question is only whether a carry-in is
                    // actually there.  The logical netlist answers it when the
                    // EDIF carries the CI connection -- but a DCP rebuilt from a
                    // netlist whose ports are named LOGICALLY (CI) rather than
                    // in nextpnr's packed form (CIN) has no logical portref for
                    // it, and every cascading CARRY4 then reported C0 instead of
                    // CIN (138 of them).  The PHYSICAL evidence is unambiguous
                    // and present either way: a CIN site pin carrying a real
                    // (non-constant) net.  Take either.
                    boolean cin = cellPinDrivenByLogic(carry4, "CI");
                    if (!cin) {
                        com.xilinx.rapidwright.design.SitePinInst cinPin = si.getSitePinInst("CIN");
                        if (cinPin != null && cinPin.getNet() != null) {
                            String cn = cinPin.getNet().getName();
                            cin = cn != null && !cn.equals("GND") && !cn.equals("VCC")
                                  && !cn.equals("<const0>") && !cn.equals("<const1>")
                                  && !cn.equals("$PACKER_GND_NET") && !cn.equals("$PACKER_VCC_NET");
                        }
                    }
                    addLine(byTile, tn, prefix + (cin ? "PRECYINIT.CIN" : "PRECYINIT.C0"));
                }
            } else {
                addLine(byTile, tn, prefix + "PRECYINIT.C0");
            }
        }
        if (ffAny) {
            if (anyFFSync) addLine(byTile, tn, prefix + "FFSYNC");
            if (anyLatch)  addLine(byTile, tn, prefix + "LATCH");
            if (anySRUsed) addLine(byTile, tn, prefix + "SRUSEDMUX");
            if (anyCEUsed) addLine(byTile, tn, prefix + "CEUSEDMUX");
        }
        if (wa7Used) addLine(byTile, tn, prefix + "WA7USED");
        if (wa8Used) addLine(byTile, tn, prefix + "WA8USED");
    }

    // Determine the ZINI / ZRST polarity bits.  Mirrors fasm.cc:
    //   ZINI is set when INIT==0 (note: Z = "zero", and the segbit
    //   actually controls "init-is-not-1").
    //   ZRST is set for the R/CE family (clear-to-0), unset for the
    //   S/PE family (clear-to-1).
    static Boolean[] ffZinitZrst(Cell ff, String type) {
        boolean init1 = getBoolParam(ff, "INIT");
        boolean zinit = !init1;
        Boolean zrst;
        if (type.startsWith("FDRE") || type.startsWith("FDCE") || type.startsWith("LDCE")) {
            zrst = Boolean.TRUE;
        } else if (type.startsWith("FDSE") || type.startsWith("FDPE") || type.startsWith("LDPE")) {
            zrst = Boolean.FALSE;
        } else {
            return null;
        }
        return new Boolean[]{zinit, zrst};
    }

    // FFMUX input selector.  For *FF: feeds from O5/O6/CY/XOR/F7/F8/X/AX/...
    // For *5FF: feeds from IN_A (the LUT5 output), IN_B, etc.
    // Prototype: walk the SiteInst's sitePIPs and find one whose output
    // BEL matches the FFMUX/5FFMUX for this slot.
    // The carry sum reaching a slice mux is spelled "XOR" by Vivado and
    // "CARRY4_XOR" by a SitePIP that RapidWright selected when json2dcp built
    // the checkpoint.  Same physical selection, two names -- and the mismatch is
    // not cosmetic here: the lookup below compares the used SitePIP's input pin
    // against the BEL's pin name, so a disagreement returns null and the feature
    // is DROPPED rather than misspelled (251 of them: AFFMUX/BFFMUX/CFFMUX/
    // DFFMUX.XOR).  Fold the two spellings together and report Vivado's, which
    // is what dcp2fasm produces from Vivado's own DCP.
    static String normFFMuxPin(String pin) {
        return "CARRY4_XOR".equals(pin) ? "XOR" : pin;
    }

    static String ffMuxLine(SiteInst si, String ffBel, char slot, String suffix) {
        String muxBel = (suffix.equals("5FF") ? (slot + "5FFMUX") : (slot + "FFMUX"));
        com.xilinx.rapidwright.device.SitePIP pip = null;
        for (BEL bel : si.getBELs()) {
            if (!bel.getName().equals(muxBel)) continue;
            for (BELPin in : bel.getPins()) {
                if (!in.isInput()) continue;
                for (com.xilinx.rapidwright.device.SitePIP cand : in.getSitePIPs()) {
                    if (si.getUsedSitePIP(cand.getBELName()) != null
                        && normFFMuxPin(si.getUsedSitePIP(cand.getBELName()).getInputPinName())
                               .equals(normFFMuxPin(in.getName()))) {
                        pip = cand;
                        break;
                    }
                }
                if (pip != null) break;
            }
            if (pip != null) break;
        }
        if (pip == null) return null;
        return muxBel + "." + normFFMuxPin(pip.getInputPinName());
    }

    static boolean getBoolParam(Cell c, String name) {
        EDIFPropertyValue v = c.getProperty(name);
        if (v == null) return false;
        String s = v.getValue();
        if (s == null) return false;
        // INIT-style: "1'b1", "1'b0", or just "0"/"1"
        s = s.trim();
        if (s.endsWith("'b1") || s.endsWith("'h1") || s.equals("1") || s.equalsIgnoreCase("TRUE")) return true;
        return false;
    }

    // Look at the FF's logical pins and check whether the named pin is
    // connected to a real net (i.e., not VCC/GND/<unused>).
    // For FDRE/FDSE/FDCE/FDPE the pin names are R/S/CLR/PRE for the
    // reset family and CE for clock-enable.  fasm.cc treats SR as
    // "used" when the connected net isn't the packer GND, and CE as
    // "used" when not the packer VCC.
    static boolean cellPinDrivenByLogic(Cell ff, String pinName) {
        if (ff == null) return false;
        com.xilinx.rapidwright.edif.EDIFCellInst inst = ff.getEDIFCellInst();
        if (inst == null) return false;
        com.xilinx.rapidwright.edif.EDIFPortInst portInst = inst.getPortInst(pinName);
        if (portInst == null) return false;
        com.xilinx.rapidwright.edif.EDIFNet net = portInst.getNet();
        if (net == null) return false;
        String nm = net.getName();
        if (nm == null) return false;
        // GND/VCC pseudo-nets shouldn't activate the slice mux.
        if (nm.equals("GND") || nm.equals("VCC")) return false;
        if (nm.equals("<const0>") || nm.equals("<const1>")) return false;
        if (nm.equals("$PACKER_GND_NET") || nm.equals("$PACKER_VCC_NET")) return false;
        return true;
    }

    // True when a LUT cell's output net routes INTO this slice's <L>MUX wire
    // (the OUTMUX output) -> the OUTMUX selects O6.  Detected from the net's
    // PIPs (RapidWright drops the xMUX site pin + SitePIP on load).  A LUT
    // output exiting via the DIRECT <L> pin does NOT match.
    static boolean lutOutputUsesOutmux(SiteInst si, Cell lut, char L, String half) {
        if (lut == null) return false;
        com.xilinx.rapidwright.edif.EDIFCellInst inst = lut.getEDIFCellInst();
        if (inst == null) return false;
        com.xilinx.rapidwright.edif.EDIFPortInst po = inst.getPortInst("O");
        if (po == null) return false;
        com.xilinx.rapidwright.edif.EDIFNet en = po.getNet();
        if (en == null) return false;
        Design des = si.getDesign();
        if (des == null) return false;
        String local = en.getName();
        String parent = "";
        try {
            com.xilinx.rapidwright.edif.EDIFHierCellInst ehci = lut.getEDIFHierCellInst();
            if (ehci != null && ehci.getParent() != null)
                parent = ehci.getParent().getFullHierarchicalInstName();
        } catch (Throwable t) {}
        Net pn = null;
        if (parent != null && !parent.isEmpty()) pn = des.getNet(parent + "/" + local);
        if (pn == null) pn = des.getNet(local);
        if (pn == null || pn.getPIPs() == null) return false;
        // this slice's xMUX wire name.  X1 (SLICEL) uses the tile-type prefix
        // directly (CLBLM_L_BMUX); X0 uses the M/LL variant (CLBLM_M_BMUX /
        // CLBLL_LL_BMUX).
        // The xMUX wire prefix is normalized by SITE position and is INDEPENDENT
        // of the tile's _L/_R suffix: a CLBLM_R tile's X1 SLICEL still uses the
        // CLBLM_L_ prefix (not CLBLM_R_).  CLBLM X1->CLBLM_L, X0->CLBLM_M;
        // CLBLL X1->CLBLL_L, X0->CLBLL_LL.
        String tt = si.getTile().getName().replaceAll("_X\\d+Y\\d+$", "");
        boolean isM = tt.startsWith("CLBLM");
        boolean x1 = half.endsWith("_X1");
        String xmuxWire = (x1 ? (isM ? "CLBLM_L" : "CLBLL_L")
                              : (isM ? "CLBLM_M" : "CLBLL_LL")) + "_" + L + "MUX";
        String myTile = si.getTile().getName();
        for (com.xilinx.rapidwright.device.PIP p : pn.getPIPs()) {
            if (!p.getTile().getName().equals(myTile)) continue;
            if (xmuxWire.equals(p.getEndWireName()) || xmuxWire.equals(p.getStartWireName()))
                return true;
        }
        return false;
    }

    // True when the named logical pin is tied to the VCC const net.
    static boolean cellPinTiedToVcc(Cell c, String pinName) {
        if (c == null) return false;
        com.xilinx.rapidwright.edif.EDIFCellInst inst = c.getEDIFCellInst();
        if (inst == null) return false;
        com.xilinx.rapidwright.edif.EDIFPortInst portInst = inst.getPortInst(pinName);
        if (portInst == null) return false;
        com.xilinx.rapidwright.edif.EDIFNet net = portInst.getNet();
        if (net == null) return false;
        String nm = net.getName();
        return nm != null && (nm.equals("VCC") || nm.equals("<const1>")
                              || nm.equals("$PACKER_VCC_NET"));
    }

    static boolean hasSRNet(Cell ff) {
        for (String n : new String[]{"R","S","CLR","PRE"}) {
            if (cellPinDrivenByLogic(ff, n)) return true;
        }
        return false;
    }

    static boolean hasCENet(Cell ff) {
        return cellPinDrivenByLogic(ff, "CE");
    }

    static boolean isLutType(String t) {
        if (t == null) return false;
        return t.equals("LUT1") || t.equals("LUT2") || t.equals("LUT3")
            || t.equals("LUT4") || t.equals("LUT5") || t.equals("LUT6")
            || t.equals("LUT6_2");
    }

    // Build the 64-bit physical-pin-order INIT for a LUT slot (A/B/C/D),
    // combining the optional LUT6 and LUT5 halves.
    //
    // The DCP stores each cell's INIT in *logical* pin order; the FASM
    // wants the truth table in *physical* BEL-pin order (A1..A6 = bit
    // 0..5 of the index).  RapidWright's `getPinMappingsP2L()` gives the
    // physical -> logical map per cell; we expand each cell to its 64-
    // bit physical truth table and OR them together (LUT5 only ever
    // writes the low 32 bits, gated on physical A6 == 0; LUT6 writes
    // all 64).
    static String lutSlotInitBits(char slot, Cell lut6, Cell lut5, boolean frac6) {
        long phys = 0;
        boolean paired = (lut6 != null && lut5 != null) || frac6;
        // Bit-range ownership:
        //   6LUT-position cell: bits 32..63 when paired, all 64 solo.
        //   5LUT-position cell: only ever bits 0..31 -- Vivado leaves
        //   INIT[63:32] zero for a solo 5LUT (the O6 half is unused),
        //   unlike a solo 6LUT cell which owns all 64 bits.
        if (lut6 != null) {
            phys |= expandLutToPhys(lut6, paired ? 32 : 0, 64);
        }
        if (lut5 != null) {
            phys |= expandLutToPhys(lut5, 0, 32);
        }
        StringBuilder sb = new StringBuilder(64);
        for (int i = 63; i >= 0; i--) sb.append(((phys >>> i) & 1L) == 1 ? '1' : '0');
        return sb.toString();
    }

    // Expand a single LUT cell to its 64-bit physical truth table.
    // For LUT5 (lowHalfOnly=true), only bits 0..31 are populated and
    // returned; the upper 32 bits stay 0.
    static long expandLutToPhys(Cell lut, int lbound, int ubound) {
        // Routethru (CARRY4 DI/S buffers, LUT-to-FF passthrough):
        // RapidWright marks these with isRoutethru() and the *target*
        // cell's type (CARRY4, FDRE, ...).  The bitstream is a buffer
        // LUT through whichever physical input pin Vivado entered on
        // (NOT always A6): INIT bit p = ((p >> pinIdx) & 1).
        if (lut.isRoutethru() && !isLutType(lut.getType())) {
            int pinIdx = -1;
            for (String pp : lut.getPinMappingsP2L().keySet()) {
                if (pp != null && pp.length() == 2 && pp.charAt(0) == 'A'
                    && pp.charAt(1) >= '1' && pp.charAt(1) <= '6') {
                    pinIdx = pp.charAt(1) - '1';
                    break;
                }
            }
            if (pinIdx < 0) pinIdx = 5;   // fallback: A6 buffer
            long out = 0;
            for (int p = lbound; p < ubound; p++)
                if (((p >> pinIdx) & 1) == 1) out |= (1L << p);
            return out;
        }
        long logicalInit = parseInitHexToLong(lut.getProperty("INIT"));
        // Map: physical BEL-pin index (0..5) -> logical input index
        // (0..5).  E.g. for slot 'A', physical pin "A1" is index 0.
        // Unused physical pins (no mapping) leave the output independent
        // of that pin -- we duplicate the value across both halves.
        int[] physToLog = new int[6];        // -1 means "unused"
        Arrays.fill(physToLog, -1);
        Map<String,String> p2l = lut.getPinMappingsP2L();
        for (Map.Entry<String,String> e : p2l.entrySet()) {
            String pp = e.getKey();             // "A1".."A6" (BEL-local naming
                                                // regardless of SLICE slot)
            String lp = e.getValue();           // "I0".."I5" (or "O" for output)
            if (pp == null || lp == null) continue;
            if (pp.length() != 2) continue;
            // RapidWright names the LUT BEL pins "A1".."A6" universally
            // (BEL-local naming, not SLICE-slot naming) -- only the
            // numeric suffix carries the input index here.  Skip non-
            // input pins (e.g. "O6" output -> "O").
            char d = pp.charAt(1);
            if (d < '1' || d > '6') continue;
            int physIdx = d - '1';              // A1->0, A6->5
            // Logical input naming: I<n> for LUTs, RADR<n> for
            // distributed-RAM cells (RAMD32/RAMS32/RAMD64E/RAMS64E).
            String num = lp.startsWith("RADR") ? lp.substring(4)
                       : lp.startsWith("I")    ? lp.substring(1)
                       : null;
            if (num == null) continue;          // skip "O"/"O5"/"O6" outputs
            try {
                int logIdx = Integer.parseInt(num);
                if (logIdx >= 0 && logIdx < 6) physToLog[physIdx] = logIdx;
            } catch (NumberFormatException ex) { /* skip */ }
        }
        long out = 0;
        for (int p = lbound; p < ubound; p++) {
            // Build the logical-input index from the physical-bit values.
            int l = 0;
            for (int phys = 0; phys < 6; phys++) {
                int lg = physToLog[phys];
                if (lg < 0) continue;
                if (((p >> phys) & 1) == 1) l |= (1 << lg);
            }
            if (((logicalInit >>> l) & 1L) == 1L) out |= (1L << p);
        }
        return out;
    }

    // Parse a Vivado INIT property like "64'h1234..." or "32'h...".
    // Returns the low 64 bits as a long.
    static long parseInitHexToLong(EDIFPropertyValue v) {
        if (v == null) return 0;
        String s = v.getValue();
        if (s == null) return 0;
        String body = s.trim();
        int apos = body.indexOf('\'');
        if (apos >= 0) body = body.substring(apos + 2);    // strip "64'h"
        body = body.replace("_","").toLowerCase().trim();
        long val = 0;
        for (char c : body.toCharArray()) {
            int n = Character.digit(c, 16);
            if (n < 0) continue;
            val = (val << 4) | n;
        }
        return val;
    }

    // Legacy hex->bit-string helper (still used for parts of the
    // expansion path that don't need pin permutation).
    static String lutInitBits(Cell lut, int width) {
        EDIFPropertyValue v = lut.getProperty("INIT");
        if (v == null) return repeat('0', width);
        String s = v.getValue();
        if (s == null) return repeat('0', width);
        // Vivado writes INIT as "64'h<hex>" or "<dec>'h<hex>" or "<hex>".
        long initLow = 0, initHigh = 0;   // 128-bit max; only 64 used here
        String body = s;
        int apos = s.indexOf('\'');
        if (apos >= 0) body = s.substring(apos + 2);   // strip "64'h"
        // Parse hex string up to 'width/4' nibbles into two longs.
        body = body.replace("_","").toLowerCase().trim();
        // Pad / truncate.
        int wantNibbles = (width + 3) / 4;
        if (body.length() < wantNibbles)
            body = repeat('0', wantNibbles - body.length()) + body;
        else if (body.length() > wantNibbles)
            body = body.substring(body.length() - wantNibbles);
        // Convert hex to bit string.
        StringBuilder bits = new StringBuilder();
        for (char c : body.toCharArray()) {
            int n = Character.digit(c, 16);
            if (n < 0) n = 0;
            for (int i = 3; i >= 0; i--) bits.append(((n >> i) & 1) == 1 ? '1' : '0');
        }
        // Trim if we over-padded.
        if (bits.length() > width) bits.delete(0, bits.length() - width);
        return bits.toString();
    }

    // ===================================================================
    // BRAM FASM emission -- ports nextpnr-xilinx fasm.cc write_bram_half /
    // write_bram_width / write_bram_init.  RAMB36E1 occupies both
    // RAMB18_Y0 and RAMB18_Y1 halves of the BRAM_L/R tile, with widths
    // halved and the INIT bit lanes de-interleaved per half.
    // ===================================================================
    static int bramCount = 0;

    static String hexPropToBits(Cell c, String name, int width) {
        EDIFPropertyValue v = c.getEDIFCellInst().getProperty(name);
        if (v == null) return null;
        String sVal = v.getValue();
        if (sVal == null) return null;
        int apos = sVal.indexOf('\'');
        String body = (apos >= 0) ? sVal.substring(apos + 2) : sVal;
        body = body.replace("_", "").toLowerCase().trim();
        int wantNibbles = (width + 3) / 4;
        if (body.length() < wantNibbles)
            body = repeat('0', wantNibbles - body.length()) + body;
        else if (body.length() > wantNibbles)
            body = body.substring(body.length() - wantNibbles);
        StringBuilder bits = new StringBuilder();
        for (char ch : body.toCharArray()) {
            int n = Character.digit(ch, 16);
            if (n < 0) n = 0;
            for (int i = 3; i >= 0; i--) bits.append(((n >> i) & 1) == 1 ? '1' : '0');
        }
        if (bits.length() > width) bits.delete(0, bits.length() - width);
        return bits.toString();   // MSB first: index (width-1-k) holds bit k
    }

    static double doubleProp(Cell c, String name, double def) {
        EDIFPropertyValue v = c.getEDIFCellInst().getProperty(name);
        if (v == null) return def;
        try { return Double.parseDouble(v.getValue().trim()); }
        catch (Exception e) { return def; }
    }

    // MMCM lock/filter lookup tables, extracted from nextpnr fasm.cc
    // write_mmcm() (the HW-validated version: TABLE=filter[mult-1],
    // FILTREG1=0x8, POWER_REG=0x100).  Index = CLKFBOUT_MULT_F - 1.
    static final long[] MMCM_LKTABLE = {
        0x31BE8FA401L, 0x31BE8FA401L, 0x423E8FA401L, 0x5AFE8FA401L,
        0x73BE8FA401L, 0x8C7E8FA401L, 0x9CFE8FA401L, 0xB5BE8FA401L,
        0xCE7E8FA401L, 0xE73E8FA401L, 0xFFF84FA401L, 0xFFF39FA401L,
        0xFFEEEFA401L, 0xFFEBCFA401L, 0xFFE8AFA401L, 0xFFE71FA401L,
        0xFFE3FFA401L, 0xFFE26FA401L, 0xFFE0DFA401L, 0xFFDF4FA401L,
        0xFFDDBFA401L, 0xFFDC2FA401L, 0xFFDA9FA401L, 0xFFD90FA401L,
        0xFFD90FA401L, 0xFFD77FA401L, 0xFFD5EFA401L, 0xFFD5EFA401L,
        0xFFD45FA401L, 0xFFD45FA401L, 0xFFD2CFA401L, 0xFFD2CFA401L,
        0xFFD2CFA401L, 0xFFD13FA401L, 0xFFD13FA401L, 0xFFD13FA401L,
        0xFFCFAFA401L, 0xFFCFAFA401L, 0xFFCFAFA401L, 0xFFCFAFA401L,
        0xFFCFAFA401L, 0xFFCFAFA401L, 0xFFCFAFA401L, 0xFFCFAFA401L,
        0xFFCFAFA401L, 0xFFCFAFA401L, 0xFFCFAFA401L, 0xFFCFAFA401L,
        0xFFCFAFA401L, 0xFFCFAFA401L, 0xFFCFAFA401L, 0xFFCFAFA401L,
        0xFFCFAFA401L, 0xFFCFAFA401L, 0xFFCFAFA401L, 0xFFCFAFA401L,
        0xFFCFAFA401L, 0xFFCFAFA401L, 0xFFCFAFA401L, 0xFFCFAFA401L,
        0xFFCFAFA401L, 0xFFCFAFA401L, 0xFFCFAFA401L,
    };
    static final int[] MMCM_FILT_LOW = {
        0x0BC, 0x0BC, 0x0BC, 0x0BC, 0x09C, 0x0AC, 0x0B4, 0x08C,
        0x094, 0x094, 0x0A4, 0x0B8, 0x0B8, 0x0B8, 0x0B8, 0x084,
        0x084, 0x084, 0x098, 0x098, 0x098, 0x098, 0x098, 0x098,
        0x098, 0x0A8, 0x0A8, 0x0A8, 0x0A8, 0x0A8, 0x0B0, 0x0B0,
        0x0B0, 0x0B0, 0x0B0, 0x0B0, 0x0B0, 0x0B0, 0x0B0, 0x0B0,
        0x0B0, 0x0B0, 0x0B0, 0x0B0, 0x0B0, 0x0B0, 0x0B0, 0x088,
        0x088, 0x088, 0x088, 0x088, 0x088, 0x088, 0x088, 0x088,
        0x088, 0x088, 0x088, 0x088, 0x088, 0x088, 0x088, 0x088,
    };
    static final int[] MMCM_FILT_LOW_SS = {
        0x0BF, 0x0BF, 0x0BF, 0x0BF, 0x09F, 0x0AF, 0x0B7, 0x08F,
        0x097, 0x097, 0x0A7, 0x0BB, 0x0BB, 0x0BB, 0x0BB, 0x087,
        0x087, 0x087, 0x09B, 0x09B, 0x09B, 0x09B, 0x09B, 0x09B,
        0x09B, 0x0AB, 0x0AB, 0x0AB, 0x0AB, 0x0AB, 0x0B3, 0x0B3,
        0x0B3, 0x0B3, 0x0B3, 0x0B3, 0x0B3, 0x0B3, 0x0B3, 0x0B3,
        0x0B3, 0x0B3, 0x0B3, 0x0B3, 0x0B3, 0x0B3, 0x0B3, 0x08B,
        0x08B, 0x08B, 0x08B, 0x08B, 0x08B, 0x08B, 0x08B, 0x08B,
        0x08B, 0x08B, 0x08B, 0x08B, 0x08B, 0x08B, 0x08B, 0x08B,
    };
    static final int[] MMCM_FILT_HIGH = {
        0x0BC, 0x13C, 0x16C, 0x1DC, 0x35C, 0x3AC, 0x3B4, 0x3CC,
        0x394, 0x3D4, 0x3E4, 0x344, 0x3E4, 0x3E4, 0x3E4, 0x3E4,
        0x3D4, 0x3D4, 0x304, 0x304, 0x304, 0x170, 0x170, 0x170,
        0x170, 0x0D0, 0x0D0, 0x0D0, 0x0D0, 0x0D0, 0x0D0, 0x0D0,
        0x0D0, 0x0D0, 0x0D0, 0x0D0, 0x0D0, 0x0D0, 0x0D0, 0x0D0,
        0x0D0, 0x0A0, 0x0A0, 0x0A0, 0x0A0, 0x0A0, 0x1C4, 0x1C4,
        0x130, 0x130, 0x130, 0x130, 0x184, 0x184, 0x158, 0x158,
        0x158, 0x090, 0x090, 0x090, 0x090, 0x128, 0x0F0, 0x0F0,
    };
    static final int[] MMCM_FILT_OPTIMIZED = MMCM_FILT_HIGH;

    static void mmcmVec(List<String> lines, String prefix, String name,
                        long value, int width) {
        StringBuilder sb = new StringBuilder();
        for (int i = width - 1; i >= 0; i--)
            sb.append(((value >>> i) & 1L) == 1L ? '1' : '0');
        lines.add(prefix + name + "[" + (width - 1) + ":0] = "
                  + width + "'b" + sb);
    }

    // One MMCM output counter (DIVCLK / CLKFBOUT / CLKOUT0-6) -- ported
    // from fasm.cc write_mmcm_clkout (HW-validated encoder).
    static void emitMmcmClkout(List<String> lines, String prefix, Cell c,
                               String name) {
        int high = 1, low = 1, phasemux = 0, delaytime = 0, frac = 0;
        boolean noCount = false, edge = false;
        String divProp = name + (name.equals("CLKFBOUT") ? "_MULT_F"
                       : name.equals("CLKOUT0") ? "_DIVIDE_F" : "_DIVIDE");
        double divide = doubleProp(c, divProp, 1);
        double phase = doubleProp(c, name + "_PHASE", 0);
        if (divide <= 1) {
            noCount = true;
        } else {
            high = (int)Math.floor(divide / 2);
            low = (int)(Math.floor(divide) - high);
            if (high != low) edge = true;
            if (name.equals("CLKOUT0") || name.equals("CLKFBOUT"))
                frac = (int)(Math.floor(divide * 8) - Math.floor(divide) * 8);
            int phaseEights = (int)Math.floor((phase / 360) * divide * 8);
            phasemux = ((phaseEights % 8) + 8) % 8;
            delaytime = phaseEights / 8;
        }
        boolean used = name.equals("DIVCLK") || name.equals("CLKFBOUT");
        if (!used) {
            com.xilinx.rapidwright.edif.EDIFCellInst inst = c.getEDIFCellInst();
            com.xilinx.rapidwright.edif.EDIFPortInst pi =
                inst == null ? null : inst.getPortInst(name);
            used = pi != null && pi.getNet() != null;
        }
        if (name.equals("DIVCLK")) {
            mmcmVec(lines, prefix, "DIVCLK_DIVCLK_HIGH_TIME", high, 6);
            mmcmVec(lines, prefix, "DIVCLK_DIVCLK_LOW_TIME", low, 6);
            if (edge) lines.add(prefix + "DIVCLK_DIVCLK_EDGE[0]");
            if (noCount) lines.add(prefix + "DIVCLK_DIVCLK_NO_COUNT[0]");
        } else if (used) {
            boolean is56 = name.equals("CLKOUT5") || name.equals("CLKOUT6");
            boolean isC0 = name.equals("CLKOUT0");
            boolean isFb = name.equals("CLKFBOUT");
            if ((isC0 || isFb) && frac != 0) {
                --high; --low;
                int fracShifted = frac >> 1;
                String fcn = isC0 ? "CLKOUT5_CLKOUT2_" : "CLKOUT6_CLKOUT2_";
                if (fracShifted >= 1) {
                    lines.add(prefix + fcn + "FRACTIONAL_FRAC_WF_F[0]");
                    mmcmVec(lines, prefix, fcn + "FRACTIONAL_PHASE_MUX_F", fracShifted, 2);
                }
            }
            lines.add(prefix + name + "_CLKOUT1_OUTPUT_ENABLE[0]");
            mmcmVec(lines, prefix, name + "_CLKOUT1_HIGH_TIME", high, 6);
            mmcmVec(lines, prefix, name + "_CLKOUT1_LOW_TIME", low, 6);
            mmcmVec(lines, prefix, name + "_CLKOUT1_PHASE_MUX", phasemux, 3);
            if (edge) lines.add(prefix + name +
                (is56 ? "_CLKOUT2_FRACTIONAL_EDGE[0]" : "_CLKOUT2_EDGE[0]"));
            if (noCount) lines.add(prefix + name +
                (is56 ? "_CLKOUT2_FRACTIONAL_NO_COUNT[0]" : "_CLKOUT2_NO_COUNT[0]"));
            mmcmVec(lines, prefix, name +
                (is56 ? "_CLKOUT2_FRACTIONAL_DELAY_TIME" : "_CLKOUT2_DELAY_TIME"),
                delaytime, 6);
            if (!is56 && frac != 0) {
                lines.add(prefix + name + "_CLKOUT2_FRAC_EN[0]");
                lines.add(prefix + name + "_CLKOUT2_FRAC_WF_R[0]");
                mmcmVec(lines, prefix, name + "_CLKOUT2_FRAC", frac, 3);
            }
        } else {
            // Unused counter: Vivado programs divide-by-1 with NO_COUNT
            // (HIGH_TIME=1, LOW_TIME=1); all-zero registers never match
            // golden bitstreams.
            boolean is56 = name.equals("CLKOUT5") || name.equals("CLKOUT6");
            mmcmVec(lines, prefix, name + "_CLKOUT1_HIGH_TIME", 1, 6);
            mmcmVec(lines, prefix, name + "_CLKOUT1_LOW_TIME", 1, 6);
            lines.add(prefix + name +
                (is56 ? "_CLKOUT2_FRACTIONAL_NO_COUNT[0]" : "_CLKOUT2_NO_COUNT[0]"));
        }
    }

    static void emitMmcm(SiteInst si, Map<String,List<String>> byTile) {
        Cell c = null;
        for (Cell sc : si.getCells()) {
            String t = sc.getType();
            if (t != null && t.startsWith("MMCME2")) { c = sc; break; }
        }
        if (c == null) { bump("MMCM:no-cell"); return; }
        String tn = si.getTile().getName();
        String prefix = tn + ".MMCME2_ADV.";
        List<String> lines = new ArrayList<>();
        lines.add(prefix + "IN_USE");
        // ZINV on the static control pins: golden sets them when the pin
        // is constant-tied (not logic-driven) and not param-inverted;
        // a logic-driven pin (e.g. a real RST) leaves the bit clear.
        for (String p : new String[]{"PWRDWN","RST","PSEN","PSINCDEC"}) {
            if (!cellPinDrivenByLogic(c, p)
                && !getBoolParam(c, "IS_" + p + "_INVERTED"))
                lines.add(prefix + "ZINV_" + p);
        }
        if (getBoolParam(c, "IS_CLKINSEL_INVERTED"))
            lines.add(prefix + "INV_CLKINSEL");
        for (String n : new String[]{"DIVCLK","CLKFBOUT","CLKOUT0","CLKOUT1",
                                     "CLKOUT2","CLKOUT3","CLKOUT4","CLKOUT5","CLKOUT6"})
            emitMmcmClkout(lines, prefix, c, n);
        String comp = strProp(c, "COMPENSATION", "INTERNAL");
        if (comp.equals("INTERNAL") || comp.equals("ZHOLD"))
            lines.add(prefix + "COMP.Z_ZHOLD");
        else
            System.err.println("[dcp2fasm] WARNING: unsupported MMCM COMPENSATION " + comp);
        int mult = (int)doubleProp(c, "CLKFBOUT_MULT_F", 5.0);
        if (mult < 1) mult = 1;
        if (mult > 63) mult = 63;
        mmcmVec(lines, prefix, "LKTABLE", MMCM_LKTABLE[mult - 1], 40);
        String bw = strProp(c, "BANDWIDTH", "OPTIMIZED");
        int[] filt = bw.equals("LOW") ? MMCM_FILT_LOW
                   : bw.equals("LOW_SS") ? MMCM_FILT_LOW_SS
                   : bw.equals("HIGH") ? MMCM_FILT_HIGH
                   : MMCM_FILT_OPTIMIZED;
        mmcmVec(lines, prefix, "FILTREG1_RESERVED", 0x8, 12);
        mmcmVec(lines, prefix, "POWER_REG_POWER_REG_POWER_REG", 0x100, 16);
        lines.add(prefix + "LOCKREG3_RESERVED[0]");
        mmcmVec(lines, prefix, "TABLE", filt[mult - 1], 10);
        for (String l : lines) addLine(byTile, tn, l);
        bumpClock();
    }

    static long mmcmCount = 0;
    static void bumpClock() { mmcmCount++; }

    static int intProp(Cell c, String name, int def) {
        EDIFPropertyValue v = c.getEDIFCellInst().getProperty(name);
        if (v == null) return def;
        try {
            String sVal = v.getValue().trim();
            int apos = sVal.indexOf('\'');
            if (apos >= 0) sVal = sVal.substring(apos + 2);   // "18'd36"
            return Integer.parseInt(sVal);
        } catch (Exception e) { return def; }
    }

    static void bramWidth(List<String> lines, String prefix, Cell c,
                          String name, boolean is36, boolean isY1) {
        int width = intProp(c, name, 0);
        if (width == 0) width = 1;   // golden encodes unused ports as width 1
        int actual = width;
        if (is36) actual = (width == 1) ? 1 : width / 2;
        if (((is36 && width == 72) || (isY1 && actual == 36)) && name.equals("READ_WIDTH_A"))
            lines.add(prefix + name + "_18");
        if (actual == 36) {
            lines.add(prefix + "SDP_" + name.substring(0, name.length() - 2) + "_36");
            if (name.startsWith("WRITE")) {
                lines.add(prefix + name.substring(0, name.length() - 1) + "A_18");
                lines.add(prefix + name.substring(0, name.length() - 1) + "B_18");
            } else {
                lines.add(prefix + name.substring(0, name.length() - 1) + "B_18");
            }
        } else {
            lines.add(prefix + name + "_" + actual);
        }
    }

    static void emitBram(SiteInst si, Map<String,List<String>> byTile) {
        String stype = si.getSiteTypeEnum().name();
        Cell cell = null;
        for (Cell c : si.getCells()) {
            String t = c.getType();
            if (t != null && t.startsWith("RAMB")) { cell = c; break; }
        }
        if (cell == null) { bump("BRAM:no-cell:" + stype); return; }
        String tn = si.getTile().getName();
        boolean is36 = cell.getType().startsWith("RAMB36");
        int[] halves;
        if (is36) {
            halves = new int[]{0, 1};
        } else {
            int sy = Integer.parseInt(si.getSiteName().replaceAll(".*Y", ""));
            halves = new int[]{ sy % 2 };
        }
        boolean[] pinUsed = new boolean[10];
        String[] zinvPins = {"CLKARDCLK","CLKBWRCLK","ENARDEN","ENBWREN",
                             "REGCLKARDRCLK","REGCLKB","RSTRAMARSTRAM",
                             "RSTRAMB","RSTREGARSTREG","RSTREGB"};
        for (int pi = 0; pi < zinvPins.length; pi++) {
            // golden programs ZINV on pins with logical connections; the
            // EN pins additionally get it when tied to constant VCC
            // (always-enabled RAM), and the REGCLK/RSTREG pins only when
            // the corresponding output register actually exists.
            String p = zinvPins[pi];
            pinUsed[pi] = cellPinDrivenByLogic(cell, p);
            if (p.startsWith("EN"))
                pinUsed[pi] |= cellPinTiedToVcc(cell, p);
            if (p.equals("RSTREGARSTREG") || p.equals("REGCLKARDRCLK"))
                pinUsed[pi] &= intProp(cell, "DOA_REG", 0) != 0;
            if (p.equals("RSTREGB") || p.equals("REGCLKB"))
                pinUsed[pi] &= intProp(cell, "DOB_REG", 0) != 0;
        }
        for (int half : halves) {
            String prefix = tn + ".RAMB18_Y" + half + ".";
            List<String> lines = new ArrayList<>();
            lines.add(prefix + "IN_USE");
            bramWidth(lines, prefix, cell, "READ_WIDTH_A", is36, half == 1);
            bramWidth(lines, prefix, cell, "READ_WIDTH_B", is36, half == 1);
            bramWidth(lines, prefix, cell, "WRITE_WIDTH_A", is36, half == 1);
            bramWidth(lines, prefix, cell, "WRITE_WIDTH_B", is36, half == 1);
            if (intProp(cell, "DOA_REG", 0) != 0) lines.add(prefix + "DOA_REG");
            if (intProp(cell, "DOB_REG", 0) != 0) lines.add(prefix + "DOB_REG");
            for (int pi = 0; pi < zinvPins.length; pi++) {
                if (pinUsed[pi] && !getBoolParam(cell, "IS_" + zinvPins[pi] + "_INVERTED"))
                    lines.add(prefix + "ZINV_" + zinvPins[pi]);
            }
            for (String wm : new String[]{"WRITE_MODE_A", "WRITE_MODE_B"}) {
                String mode = strProp(cell, wm, "WRITE_FIRST");
                if (!mode.equals("WRITE_FIRST"))
                    lines.add(prefix + wm + "_" + mode);
            }
            // golden defaults
            String rdc = strProp(cell, "RDADDR_COLLISION_HWCONFIG", "DELAYED_WRITE");
            if (rdc.equals("PERFORMANCE"))
                lines.add(prefix + "RDADDR_COLLISION_HWCONFIG_PERFORMANCE");
            for (String ab : new String[]{"A","B"}) {
                String rp = strProp(cell, "RSTREG_PRIORITY_" + ab, "RSTREG");
                lines.add(prefix + "RSTREG_PRIORITY_" + ab + "_" + rp);
            }
            // nextpnr emits all-ones ZINIT/ZSRVAL (INIT_A/B = SRVAL_A/B = 0
            // assumed); warn if the design says otherwise.
            for (String z : new String[]{"INIT_A","INIT_B","SRVAL_A","SRVAL_B"}) {
                String bits = hexPropToBits(cell, z, 36);
                if (bits != null && bits.indexOf('1') >= 0)
                    System.err.println("[dcp2fasm] WARNING: nonzero " + z +
                        " on " + cell.getName() + " not encoded");
            }
            lines.add(prefix + "ZINIT_A[17:0] = 18'b" + repeat('1', 18));
            lines.add(prefix + "ZINIT_B[17:0] = 18'b" + repeat('1', 18));
            lines.add(prefix + "ZSRVAL_A[17:0] = 18'b" + repeat('1', 18));
            lines.add(prefix + "ZSRVAL_B[17:0] = 18'b" + repeat('1', 18));
            // INIT content (de-interleaved for RAMB36)
            for (String mode : new String[]{"", "P"}) {
                int n = mode.equals("P") ? 8 : 64;
                for (int i = 0; i < n; i++) {
                    char[] init = new char[256];
                    Arrays.fill(init, '0');
                    boolean has = false;
                    if (is36) {
                        for (int j = 0; j < 2; j++) {
                            String bs = hexPropToBits(cell,
                                String.format("INIT%s_%02X", mode, i * 2 + j), 256);
                            if (bs == null) continue;
                            has = true;
                            for (int k = half; k < 256; k += 2) {
                                if (bs.charAt(255 - k) == '1')
                                    init[255 - (j * 128 + (k / 2))] = '1';
                            }
                        }
                    } else {
                        String bs = hexPropToBits(cell,
                            String.format("INIT%s_%02X", mode, i), 256);
                        if (bs != null) { has = true; init = bs.toCharArray(); }
                    }
                    if (has)
                        lines.add(prefix + String.format("INIT%s_%02X[255:0] = 256'b", mode, i)
                                  + new String(init));
                }
            }
            for (String l : lines) addLine(byTile, tn, l);
        }
        // RAMB36-level aggregate features (tile namespace "RAMB36").
        // Vivado writes the port-A width-1 defaults on EVERY used BRAM
        // tile (RAMB18 pairs included); the B forms only for a genuine
        // width-1 port B (never for an unused width-0 port).
        // BRAM36_*_WIDTH_*_1 are REAL bits; golden/Vivado set them ONLY for a
        // genuine RAMB36E1 with a width-1 port -- NOT on width-1 RAMB18 pairs
        // and NOT on width-18 RAMB36.  Emitting them unconditionally (the old
        // behaviour, == the nextpnr fasm.cc bug) corrupts every RAMB.
        for (String rw : new String[]{"READ_WIDTH_A","WRITE_WIDTH_A",
                                      "READ_WIDTH_B","WRITE_WIDTH_B"}) {
            if (is36 && intProp(cell, rw, 0) == 1)
                addLine(byTile, tn, tn + ".RAMB36.BRAM36_" + rw + "_1");
        }
        if (is36) {
            for (String ab : new String[]{"A","B"}) {
                String ext = strProp(cell, "RAM_EXTENSION_" + ab, "NONE");
                addLine(byTile, tn, tn + ".RAMB36.RAM_EXTENSION_" + ab +
                        (ext.equals("LOWER") ? "_LOWER" : "_NONE_OR_UPPER"));
            }
        }
        // FIFO almost-empty/full offsets: complement encoding, all-ones for
        // the BRAM (non-FIFO) default of 0.
        addLine(byTile, tn, tn + ".ZALMOST_EMPTY_OFFSET[12:0] = 13'b" + repeat('1', 13));
        addLine(byTile, tn, tn + ".ZALMOST_FULL_OFFSET[12:0] = 13'b" + repeat('1', 13));
        bramCount++;
    }

    static String strProp(Cell c, String name, String def) {
        EDIFPropertyValue v = c.getEDIFCellInst().getProperty(name);
        if (v == null || v.getValue() == null) return def;
        return v.getValue().trim().replace("\"", "");
    }

    static String repeat(char c, int n) {
        char[] a = new char[n];
        Arrays.fill(a, c);
        return new String(a);
    }

    // ===================================================================
    // IOB FASM emission -- ports nextpnr-xilinx fasm.cc:843 write_io_config.
    // ===================================================================

    // Pull the bundled top.xdc out of the DCP and harvest IOSTANDARD /
    // SLEW / DRIVE / PULLTYPE / IN_TERM per top-level port.  The dcp
    // file is a zip; the xdc lives at "top.xdc" (or any *.xdc entry).
    static void parseBundledXdc(String dcpPath) {
        // Lines look like:
        //   set_property IOSTANDARD LVCMOS18 [get_ports {led[2]}]
        //   set_property PACKAGE_PIN AR37     [get_ports {led[2]}]
        // Use brace-balanced extraction so port names that contain
        // brackets (like "led[2]") survive intact.
        try (ZipFile zf = new ZipFile(dcpPath)) {
            var entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry ze = entries.nextElement();
                if (!ze.getName().endsWith(".xdc")) continue;
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(zf.getInputStream(ze)))) {
                    String line;
                    while ((line = br.readLine()) != null) harvestXdcLine(line);
                }
            }
        } catch (Exception ex) {
            System.err.println("[dcp2fasm] WARNING: failed to read XDC from DCP: " + ex);
        }
    }

    // Harvest IOSTANDARD/SLEW/DRIVE/PULLTYPE/IN_TERM from the Design's own
    // constraint list.  RapidWright populates getXDCConstraints() from the
    // bundled top.xdc when reading a DCP, and xml2dcp populates it from the
    // opendcp <constraints> block -- so this single path covers BOTH the DCP
    // and the XML->FASM (xml2fasm) flows.  Without it the xml2fasm path has no
    // bundled DCP to read and every IOB silently defaults to LVCMOS33.
    static void parseDesignXdc(Design des) {
        for (com.xilinx.rapidwright.design.ConstraintGroup g
                 : com.xilinx.rapidwright.design.ConstraintGroup.values()) {
            List<String> xs = des.getXDCConstraints(g);
            if (xs != null) for (String line : xs) harvestXdcLine(line);
        }
    }

    // Parse one "set_property PROP VALUE [get_ports {NAME}]" line into xdcByPort.
    // Brace-balanced so port names with brackets (like "led[2]") survive intact.
    static void harvestXdcLine(String line) {
        if (line == null) return;
        line = line.trim();
        if (!line.startsWith("set_property ")) return;
        String rest = line.substring("set_property ".length()).trim();
        int sp1 = rest.indexOf(' ');
        if (sp1 < 0) return;
        String prop = rest.substring(0, sp1);
        rest = rest.substring(sp1 + 1).trim();
        int sp2 = rest.indexOf(' ');
        if (sp2 < 0) return;
        String val = rest.substring(0, sp2);
        rest = rest.substring(sp2 + 1).trim();
        if (!rest.startsWith("[get_ports")) return;
        int braceL = rest.indexOf('{');
        int braceR = rest.lastIndexOf('}');
        String port;
        if (braceL >= 0 && braceR > braceL) {
            port = rest.substring(braceL + 1, braceR).trim();
        } else {
            int start = rest.indexOf("get_ports") + "get_ports".length();
            int end = rest.lastIndexOf(']');
            if (end < 0) return;
            port = rest.substring(start, end).trim();
        }
        if (prop.equals("IOSTANDARD") || prop.equals("SLEW")
            || prop.equals("DRIVE") || prop.equals("PULLTYPE")
            || prop.equals("IN_TERM")) {
            xdcByPort.computeIfAbsent(port, k -> new HashMap<>()).put(prop, val);
        }
    }

    // For an IOB cell, walk its PAD net to a top-level port and return
    // that port's name.  Both OBUF (driver from internal -> pad net out)
    // and IBUF (pad net in -> sink to internal) leave the pad-side net
    // matched to the top-level port name; we just need the port-side
    // pin's parent cell to be the top module.
    static String findTopPortName(Cell ioCell) {
        com.xilinx.rapidwright.edif.EDIFCellInst inst = ioCell.getEDIFCellInst();
        if (inst == null) return null;
        // Look at every pin of the IOB cell; for the one that connects
        // out to a top-level port, the corresponding net carries the
        // port name.  For OBUF the "O" pin drives the pad-out net; for
        // IBUF the "I" pin is driven by the pad-in net; for IBUFDS the
        // "I" and "IB" pins are driven by the +/- pad-in nets.
        for (com.xilinx.rapidwright.edif.EDIFPortInst pi : inst.getPortInsts()) {
            com.xilinx.rapidwright.edif.EDIFNet net = pi.getNet();
            if (net == null) continue;
            for (com.xilinx.rapidwright.edif.EDIFPortInst other : net.getPortInsts()) {
                // A top-level port-instance has no cell instance --
                // it's a port on the top cell directly.
                if (other.getCellInst() == null) {
                    return other.getName();
                }
            }
        }
        return null;
    }

    // Within-tile Y offset for an IOB site.  For non-SING tiles, the
    // tile contains two IOB sites; whichever has the lower instanceY is
    // the bottom (Y0 in Vivado's master/slave convention).  fasm.cc's
    // legacy `yLoc = 1 - ioLoc.y` was already inverted there; we use
    // the master/slave convention directly (lower-Y site == Y0 master).
    // SING tiles have a single site -- always Y0 except when the site
    // is in the top half of its IO bank (then Y1, mirroring fasm.cc's
    // is_top_sing path).
    static int getIobYLoc(SiteInst si) {
        Tile t = si.getTile();
        String tn = t.getName();
        Site s = si.getSite();
        if (tn.contains("_SING_")) {
            // fasm.cc: is_top_sing = pad->bel.tile < ctx->getHclkForIob(pad->bel)
            // i.e. SING tile sits ABOVE the HCLK row of its bank -> Y1.
            // RapidWright doesn't expose the HCLK-for-IOB map directly,
            // but every IO bank is bounded by HCLK_IOI tiles at known
            // positions.  Use the site's instanceY parity within its
            // bank (each bank is 50 sites tall, centre at Y%50==25);
            // sites in the lower half of the bank are Y0, upper half Y1.
            // Empirically for LIOB18_SING_X81Y51 (site IOB_X0Y49), this
            // gives Y1 -- matches nextpnr.
            int siteY = s.getInstanceY();
            int withinBank = siteY % 50;
            return (withinBank >= 25) ? 1 : 0;
        }
        int myY = s.getInstanceY();
        int otherY = Integer.MAX_VALUE;
        for (Site cand : t.getSites()) {
            if (cand == s) continue;
            if (cand.getName().startsWith("IOB"))
                otherY = Math.min(otherY, cand.getInstanceY());
        }
        if (otherY == Integer.MAX_VALUE) return 0;   // only one site
        // Convention in nextpnr fasm.cc / prjxray: the HIGHER-instanceY
        // site within a tile is the master (FASM IOB_Y0); the lower is
        // the slave (IOB_Y1).  Confirmed from rst_IBUF_inst @
        // IOB_X0Y124 in tile LIOB18_X81Y128 (other site IOB_X0Y123)
        // mapping to IOB_Y0 in nextpnr's FASM.
        return (myY > otherY) ? 0 : 1;
    }

    static String iostandardOf(Cell ioCell) {
        String port = findTopPortName(ioCell);
        if (port == null) return "LVCMOS33";
        Map<String,String> m = xdcByPort.get(port);
        if (m == null) return "LVCMOS33";
        return m.getOrDefault("IOSTANDARD", "LVCMOS33");
    }

    static String pulltypeOf(Cell ioCell) {
        String port = findTopPortName(ioCell);
        if (port == null) return "NONE";
        Map<String,String> m = xdcByPort.get(port);
        if (m == null) return "NONE";
        return m.getOrDefault("PULLTYPE", "NONE");
    }

    static String slewOf(Cell ioCell) {
        String port = findTopPortName(ioCell);
        if (port == null) return "SLOW";
        Map<String,String> m = xdcByPort.get(port);
        if (m == null) return "SLOW";
        return m.getOrDefault("SLEW", "SLOW");
    }

    static int driveOf(Cell ioCell, String iostandard, boolean isRiob18) {
        String port = findTopPortName(ioCell);
        int defDrive = (isRiob18 && iostandard.equals("LVCMOS12")) ? 8 : 12;
        if (port == null) return defDrive;
        Map<String,String> m = xdcByPort.get(port);
        if (m == null) return defDrive;
        String s = m.get("DRIVE");
        if (s == null) return defDrive;
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return defDrive; }
    }

    static void emitIob(SiteInst si, Map<String,List<String>> byTile) {
        // Find the bound PAD cell -- OBUF/IBUF/IBUFDS/OBUFDS sit at
        // OUTBUF_DCIEN or INBUF_DCIEN on the IOB site.
        Cell ioCell = null;
        for (BEL bel : si.getBELs()) {
            Cell c = si.getCell(bel);
            if (c == null) continue;
            String t = c.getType();
            if (t == null) continue;
            if (t.equals("OBUF") || t.equals("OBUFT") || t.equals("OBUFDS")
                || t.equals("IBUF") || t.equals("IBUFDS")
                || t.equals("IOBUF") || t.equals("IOBUFDS")) {
                ioCell = c;
                break;
            }
        }
        if (ioCell == null) { bump("IOB-noCell"); return; }
        String type = ioCell.getType();
        boolean is_input = type.startsWith("IBUF") || type.equals("IOBUF") || type.equals("IOBUFDS");
        boolean is_output = type.startsWith("OBUF") || type.equals("IOBUF") || type.equals("IOBUFDS");
        if (is_output) obufTiles.add(si.getTile());

        // Find the HCLK_IOI tile for this IOB and accumulate the per-bank
        // ioconfig flags there.  Each IO bank is bounded by an HCLK row;
        // HCLK_IOI tiles sit in the adjacent INT column at the bank
        // centre (every ~50 sites of Y).
        String hclkTile = findHclkIoiForIob(si);
        HclkIoConfig cfg = hclkTile == null ? null
            : hclkIoCfg.computeIfAbsent(hclkTile, k -> new HclkIoConfig());
        boolean is_diff = type.endsWith("DS");

        Tile t = si.getTile();
        String tn = t.getName();
        boolean is_riob18 = tn.startsWith("RIOB18_");
        boolean is_hp_bank = is_riob18 || tn.startsWith("LIOB18_");
        boolean is_sing = tn.contains("_SING_");
        String iostandard = iostandardOf(ioCell);
        String pulltype = pulltypeOf(ioCell);
        String slew = slewOf(ioCell);
        int drive = is_output ? driveOf(ioCell, iostandard, is_riob18) : 12;

        boolean has_diff_prefix = iostandard.startsWith("DIFF_");
        boolean is_tmds33 = iostandard.equals("TMDS_33");
        boolean is_lvds25 = iostandard.equals("LVDS_25");
        boolean is_lvds = iostandard.startsWith("LVDS");
        boolean only_diff = is_tmds33 || is_lvds;
        boolean iod = only_diff || has_diff_prefix;
        is_diff = is_diff || iod;
        if (has_diff_prefix) iostandard = iostandard.substring(5);
        boolean is_sstl = iostandard.equals("SSTL12") || iostandard.equals("SSTL135") || iostandard.equals("SSTL15");
        boolean is_lvcmos = iostandard.startsWith("LVCMOS");
        boolean is_low_volt_lvcmos = iostandard.equals("LVCMOS12") || iostandard.equals("LVCMOS15") || iostandard.equals("LVCMOS18");
        boolean is_stepdown = false;

        int yLoc = getIobYLoc(si);
        String ybase = "IOB_Y" + yLoc + ".";
        String prefix = tn + "." + ybase;

        if (is_output) {
            // DRIVE
            if (iostandard.equals("SSTL135")) {
                addLine(byTile, tn, prefix + "SSTL135.DRIVE.I_FIXED");
            } else if (is_riob18) {
                if (iostandard.equals("LVCMOS18") || iostandard.equals("LVCMOS15"))
                    addLine(byTile, tn, prefix + "LVCMOS15_LVCMOS18.DRIVE.I12_I16_I2_I4_I6_I8");
                else if (iostandard.equals("LVCMOS12"))
                    addLine(byTile, tn, prefix + "LVCMOS12.DRIVE.I2_I4_I6_I8");
                else if (iostandard.equals("LVDS"))
                    addLine(byTile, tn, prefix + "LVDS.DRIVE.I_FIXED");
                else if (is_sstl)
                    addLine(byTile, tn, prefix + iostandard + ".DRIVE.I_FIXED");
            } else {
                if (iostandard.equals("TMDS_33") && yLoc == 0) {
                    addLine(byTile, tn, prefix + "TMDS_33.DRIVE.I_FIXED");
                    addLine(byTile, tn, prefix + "TMDS_33.OUT");
                } else if (iostandard.equals("LVDS_25") && yLoc == 0) {
                    addLine(byTile, tn, prefix + "LVDS_25.DRIVE.I_FIXED");
                    addLine(byTile, tn, prefix + "LVDS_25.OUT");
                } else if ((iostandard.equals("LVCMOS15") && drive == 16) || iostandard.equals("SSTL15"))
                    addLine(byTile, tn, prefix + "LVCMOS15_SSTL15.DRIVE.I16_I_FIXED");
                else if (iostandard.equals("LVCMOS18") && (drive == 12 || drive == 8))
                    addLine(byTile, tn, prefix + "LVCMOS18.DRIVE.I12_I8");
                else if ((iostandard.equals("LVCMOS33") && drive == 16) ||
                         (iostandard.equals("LVTTL") && drive == 16))
                    addLine(byTile, tn, prefix + "LVCMOS33_LVTTL.DRIVE.I12_I16");
                else if ((iostandard.equals("LVCMOS33") && (drive == 8 || drive == 12)) ||
                         (iostandard.equals("LVTTL") && (drive == 8 || drive == 12)))
                    addLine(byTile, tn, prefix + "LVCMOS33_LVTTL.DRIVE.I12_I8");
                else if ((iostandard.equals("LVCMOS33") && drive == 4) ||
                         (iostandard.equals("LVTTL") && drive == 4))
                    addLine(byTile, tn, prefix + "LVCMOS33_LVTTL.DRIVE.I4");
                else if (drive == 8 && (iostandard.equals("LVCMOS12") || iostandard.equals("LVCMOS25")))
                    addLine(byTile, tn, prefix + "LVCMOS12_LVCMOS25.DRIVE.I8");
                else if (drive == 4 && (iostandard.equals("LVCMOS15") || iostandard.equals("LVCMOS18") || iostandard.equals("LVCMOS25")))
                    addLine(byTile, tn, prefix + "LVCMOS15_LVCMOS18_LVCMOS25.DRIVE.I4");
                else if (is_lvcmos || iostandard.equals("LVTTL"))
                    addLine(byTile, tn, prefix + iostandard + ".DRIVE.I" + drive);
            }

            // HP-bank-specific additional DRIVE family bit (Vivado emits
            // both the IOB33 narrow bit and the HP family bit).
            if (is_hp_bank && !is_riob18) {
                if (iostandard.equals("LVCMOS18") || iostandard.equals("LVCMOS15"))
                    addLine(byTile, tn, prefix + "LVCMOS15_LVCMOS18.DRIVE.I12_I16_I2_I4_I6_I8");
            }

            if (is_riob18 && is_sstl) addLine(byTile, tn, prefix + iostandard + ".IN_USE");

            // SLEW
            if (slew.equals("SLOW")) {
                if (!iostandard.equals("LVDS_25") && !iostandard.equals("TMDS_33"))
                    addLine(byTile, tn, prefix + "LVCMOS12_LVCMOS15_LVCMOS18_LVCMOS25_LVCMOS33_LVTTL_SSTL135_SSTL15.SLEW.SLOW");
                if (is_hp_bank) {
                    if (iostandard.equals("SSTL135"))
                        addLine(byTile, tn, prefix + "SSTL135.SLEW.SLOW");
                    else if (iostandard.equals("SSTL15"))
                        addLine(byTile, tn, prefix + "SSTL15.SLEW.SLOW");
                    else if (!iostandard.equals("LVDS_25") && !iostandard.equals("TMDS_33"))
                        addLine(byTile, tn, prefix + "LVCMOS12_LVCMOS15_LVCMOS18.SLEW.SLOW");
                }
            } else if (is_riob18) {
                addLine(byTile, tn, prefix + iostandard + ".SLEW.FAST");
            } else if (iostandard.equals("SSTL135") || iostandard.equals("SSTL15")) {
                addLine(byTile, tn, prefix + "SSTL135_SSTL15.SLEW.FAST");
            } else {
                addLine(byTile, tn, prefix + "LVCMOS12_LVCMOS15_LVCMOS18_LVCMOS25_LVCMOS33_LVTTL.SLEW.FAST");
            }

            if (is_hp_bank) addLine(byTile, tn, prefix + "OBUF_HP_BANK_GLUE");
        }

        if (is_input) {
            if (!is_output && !is_diff && slew.equals("SLOW")
                && !iostandard.equals("LVDS_25") && !iostandard.equals("TMDS_33")) {
                addLine(byTile, tn, prefix + "LVCMOS12_LVCMOS15_LVCMOS18_LVCMOS25_LVCMOS33_LVTTL_SSTL135_SSTL15.SLEW.SLOW");
                if (is_hp_bank)
                    addLine(byTile, tn, prefix + "LVCMOS12_LVCMOS15_LVCMOS18.SLEW.SLOW");
            }
            if (is_hp_bank && !is_output) {
                if (is_diff) {
                    if (yLoc == 0)
                        addLine(byTile, tn, prefix + "IBUFDS_BANK_GLUE");
                } else {
                    addLine(byTile, tn, prefix + "IBUF_HP_BANK_GLUE");
                }
            }
            if (is_hp_bank && !is_diff && is_low_volt_lvcmos)
                addLine(byTile, tn, prefix + "LVCMOS12_LVCMOS15.IN");
            if (is_hp_bank && !is_output && !is_diff)
                addLine(byTile, tn, prefix + "LVCMOS12_LVCMOS15_SSTL12_SSTL135_SSTL15.IN_ONLY");
            if (!is_diff) {
                if (iostandard.equals("LVCMOS33") || iostandard.equals("LVTTL") || iostandard.equals("LVCMOS25")) {
                    if (!is_riob18)
                        addLine(byTile, tn, prefix + "LVCMOS25_LVCMOS33_LVTTL.IN");
                }
                if (is_sstl) {
                    if (!is_riob18) addLine(byTile, tn, prefix + "SSTL135_SSTL15.IN");
                    if (is_riob18)  addLine(byTile, tn, prefix + "SSTL12_SSTL135_SSTL15.IN");
                }
                if (is_low_volt_lvcmos)
                    addLine(byTile, tn, prefix + "LVCMOS12_LVCMOS15_LVCMOS18.IN");
            } else {
                if (is_riob18) {
                    if (yLoc == 0) {
                        addLine(byTile, tn, prefix + "LVDS_SSTL12_SSTL135_SSTL15.IN_DIFF");
                        if (iostandard.equals("LVDS"))
                            addLine(byTile, tn, prefix + "LVDS.IN_USE");
                    }
                } else {
                    addLine(byTile, tn, prefix + "LVDS_25_SSTL135_SSTL15.IN_DIFF");
                }
            }
            // IN_ONLY
            if (!is_output) {
                if (is_riob18) {
                    if (is_diff && yLoc == 0)
                        addLine(byTile, tn, prefix + "LVDS.IN_ONLY");
                    else
                        addLine(byTile, tn, prefix + "LVCMOS12_LVCMOS15_LVCMOS18_SSTL12_SSTL135_SSTL15.IN_ONLY");
                } else {
                    addLine(byTile, tn, prefix + "LVCMOS12_LVCMOS15_LVCMOS18_LVCMOS25_LVCMOS33_LVDS_25_LVTTL_SSTL135_SSTL15_TMDS_33.IN_ONLY");
                }
            }
        }

        if (!is_riob18 && (is_low_volt_lvcmos || is_sstl)) {
            addLine(byTile, tn, prefix + "LVCMOS12_LVCMOS15_LVCMOS18_SSTL135_SSTL15.STEPDOWN");
            is_stepdown = true;
            if (cfg != null) cfg.stepdown = true;
        }
        // Per-bank ioconfig accumulators (fasm.cc:891-893, 1029).
        if (cfg != null) {
            if (only_diff) cfg.onlyDiff = true;
            if (is_tmds33) cfg.tmds33 = true;
            if (is_lvds25) cfg.lvds25 = true;
            if (is_sstl) cfg.vref = true;
        }

        addLine(byTile, tn, prefix + "PULLTYPE." + pulltype);

        // Cross-site STEPDOWN duplicate on the other half (non-SING only).
        if (is_stepdown && !is_sing) {
            String other = "IOB_Y" + (1 - yLoc) + ".";
            addLine(byTile, tn, tn + "." + other + "LVCMOS12_LVCMOS15_LVCMOS18_SSTL135_SSTL15.STEPDOWN");
        }

        // HP-bank cross-site SLEW.SLOW defaults on the unused half.
        if (is_hp_bank && !is_sing && !is_diff && slew.equals("SLOW")
            && !iostandard.equals("LVDS_25") && !iostandard.equals("TMDS_33")) {
            String other = "IOB_Y" + (1 - yLoc) + ".";
            addLine(byTile, tn, tn + "." + other + "LVCMOS12_LVCMOS15_LVCMOS18_LVCMOS25_LVCMOS33_LVTTL_SSTL135_SSTL15.SLEW.SLOW");
            addLine(byTile, tn, tn + "." + other + "LVCMOS12_LVCMOS15_LVCMOS18.SLEW.SLOW");
        }

        // Differential pair on HP bank: the IBUFDS sits on the Y0 master
        // side; the Y1 slave site is electrically active (it's the IB
        // pin of the pair) but has no logical cell.  Vivado emits the
        // basic slave-side defaults (IN_ONLY and PULLTYPE.NONE) on Y1
        // anyway -- mirror that here.
        if (is_hp_bank && is_diff && !is_sing && is_input) {
            String other = "IOB_Y" + (1 - yLoc) + ".";
            if (is_riob18)
                addLine(byTile, tn, tn + "." + other + "LVCMOS12_LVCMOS15_LVCMOS18_SSTL12_SSTL135_SSTL15.IN_ONLY");
            addLine(byTile, tn, tn + "." + other + "PULLTYPE." + pulltype);
        }

        iobCount++;
    }

    // ===================================================================
    // IOLOGIC (ILOGICE2 / OLOGICE2) FASM emission -- ports fasm.cc:1146
    // write_iol_config.  Covers the common cases where Vivado packs
    // input/output FFs into the IOB's ILOGIC/OLOGIC sites for timing.
    // ===================================================================
    static long iolCount = 0;
    static void emitIolConfig(SiteInst si, Map<String,List<String>> byTile) {
        Tile t = si.getTile();
        String tn = t.getName();
        Site s = si.getSite();
        boolean isSing = tn.contains("_SING_");

        // Within-tile yLoc, same convention as IOBs (lower instanceY
        // means slave Y1, higher means master Y0).
        int yLoc;
        if (isSing) {
            yLoc = (s.getInstanceY() % 50 >= 25) ? 1 : 0;
        } else {
            int myY = s.getInstanceY();
            int otherY = Integer.MAX_VALUE;
            String myType = si.getSiteTypeEnum().name();
            String baseType = myType.startsWith("ILOGIC") ? "ILOGIC" : "OLOGIC";
            for (Site cand : t.getSites()) {
                if (cand == s) continue;
                if (cand.getName().startsWith(baseType))
                    otherY = Math.min(otherY, cand.getInstanceY());
            }
            yLoc = (otherY == Integer.MAX_VALUE || myY > otherY) ? 0 : 1;
        }
        String siteKind = si.getSiteTypeEnum().name().startsWith("ILOGIC") ? "ILOGIC" : "OLOGIC";
        String prefix = tn + "." + siteKind + "_Y" + yLoc + ".";

        // Walk BELs in this site to find the FF cell.  Common BELs:
        //   ILOGIC: IFF (input FF), used when Vivado packs an input FDRE
        //           there for fast-path register; otherwise no cell.
        //   OLOGIC: OUTFF (output FF), used when Vivado packs an output
        //           FDRE there.  Cell type stays "FDRE"/"FDSE"/etc.
        Cell ff = null;
        for (BEL bel : si.getBELs()) {
            Cell c = si.getCell(bel);
            if (c == null) continue;
            String bn = bel.getName();
            if (bn.equals("OUTFF") || bn.equals("IFF") || bn.equals("OUTFF2")
                || bn.equals("IFF2")) {
                ff = c;
                break;
            }
        }
        if (ff == null) {
            // No FF in this IOLOGIC site -- routethru only.  Nothing to emit.
            return;
        }
        String ctype = ff.getType();
        if (ctype == null) return;

        if (siteKind.equals("OLOGIC")) {
            // fasm.cc:1197-1221 (OLOGICE2_OUTFF / OLOGICE3_OUTFF).
            addLine(byTile, tn, prefix + "ODDR_TDDR.IN_USE");
            addLine(byTile, tn, prefix + "OQUSED");
            addLine(byTile, tn, prefix + "OSERDES.DATA_RATE_OQ.DDR");
            addLine(byTile, tn, prefix + "OSERDES.DATA_RATE_TQ.BUF");
            // SRTYPE default is SYNC for FDRE/FDSE family.
            addLine(byTile, tn, prefix + "OSERDES.SRTYPE.SYNC");
            // INIT default is 0 for FDRE -> emit ZINIT_OQ.
            if (!getBoolParam(ff, "INIT"))
                addLine(byTile, tn, prefix + "ZINIT_OQ");
            // SR (reset) used?  FDRE has R pin.
            if (cellPinDrivenByLogic(ff, "R") || cellPinDrivenByLogic(ff, "S")
                || cellPinDrivenByLogic(ff, "CLR") || cellPinDrivenByLogic(ff, "PRE")) {
                addLine(byTile, tn, prefix + "ODDR.SRUSED");
                // sr_name == "R" -> ZSRVAL_OQ
                if (ctype.startsWith("FDRE") || ctype.startsWith("FDCE"))
                    addLine(byTile, tn, prefix + "ZSRVAL_OQ");
            }
            // CLK inversion default is false -> emit ZINV_CLK.
            if (!getBoolParam(ff, "IS_CLK_INVERTED"))
                addLine(byTile, tn, prefix + "ZINV_CLK");
        } else {
            // fasm.cc:1158-1196 (ILOGICE3_IFF) -- treat ILOGICE2_IFF the same.
            addLine(byTile, tn, prefix + "IDDR_OR_ISERDES.IN_USE");
            addLine(byTile, tn, prefix + "IFF.DDR_CLK_EDGE.OPPOSITE_EDGE");
            addLine(byTile, tn, prefix + "IFF.SRTYPE.SYNC");
            addLine(byTile, tn, prefix + "IFF.ZINV_C");
            if (!getBoolParam(ff, "IS_D_INVERTED"))
                addLine(byTile, tn, prefix + "ZINV_D");
            if (!getBoolParam(ff, "INIT_Q1"))
                addLine(byTile, tn, prefix + "IFF.ZINIT_Q1");
            if (!getBoolParam(ff, "INIT_Q2"))
                addLine(byTile, tn, prefix + "IFF.ZINIT_Q2");
            // sr_name == "R" -> ZSRVAL_Q1/Q2
            if (ctype.startsWith("FDRE") || ctype.startsWith("FDCE")) {
                addLine(byTile, tn, prefix + "IFF.ZSRVAL_Q1");
                addLine(byTile, tn, prefix + "IFF.ZSRVAL_Q2");
            }
            addLine(byTile, tn, prefix + "IDELMUXE3.P1");
        }
        iolCount++;
    }

    // ===================================================================
    // NOCLKINV defaults for routethru-only slices.  fasm.cc emits the
    // slice's clock-polarity default for every SLICE half it touches,
    // including ones where no cell sits but routing passes through.
    // We detect those by walking pipsByTile: any CLB tile whose routing
    // touched a SLICE site that has no SiteInst gets a NOCLKINV line.
    // ===================================================================
    static void emitNoClkInvDefaults(Design des, Map<String,List<String>> byTile) {
        for (String tn : pipsByTile.keySet()) {
            Tile t = des.getDevice().getTile(tn);
            if (t == null) continue;
            String ttype = t.getTileTypeEnum().name();
            if (!ttype.startsWith("CLBL")) continue;
            boolean isCLBLM = ttype.startsWith("CLBLM");
            // Find the two SLICE sites in this tile; for each, if a
            // SiteInst exists in the design, emitSlice already wrote
            // NOCLKINV (or CLKINV).  If not, add the NOCLKINV default.
            int loX = Integer.MAX_VALUE, hiX = Integer.MIN_VALUE;
            Site loSite = null, hiSite = null;
            for (Site s : t.getSites()) {
                if (!s.getName().startsWith("SLICE")) continue;
                int sx = s.getInstanceX();
                if (sx < loX) { loX = sx; loSite = s; }
                if (sx > hiX) { hiX = sx; hiSite = s; }
            }
            for (int half = 0; half < 2; half++) {
                Site s = (half == 0) ? loSite : hiSite;
                if (s == null) continue;
                SiteInst si = des.getSiteInstFromSiteName(s.getName());
                if (si != null && !si.getCells().isEmpty()) continue;
                String halfTag;
                if (isCLBLM) {
                    halfTag = (half == 0) ? "SLICEM_X0" : "SLICEL_X1";
                } else {
                    halfTag = (half == 0) ? "SLICEL_X0" : "SLICEL_X1";
                }
                addLine(byTile, tn, tn + "." + halfTag + ".NOCLKINV");
            }
        }
    }

    // ===================================================================
    // HCLK_IOI per-bank ioconfig emission (fasm.cc:1348-1356).
    // ===================================================================
    static void emitHclkIoConfig(Map<String,List<String>> byTile) {
        for (Map.Entry<String, HclkIoConfig> e : hclkIoCfg.entrySet()) {
            String tn = e.getKey();
            HclkIoConfig c = e.getValue();
            if (c.stepdown) addLine(byTile, tn, tn + ".STEPDOWN");
            if (c.vref)     addLine(byTile, tn, tn + ".VREF.V_675_MV");
            if (c.onlyDiff) addLine(byTile, tn, tn + ".ONLY_DIFF_IN_USE");
            if (c.tmds33)   addLine(byTile, tn, tn + ".TMDS_33_IN_USE");
            if (c.lvds25)   addLine(byTile, tn, tn + ".LVDS_25_IN_USE");
        }
    }

    // Find the HCLK_IOI tile responsible for this IOB site.  Banks are
    // ~50 sites tall with HCLK rows at the boundaries.  Walk neighbour
    // tiles around the IOB looking for an HCLK_IOI tile in the same
    // tile-X column or one step in/out from the IO column.
    static String findHclkIoiForIob(SiteInst si) {
        Tile io = si.getTile();
        com.xilinx.rapidwright.device.Device dev = io.getDevice();
        int gy = io.getRow();
        int gx = io.getColumn();
        int rows = dev.getRows();
        // Scan +/- 50 rows in the immediate X-neighborhood.
        Tile best = null;
        int bestDy = Integer.MAX_VALUE;
        for (int dx : new int[]{0, 1, -1, 2, -2}) {
            int xScan = gx + dx;
            if (xScan < 0 || xScan >= dev.getColumns()) continue;
            for (int dy = -50; dy <= 50; dy++) {
                int yScan = gy + dy;
                if (yScan < 0 || yScan >= rows) continue;
                Tile cand = dev.getTile(yScan, xScan);
                if (cand == null) continue;
                if (cand.getTileTypeEnum().name().equals("HCLK_IOI")
                    || cand.getTileTypeEnum().name().equals("HCLK_IOI3")) {
                    if (Math.abs(dy) < bestDy) {
                        bestDy = Math.abs(dy);
                        best = cand;
                    }
                }
            }
            if (best != null) break;    // prefer X-closest
        }
        return best == null ? null : best.getName();
    }

    // ===================================================================
    // INT_L / INT_R IOB_COL_BANK_ACTIVE emission (fasm.cc:1360-1422).
    //
    // For each active OBUF tile, find the nearest INT_L (for L-side) or
    // INT_R (for R-side) tile in the same Y row (or ±2 rows) walking
    // inward through the X axis.  Emit IOB_COL_BANK_ACTIVE and
    // IOB_COL_OBUF_CASCADE_Y1 on that INT tile.
    // ===================================================================
    // Propagate CLK_HROW CK_GCLK<n>_ACTIVE to every clock-region row that
    // CONSUMES the global clock, not just the row that drives it.  dcp2fasm's
    // PIP-based emission already sets the DRIVING row (where the GCLK<n> ->
    // CK_MUX_OUT pip lives).  golden also marks pure-CONSUMER rows -- a pip-less
    // HROW tile whose clock region holds fabric loads of the net.  Without them
    // the GCLK spine never enables in that region and the fabric there stays
    // frozen.  Map: net -> GCLK<n> (from its routing) -> load clock-region rows
    // -> the CLK_HROW_BOT_R tile at that row (Y-sorted index).
    static final Pattern GCLK_WIRE = Pattern.compile("CLK_HROW_R_CK_GCLK(\\d+)");
    static void emitGclkActive(Design des, Map<String,List<String>> byTile) {
        com.xilinx.rapidwright.device.Device dev = des.getDevice();
        // CLK_HROW_BOT_R tiles indexed by clock-region row (ascending Y).
        TreeMap<Integer,String> byY = new TreeMap<>();
        for (Tile t : dev.getAllTiles())
            if (t.getName().startsWith("CLK_HROW_BOT_R"))
                byY.put(t.getTileYCoordinate(), t.getName());
        List<String> H = new ArrayList<>(byY.values());     // index == region row
        Integer[] HY = byY.keySet().toArray(new Integer[0]); // HROW tile Y per row
        if (H.isEmpty()) return;
        for (Net n : des.getNets()) {
            int g = -1;
            for (PIP p : n.getPIPs()) {
                Matcher m = GCLK_WIRE.matcher(p.getStartWireName());
                if (!m.matches()) m = GCLK_WIRE.matcher(p.getEndWireName());
                if (m.matches()) { g = Integer.parseInt(m.group(1)); break; }
            }
            if (g < 0) continue;
            // The GCLK spine is active across every clock-region row it spans --
            // from the DRIVER (BUFG) row down through the lowest LOAD row.  Loads
            // sit in normal fabric (getClockRegion gives their row); the BUFG
            // source is in the centre column (no clock region) so map its tile-Y
            // to the nearest HROW row.
            int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
            for (SitePinInst spi : n.getPins()) {
                Tile lt = spi.getTile();
                if (lt == null) continue;
                int row;
                com.xilinx.rapidwright.device.ClockRegion cr = lt.getClockRegion();
                if (cr != null) {
                    row = cr.getInstanceY();
                } else if (spi.isOutPin()) {
                    int y = lt.getTileYCoordinate(), best = 0;
                    for (int i = 1; i < HY.length; i++)
                        if (Math.abs(HY[i] - y) < Math.abs(HY[best] - y)) best = i;
                    row = best;
                } else continue;
                if (row < lo) lo = row;
                if (row > hi) hi = row;
            }
            for (int r = lo; r <= hi; r++)
                if (r >= 0 && r < H.size())
                    addLine(byTile, H.get(r), H.get(r) + ".CLK_HROW_R_CK_GCLK" + g + "_ACTIVE");
        }
    }

    static void emitIobColBankActive(Design des, Map<String,List<String>> byTile) {
        if (obufTiles.isEmpty()) return;
        com.xilinx.rapidwright.device.Device dev = des.getDevice();
        java.util.Set<String> emitted = new java.util.HashSet<>();
        for (Tile io : obufTiles) {
            String tname = io.getName();
            boolean isLeft = tname.startsWith("LIOB18_") || tname.startsWith("LIOB33_");
            boolean isRight = tname.startsWith("RIOB18_") || tname.startsWith("RIOB33_");
            if (!isLeft && !isRight) continue;
            String want = isLeft ? "INT_L" : "INT_R";
            int gy = io.getRow();
            int gx = io.getColumn();
            int chipW = dev.getColumns();
            String chosen = null;
            // dy=0 only: the ±2-row fallback emitted on INT_L_X32Y127
            // (from an OBUF whose own row lacks an INT tile) where the
            // golden bitstream has no IOB_COL bits -- 6 stray bits.
            for (int dy = 0; dy <= 0 && chosen == null; dy++) {
                for (int sgn = 1; sgn >= -1; sgn -= 2) {
                    if (dy == 0 && sgn == -1) continue;
                    int yScan = gy + sgn * dy;
                    if (yScan < 0 || yScan >= dev.getRows()) continue;
                    int step = isLeft ? +1 : -1;
                    int start = gx + step;
                    int end = isLeft ? chipW : -1;
                    for (int xScan = start; xScan != end; xScan += step) {
                        Tile cand = dev.getTile(yScan, xScan);
                        if (cand == null) continue;
                        if (cand.getTileTypeEnum().name().equals(want)) {
                            chosen = cand.getName();
                            break;
                        }
                    }
                    if (chosen != null) break;
                }
            }
            if (chosen == null || emitted.contains(chosen)) continue;
            emitted.add(chosen);
            addLine(byTile, chosen, chosen + ".IOB_COL_BANK_ACTIVE");
            addLine(byTile, chosen, chosen + ".IOB_COL_OBUF_CASCADE_Y1");
        }
    }

    // ===================================================================
    // BUFG cell emission.
    //
    // For a bound BUFGCTRL cell, emit IN_USE plus the input-mux defaults
    // (IS_IGNORE1_INVERTED, ZINV_CE0, ZINV_S0 -- selecting the I0 path).
    // The cell-property variants (IS_CE0_INVERTED etc.) override the
    // ZINV defaults; track them when they're set on the cell.
    // ===================================================================
    static long bufgCount = 0;
    // Tiles of active OBUF cells -- needed later to emit INT_L/R
    // IOB_COL_BANK_ACTIVE features for the interconnect column that
    // serves this IO bank (fasm.cc:1360-1422 / task #24).
    static List<Tile> obufTiles = new ArrayList<>();

    // Per-HCLK-bank ioconfig flags accumulated from IOBs.  Keyed by the
    // HCLK_IOI tile name that fasm.cc:1348 emits the features on.
    static class HclkIoConfig {
        boolean stepdown = false;
        boolean onlyDiff = false;
        boolean tmds33 = false;
        boolean lvds25 = false;
        boolean vref = false;
    }
    static Map<String, HclkIoConfig> hclkIoCfg = new HashMap<>();
    static void emitBufg(SiteInst si, Map<String,List<String>> byTile) {
        Cell bufgCell = null;
        for (BEL bel : si.getBELs()) {
            Cell c = si.getCell(bel);
            if (c == null) continue;
            String t = c.getType();
            if (t != null && (t.equals("BUFGCTRL") || t.equals("BUFG"))) {
                bufgCell = c;
                break;
            }
        }
        if (bufgCell == null) { bump("BUFG-noCell"); return; }
        Tile t = si.getTile();
        String tn = t.getName();
        int slotY = si.getSite().getInstanceY() % 16;
        String pf = tn + ".BUFGCTRL.BUFGCTRL_X0Y" + slotY + ".";
        addLine(byTile, tn, pf + "IN_USE");
        // Default I0 input is selected; emit the corresponding control
        // defaults (IS_IGNORE1_INVERTED, ZINV_CE0, ZINV_S0).
        addLine(byTile, tn, pf + "IS_IGNORE1_INVERTED");
        addLine(byTile, tn, pf + "ZINV_CE0");
        addLine(byTile, tn, pf + "ZINV_S0");
        bufgCount++;
    }

    // ===================================================================
    // Routing-PIP FASM emission -- ports nextpnr-xilinx fasm.cc:286
    // write_pip + pp_config (pseudo-pip substitution table).
    // ===================================================================

    // Map: tile-name -> list of {dst, src} wire-name pairs for PIPs we
    // emitted on that tile.  Used by the clock-distribution per-tile
    // loops which need "what source wires touched in this tile".
    static Map<String, List<String[]>> pipsByTile = new HashMap<>();

    static void emitRouting(Design des, Map<String,List<String>> byTile) {
        java.util.Set<String> touchedTiles = new java.util.HashSet<>();
        // Track which BUFGCTRL slots are bound per CLK_BUFG_*_R tile, so
        // we can later emit unused-slot defaults (fasm.cc:1613-1634).
        Map<String, java.util.Set<Integer>> bufgUsedSlots = new HashMap<>();
        // First pass over placed BUFGCTRL cells to populate bufgUsedSlots.
        for (SiteInst si : des.getSiteInsts()) {
            if (!si.getSiteTypeEnum().name().startsWith("BUFG")) continue;
            String tileName = si.getTile().getName();
            if (!tileName.startsWith("CLK_BUFG_TOP_R") && !tileName.startsWith("CLK_BUFG_BOT_R"))
                continue;
            int slotY = si.getSite().getInstanceY() % 16;
            bufgUsedSlots.computeIfAbsent(tileName, k -> new java.util.HashSet<>()).add(slotY);
        }

        for (Net net : des.getNets()) {
            List<PIP> pips = net.getPIPs();
            if (pips == null || pips.isEmpty()) continue;
            for (PIP pip : pips) {
                String tn = pip.getTile().getName();
                touchedTiles.add(tn);
                String start = pip.getStartWireName();
                String end   = pip.getEndWireName();
                String src, dst;
                if (pip.isReversed()) { src = end; dst = start; }
                else                  { src = start; dst = end; }

                // NOTE: the CLBLM_M_COUT_N <- CLBLM_M_COUT PIP is a real
                // routing PIP in Vivado-pure DCPs (the inter-SLICE
                // carry-chain extension), so we MUST emit it.  Earlier
                // versions filtered it as "synthetic json2dcp injection"
                // but doing so breaks Vivado-routed designs where the
                // count[3]->count[4] chain depends on this bit.

                // LIOI/RIOI OLOGIC/ILOGIC routethru PIPs: Vivado treats
                // these as transparent (fasm.cc pp_config has empty
                // entries for them).  Emitting them sets bits that
                // interfere with the OBUF output path, making LED
                // outputs dark.  Filter the common patterns.
                if (tn.startsWith("LIOI") || tn.startsWith("RIOI")) {
                    if (dst.contains("_OLOGIC") || src.contains("_OLOGIC"))
                        continue;
                    if (dst.contains("_ILOGIC") || src.contains("_ILOGIC"))
                        continue;
                    if ((dst.endsWith("_O0") || dst.endsWith("_O1"))
                        && src.contains("OLOGIC"))
                        continue;
                    if ((src.endsWith("_I0") || src.endsWith("_I1"))
                        && dst.contains("ILOGIC"))
                        continue;
                }

                // CLBL* tile intra-slice PIPs: CLBLM_LOGIC_OUTS<n> <-
                // CLBLM_M_<A|B|C|D>Q, CLBLM_M_<pin>.<CLBLM_IMUX|...>,
                // CLBLM_M_<input>.CLBLM_BYP<n>.  These are slice
                // input/output connections that Vivado's bitgen treats
                // as implicit defaults (the slice's cell binding sets
                // the bits).  Emitting them programs conflicting bits.
                if (tn.startsWith("CLBL")) {
                    if (dst.startsWith("CLBLM_LOGIC_OUTS")
                        || dst.startsWith("CLBLL_LOGIC_OUTS"))
                        continue;
                }

                // Drop CARRY4_XOR FFMUX SitePIPs that json2dcp injects
                // for #56 (CARRY4 sum-output -> FF.D).  Same reasoning:
                // intra-site, implicit in the CARRY4+FF binding, so
                // nextpnr's FASM doesn't carry them.
                if (tn.startsWith("CLBLM_") || tn.startsWith("CLBLL_")) {
                    if ((dst.endsWith("FFMUX") || dst.endsWith("OUTMUX"))
                        && src.equals("CARRY4_XOR"))
                        continue;
                    // nextpnr-format pseudo "SLICE_X*.FFMUX.CARRY4_XOR"
                    if (src.equals("CARRY4_XOR") && dst.contains("FFMUX"))
                        continue;
                }

                // Phantom BUFGCTRL guard for bare PIPs (fasm.cc:412+).
                // The chipdb's pseudo-pip path includes BUFGCTRL<N>_O <-
                // BUFGCTRL<N>_I0/I1 PIPs even on unbound slots; emit
                // the bare PIP only when slot N is actually bound.
                if (tn.startsWith("CLK_BUFG_TOP_R") || tn.startsWith("CLK_BUFG_BOT_R")) {
                    if (dst.startsWith("CLK_BUFG_BUFGCTRL") && dst.endsWith("_O")
                        && src.startsWith("CLK_BUFG_BUFGCTRL")
                        && (src.endsWith("_I0") || src.endsWith("_I1"))) {
                        int slot;
                        try {
                            slot = Integer.parseInt(
                                src.substring("CLK_BUFG_BUFGCTRL".length(), src.length() - 3));
                        } catch (NumberFormatException e) { slot = -1; }
                        java.util.Set<Integer> used = bufgUsedSlots.get(tn);
                        if (slot >= 0 && (used == null || !used.contains(slot)))
                            continue;
                    }
                }

                List<String> pp = pseudoPipExpand(tn, dst, src);
                if (pp != null) {
                    // Pseudo-PIP: emit substitutions ONLY (no bare PIP),
                    // matching fasm.cc:362 which replaces the PIP with
                    // its feature list.  Phantom-slot guard suppresses
                    // BUFGCTRL.BUFGCTRL_X0Y<N>.* features when slot N
                    // isn't actually bound in this tile (fasm.cc:349).
                    for (String feat : pp) {
                        if (feat.startsWith("BUFGCTRL.BUFGCTRL_X0Y")) {
                            int p0 = "BUFGCTRL.BUFGCTRL_X0Y".length();
                            int p1 = feat.indexOf('.', p0);
                            if (p1 < 0) p1 = feat.length();
                            int slot;
                            try { slot = Integer.parseInt(feat.substring(p0, p1)); }
                            catch (NumberFormatException e) { slot = -1; }
                            java.util.Set<Integer> used = bufgUsedSlots.get(tn);
                            if (slot >= 0 && (used == null || !used.contains(slot)))
                                continue;
                        }
                        addLine(byTile, tn, tn + "." + feat);
                    }
                    pipCount++;
                    continue;
                }
                addLine(byTile, tn, tn + "." + dst + "." + src);
                pipsByTile.computeIfAbsent(tn, k -> new ArrayList<>())
                          .add(new String[]{dst, src});
                pipCount++;
            }
        }

        // ---------- per-tile clock-distribution emission ----------
        // Ports fasm.cc:1558-1599 (first per-tile loop): for each tile,
        // emit ENABLE_BUFFER / *_ACTIVE / *_USED features based on which
        // source wires were touched by routing PIPs.  Track all_gclk
        // and hclk_by_row sets so the second pass can emit the
        // CLK_BUFG_REBUF GCLK enables and HCLK_CMT BUFHCLK_USED
        // defaults at every tile in the same row.
        java.util.Set<String> allGclk = new java.util.TreeSet<>();
        Map<Integer, java.util.Set<String>> hclkByRow = new HashMap<>();
        for (Map.Entry<String, List<String[]>> e : pipsByTile.entrySet()) {
            String tn = e.getKey();
            Tile t = des.getDevice().getTile(tn);
            if (t == null) continue;
            String ttype = t.getTileTypeEnum().name();
            int row = t.getRow();

            if (ttype.equals("HCLK_L") || ttype.equals("HCLK_R")
                || ttype.equals("HCLK_L_BOT_UTURN") || ttype.equals("HCLK_R_BOT_UTURN")) {
                java.util.Set<String> usedSrc = new java.util.TreeSet<>();
                for (String[] dsps : e.getValue()) {
                    if (dsps[1].startsWith("HCLK_CK_")) usedSrc.add(dsps[1]);
                }
                for (String s : usedSrc) {
                    if (s.contains("BUFHCLK")) {
                        addLine(byTile, tn, tn + ".ENABLE_BUFFER." + s);
                        hclkByRow.computeIfAbsent(row, k -> new java.util.TreeSet<>())
                                 .add(s.substring(s.indexOf("BUFHCLK")));
                    }
                }
            } else if (ttype.startsWith("CLK_HROW")) {
                java.util.Set<String> usedGclk = new java.util.TreeSet<>();
                java.util.Set<String> usedCkIn = new java.util.TreeSet<>();
                for (String[] dsps : e.getValue()) {
                    String s = dsps[1];
                    if (s.startsWith("CLK_HROW_R_CK_GCLK")) usedGclk.add(s);
                    else if (s.startsWith("CLK_HROW_CK_IN")) usedCkIn.add(s);
                }
                for (String s : usedGclk) {
                    addLine(byTile, tn, tn + "." + s + "_ACTIVE");
                    allGclk.add(s.substring(s.indexOf("GCLK")));
                }
                for (String s : usedCkIn) {
                    if (s.contains("HROW_CK_INT")) continue;
                    addLine(byTile, tn, tn + "." + s + "_ACTIVE");
                }
            } else if (ttype.startsWith("HCLK_CMT")) {
                java.util.Set<String> usedCcio = new java.util.TreeSet<>();
                java.util.Set<String> usedHclk = new java.util.TreeSet<>();
                for (String[] dsps : e.getValue()) {
                    String s = dsps[1];
                    if (s.startsWith("HCLK_CMT_CCIO")) usedCcio.add(s);
                    else if (s.startsWith("HCLK_CMT_CK_")) usedHclk.add(s);
                }
                for (String s : usedCcio) {
                    addLine(byTile, tn, tn + "." + s + "_ACTIVE");
                    addLine(byTile, tn, tn + "." + s + "_USED");
                }
                for (String s : usedHclk) {
                    if (s.contains("BUFHCLK")) {
                        addLine(byTile, tn, tn + "." + s + "_USED");
                        hclkByRow.computeIfAbsent(row, k -> new java.util.TreeSet<>())
                                 .add(s.substring(s.indexOf("BUFHCLK")));
                    }
                }
            }
        }

        // For each CLK_BUFG_REBUF tile in the SAME COLUMN as a touched
        // CLK_BUFG_TOP_R / BOT_R tile, the clock spine traverses the
        // REBUF tile vertically -- nextpnr emits GCLK enables for every
        // such tile.  Compute the set of active X columns.
        java.util.Set<Integer> bufgClkColX = new java.util.HashSet<>();
        for (String tname : touchedTiles) {
            if (!tname.startsWith("CLK_BUFG_TOP_R") && !tname.startsWith("CLK_BUFG_BOT_R")) continue;
            int xPos = tname.lastIndexOf('X');
            int yPos = tname.lastIndexOf('Y');
            if (xPos < 0 || yPos < xPos) continue;
            try { bufgClkColX.add(Integer.parseInt(tname.substring(xPos+1, yPos))); }
            catch (NumberFormatException e) { /* skip */ }
        }

        // CLK_BUFG_REBUF GCLK enables: Vivado sets them per SEGMENT
        // occupancy of each routed clock.  A REBUF tile's _TOP wire
        // belongs to the spine segment above the tile and _BOT to the
        // segment below; walking every routed node's wires gives the
        // exact set (golden rule: wire <n>_TOP used -> GCLK<n>_ENABLE_
        // BELOW, <n>_BOT used -> GCLK<n>_ENABLE_ABOVE; through-tiles
        // naturally get both).
        {
            java.util.Set<String> rebufFeats = new java.util.TreeSet<>();
            java.util.regex.Pattern rp = java.util.regex.Pattern.compile(
                "CLK_BUFG_REBUF_R_CK_(GCLK\\d+)_(TOP|BOT)");
            for (com.xilinx.rapidwright.design.Net net : des.getNets()) {
                for (PIP pip : net.getPIPs()) {
                    for (com.xilinx.rapidwright.device.Node node :
                             new com.xilinx.rapidwright.device.Node[]{
                                 pip.getStartNode(), pip.getEndNode()}) {
                        if (node == null) continue;
                        for (com.xilinx.rapidwright.device.Wire w : node.getAllWiresInNode()) {
                            Tile wt = w.getTile();
                            if (wt == null || !wt.getTileTypeEnum().name().equals("CLK_BUFG_REBUF"))
                                continue;
                            java.util.regex.Matcher wm = rp.matcher(w.getWireName());
                            if (!wm.matches()) continue;
                            rebufFeats.add(wt.getName() + "." + wm.group(1) + "_ENABLE_"
                                + (wm.group(2).equals("TOP") ? "BELOW" : "ABOVE"));
                        }
                    }
                }
            }
            for (String f : rebufFeats)
                addLine(byTile, f.substring(0, f.indexOf('.')), f);
        }

        // Per-tile-type defaults emitted regardless of bare PIPs.
        // fasm.cc:1601-1638 second per-tile loop.
        // Default allGclk to GCLK16/GCLK30 if the first-pass walk didn't
        // populate it (small designs only touch a couple of GCLK rails).
        java.util.Set<String> gclkSet = allGclk.isEmpty()
            ? new java.util.TreeSet<>(java.util.Arrays.asList("GCLK16", "GCLK30"))
            : allGclk;
        for (Tile t : des.getDevice().getAllTiles()) {
            String tn = t.getName();
            String ttype = t.getTileTypeEnum().name();
            int row = t.getRow();
            if (ttype.equals("CLK_BUFG_REBUF")) {
                continue;   // handled by the segment-occupancy pass above
            }
            if (ttype.startsWith("HCLK_CMT")) {
                // fasm.cc:1609-1612 -- HCLK_CMT_CK_<BUFHCLK*>_USED for
                // every BUFHCLK row in the same chipdb row as this tile.
                java.util.Set<String> rowBufhclks = hclkByRow.get(row);
                if (rowBufhclks != null) {
                    for (String h : rowBufhclks) {
                        addLine(byTile, tn, tn + ".HCLK_CMT_CK_" + h + "_USED");
                    }
                }
            }
            if (!touchedTiles.contains(tn)) continue;
            if (ttype.equals("CLK_BUFG_TOP_R") || ttype.equals("CLK_BUFG_BOT_R")) {
                // fasm.cc:1620-1634 -- if any BUFGCTRL in this tile is
                // bound, emit unused-slot input-mux defaults for the
                // other 15 slots (slot N -> CLK_BUFG_IMUX<28+N%4>_<N/4>).
                java.util.Set<Integer> used = bufgUsedSlots.get(tn);
                if (used != null && !used.isEmpty()) {
                    for (int n = 0; n < 16; n++) {
                        if (used.contains(n)) continue;
                        String imux = "CLK_BUFG_IMUX" + (28 + (n % 4)) + "_" + (n / 4);
                        addLine(byTile, tn, tn + ".CLK_BUFG_BUFGCTRL" + n + "_I0." + imux);
                        addLine(byTile, tn, tn + ".CLK_BUFG_BUFGCTRL" + n + "_I1." + imux);
                    }
                }
            }
        }
    }

    // Pseudo-pip substitution table.  Returns the feature list to emit
    // in place of the bare PIP, or null if the PIP is a regular routing
    // PIP.  Mirrors a subset of fasm.cc's pp_config (get_pseudo_pip_data
    // at fasm.cc:142).  Start with the BUFG/BUFR set; the IOI3/RIOI/
    // CLK_HROW entries are extensions for #79 follow-up.
    static List<String> pseudoPipExpand(String tileName, String dst, String src) {
        // ILOGIC pseudo-pip: <tile>.IOI_ILOGIC0_O.<L|R>IOI_ILOGIC0_D
        // -> ILOGIC_Y0.ZINV_D (fasm.cc convention for active-low D
        // inversion default on an active ILOGIC input).
        if (tileName.startsWith("RIOI")
            && dst.equals("IOI_ILOGIC0_O")
            && src.equals("RIOI_ILOGIC0_D")) {
            return java.util.Arrays.asList("ILOGIC_Y0.ZINV_D");
        }
        // CLK_HROW BUFHCE: fasm.cc:233-237.  The CLK_HROW_CK_HCLK_OUT_<hck>
        // <- CLK_HROW_CK_MUX_OUT_<hck> PIP marks a BUFHCE site as active;
        // emit IN_USE + ZINV_CE on the corresponding BUFHCE slot.
        // L-side BUFHCEs are at X0, R-side at X1.
        if (tileName.startsWith("CLK_HROW_TOP_R") || tileName.startsWith("CLK_HROW_BOT_R")) {
            String dstPfx = "CLK_HROW_CK_HCLK_OUT_";
            String srcPfx = "CLK_HROW_CK_MUX_OUT_";
            if (dst.startsWith(dstPfx) && src.startsWith(srcPfx)) {
                String dh = dst.substring(dstPfx.length());
                String sh = src.substring(srcPfx.length());
                if (dh.equals(sh) && dh.length() >= 2) {
                    char side = dh.charAt(0);    // 'L' or 'R'
                    if (side == 'L' || side == 'R') {
                        String idx = dh.substring(1);
                        try {
                            Integer.parseInt(idx);     // must be numeric
                            String x = (side == 'R') ? "X1Y" : "X0Y";
                            String buf = "BUFHCE.BUFHCE_" + x + idx + ".";
                            return java.util.Arrays.asList(
                                buf + "IN_USE",
                                buf + "ZINV_CE"
                            );
                        } catch (NumberFormatException e) { /* fall through */ }
                    }
                }
            }
        }
        // CLK_BUFG_{TOP,BOT}_R: BUFGCTRL inputs.  fasm.cc:243-256 keys.
        if (tileName.startsWith("CLK_BUFG_TOP_R") || tileName.startsWith("CLK_BUFG_BOT_R")) {
            // dst == "CLK_BUFG_BUFGCTRL<n>_O" && src == "CLK_BUFG_BUFGCTRL<n>_I0"
            // -> emits BUFGCTRL_X0Y<n>.IN_USE / IS_IGNORE1_INVERTED /
            //          ZINV_CE0 / ZINV_S0 plus the bare PIP.
            String ppfx = "CLK_BUFG_BUFGCTRL";
            if (dst.startsWith(ppfx) && dst.endsWith("_O")
                && src.startsWith(ppfx)
                && (src.endsWith("_I0") || src.endsWith("_I1"))) {
                String nn = src.substring(ppfx.length(), src.length() - 3);
                boolean isI0 = src.endsWith("_I0");
                List<String> out = new ArrayList<>();
                out.add(dst + "." + src);    // keep the bare PIP too
                String prefix = "BUFGCTRL.BUFGCTRL_X0Y" + nn + ".";
                out.add(prefix + "IN_USE");
                if (isI0) {
                    out.add(prefix + "IS_IGNORE1_INVERTED");
                    out.add(prefix + "ZINV_CE0");
                    out.add(prefix + "ZINV_S0");
                } else {
                    out.add(prefix + "IS_IGNORE0_INVERTED");
                    out.add(prefix + "ZINV_CE1");
                    out.add(prefix + "ZINV_S1");
                }
                return out;
            }
        }
        return null;
    }
}
