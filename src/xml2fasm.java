package dev.fpga.rapidwright;

import com.xilinx.rapidwright.design.Design;
import java.io.*;

/**
 * xml2fasm — convert opendcp XML directly to prjxray FASM, bypassing the DCP.
 *
 * The opendcp XML holds the COMPLETE physical state (cells + config, SitePIPs,
 * native routethrus, all inter-tile routing PIPs).  FASM is feature-based: it
 * needs the mux SELECTIONS (SitePIPs) and PIP features, NOT the intra-site net
 * occupancy that writeCheckpoint requires.  So XML -> FASM is lossless even
 * though XML -> DCP is not (RapidWright exposes no site-wire-net setter).
 *
 *   opendcp.xml -> RapidWright Design (xml2dcp.buildDesign, no routeSite)
 *               -> FASM           (dcp2fasm.emitDesign)
 *               -> frames/bit     (prjxray fasm2frames + xc7frames2bit)
 */
public class xml2fasm {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: xml2fasm <in.opendcp.xml> <out.fasm> [sitepips_sidefile]");
            System.exit(2);
        }
        Design des = xml2dcp.buildDesign(args[0], false);   // no site-routing reconstruction
        PrintStream out = new PrintStream(new FileOutputStream(args[1]));
        dcp2fasm.emitDesign(des, out, args.length > 2 ? args[2] : null);
        System.out.println("xml2fasm: wrote " + args[1]);
    }
}
