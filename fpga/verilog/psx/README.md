# PlayStation core (vendored from MiSTer)

This directory contains the PlayStation emulation core from
[MiSTer-devel/PSX_MiSTer](https://github.com/MiSTer-devel/PSX_MiSTer)
(by Robert Peip), vendored at commit `cd17b5a0ad261005c321d9d5ba41b8dbe313a7d3`
(2026-08-21). It is licensed under the GPLv2 (see `LICENSE`), separately from
the rest of `fpga/`, which is CERN-OHL-S.

Only the emulation core itself (`rtl/*.vhd`) is included, plus a wrapper
(`gamebub/psx_gamebub.vhd`, Game Bub code) that fixes the options the port
does not offer to constants, so that synthesis removes the logic behind
them. The MiSTer framework (`sys/`), the MiSTer top level (`PSX.sv`), its
SDRAM / DDR3 controllers (`rtl/sdram.sv`, `rtl/ddram.sv`), the HPS
interface, the PLLs and the save-state OSD front end (`rtl/savestate_ui.sv`)
are not used; their roles are played by the Chisel glue in
`fpga/src/main/scala/net/gamebub/core/psx/`.

All files are compiled into the VHDL library `mem`: upstream refers to its
RAM wrappers both as `mem.dpram` and as `work.dpram`, which then name the
same entity.

## Local modifications

Changes relative to upstream are portability fixes so that the core
synthesizes with Vivado, and changes that make it fit the XC7A100T.

| File | Change |
| --- | --- |
| `rtl/dpram.vhd` | Rewritten: `dpram` and `dpram_dif` as inferrable block RAM (mixed-width ports in Altera's word order, clock enables gating the inputs, same-port write-first) instead of `altsyncram`. The RAM is read-first with a per-port bypass for the written word: in write-first mode a Xilinx block RAM returns garbage on the other port when it reads the address being written, whereas the Cyclone V returns the old data. |
| `rtl/RamMLAB.vhd` | Rewritten: asynchronous-read distributed RAM instead of `altdpram`. |
| `rtl/spu_ram.vhd` | `altera_mf` library clause removed (nothing in the file uses it). The reverb area (the top 64 KiB of SPU RAM) stays in block RAM as upstream has it: moved to the SRAM, its accesses took the SPU over its 768 cycles per sample (the core flagged its SPU timeout, and Final Fantasy IX hung before its intro movie). |
| `rtl/psx_top.vhd`, `rtl/psx_mister.vhd` | `video_vDisplayRange` and `video_hDisplayRange` passed out from the GPU. The save-state machine's busy flag passed out (`savestate_busy_out`, `savestate_busy` in `psx_mister`), so that the glue knows when a save has finished. The SPU's `sound_out_valid` passed out. Debug outputs: `debug_errors` (every error code seen since reset, bit n for code n, the codes MiSTer shows on screen), `debug_pc` (the CPU's fetch address), `debug_mdec` and `debug_dma` (below) and `debug_flags` (the CPU's pause, the DMA requests). |
| `rtl/spu.vhd` | Output `sound_out_valid`: pulses with each new sample on `sound_out_left` / `sound_out_right`. The SPU finishes a sample anywhere in its 768-cycle period (and, when it has fallen behind, two in a row), so the glue queues samples on it for the DAC, which takes exactly one per period, instead of reading the outputs at a fixed time. |
| `rtl/dma.vhd` | Debug output `debug_state`: the controller's state, active channel, blocks and words left. |
| `rtl/mdec.vhd` | Debug output `debug_status`: MDECSTAT. |
| `rtl/cpu.vhd` | The data cache (`datacache`) is not instantiated (no hits, no data): it only serves the turbo modes, which the port does not offer, and its read data shares a multiplexer with the scratchpad's, so synthesis keeps all 19 of its block RAMs even with the modes off. |
| `rtl/datacache.vhd` | Removed (see `cpu.vhd`). |
| `rtl/gpu.vhd` | `video_fbmode` and `video_fb24` carry GPUSTAT's display-disable and 24-bit color bits (upstream: the 480p hack's framebuffer mode, unused here), and the new `video_vDisplayRange` / `video_hDisplayRange` outputs carry GP1(07h) / GP1(06h), the display ranges, for the Game Bub scanout. |
| `rtl/gpu_poly.vhd` | The span skip test `to_integer(xStart(43 downto 32)) > drawingAreaRight` compares as signed: numeric_std's `">"(NATURAL, UNSIGNED)` with a negative left side is out of range, and Vivado converts it to unsigned (a large number), so every span starting left of x = 0 was skipped (black, jagged left edges in Final Fantasy IX's battles); Quartus, evidently, does not. The triangle's share of `targetTiming` (the drawing time the GPU waits out at the end of a polygon) is added two cycles after `CALCBOUNDARY1` through registers, instead of in that cycle: the multiply and the 32-bit add after the vertex comparisons are about 21 ns at clk2x, 6.4 ns over. `targetTiming` is only read when the triangle has been drawn. |
| `rtl/SyncRamDualByteEnable.vhd` | Removed (unused, Altera-specific). |

Everything else is byte-for-byte upstream. When updating the vendored copy,
re-apply the table above.
