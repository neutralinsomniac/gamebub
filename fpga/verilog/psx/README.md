# PlayStation core (vendored from MiSTer)

Vendored from [MiSTer-devel/PSX_MiSTer](https://github.com/MiSTer-devel/PSX_MiSTer) (by Robert Peip) at commit `cd17b5a0ad261005c321d9d5ba41b8dbe313a7d3` (2026-08-21). Licensed under GPLv2 (see `LICENSE`), separately from the CERN-OHL-S license on the rest of `fpga/`.

Only the emulation RTL (`rtl/*.vhd`) is vendored here, along with `gamebub/psx_gamebub.vhd` (Game Bub wrapper that ties unused options to constants so synthesis prunes their logic). The MiSTer framework (`sys/`), top level (`PSX.sv`), SDRAM/DDR3 controllers (`rtl/sdram.sv`, `rtl/ddram.sv`), HPS interface, PLLs, and OSD save-state UI (`rtl/savestate_ui.sv`) are excluded; Chisel glue in `fpga/src/main/scala/net/gamebub/core/psx/` replaces them.

All files are compiled into VHDL library `mem` because upstream references its RAM wrappers as both `mem.dpram` and `work.dpram`.

## Local modifications

Changes relative to upstream are Vivado portability fixes, XC7A100T resource reductions, and a few glue/debug ports:

| File | Change |
| --- | --- |
| `rtl/dpram.vhd` | Rewritten as inferrable block RAM (`dpram` and `dpram_dif` with Altera mixed-width word ordering, clock enables, and same-port write-first) replacing `altsyncram`. Uses read-first mode with a per-port write bypass because Xilinx BRAM in write-first mode returns undefined data on the opposite port during same-address conflicts, whereas Cyclone V M10K returns the old value. |
| `rtl/RamMLAB.vhd` | Rewritten as async-read distributed RAM replacing `altdpram`. |
| `rtl/spu_ram.vhd` | Removed unused `altera_mf` library clause. The top 64 KiB reverb buffer remains in block RAM as upstream; moving it to external SRAM pushed the SPU past its 768-cycle sample budget and caused Final Fantasy IX to hang before its intro movie. |
| `rtl/psx_top.vhd`, `rtl/psx_mister.vhd` | Exposed `video_vDisplayRange`, `video_hDisplayRange`, `savestate_busy` / `savestate_busy_out`, `validSStates_set`, `sound_out_valid`, and debug ports (`debug_errors`, `debug_pc`, `debug_mdec`, `debug_dma`, `debug_flags`). |
| `rtl/savestates.vhd` | Added `validSStates_set` input to mark valid slots when the host loads a new disc's save-state files without resetting the core. |
| `rtl/spu.vhd` | Added `sound_out_valid` strobe pulsing with each new audio sample. Because the SPU can finish a sample at any point in its 768-cycle period (or output two back-to-back when catching up), the glue queues samples on this strobe instead of polling at a fixed interval. |
| `rtl/joypad_pad.vhd` | Sample `ToggleDS` even when `ce` is low so a single-cycle toggle pulse from the glue is not lost when the core pauses waiting on a CD sector. |
| `rtl/dma.vhd` | Added `debug_state` output (FSM state, active channel, remaining blocks and words). |
| `rtl/mdec.vhd` | Added `debug_status` output (`MDECSTAT`). |
| `rtl/cpu.vhd` | Removed `datacache` instantiation (tied off to miss). It is only used by turbo modes that this port omits, and its mux shared with scratchpad prevented synthesis from pruning its 19 block RAMs. |
| `rtl/datacache.vhd` | Removed (see `cpu.vhd`). |
| `rtl/gpu.vhd` | Repurposed `video_fbmode` and `video_fb24` (unused 480p hack outputs) to carry GPUSTAT display-disable and 24-bit color flags, and added `video_vDisplayRange` / `video_hDisplayRange` (`GP1(07h)` / `GP1(06h)`) for Game Bub scanout. |
| `rtl/gpu_poly.vhd` | Fixed `to_integer(xStart(43 downto 32)) > drawingAreaRight` to use a signed comparison: `numeric_std` `>` on `(NATURAL, UNSIGNED)` fails on negative `xStart` in Vivado (converting it to a large unsigned value and dropping spans starting left of x = 0, causing jagged black left edges in Final Fantasy IX battles). Also pipelined the `targetTiming` polygon-area addition by two cycles after `CALCBOUNDARY1` to fix a 6.4 ns timing violation at `clk2x` (`targetTiming` is only read after drawing finishes). |
| `rtl/SyncRamDualByteEnable.vhd` | Removed (unused, Altera-specific). |

All other files are unmodified upstream copies. Re-apply the changes above when updating the vendored core.
