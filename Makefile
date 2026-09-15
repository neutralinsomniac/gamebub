# Release package: the rev4 UF2 (firmware plus the built-in boot/gameboy/gba
# bitstreams and free BIOS replacements of its system partition) and the
# SNES external core package for the SD card, zipped with a README and
# checksums.
#
#   make release             build/release/gamebub-handheld-<ver>-snes-<hash>.zip
#   make firmware            cargo build --release --features=rev4 (release does this)
#   make bitstreams          all four bitstreams (~40 minutes; release does NOT)
#   make HW=rev3 release     another board revision
#
# The bitstreams under fpga/build/ must come from the same tree as the
# firmware, or the menu never appears. Free BIOS replacements are taken from
# BIOS_DIR (the system partition of an official release works). Commands run
# through `nix develop` unless espflash is already on PATH.

HW ?= rev4
HW_REVISION := $(patsubst rev%,%,$(HW))
BUILD ?= build
RELEASE_DIR ?= $(BUILD)/release
BIOS_DIR ?= fpga/build/official-v1.0.1

VER := $(shell sed -n 's/^version = "\(.*\)"/\1/p' firmware/handheld/Cargo.toml | head -1)
HASH := $(shell git describe --always --dirty --exclude '*')
NAME := gamebub-handheld-$(VER)-snes-$(HASH)
OUT := $(RELEASE_DIR)/$(NAME)

FIRMWARE_ELF := firmware/handheld/target/xtensa-esp32s3-espidf/release/handheld
UF2 := $(OUT)/gamebub-handheld-$(HW)-$(VER).uf2
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

.PHONY: release firmware bitstreams clean

release: $(OUT)/README.md $(OUT)/SHA256SUMS
	cd $(RELEASE_DIR) && rm -f $(NAME).zip && zip -qr $(NAME).zip $(NAME)
	@echo "RELEASE: $(RELEASE_DIR)/$(NAME).zip"

firmware:
	cd firmware/handheld && $(RUN) cargo build --release --features=$(HW)

bitstreams:
	$(MAKE) -C fpga TARGET=gamebub_$(HW) all

$(UF2): firmware $(BITSTREAMS) $(BIOS) firmware/handheld/generate_firmware_uf2.py
	rm -rf $(OUT)/system_data && mkdir -p $(OUT)/system_data
	cp $(BITSTREAMS) $(BIOS) $(OUT)/system_data/
	cd firmware/handheld && IDF_PATH=$(IDF_PATH) $(RUN) sh -c 'PATH=$(IDF_PYENV)/bin:$$PATH \
	    python3 generate_firmware_uf2.py --firmware target/xtensa-esp32s3-espidf/release/handheld \
	    --system-data ../../$(OUT)/system_data --hw-revision $(HW_REVISION) --output ../../$@'
	rm -rf $(OUT)/system_data

$(OUT)/sdcard: fpga/build/snes/snes.bit $(wildcard fpga/cores/snes/*)
	rm -rf $@
	cd fpga && python3 scripts/package_core.py --name snes --build-root build/snes --out ../$@

$(OUT)/README.md: release/README.template.md
	mkdir -p $(OUT)
	sed -e 's/{VER}/$(VER)/g' -e 's/{HASH}/$(HASH)/g' $< > $@

$(OUT)/SHA256SUMS: $(UF2) $(OUT)/sdcard
	cd $(OUT) && find . -type f ! -name SHA256SUMS ! -name README.md | sort | xargs sha256sum > SHA256SUMS

$(BITSTREAMS) fpga/build/snes/snes.bit:
	@echo "$@ is missing: run 'make bitstreams' (or 'make -C fpga <core>') first" >&2; exit 1

$(BIOS):
	@echo "$@ is missing: set BIOS_DIR to a directory with free BIOS replacements" >&2; exit 1

clean:
	rm -rf $(RELEASE_DIR)
