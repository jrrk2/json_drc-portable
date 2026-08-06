// dcp2routes: dump a routed DCP's per-net routing PIPs in the wire-name form
// that nextpnr-xilinx's --fixed-routes accepts:
//
//     <net_name> <tile>/<src_wire>-><tile>/<dst_wire>
//
// One line per routing PIP.  This lets a Vivado-generated (hold-clean) routing
// of a hard-macro island be LOCKED into nextpnr, which does no hold analysis of
// its own.  Wire names are the shared Xilinx nomenclature that prjxray/nextpnr
// and Vivado both use, so getWireByName() resolves them directly.
//
// Optional 3rd arg = a substring filter on the net name (e.g. a macro instance
// hierarchy prefix) so only the frozen island's nets are emitted; omit to dump
// every net.
package dev.fpga.rapidwright;

import java.io.PrintStream;
import java.io.FileOutputStream;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.device.PIP;

public class dcp2routes {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: dcp2routes <in.dcp> <out.routes> [net-name-filter]");
            System.exit(1);
        }
        String filter = args.length > 2 ? args[2] : null;
        Design des = Design.readCheckpoint(args[0]);
        PrintStream out = new PrintStream(new FileOutputStream(args[1]));
        out.println("# nextpnr-xilinx fixed-routes from " + args[0]
                    + (filter != null ? " (filter='" + filter + "')" : ""));

        int nnets = 0, npips = 0, nskip = 0;
        for (Net net : des.getNets()) {
            String nn = net.getName();
            if (filter != null && !nn.contains(filter)) { nskip++; continue; }
            java.util.List<PIP> pips = net.getPIPs();
            if (pips == null || pips.isEmpty()) continue;
            boolean any = false;
            for (PIP pip : pips) {
                String tn = pip.getTile().getName();
                String start = pip.getStartWireName();
                String end   = pip.getEndWireName();
                String src, dst;
                if (pip.isReversed()) { src = end; dst = start; }
                else                  { src = start; dst = end; }
                out.println(nn + " " + tn + "/" + src + "->" + tn + "/" + dst);
                npips++;
                any = true;
            }
            if (any) nnets++;
        }
        out.close();
        System.err.println("[dcp2routes] nets=" + nnets + " pips=" + npips
                           + " skipped(filter)=" + nskip);
    }
}
