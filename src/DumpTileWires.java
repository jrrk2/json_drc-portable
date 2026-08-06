// Dump RapidWright's view of a tile: every wire name + every PIP (src→dst).
// Use to reverse-engineer the wire-name namespace mismatch between nextpnr's
// chipdb and RapidWright's device model (task #46).
//
// Usage:
//   java -cp rapidwright_json2dcp.jar:<path>/rapidwright-2025.2.1-standalone-lin64.jar:<path>/gson-2.10.1.jar \
//        dev.fpga.rapidwright.DumpTileWires <part> <tile_name> [<grep_substring>]
//   e.g.: ... xc7vx485tffg1761-2 CLK_BUFG_REBUF_X192Y221 GCLK16

package dev.fpga.rapidwright;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.Tile;

public class DumpTileWires {
    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("usage: DumpTileWires <part> <tile> [<grep>]");
            System.exit(1);
        }
        String part = args[0];
        String tileName = args[1];
        String grep = args.length >= 3 ? args[2] : null;

        Design des = new Design("dump", part);
        Device dev = des.getDevice();
        Tile t = dev.getTile(tileName);
        if (t == null) {
            System.err.println("tile not found: " + tileName);
            System.exit(2);
        }

        System.out.println("==== tile " + tileName
            + " type=" + t.getTileTypeEnum()
            + " wires=" + t.getWireCount()
            + " ====");
        System.out.println("==== WIRES ====");
        for (int i = 0; i < t.getWireCount(); i++) {
            String w = t.getWireName(i);
            if (grep != null && !w.contains(grep)) continue;
            System.out.println("  [" + i + "] " + w);
        }

        System.out.println("==== PIPS ====");
        int count = 0;
        for (PIP p : t.getPIPs()) {
            String src = p.getStartWire().getWireName();
            String dst = p.getEndWire().getWireName();
            String line = src + " -> " + dst;
            if (grep != null && !line.contains(grep)) continue;
            System.out.println("  " + line);
            if (++count >= 200) {
                System.out.println("  ... (truncated at 200)");
                break;
            }
        }
    }
}
