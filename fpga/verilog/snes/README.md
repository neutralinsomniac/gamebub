# SNES core (vendored from MiSTer)

This directory contains the SNES emulation core from
[MiSTer-devel/SNES_MiSTer](https://github.com/MiSTer-devel/SNES_MiSTer)
(originally by srg320), vendored at commit `ef6aec399af9fec5f228244c16aadc6a63e7ea16`
(2026-08-25). It is licensed under the GPLv3 (see `LICENSE`), separately from
the rest of `fpga/`, which is CERN-OHL-S.

Only the emulation core itself (`rtl/`) is included. The MiSTer framework
(`sys/`), the MiSTer top level (`SNES.sv`), and the MiSTer-specific SDRAM /
DDR controllers, HPS interface, UART/SNI, light gun, mouse, and PLL files are
not used; their roles are played by the Chisel glue in
`fpga/src/main/scala/net/gamebub/core/snes/HandheldSnes.scala`.

## Local modifications

Changes relative to upstream are of two kinds: portability fixes so that the
core synthesizes with Vivado (and analyzes under GHDL), and a few outputs added
to the GSU and SA-1 that tell the Game Bub glue when a coprocessor consumes
memory data.

| File | Change |
| --- | --- |
| `rtl/bram.vhd` | Rewritten: `spram`, `spram_sz`, `dpram`, `dpram_dif`, `dpram_difclk` as inferrable RAM (incl. mixed-width dual port) instead of `altsyncram`. The dual-port RAM is read-first with a per-port write-first bypass: Xilinx block RAM returns garbage on the reading port when the other port writes the same address in write-first mode, whereas Altera M10K returns old data (the DSP's `BRR_BUF`/`REGRAM` hit this). |
| `rtl/mlab.vhd` | Rewritten: async-read distributed RAM instead of `altdpram`. |
| `rtl/chip/CX4/cx4cache.vhd` | Rewritten on top of `mlab`. |
| `rtl/chip/SA1/SA1MULT.vhd` | `lpm_mult` replaced with `signed * signed`. |
| `rtl/chip/SPC7110/SPC7110_MULDIV.vhd` | `lpm_mult` / `lpm_divide` replaced with generic arithmetic (combinational multipliers; the dividers keep the upstream 8-cycle latency). |
| `rtl/chip/SPC7110/SPC7110_FIFO.vhd` | `scfifo` replaced with a generic show-ahead FIFO. |
| `rtl/chip/DSP/DSPn.vhd`, `rtl/chip/CX4/CX4.vhd` | ROM instances point at `dspn_prog_rom` / `dspn_data_rom` / `cx4_data_rom` (see below) instead of `.mif`-initialized `spram`. |
| `rtl/chip/DSP/DSPn.vhd` | `DATA_RAM_ADDR_A or x"40"` given matching operand widths (Vivado rejects the mismatch). |
| `rtl/chip/CX4/CX4Map.vhd` | `CART_ADDR and ROM_MASK(22 downto 0)` given matching operand widths (22-bit `CART_ADDR` zero-extended; Vivado rejects the mismatch). |
| `rtl/main.v` | Every mapper instance gets an explicit `.ENABLE(1'b1)` (upstream leaves the port open and relies on its VHDL default `'1'`; Vivado warns about the open port). |
| `rtl/chip/GSU/GSU.vhd` | Processes with both a `falling_edge(CLK)` and a `rising_edge(CLK)` branch split into two single-edge processes (`tools/split_dual_edge.py`; Vivado: "else clause after check for clock not supported"). |
| `rtl/chip/GSU/GSU.vhd`, `rtl/chip/GSU/GSUMap.vhd`, `rtl/main.v` | New outputs (`GSU_*` on `main`): `ROM_SAMPLE` / `RAM_SAMPLE`, the cycle in which the GSU latches `ROM_DI` / `RAM_DI`, so that the glue can stall the core only then; `RAM_OWNED` (`GSU_RAM_ACCESS`), the GSU owns the RAM bus; `RAM_RD_N`, low only in the states that consume `RAM_DI`, which `GSUMap` uses as `BSRAM_OE_N` instead of `not RAM_WE_N` so that the glue does not issue the speculative reads the original strobe implied (CE with WE high before every store byte, and in the pixel cache write phase). |
| `rtl/chip/SA1/SA1.vhd`, `rtl/chip/SA1/SA1Map.vhd`, `rtl/main.v` | New outputs (`SA1_*` on `main`): `ROM_SAMPLE` (`EN and not SNES_ROM_SEL`), the cycles in which the SA-1 side (65C816, DMA, VBP) consumes `ROM_DI`; `BWRAM_SAMPLE` (`EN and not SNES_BWRAM_SEL`), likewise for `BWRAM_DI`; `BWRAM_OWNED` (`not SNES_BWRAM_SEL`), the SA-1 side owns the BW-RAM bus. |
| `rtl/main.v` | Port connections to VHDL entities rewritten to the exact VHDL spelling (`tools/fix_port_case.py`); Vivado binds Verilog-to-VHDL ports case-sensitively and would leave every lowercase `.mclk(...)` unconnected. `SNI_JOY` declared `output` instead of `output reg` (driven by an instance; the file must be compiled as Verilog-2001 because it connects VHDL ports named `do`). |
| `rtl/main.v` | New output `SS_BUSY` (the save-state program is running), so that the glue can report it to the firmware. |
| `rtl/main.v` | The `SS_*` wire declarations moved above the coprocessor `generate` blocks. Upstream connects `SS_GSU_DI`, `SS_DSPN_*`, `SS_CX4_*` inside those blocks before declaring them; Vivado then creates implicit 1-bit nets in the generate scope and leaves the real wires undriven, so coprocessor save states silently save zeros. |
| `rtl/savestates.sv` | `rom_addr` declared `output reg` (it is assigned in an `always` block; Vivado rejects the original `output`). |
| `rtl/cheatcodes.sv` | `wire`s assigned in `always_comb` declared as `logic` (Vivado rejects the original). |
| `rtl/chip/MSU1/` | Removed (needs streaming storage that Game Bub does not have). |

Everything else is byte-for-byte upstream. When updating the vendored copy,
re-apply the table above.

## Save states

`savestates/savestates.bin` is upstream's `releases/boot1.rom`, the 65816
program that `rtl/savestates.sv` runs on the S-CPU to dump and restore the
machine state (source: `src/savestates*.asm` upstream, bass syntax). The
firmware embeds it and writes it to SDRAM at byte address `0xFF0000`, where
the core fetches it; see `docs/snes.md`.

## Coprocessor ROMs

The DSP-1/1B/2/3/4, ST010 and CX4 coprocessors contain firmware ROMs that
are not redistributable and therefore not checked in. `tools/gen_roms.py`
builds `rtl/generated/coprocessor_roms.vhd` from images placed in `roms/`
(git-ignored); without them the ROMs are zero-filled and games using those
chips will not run, everything else is unaffected. `scripts/build_core.py`
runs the generator automatically. See the script's docstring for accepted
file names.

## Integration notes

* `main.v` is the top of the vendored core. Its `USE_*` parameters select
  which coprocessors are synthesized; they are set from
  `snes.SnesCoreConfig` in Chisel (DSP-n, S-DD1, CX4, Super FX and SA-1 on;
  SPC7110, BS-X and Sufami Turbo off).
* The core is clocked at the SNES master clock (21.477 MHz). External memory
  (ROM in SDRAM, WRAM/BSRAM in SRAM) is slower than the single-cycle memory
  the core expects, so the core clock is gated (`BUFGCE`) while an access is
  outstanding.
* VRAM and ARAM live in block RAM; BSRAM (cartridge save RAM) and WRAM live
  in the external 512 KiB SRAM so the MCU can load/save them.
