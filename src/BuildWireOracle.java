// Build a static wire-name + PIP oracle from RapidWright's Device model.
//
// Why: json2dcp currently does a runtime node-search to translate
// prjxray-derived (tile, src_wire, dst_wire) PIPs into RapidWright PIP objects.
// That search is slow, buggy for bidir direction (#50) and confuses SITEWIRE
// with tile-wire (#49).  A precomputed table per tile-type answers those
// questions in O(1) at writer time.
//
// Cost model: xc7vx485 has 115 tile types, ~150k tiles, 207M PIP instances.
// Per-type storage collapses PIPs ~1000x.  Per-type wire names ~400x.
//
// Output format (gzipped text, one record per line):
//   T <type_name> <wire_count> <pip_count>
//   W <type_name> <idx> <wire_name>
//   P <type_name> <src_idx> <dst_idx> <BFRS-flag>
//        B=bidir R=routethru F=forward (lowercase if false)
//   I <tile_name> <type_name> <col> <row>
//   S <site_name> <site_type> <tile_name>
//        — site→tile mapping; this is what unlocks the chipdb-vs-RapidWright
//          tile-name divergence (chipdb IOB18_X1Y276 vs RW IOB_X0Y348 in tile
//          LIOB18_X81Y361, same physical pad).
//
// One pass per tile-type catalogue, one pass over the tile grid for the
// instance index.
//
// Usage:
//   java -Xmx12g -cp <build>:<rapidwright.jar> \
//        dev.fpga.rapidwright.BuildWireOracle xc7vx485tffg1761-2 /tmp/oracle.txt.gz

package dev.fpga.rapidwright;

import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.device.TileTypeEnum;

import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPOutputStream;

public class BuildWireOracle {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: BuildWireOracle <part> <out.gz>");
            System.exit(1);
        }
        String part = args[0];
        String outPath = args[1];

        long t0 = System.currentTimeMillis();
        Device dev = Device.getDevice(part);
        long t1 = System.currentTimeMillis();
        System.err.println("Loaded " + part + " in " + (t1 - t0) + " ms");

        Tile[][] tiles = dev.getTiles();
        int rows = tiles.length;
        int cols = tiles[0].length;

        // Pick one canonical tile per TileTypeEnum to harvest its wire/PIP catalogue
        Map<TileTypeEnum, Tile> exemplarByType = new LinkedHashMap<>();
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                Tile t = tiles[r][c];
                if (t == null) continue;
                exemplarByType.putIfAbsent(t.getTileTypeEnum(), t);
            }
        }
        System.err.println("Tile types: " + exemplarByType.size());

        try (GZIPOutputStream gz = new GZIPOutputStream(new FileOutputStream(outPath));
             Writer w = new BufferedWriter(new OutputStreamWriter(gz, "UTF-8"))) {

            w.write("# part " + part + "\n");
            w.write("# tile-types " + exemplarByType.size() + "\n");

            // Per-type catalogue (T / W / P records)
            long totalWires = 0, totalPIPs = 0, totalSitewires = 0;
            for (Map.Entry<TileTypeEnum, Tile> e : exemplarByType.entrySet()) {
                String typeName = e.getKey().toString();
                Tile ex = e.getValue();
                int wireCount = ex.getWireCount();

                // Build wire-name -> stable index.  Use RapidWright's getWireIndex
                // as the canonical id so consumers can join against tile.getPIPs()
                // results directly.
                List<PIP> pips = ex.getPIPs();
                w.write("T " + typeName + " " + wireCount + " " + pips.size() + "\n");

                for (int i = 0; i < wireCount; i++) {
                    String wireName = ex.getWireName(i);
                    if (wireName == null) continue;
                    // Tile-wire vs site-wire distinction: site-wire names start
                    // with "SITEWIRE" prefix in RapidWright's catalog for some
                    // tile types; for most it's just intra-site convention.
                    // We expose the raw name; consumer maps as needed.
                    w.write("W " + typeName + " " + i + " " + wireName + "\n");
                }

                for (PIP p : pips) {
                    int src = p.getStartWireIndex();
                    int dst = p.getEndWireIndex();
                    char bidir = p.isBidirectional() ? 'B' : 'b';
                    char rt    = p.isRouteThru()    ? 'R' : 'r';
                    char fwd   = 'F'; // start->end is forward by definition
                    w.write("P " + typeName + " " + src + " " + dst + " "
                            + bidir + rt + fwd + "\n");
                }

                totalWires += wireCount;
                totalPIPs += pips.size();
            }

            // Per-instance tile dir (I records) + site-to-tile mapping (S records).
            // S records are what bridge the chipdb-vs-RapidWright tile-name
            // mismatch: chipdb refers to BELs by site (e.g. IOB_X1Y276), while
            // RapidWright places sites inside tiles whose names follow a
            // different X scheme (LIOB18_X81Y361).  The S record carries both.
            int tileInstances = 0, siteInstances = 0;
            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < cols; c++) {
                    Tile t = tiles[r][c];
                    if (t == null) continue;
                    w.write("I " + t.getName() + " "
                            + t.getTileTypeEnum().toString() + " "
                            + t.getColumn() + " " + t.getRow() + "\n");
                    tileInstances++;
                    for (Site s : t.getSites()) {
                        w.write("S " + s.getName() + " "
                                + s.getSiteTypeEnum().toString() + " "
                                + t.getName() + "\n");
                        siteInstances++;
                    }
                }
            }

            w.write("# wires-per-type " + totalWires + "\n");
            w.write("# pips-per-type " + totalPIPs + "\n");
            w.write("# tile-instances " + tileInstances + "\n");
            w.write("# site-instances " + siteInstances + "\n");

            long t2 = System.currentTimeMillis();
            System.err.println("Wrote " + outPath
                + ": " + exemplarByType.size() + " types, "
                + totalWires + " catalogue wires, "
                + totalPIPs + " catalogue pips, "
                + tileInstances + " tile instances "
                + "(elapsed " + (t2 - t0) + " ms)");
        }
    }
}
