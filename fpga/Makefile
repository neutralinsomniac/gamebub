# Bitstreams and the SNES external core package.
#
#   make snes                build/snes/snes.bit and snes.bit.hs
#   make package             build/package/cores/jeremy.SNES/ from build/snes
#   make snes package        build, then package
#   make all                 every bitstream (boot, gameboy, gba, snes)
#   make TARGET=gamebub_rev3 gba
#
# A bitstream target always rebuilds (Vivado takes ~10 minutes; make cannot
# tell whether the source tree changed). `package` only copies from an
# existing build. Commands run through `nix develop ..#vivado` unless vivado
# is already on PATH (i.e. you are inside that shell).

TARGET ?= gamebub_rev4
BUILD ?= build
PACKAGE_OUT ?= $(BUILD)/package

CORES := boot gameboy gba snes
CLASS_boot := net.gamebub.core.boot.HandheldBoot
CLASS_gameboy := net.gamebub.core.gameboy.HandheldGameboy
CLASS_gba := net.gamebub.core.gba.HandheldGba
CLASS_snes := net.gamebub.core.snes.HandheldSnes

ifeq ($(shell command -v vivado 2>/dev/null),)
RUN := nix develop ..\#vivado -c
endif

.PHONY: all $(CORES) package clean

all: $(CORES)

$(CORES):
	$(RUN) python3 scripts/build_core.py --target $(TARGET) --name $@ \
	    --core-class $(CLASS_$@) --build-root $(BUILD)/$@

package: $(BUILD)/snes/snes.bit
	python3 scripts/package_core.py --name snes --build-root $(BUILD)/snes \
	    --out $(PACKAGE_OUT)

$(BUILD)/snes/snes.bit:
	@echo "$@ is missing: run 'make snes' first" >&2; exit 1

clean:
	rm -rf $(BUILD)
