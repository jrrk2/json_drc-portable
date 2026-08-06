package dev.fpga.rapidwright;

import com.xilinx.rapidwright.device.Device;

/**
 * FetchDevice — materialise one part's device database under RAPIDWRIGHT_PATH.
 *
 * RapidWright downloads device data lazily, per part, the first time a Device
 * is opened.  That matters here: the release's rapidwright_data.zip is 2.0 GB,
 * but this bundle only ever touches one 7-series part, and asking RapidWright
 * for it fetches ~3.6 MB.  The Makefile's `data` target is therefore just this
 * program rather than a zip download.
 *
 *   RAPIDWRIGHT_PATH=<bundle>/data java ... FetchDevice xc7vx485tffg1761-2
 *
 * Exits non-zero if the device cannot be opened, so make stops rather than
 * leaving a half-populated data directory that looks fetched.
 */
public class FetchDevice {
    public static void main(String[] args) {
        String part = args.length > 0 ? args[0] : "xc7vx485tffg1761-2";
        Device d = Device.getDevice(part);
        if (d == null) {
            System.err.println("FetchDevice: could not open device " + part);
            System.exit(1);
        }
        System.out.println("FetchDevice: " + part + " -> " + d.getName()
                + " (" + d.getTiles().length + " tile rows)");
    }
}
