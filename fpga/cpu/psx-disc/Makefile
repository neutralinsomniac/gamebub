# The PSX disc reader (see README.md). `make` builds it and updates the
# image the glue embeds, ../../src/main/resources/psx_disc.bin (committed,
# so that a bitstream build needs no RISC-V toolchain). `make test` runs
# the image logic's tests on the host. Commands run through `nix develop`
# unless cargo is already on PATH.

ELF := target/riscv32imc-unknown-none-elf/release/psx-disc
BIN := ../../src/main/resources/psx_disc.bin

ifeq ($(shell command -v cargo 2>/dev/null),)
RUN := nix develop ../../..\# -c
endif

.PHONY: all test $(ELF)

all: $(BIN)

$(ELF):
	$(RUN) cargo build --release

$(BIN): $(ELF) elf2bin.py
	python3 elf2bin.py $(ELF) $@

test:
	$(RUN) cargo test --lib --target x86_64-unknown-linux-gnu
