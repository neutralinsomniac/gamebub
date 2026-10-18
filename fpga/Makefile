# Bitstreams and the external core packages (SNES, PSX).
#
#   make snes                build/snes/snes.bit and snes.bit.hs
#   make package             build/package/cores/jeremy.SNES/ from build/snes
#   make snes package        build, then package
#   make psx package-psx     the same for build/package/cores/jeremy.PSX/ (rev 4 only)
#   make all                 every bitstream (boot, gameboy, gba, snes, psx)
#   make TARGET=gamebub_rev3 gba
#
# A bitstream target always rebuilds (Vivado takes ~10 minutes; make cannot
# tell whether the source tree changed). `package` only copies from an
# existing build. Commands run through `nix develop ..#vivado` unless vivado
# is already on PATH (i.e. you are inside that shell).

TARGET ?= gamebub_rev4
BUILD ?= build
PACKAGE_OUT ?= $(BUILD)/package

CORES := boot gameboy gba snes psx
CLASS_boot := net.gamebub.core.boot.HandheldBoot
CLASS_gameboy := net.gamebub.core.gameboy.HandheldGameboy
CLASS_gba := net.gamebub.core.gba.HandheldGba
CLASS_snes := net.gamebub.core.snes.HandheldSnes
CLASS_psx := net.gamebub.core.psx.HandheldPsx

ifeq ($(shell command -v vivado 2>/dev/null),)
RUN := nix develop ..\#vivado -c
endif

.PHONY: all $(CORES) package package-psx clean

all: $(CORES)

$(CORES):
	$(RUN) python3 scripts/build_core.py --target $(TARGET) --name $@ \
	    --core-class $(CLASS_$@) --build-root $(BUILD)/$@

package: $(BUILD)/snes/snes.bit
	python3 scripts/package_core.py --name snes --build-root $(BUILD)/snes \
	    --out $(PACKAGE_OUT)

package-psx: $(BUILD)/psx/psx.bit
	python3 scripts/package_core.py --name psx --build-root $(BUILD)/psx \
	    --out $(PACKAGE_OUT)

$(BUILD)/snes/snes.bit $(BUILD)/psx/psx.bit:
	@echo "$@ is missing: run 'make $(notdir $(basename $@))' first" >&2; exit 1

clean:
	rm -rf $(BUILD)
