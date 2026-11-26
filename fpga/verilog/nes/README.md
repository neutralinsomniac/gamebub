# NES core (vendored from MiSTer)

Vendored from [MiSTer-devel/NES_MiSTer](https://github.com/MiSTer-devel/NES_MiSTer) (originally by Ludvig Strigeus, MiSTer port by Sorgelig / Kitrinx / Robert Peip et al.) at commit `cf7fe6c394b9568896b903c62b38aa4ee58491f2` (2026-11-16). Licensed under GPLv3 (see `LICENSE`), separately from the CERN-OHL-S license on the rest of `fpga/`.

Only the emulation RTL (`rtl/`) is vendored here, along with `gamebub/nes_gamebub.sv` (Game Bub wrapper instantiating `NES`, `video`, `GameLoader`, the 2 KiB EEPROM BRAM, the 4 KiB Loopy NSF player ROM, the `SS_Ext` savestate register, and the 4-player joypad shift registers). The MiSTer framework (`sys/`), top level (`NES.sv`), Altera PLLs (`rtl/pll*`), MiSTer SDRAM/DDR controllers (`rtl/sdram.sv`, `rtl/ddram.sv`), OSD save-state UI (`rtl/savestate_ui.sv`), composite decoder (`rtl/composite_decoder.sv`, `rtl/composite_crop.sv`), Zapper/keyboard/Miracle Piano peripherals, and unused `compat.v`/`dsp.v` are excluded; Chisel glue in `fpga/src/main/scala/net/gamebub/core/nes/HandheldNes.scala` and `fpga/src/main/scala/nes/` replaces them.

## Local modifications

Changes relative to upstream are Vivado portability fixes and a few glue exports:

| File | Change |
| --- | --- |
| `rtl/dpram.vhd` | Rewritten as inferrable Vivado block RAM (`spram`, `spram_sz`, `dpram`) replacing `altsyncram`, using read-first storage with per-port write-first bypass (matching Cyclone V M10K collision semantics). |
| `rtl/nsf_rom.v` | Generated Verilog ROM from `rtl/loopy_NSF.mif` (4 KiB Loopy NSF player ROM). |
| `rtl/video.sv` | Replaced `spram` `.mif` instance for the Tao palette with an inline 64-entry `pal_tao_lut` (from `rtl/tao.mif`), and changed `always_comb`-driven `wire`s to `logic`. |
| `rtl/iir_filter.v` | Vendored from upstream `sys/iir_filter.v` (used by `fds_mixed` in `FDS.sv`), hoisted forward-declared `tap0`/`tap1`/`tap2`/`ch`/`inp` signals above their port/expression uses, and converted `intreg[2]` to Verilog `intreg[0:1]`. |
| `rtl/nes.v` | Added `import regs_savestates::*;`, fixed `T65` VHDL port-name casing (`.Mode`, `.Res_n`, `.Pwr_n`, `.Clk`, `.Enable`, `.Rdy`, `.Abort_n(1'b1)`, `.SO_n(1'b1)`, 24-bit `.A`), hoisted forward-declared save-state and `apu_cs` wires above their submodule port uses (preventing Vivado 1-bit implicit wire truncation), and exported `ppu_ce_out`, `cart_ce_out`, `cpu_ce_out`, and `savestate_busy_out` for the Game Bub glue. |
| `rtl/ppu.sv`, `rtl/cart.sv`, `rtl/mappers/{Sunsoft,VRC}.sv` | Hoisted forward-declared multi-bit wires (`ppu_dbus`, `vram_din`, `oam_bus`, `color0`, expansion audio/data buses, `SaveStateBus_wired_or`, `me`, `exp_out`, `vrc6sq*`/`vrc6saw_out`) above their submodule port uses so Vivado does not implicitly declare them as 1-bit wires. |
| `rtl/SOUND/OPLL/VM2413/{controller,voicerom,voicememory}.vhd` | Changed `array (<scalar_subtype>'range)` to `array (<scalar_subtype>)` (VHDL `'range` is only valid on array types in Vivado). |
| `rtl/apu.sv`, `rtl/EEPROM_24C0x.sv`, `rtl/mappers/*.sv` | Added explicit `import regs_savestates::*;` at the top of each compilation unit (Quartus shares package imports across files; Vivado compiles each SV file independently). |
| `rtl/mappers/FDS.sv` | Hoisted `wire [11:0] audio_exp;` declaration above its port connection (preventing Vivado 1-bit implicit wire truncation), and changed `always_comb`-driven `level_out` from `wire` to `logic`. |
| `rtl/cheatcodes.sv`, `rtl/cart.sv`, `rtl/ppu.sv`, `rtl/mappers/{JYCompany,MMC5,Namco,Sachen,Sunsoft,VRC,misc}.sv` | Changed `wire` / `output` nets assigned inside `always_comb` / `always @*` blocks to `logic` (required by Vivado), and added `default: prg_bank = 8'd0;` in `misc.sv` Mapper162 to eliminate an inferred latch on unreachable cases. |

All other files are unmodified upstream copies. Re-apply the changes above when updating the vendored core.
