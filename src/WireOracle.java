// In-process reader for the static wire-name oracle (BuildWireOracle output).
//
// Loads per-tile-type wire + PIP catalogues so that PIP-direction questions
// can be answered O(1) without iterating Device.getAllWiresInNode() at runtime.
//
// The oracle file is the gzipped text produced by BuildWireOracle.  Records:
//   T type wireCount pipCount
//   W type idx wireName
//   P type srcIdx dstIdx flagsBRF       (B/b bidir, R/r routethru, F forward)
//   I tile type col row                 (tile instance -> type)
//   S site siteType tile                (site -> tile mapping)
//
// Usage from json2dcp:
//   WireOracle oracle = WireOracle.load(path);            // or null on miss
//   WireOracle.PipMatch m = oracle.lookupPipAtTile(
//       tileName, srcWireName, dstWireName);
//   if (m != null) {
//       if (m.direction == Direction.FORWARD)        { use as-is }
//       if (m.direction == Direction.REVERSE_BIDIR)  { setIsReversed(true) }
//       if (m.routethru)                             { it's a bel-traversing pip }
//   }

package dev.fpga.rapidwright;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

public class WireOracle {

    public enum Direction { FORWARD, REVERSE_BIDIR, REVERSE_ONLY }

    public static class PipMatch {
        public final Direction direction;
        public final boolean bidir;
        public final boolean routethru;
        public final String type;
        public final int srcIdx;
        public final int dstIdx;
        PipMatch(Direction d, boolean b, boolean rt, String t, int s, int dd) {
            direction = d; bidir = b; routethru = rt;
            type = t; srcIdx = s; dstIdx = dd;
        }
    }

    // type -> wire-name -> idx
    private final Map<String, Map<String, Integer>> wireIdx = new HashMap<>();
    // type -> (src,dst) packed long -> flags ('BRF' string)
    private final Map<String, Map<Long, String>> pips = new HashMap<>();
    // tile -> type
    private final Map<String, String> tileType = new HashMap<>();

    private static long key(int src, int dst) {
        return (((long) src) << 32) | (dst & 0xffffffffL);
    }

    /**
     * Load the oracle from a gzipped text file.  Returns null if the file
     * is missing — caller can fall back to the slow node-search path.
     */
    public static WireOracle load(String path) {
        WireOracle o = new WireOracle();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(new GZIPInputStream(new FileInputStream(path)), "UTF-8"))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty() || line.charAt(0) == '#') continue;
                int sp1 = line.indexOf(' ');
                if (sp1 < 0) continue;
                char kind = line.charAt(0);
                switch (kind) {
                    case 'W': {
                        // W type idx wireName
                        int sp2 = line.indexOf(' ', sp1 + 1);
                        int sp3 = line.indexOf(' ', sp2 + 1);
                        String type = line.substring(sp1 + 1, sp2);
                        int idx = Integer.parseInt(line.substring(sp2 + 1, sp3));
                        String name = line.substring(sp3 + 1);
                        o.wireIdx.computeIfAbsent(type, k -> new HashMap<>()).put(name, idx);
                        break;
                    }
                    case 'P': {
                        // P type src dst flags
                        int sp2 = line.indexOf(' ', sp1 + 1);
                        int sp3 = line.indexOf(' ', sp2 + 1);
                        int sp4 = line.indexOf(' ', sp3 + 1);
                        String type = line.substring(sp1 + 1, sp2);
                        int src = Integer.parseInt(line.substring(sp2 + 1, sp3));
                        int dst = Integer.parseInt(line.substring(sp3 + 1, sp4));
                        String flags = line.substring(sp4 + 1);
                        o.pips.computeIfAbsent(type, k -> new HashMap<>())
                              .put(key(src, dst), flags);
                        break;
                    }
                    case 'I': {
                        // I tile type col row
                        int sp2 = line.indexOf(' ', sp1 + 1);
                        int sp3 = line.indexOf(' ', sp2 + 1);
                        String tile = line.substring(sp1 + 1, sp2);
                        String type = line.substring(sp2 + 1, sp3);
                        o.tileType.put(tile, type);
                        break;
                    }
                    case 'T':
                    case 'S':
                    default:
                        // T headers and S site-rows are not consulted by the
                        // hot path here.  Add lookups if/when needed.
                        break;
                }
            }
        } catch (IOException e) {
            System.err.println("[wire-oracle] could not load " + path + ": " + e.getMessage());
            return null;
        }
        return o;
    }

    /**
     * For (tile, srcWire, dstWire), look up the PIP in RapidWright's
     * authoritative catalogue and report whether it's a forward, reverse-bidir,
     * or reverse-only match.  Returns null if no such PIP exists at this tile.
     */
    public PipMatch lookupPipAtTile(String tileName, String srcWire, String dstWire) {
        String type = tileType.get(tileName);
        if (type == null) return null;
        Map<String, Integer> wires = wireIdx.get(type);
        if (wires == null) return null;
        Integer s = wires.get(srcWire);
        Integer d = wires.get(dstWire);
        if (s == null || d == null) return null;
        Map<Long, String> pipsHere = pips.get(type);
        if (pipsHere == null) return null;
        String fwdFlags = pipsHere.get(key(s, d));
        if (fwdFlags != null) {
            return new PipMatch(Direction.FORWARD,
                    fwdFlags.charAt(0) == 'B',
                    fwdFlags.charAt(1) == 'R',
                    type, s, d);
        }
        String revFlags = pipsHere.get(key(d, s));
        if (revFlags != null) {
            Direction dir = (revFlags.charAt(0) == 'B')
                    ? Direction.REVERSE_BIDIR : Direction.REVERSE_ONLY;
            return new PipMatch(dir,
                    revFlags.charAt(0) == 'B',
                    revFlags.charAt(1) == 'R',
                    type, s, d);
        }
        return null;
    }

    public String tileTypeOf(String tileName) {
        return tileType.get(tileName);
    }

    public int numTypes()     { return wireIdx.size(); }
    public int numTiles()     { return tileType.size(); }
    public int numCatalogPIPs() {
        int n = 0;
        for (Map<Long,String> m : pips.values()) n += m.size();
        return n;
    }
}
