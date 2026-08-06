# Source — preferred form for modification

The `.java` files and the two shell scripts in this directory are the
preferred form for modification of every component of this tool that
we authored.  Together they reproduce the bundle byte-for-byte (modulo
the timestamps embedded in the tar header).

## Rebuild from inside the bundle

The bundle's `lib/` already contains all the jars that `javac` needs:

    cd src
    javac -d ../lib/classes \
          -cp ../lib/rapidwright-2025.2.1-standalone-lin64.jar:../lib/gson-2.10.1.jar \
          WireOracle.java json2dcp.java dcp2fasm.java json_drc.java
    jar -cfm ../lib/rapidwright_json_drc.jar manifest.mf -C ../lib/classes dev

After that, `../run.sh` picks up the new jar.  Edit any of the `.java`
files first to change behaviour.

## Repackaging

`package_json_drc.sh` is the script that produced this tarball.  It
expects the original development layout (`~/rapidwright/build/`,
`~/.local/share/RapidWright/data/`, the wire-oracle path) and will not
work unmodified from the unpacked bundle — it is included here as the
authoritative record of how the archive was assembled.

## Upstream sources

These libraries are bundled as binaries; their source lives upstream:

| Component                                | Upstream                                                                |
|------------------------------------------|-------------------------------------------------------------------------|
| `rapidwright-2025.2.1-standalone-lin64.jar` | <https://github.com/Xilinx/RapidWright> (release tag 2025.2.1)        |
| `gson-2.10.1.jar`                        | <https://github.com/google/gson> (release tag gson-parent-2.10.1)       |
| `data/cell_pin_defaults.dat` etc.        | RapidWright `data/` zip — fetched by RapidWright from its release URL  |
| `data/devices/virtex7/xc7vx485t_db.dat`  | Same as above                                                          |
| `oracle/*.oracle.txt.gz`                 | Built by `BuildWireOracle.java` against RapidWright's DeviceResources  |

The oracle file is reproducible by running:

    javac -cp ../lib/rapidwright-2025.2.1-standalone-lin64.jar \
          BuildWireOracle.java
    java  -cp .:../lib/rapidwright-2025.2.1-standalone-lin64.jar \
          BuildWireOracle xc7vx485tffg1761-2 \
          ../oracle/xc7vx485tffg1761-2.oracle.txt.gz
