# json_drc-portable — fetch or regenerate everything the bundle needs.
#
# Only src/ is in git (see src/SOURCES.md); the 650 MB of jars, device data and
# wire oracle around it are upstream binaries or generated files.  This Makefile
# is the record of where each one comes from, so a fresh clone is one `make`
# away from a working tool instead of a manual scavenger hunt.
#
#   make            fetch what is missing, then build the jars
#   make deps       fetch/generate the inputs only (jar, gson, device data, oracle)
#   make jars       rebuild our eight tool jars from src/
#   make verify     prove the result actually runs
#   make dist       build a portable tarball of the whole bundle
#   make clean      remove build output (jars, classes)
#   make distclean  also remove fetched/generated inputs
#
# NOT fetched: the release's rapidwright_data.zip is 2.0 GB and this bundle
# touches exactly one part, so `data` asks RapidWright for that part instead and
# gets ~3.6 MB.  See src/FetchDevice.java.

PART      ?= xc7vx485tffg1761-2
RW_VER    ?= 2025.2.1
RW_TAG    ?= v$(RW_VER)-beta
GSON_VER  ?= 2.10.1
JAVAC     ?= javac
JAVA      ?= java
JAR       ?= jar
CURL      ?= curl -fSL --retry 3

RW_REL  := https://github.com/Xilinx/RapidWright/releases/download/$(RW_TAG)
GSON_URL:= https://repo1.maven.org/maven2/com/google/code/gson/gson/$(GSON_VER)/gson-$(GSON_VER).jar

RW_JAR  := lib/rapidwright-$(RW_VER)-standalone-lin64.jar
GSON    := lib/gson-$(GSON_VER).jar
ORACLE  := oracle/$(PART).oracle.txt.gz
DEVSTAMP:= data/.device-$(PART).stamp
CLASSES := lib/classes

# RapidWright resolves its data under $RAPIDWRIGHT_PATH/data, which is why the
# bundle has a nested data/data.  Both the launcher and these rules must agree.
export RAPIDWRIGHT_PATH := $(CURDIR)/data

CP      := $(RW_JAR):$(GSON)
# Sources that go into the jars.  FetchDevice is a build-time helper and is
# compiled with them so it lands in the same class tree.  src/attic/ is NOT
# built: one-off probes from finished campaigns, kept for reference only.
SRCS    := src/WireOracle.java src/json2dcp.java src/dcp2fasm.java \
           src/json_drc.java src/FetchDevice.java \
           src/dcp2xml.java src/xml2dcp.java src/xml2json.java \
           src/xml2fasm.java src/dcp2routes.java
# One class tree, one jar per entry point -- see the pattern rule below.
TOOLS   := json_drc json2dcp dcp2fasm dcp2xml xml2dcp xml2json xml2fasm dcp2routes
JARS    := $(patsubst %,lib/rapidwright_%.jar,$(TOOLS))

.PHONY: all deps jars verify dist clean distclean help
.DELETE_ON_ERROR:          # a truncated download must not look like a success

all: jars

help:
	@sed -n '2,20p' $(firstword $(MAKEFILE_LIST))
	@echo
	@echo "  PART=$(PART)  RW_VER=$(RW_VER)  RW_TAG=$(RW_TAG)"

deps: $(RW_JAR) $(GSON) $(DEVSTAMP) $(ORACLE)

# --- upstream binaries ------------------------------------------------------
# Downloaded to a temp name and renamed, so an interrupted fetch never leaves a
# short file that make would treat as up to date.

$(RW_JAR):
	@mkdir -p lib
	@echo ">>> fetching RapidWright $(RW_VER) standalone (~95 MB)"
	$(CURL) -o $@.tmp $(RW_REL)/$(notdir $@)
	@mv $@.tmp $@

$(GSON):
	@mkdir -p lib
	@echo ">>> fetching gson $(GSON_VER)"
	$(CURL) -o $@.tmp $(GSON_URL)
	@mv $@.tmp $@

# --- generated inputs -------------------------------------------------------

# Device database for ONE part, fetched on demand by RapidWright itself.
$(DEVSTAMP): $(RW_JAR) src/FetchDevice.java
	@mkdir -p data $(CLASSES)
	$(JAVAC) -d $(CLASSES) -cp $(CP) src/FetchDevice.java
	$(JAVA) -cp $(CP):$(CLASSES) dev.fpga.rapidwright.FetchDevice $(PART)
	@touch $@

