# Release assets: per-core zips in Game Bub Core Directory layout
# (<CoreID>/core.json at zip root), the standalone rev4 UF2 (firmware plus the
# built-in boot/gameboy/gba bitstreams and free BIOS replacements of its system
# partition), and SHA256SUMS.
#
#   make release             build/release/<tag>/ containing:
#                              jeremy.SNES-<tag>.zip
#                              jeremy.PSX-<tag>.zip
#                              gamebub-handheld-<hw>-<fw>-<tag>.uf2
#                              SHA256SUMS
#   make firmware            cargo build --release --features=rev4 (release does this)
#   make bitstreams          all five bitstreams (~50 minutes; release does NOT)
#   make HW=rev3 release     another board revision
#   make FIRMWARE=no release omit the UF2 (core zips + SHA256SUMS only, for
#                            stock firmware v1.1.0-beta.5 or later)
#
# The bitstreams under fpga/build/ must come from the same tree as the
# firmware, or the menu never appears. Free BIOS replacements are taken from
# BIOS_DIR (the system partition of an official release works). Commands run
# through `nix develop` unless espflash is already on PATH. <tag> is the
# newest release tag (0.2.0 and so on; rebases leave old tags off the current
# history, so it is not git describe): tag the commit first, or pass VER=.

HW ?= rev4
HW_REVISION := $(patsubst rev%,%,$(HW))
BUILD ?= build
RELEASE_DIR ?= $(BUILD)/release
BIOS_DIR ?= fpga/build/official-v1.0.1
FIRMWARE ?= yes

VER := $(shell git tag -l '[0-9]*' --sort=-v:refname | head -1)
FW_VER := $(shell sed -n 's/^version = "\(.*\)"/\1/p' firmware/handheld/Cargo.toml | head -1)
OUT := $(RELEASE_DIR)/$(VER)

SNES_ZIP := $(OUT)/jeremy.SNES-$(VER).zip
PSX_ZIP := $(OUT)/jeremy.PSX-$(VER).zip
UF2 := $(OUT)/gamebub-handheld-$(HW)-$(FW_VER)-$(VER).uf2
ASSETS := $(SNES_ZIP) $(PSX_ZIP) $(if $(filter no,$(FIRMWARE)),,$(UF2))

FIRMWARE_ELF := firmware/handheld/target/xtensa-esp32s3-espidf/release/handheld
BITSTREAMS := $(foreach c,boot gameboy gba,fpga/build/$(c)/$(c).bit.hs)
BIOS := $(addprefix $(BIOS_DIR)/,gameboy.bios-dmg.bin gameboy.bios-cgb.bin gba.bios.bin)

# generate_firmware_uf2.py needs espflash (nix) and ESP-IDF's fatfsgen.py,
# whose `construct` module only the ESP-IDF Python venv has.
ESP_IDF_VERSION := $(shell sed -n 's/^ESP_IDF_VERSION = "\(.*\)"/\1/p' firmware/handheld/.cargo/config.toml)
IDF_PATH ?= $(HOME)/.espressif/esp-idf/$(ESP_IDF_VERSION)
IDF_PYENV ?= $(firstword $(wildcard $(HOME)/.espressif/python_env/idf$(patsubst v%,%,$(basename $(ESP_IDF_VERSION)))_*))

ifeq ($(shell command -v espflash 2>/dev/null),)
RUN := nix develop $(CURDIR) -c
endif

.PHONY: release firmware bitstreams clean $(OUT)/SHA256SUMS

release: $(OUT)/SHA256SUMS
	@echo "RELEASE ASSETS in $(OUT):"
	@ls -lh $(ASSETS) $(OUT)/SHA256SUMS

firmware:
	cd firmware/handheld && $(RUN) cargo build --release --features=$(HW)

bitstreams:
	$(MAKE) -C fpga TARGET=gamebub_$(HW) all

$(UF2): firmware $(BITSTREAMS) $(BIOS) firmware/handheld/generate_firmware_uf2.py
	@test -n "$(VER)" || { echo "VER is empty: tag a release or pass VER=..." >&2; exit 1; }
	rm -rf $(OUT)/.system_data && mkdir -p $(OUT)/.system_data
	cp $(BITSTREAMS) $(BIOS) $(OUT)/.system_data/
	cd firmware/handheld && IDF_PATH=$(IDF_PATH) $(RUN) sh -c 'PATH=$(IDF_PYENV)/bin:$$PATH \
	    python3 generate_firmware_uf2.py --firmware target/xtensa-esp32s3-espidf/release/handheld \
	    --system-data $(abspath $(OUT)/.system_data) --hw-revision $(HW_REVISION) --output $(abspath $@)'
	rm -rf $(OUT)/.system_data

$(SNES_ZIP): fpga/build/snes/snes.bit $(wildcard fpga/cores/snes/*) fpga/scripts/package_core.py
	@test -n "$(VER)" || { echo "VER is empty: tag a release or pass VER=..." >&2; exit 1; }
	rm -rf $(OUT)/.stage-snes $@ && mkdir -p $(OUT)/.stage-snes
	python3 fpga/scripts/package_core.py --name snes --build-root fpga/build/snes \
	    --out $(OUT)/.stage-snes --flat --version $(VER)
	cd $(OUT)/.stage-snes && zip -qr $(abspath $@) jeremy.SNES
	rm -rf $(OUT)/.stage-snes

$(PSX_ZIP): fpga/build/psx/psx.bit $(wildcard fpga/cores/psx/*) fpga/scripts/package_core.py
	@test -n "$(VER)" || { echo "VER is empty: tag a release or pass VER=..." >&2; exit 1; }
	rm -rf $(OUT)/.stage-psx $@ && mkdir -p $(OUT)/.stage-psx
	python3 fpga/scripts/package_core.py --name psx --build-root fpga/build/psx \
	    --out $(OUT)/.stage-psx --flat --version $(VER)
	cd $(OUT)/.stage-psx && zip -qr $(abspath $@) jeremy.PSX
	rm -rf $(OUT)/.stage-psx

$(OUT)/SHA256SUMS: $(ASSETS)
	cd $(OUT) && sha256sum $(notdir $(ASSETS)) > SHA256SUMS

$(BITSTREAMS) fpga/build/snes/snes.bit fpga/build/psx/psx.bit:
	@echo "$@ is missing: run 'make bitstreams' (or 'make -C fpga <core>') first" >&2; exit 1

$(BIOS):
	@echo "$@ is missing: set BIOS_DIR to a directory with free BIOS replacements" >&2; exit 1

clean:
	rm -rf $(RELEASE_DIR)