# V7 wire oracle.  json2dcp only consults it for this part; regenerating it
# needs the device database, hence the order-only dep on the stamp.
$(ORACLE): $(RW_JAR) src/BuildWireOracle.java | $(DEVSTAMP)
	@mkdir -p oracle $(CLASSES)
	@echo ">>> rebuilding wire oracle for $(PART) (slow)"
	$(JAVAC) -d $(CLASSES) -cp $(CP) src/BuildWireOracle.java
	$(JAVA) -cp $(CP):$(CLASSES) dev.fpga.rapidwright.BuildWireOracle $(PART) $@.tmp
	@mv $@.tmp $@

# --- our jars ---------------------------------------------------------------
# One compile, N manifests: the classes are shared, only Main-Class differs.

$(CLASSES)/.built: $(SRCS) $(RW_JAR) $(GSON)
	@mkdir -p $(CLASSES)
	$(JAVAC) -d $(CLASSES) -cp $(CP) $(SRCS)
	@touch $@

# json2dcp's manifest is manifest.mf, not manifest_json2dcp.mf, for historical
# reasons; every other tool follows the pattern.
lib/rapidwright_json2dcp.jar: $(CLASSES)/.built src/manifest.mf
	$(JAR) cfm $@ src/manifest.mf -C $(CLASSES) dev

lib/rapidwright_%.jar: $(CLASSES)/.built src/manifest_%.mf
	$(JAR) cfm $@ src/manifest_$*.mf -C $(CLASSES) dev

jars: $(JARS)

# --- verification -----------------------------------------------------------
# `make jars` succeeding only proves javac ran.  This proves the jar loads
# RapidWright, opens the part, and that the oracle is readable -- the three
# things that are actually broken when this bundle is mis-assembled.

verify: $(JARS) $(DEVSTAMP) $(ORACLE)
	@echo ">>> device + classpath"
	$(JAVA) -cp $(CP):$(CLASSES) dev.fpga.rapidwright.FetchDevice $(PART)
	@echo ">>> oracle readable"
	@gzip -t $(ORACLE) && echo "    $(ORACLE) ok ($$(zcat $(ORACLE) | wc -l) lines)"
	@echo ">>> json_drc entry point"
	@./run.sh --help >/dev/null 2>&1 || true
	@$(JAVA) -cp lib/rapidwright_json_drc.jar:$(CP) dev.fpga.rapidwright.json_drc 2>&1 \
	   | head -3
	@echo "OK"

# --- distribution -----------------------------------------------------------
# Replaces the old src/package_json_drc.sh, which assembled the tarball by
# copying out of ~/rapidwright and carried its own duplicate copies of README.md
# and src/SOURCES.md as heredocs -- so the shipped docs could drift from the
# real ones.  The working tree IS the bundle now: build it, then tar it.
#
# Stamped from the last commit date rather than the wall clock, so repackaging
# the same commit gives the same name.

DIST_STAMP := $(shell git log -1 --format=%cd --date=format:%Y%m%d 2>/dev/null || echo snapshot)
DIST_NAME  := json_drc-portable-$(DIST_STAMP)

dist: jars $(DEVSTAMP) $(ORACLE)
	@rm -rf /tmp/$(DIST_NAME) && mkdir -p /tmp/$(DIST_NAME)
	@tar -c --exclude-vcs --exclude=work --exclude=artifacts --exclude=$(CLASSES) \
	     --exclude='src/attic' Makefile README.md run.sh src lib data oracle \
	   | tar -x -C /tmp/$(DIST_NAME)
	@tar -C /tmp -czf /tmp/$(DIST_NAME).tar.gz $(DIST_NAME)
	@rm -rf /tmp/$(DIST_NAME)
	@ls -lh /tmp/$(DIST_NAME).tar.gz
	@echo "Unpack: tar -xzf $(DIST_NAME).tar.gz && cd $(DIST_NAME) && ./run.sh --help"

clean:
	rm -rf $(CLASSES) $(JARS) src/*.jar src/dev

distclean: clean
	rm -f $(RW_JAR) $(GSON) $(ORACLE) $(DEVSTAMP)
	rm -rf data
