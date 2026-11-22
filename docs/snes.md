# SNES core

Port of the MiSTer SNES core ([MiSTer-devel/SNES_MiSTer](https://github.com/MiSTer-devel/SNES_MiSTer), by srg320 et al., GPLv3), packaged as the external core `jeremy.SNES`.

* Vendored RTL: `fpga/verilog/snes/` (see its README for local patches)
* Chisel glue: `fpga/src/main/scala/net/gamebub/core/snes/HandheldSnes.scala`
* Core descriptors: `fpga/cores/snes/`

## Status

Runs on rev4 hardware with video, audio, input, battery saves, and save states. Base cartridges, DSP-n, S-DD1, CX4, Super FX, and SA-1 are synthesized (DSP-n and CX4 games require the user's coprocessor ROM images at build time).

Measured run rates (`core cycles / (core cycles + stall cycles)`, logged by the firmware when pausing a game):

| Game | Cartridge | Run rate | Worst 49 ms window |
| --- | --- | --- | --- |
| Chrono Trigger, Final Fantasy VI | Base (HiROM) | 99.99% | |
| Star Fox | Super FX | 99.7% | 1.1% (all ROM misses) |
| Yoshi's Island | Super FX 2 | 99.60% | 1.0% |
| Super Mario RPG | SA-1 | 99.80% | 2.5% |

Resource usage (Vivado 2025.1): ~45.1k LUTs (71%), 21.5k registers, and 130 of 135 block RAM tiles (96%). Timing closes at 21.477 MHz core / 42.955 MHz SDRAM (4.4 ns output setup slack at the SDRAM chip, 8.2 ns read slack with IOB capture). Block RAM is the tightest resource.

## Design

### Clocking

The MiSTer core runs on the SNES master clock (21.477 MHz, `MCLK`), using internal clock enables for the CPU, PPU, SMP, and DSP.

The framework's default 939.583 MHz MMCM cannot generate this frequency cleanly:
* The nearest integer divider gives 939.583 / 44 = 21.354 MHz (0.57% slow, which shifts audio pitch and frame rate because the DSP's `CEGen` assumes the nominal rate).
* The fractional divider (43.75) cannot be used because `PipelineMemoryBurstCdc` requires the SDRAM clock to be an integer multiple of the system clock, and only MMCM output 0 supports fractional division.
* No single MMCM multiplier (in 1/8 steps) produces 945/44 MHz alongside a 2x or 4x SDRAM clock.

Instead, `HandheldSnes` cascades a `xilinx.PLL` behind its MMCM:
* **MMCM**: 50 MHz / 1 * 23.625 = 1181.25 MHz VCO
  * `/11` (107.386 MHz) feeds the PLL
  * Display clock divided from the MMCM VCO to the lowest frequency the board's display driver accepts (26.25 MHz on rev4)
  * Host SPI clock divided from the MMCM VCO to 168.75 MHz (`/7`, the lowest rate above the framework's 160 MHz minimum, chosen to ease timing closure on the SPI request FIFO)
* **PLL**: 107.386 MHz * 12 = 1288.636 MHz VCO
  * `/60` = 21.4773 MHz system clock
  * `/30` = 42.955 MHz SDRAM controller clock
  * `/30` delayed by 14 ns = SDRAM pin clock (see below)

### SDRAM interface timing

The SDRAM runs at 2x the core clock (42.955 MHz, 23.28 ns period). Running at 4x is not viable because routed pin delays span most of an 11.64 ns period across corners, leaving no single clock phase that works for all pins. Running at 2x halves peak SDRAM bandwidth, which the ROM cache's prefetch hides except on miss latency.

* **Write/command launch**: `BurstSdramController` launches commands, addresses, and write data on the rising edge of the controller clock. The SDRAM chip receives a third PLL output delayed by 14 ns (`HandheldSnes.SdramClockPhaseNs`, forwarded via BUFG and pin), so the chip samples 4-13 ns after signals settle and 8-12 ns before the next transition across corners.
* **Read capture**: Read data is captured on the falling edge of the controller clock (`readCaptureFalling`) in IOB registers. At CAS latency 2, beats transition within the first quarter cycle after the controller's rising edge, putting the falling-edge capture within 1.5 ns of the data eye center (5-8 ns margin at the corners). The burst runs one beat past the two requested beats and holds via clock suspend; burst continuations resume from that beat.
* **Constraints**: `fpga/verilog/handheld/core_snes.xdc` (picked up automatically by `build_core.py`) specifies the pin clock and a PC133 timing budget (2 ns setup, 1 ns hold, 6 ns clock-to-data, 2.5 ns data hold). Check the `sdram_clk_pin` rows in Vivado's inter-clock timing summary after every build.
* **Host upload limit**: The firmware transfers files to SDRAM at 10 MB/s (`max_transfer_speed` in `fpga/cores/snes/files.json`). At the 2x clock, each host write takes 4 core cycles through the CDC and the controller only refreshes during 8-cycle idle gaps; faster uploads can starve refreshes and overflow the 512-word SPI request FIFO.
* **Simulation**: `BurstSdramControllerSpec` tests both the standard rising-edge timing and this falling-edge timing against a cycle-accurate JEDEC SDRAM model, including `PipelineMemoryBurstCdc` at the 2x ratio. The CDC stalls when its 4-entry request FIFO is full and drops prefetches that complete after a newer write is queued.

### SRAM interface timing

WRAM and BSRAM live in the external asynchronous SRAM (IS61WV25616BLL-10) behind `AsyncSramController` with `registeredOutputs` enabled:
1. Cycle 0: request accepted.
2. Cycle 1: address, data, byte enables, and output enable pass through a second register stage placed in the IOBs (`IOB TRUE` in `core_snes.xdc`).
3. Cycle 2: the chip sees the signals for a full 46.56 ns cycle (write pulse generated via ODDR in the second half; read data captured in ILOGIC at the end), followed by `done`.

The SRAM's own timing budget has over 15 ns of margin on every pin. The extra IOB register stage exists to eliminate pin-to-pin output skew: when those registers sit in general fabric, routing delays vary from 2 to 9 ns across builds, and even a few hundred picoseconds of skew on `sram_ub_n` caused WRAM write corruption on boot. Placing the final stage in the IOBs matches the write-enable ODDR delay while keeping the first stage close to the request logic. `core_snes.xdc` includes I/O delay budgets that fail the build if a register drops out of its IOB; check the `sram_*` I/O timing rows after builds. The extra cycle of access latency is covered by latency hiding.

### Memory map

| Memory | Location | Notes |
| --- | --- | --- |
| Cartridge ROM (<= 16 MiB) | SDRAM, `0x000000` | Raw file loaded by MCU (including any 512-byte copier header); cached via `LineBuffer` + `LineReadCache` and translated by `RomMirrorTable` |
| Save states (4 x 1 MiB) | SDRAM, `0x1000000` | Loaded/saved as `<rom>.ss`; save-state 65816 program sits at `0xFF0000` |
| BSRAM (save RAM, <= 256 KiB) | SRAM, `0x00000` | Filled with `0xFF` by `SramFill` at ROM load, then loaded/saved as `<rom>.srm`; 2-way 2 KiB word cache in bridge |
| WRAM (128 KiB) | SRAM, `0x40000` | Initialized with the MiSTer power-on pattern after `SETUP_COMPLETE` (`SramFill`) |
| VRAM (2 x 32 KiB) | Block RAM | |
| ARAM (64 KiB) | Block RAM | |
| Framebuffer | Block RAM | 256x240, 15 bpp, double buffered (framework) |

Cartridge ROM reads go through a 16-line register buffer (`LineBuffer`, single-cycle hits with background next-line prefetch) backed by a 32 KiB 2-way `LineReadCache` (16-byte lines, next-line prefetch). Cache misses pass through `RomMirrorTable`, a 512-entry distributed-RAM table of 32 KiB blocks rebuilt at the end of the ROM transfer. It adds the copier header offset and mirrors non-power-of-two ROM sizes up to the next power of two, matching cartridge address decoding. ROM sizes that are not a multiple of 32 KiB fall back to an identity mapping and are rejected.

### Core clock gating and stall reduction

On MiSTer, ROM, WRAM, and BSRAM respond within a single core cycle. On Game Bub, ROM sits behind an SDRAM arbiter, CDC, and burst controller, while WRAM and BSRAM sit in external async SRAM taking two cycles (a single-cycle SRAM read from the core is impossible because routing from the core's address registers to `sram_a` already consumes ~47 ns).

Because the MiSTer core's `ENABLE` input does not pause its internal clock dividers, `HandheldSnes` gates the core clock using a `BUFGCE`. Whenever an external access must complete before the core advances, the clock pauses so the core still observes single-cycle memory. The glue (memory bridges, video capture, counters) runs on the ungated system clock and qualifies core pulses with `tick`.

Only the S-CPU domain (CPU, PPU, coprocessors) is gated for memory stalls. The APU (SMP, DSP, and BRAM-backed ARAM) has no external memory dependencies and runs on a second `BUFGCE` (`ACLK`) that only pauses when the game is paused. As a result, memory stalls slow the game slightly without bending audio pitch, matching a real console where the APU has its own crystal. Both clocks share the same root net, so Vivado times paths between them as a single domain. Two additions to `main.v` keep the split safe:
* `MCLK_EN` (the gated clock enable) qualifies the S-CPU's one-cycle port write strobe in `SMP.vhd` so a stall on that cycle cannot duplicate a write after the SPC700 clears the port.
* `DSP_PAL` is tied low so the DSP's clock-enable generator always uses the NTSC modulus, since the APU clock is not PAL-paced.

Memory path optimizations to minimize stalls:

* **`BytePortBridge` (WRAM and BSRAM)**: Issues SRAM requests on the strobe cycle and forwards read data on the arrival cycle (two-cycle total read latency). Writes go through an in-order posted write queue (4 entries for WRAM, 8 for BSRAM to absorb Super FX pixel cache flushes) and only stall when nearly full. `pendingRead` tracks demand reads only, so bursts of stores do not stall coprocessors.
* **BSRAM cache**: 2-way 2 KiB word cache in distributed RAM (async read for same-cycle hits, register byte-valid bits for single-cycle invalidation). Write-through and byte write-allocate so decompressors hit on recently written bytes. Only prefetches the next word during sequential runs so random accesses do not double SRAM traffic.
* **Shared BSRAM/BW-RAM ports**: When two masters share a port (S-CPU and SA-1 on BW-RAM, or S-CPU and GSU on Super FX RAM), bridge read data tracks the current port address on cache hits, and stale demand reads completing after a newer hit are discarded (`BytePortBridgeSpec` tests both cases).
* **Back-to-back SRAM arbitration**: The SRAM controller and arbiter accept a new request during the previous access's `done` cycle (2 cycles per access instead of 3; an unchanged request held through `done` is ignored for one cycle). The arbiter uses round-robin fairness so S-CPU WRAM traffic cannot starve SA-1 BW-RAM reads.
* **Critical-word-first ROM misses**: A ROM miss fetches the requested word first (wrapping the rest of the 16-byte line), issues it in the cache lookup cycle, and streams words out on `fillWord*` as they arrive. `LineBuffer` completes pending misses early (`early*`) and installs the full line when the regular response finishes, cutting random miss latency from ~12 cycles to ~5 cycles. Config bits 11 and 12 disable these optimizations for A/B testing.

#### Stall policies

Stall behavior is selected per cartridge via config register bits. By default, bit 2 is set at reset, which tells the glue to automatically select the safe mode for the cartridge's `ROM_TYPE`. Individual mode bits can also be written directly over SPI for testing.

* **Conservative** (all mode bits clear): Stalls whenever any ROM or BSRAM read is in flight. Safe for all coprocessors, at the cost of extra stall cycles on BSRAM reads and line-buffer misses. Used for CX4.
* **Base latency hiding** (bit 2): Only stalls if a read is still pending when the S-CPU latches data (`SYSCLKF_CE`). Used for base cartridges, DSP-n, and S-DD1. For S-DD1, the glue also stalls on `SDD1_ROM_SAMPLE` (added to `InputMgr.vhd`), which marks the cycle two clocks after the decompressor strobes a ROM read; without it, ROM misses return stale data and corrupt decompressed graphics. For Super FX, SA-1, and CX4 cartridges, the glue automatically remaps bit 2 to the modes below (bit 7, bits 9+10, or conservative).
* **Super FX RAM latency hiding** (bit 7): BSRAM reads stall at `SYSCLKF_CE` or at the GSU's sampling cycle. `GSU.vhd` exports three signals for this: `RAM_SAMPLE` (the cycle `RAM_ACCESS_CNT` hits zero during a fetch), `RAM_OWNED` (`GSU_RAM_ACCESS`, suppressing S-CPU `SYSCLKF_CE` stalls while the GSU owns RAM), and `RAM_RD_N` (wired to `BSRAM_OE_N` in `GSUMap` to eliminate spurious reads during write setup and pixel-cache write phases, which previously generated 10.5M reads for 4.6M writes in Star Fox). GSU ROM reads remain conservative because the GSU strobes `ROM_RD_N` every other cycle and relies on fixed latency, allowing a newer strobe to supersede a miss before sampling (bit 8 enables experimental GSU ROM hiding, which currently black-screens Star Fox).
* **SA-1 ROM latency hiding** (bit 9): ROM reads stall at `SYSCLKF_CE` or on `ROM_SAMPLE` (`EN and not SNES_ROM_SEL`). Because the SA-1 updates its ROM address on `CLK_CE` and consumes data one cycle later, requests are never superseded before sampling. `LineBuffer` issues misses on the request cycle and uses 16 lines so the SA-1's instruction and data streams do not thrash it (a 4-line buffer dropped Super Mario RPG to 87%).
* **SA-1 BW-RAM latency hiding** (bit 10): BSRAM reads stall at `SYSCLKF_CE` while `BWRAM_OWNED` (`SNES_BWRAM_SEL`) is high, or at `BWRAM_SAMPLE` (`EN and not SNES_BWRAM_SEL`). Because the SA-1 samples one cycle after asserting its address while SRAM reads take two cycles, cache hit rate is what controls performance. The 2-way 2 KiB cache achieves an 87.9% hit rate in Super Mario RPG (compared to 80% for a 16-word buffer and 89% for a 4 KiB direct-mapped cache that cost 5k more LUTs); larger caches miss timing on the strobe-cycle issue path unless byte-valid bits move into distributed RAM. Remaining misses are capacity misses. On Yoshi's Island, the GSU samples two cycles after its strobe, so an idle SRAM finishes in time and its 2.2M misses (61% hit rate) average 0.3 stall cycles each.

WRAM reads always use latency hiding (only the S-CPU and its DMA access WRAM, latching on `SYSCLKF_CE`). A nearly full SRAM write queue stalls the core in all modes.

#### Statistics

`HandheldSnes` tracks run and stall cycles in host registers `0x1000-0x1078` (write to clear; see the `HandheldSnes.scala` header comments for the full map). Counters break down stalls by port (ROM, WRAM, BSRAM, write queue full), BSRAM bridge state (write, prefetch, demand read, idle), and S-CPU vs. coprocessor sampling. It also tracks the worst 2^20-cycle (49 ms) window since the last clear to catch brief spikes (such as area transitions or menus) that totals average out.

Whenever the core loses focus (opening the pause menu), `CoreManager::log_statistics` reads, clears, and logs the standard run/stall counters (`0x1000`, `0x1004`) plus the entries listed under `statistics` in `fpga/cores/snes/core.json`:

```text
Run rate: 99.80% (1051797216 run, 2118143 stalled, over 49.0 s)
  ROM stalls: 1983204 (0.19%)
  ...
  worst 49 ms: 26214 (2.50%)
```

Counters are 32-bit, so the cycle counter wraps after ~200 seconds at 21.477 MHz. Pause within three minutes to get valid numbers; compare the total cycles against the logged duration to verify no wrap occurred.

#### Implementation notes and pitfalls

* **Early ROM fill vs. line installation**: Because early answers let the core advance before the full line finishes filling, a new request arriving on the cycle the cache delivers the line must resolve as a hit, and a new request supersedes any older miss. `LineBuffer` never asserts `hit` and `respValid` simultaneously, and the glue clears `romPending` on either.
* **Simulation clock alignment**: `SnesRomPathSpec` derives both the 1x and 2x clocks from a single counter so coincident edges happen in the same simulation delta. Generating the slow clock from a fast-domain flip-flop introduces a one-delta lag that makes the CDC behave differently from hardware.
* **Verilog generate blocks in `main.v`**: A missing `begin`/`end` on a generate `else` silently tied the SA-1 strobe to ground, leaving only a Vivado `multi-driven net ... constant driver preserved` critical warning in `runme.log`. Check `runme.log` for that warning after editing `main.v`.
* **Reset clock domain**: Generating `RESET_N` in the ungated system clock domain and releasing it on `HVCNT_ATZERO` caused Vivado to optimize away every coprocessor (their resets are `RESET_N` gated by mapper select) while keeping the base SNES intact. `HandheldSnes` generates `RESET_N` in the core's gated clock domain. Verify that `GSUMap`, `SA1Map`, etc. remain in the utilization hierarchy after reset changes.
* **Dual-edge VHDL**: Vivado rejects the GSU's dual-edge processes; `fpga/verilog/snes/tools/split_dual_edge.py` splits them into single-edge processes.

### Video and audio

#### Video capture and timing

The PPU outputs one pixel per rising edge of `DOTCLK`. The glue latches RGB and blanking on each edge, counts pixels per line, and drops odd pixels in 512-pixel hi-res modes.

The framebuffer is 256x240:
* Standard 224-line mode writes to rows 8-231.
* 239-line overscan mode (`$2133` bit 2) writes to rows 0-238.
* During vertical blank, the glue writes black padding rows so switching modes leaves no stale pixels (`HandheldSnes` generates its own `hblank`/`vblank` for the framework row counter).
* Interlaced modes display one frame per field (bob deinterlacing at half vertical height).

PAL games run at the PAL master clock rate (21.281 MHz, 312 lines, 50.0 Hz) by keeping the physical clock at 21.477 MHz and withholding ~1 in 110 clock-enable pulses via a phase accumulator. The rev4 ILI9806E display driver can stretch its vertical front porch by up to 31% to follow 50 Hz (rev1-3 drivers cannot and refresh unsynchronized). PAL on the LCD is currently untested; frame-rate conversion is the fallback if the panel rejects 50 Hz.

#### Scaling and CRT filter

`SnesVideoFilter` implements `VideoScalerV0` (driving per-pixel framebuffer coordinates directly) alongside the shared `lib.video.Crt` filter (linear-light scanlines with brightness-dependent beam width, aperture-grille/shadow mask, and horizontal blur). It replaces the framework's color correction lookup table, which is unused by external cores.

Aspect Ratio (register `0x002C`, default `4:3`) and output dimensions for 224-line / 239-line modes:

* **4:3**: Full screen height. 224 lines scale to 640x480 on the LCD and 720x480 on HDMI (4:3 on a TV). 239-line overscan scales 2x vertically (640x478 LCD, centered).
* **8:7**: Square pixels at full screen height. 224 lines scale to 549x480 on the LCD and 617x480 on HDMI. 239-line overscan scales 2x vertically (512x478 LCD, centered).
* **8:7 Integer**: 2x vertical integer scale. 512x448 with black borders on the LCD; 576x448 on HDMI (compensating for HDMI's 8:9 pixel aspect ratio).
* **4:3 Integer**: 2x vertical and 7/3 horizontal scale (597x448 LCD, 672x448 HDMI). Keeps scanlines uniform across every source line without beating against non-integer vertical scaling.

With **Interpolation** set to **Bilinear** (register `0x0028` bit 5, default), each output pixel blends neighboring source pixels horizontally via `Crt`'s blur stage and vertically (when scanlines are off and not using an integer mode) via `Crt`'s previous-line blend (`Pixel.lineWeight`). When scanlines are enabled, the scanline beam profile handles vertical filtering. In **Nearest** mode, each screen pixel samples the single source pixel at its center. Out-of-bounds neighbors sample black.

With 224 lines scaled to 480 rows (15 screen rows per 7 source lines), the image is shifted down by 1/60 line so screen rows land cleanly in the center of `Crt`'s 1/30-line scanline phases instead of straddling phase boundaries. On HDMI and rev3 LCDs (which scan row-by-row), the `Crt` line buffer holds 1024 columns.

Pause-menu CRT settings live in register `0x0028`: **Scanlines** (default 75%), **CRT Mask** (default 25%), **Mask Type** (default Magenta/Green), and **Interpolation** (default Bilinear).

#### Audio

The DSP outputs 16-bit stereo audio every 671.16 APU clock cycles (~32 kHz). The framework's `lib.audio.AudioFilterChain` resamples it to the DAC's 48 kHz rate (CIC decimation from the system clock, 20 kHz FIR low-pass, cubic interpolation, and DC blocker), eliminating 32 kHz aliasing images. Running the APU on its own free-running `BUFGCE` keeps sample timing steady during S-CPU stalls, so no rate-servo FIFO is needed.

Setting `HandheldSnes.DebugAudio = true` synthesizes audio debug tools (4 extra BRAMs; config bits 3-6, registers `0x0020`/`0x0024`, capture buffer at host window `0x1xxxxx`): a 1 kHz test tone, capture playback, and raw DSP sample or inter-sample cycle-count capture. There is no firmware UI for these registers; drive them from a debug build and decode serial `CAP` dumps with `fpga/scripts/snes_audio_capture.py`.

### Coprocessors

`snes.SnesCoreConfig` enables DSP-n, S-DD1, CX4, Super FX (GSU), and SA-1, and leaves SPC7110, BS-X, and Sufami Turbo disabled. MSU-1 is removed. `RomHeaderAnalyzer` in the FPGA glue detects the cartridge type, rejects ROMs requiring disabled chips during `SETUP_COMPLETE`, and selects the stall policy. DSP-n and CX4 require external firmware ROMs that are not distributed with Game Bub (see `fpga/verilog/snes/README.md`).

### Save states

The MiSTer core implements save states in software. On a save/load request, `rtl/savestates.sv` overrides the NMI (or IRQ) vector to run a 65816 routine (`fpga/verilog/snes/savestates/savestates.bin`, upstream's `boot1.rom`). That program streams CPU, PPU, DMA, coprocessor, WRAM, VRAM, OAM, CGRAM, ARAM, SMP/DSP, and BSRAM state through `$C06000`, where the RTL packs 8 bytes per 64-bit word into four 1 MiB slots (`SS_DDR_ADDR[21:3]`). Each slot header stores the size (words in bits 49:32), a save counter (bits 31:0), and the `SNES` magic at byte 8. Base cartridges, DSP-n, CX4, Super FX, and SA-1 support save states (`SS_AVAIL`); S-DD1 does not.

On Game Bub:
* `SaveStateProgramLoader` embeds `savestates.bin` in the bitstream and copies it to SDRAM at `0xFF0000` after `SETUP_COMPLETE` (unless the padded ROM extends up to `0xFF0000`, which clears `SS_AVAIL`).
* `snes.SaveStateMemoryPort` adapts the 64-bit port to two little-endian 32-bit SDRAM accesses starting at `0x1000000`, sharing SDRAM behind the ROM cache via `lib.mem.PipelineMemoryLowPriorityMux`.
* Because the 65816 program expects single-access memory latency, the glue stalls the core clock on every save-state transfer (tracked in stat `0x1078`), adding a few tens of milliseconds on top of the ~1 s program runtime.
* Save and load actions from the pause menu write `0x11` or `0x12` to register `0x0014`, using the slot in register `0x0018`. Because the core lacks focus while paused, the glue temporarily clocks the core (with input and audio muted) until completion and reports the outcome in register `0x001C` (done, empty slot, timed out, or unavailable) so the firmware can show a notification before returning focus.
* If a game leaves interrupts disabled and never enters the save-state handler, the glue cancels the request after 5 seconds and asserts `SS_CANCEL` (a local addition to `savestates.sv`) so it does not trigger later when the game resumes.
* All four slots live in a single `<rom>.ss` file next to the ROM (trimmed to the last populated 1 MiB slot). The firmware loads it with the ROM and writes it back on **Exit Core** or power-off, not immediately on save.

### External core packaging

The SNES core runs as an external core from `/sdcard/cores/jeremy.SNES/` with no SNES-specific code in the firmware. `fpga/cores/snes/` holds its descriptors:
* `core.json`: metadata, rev4 bitstream path, and stall statistics registers
* `files.json`: `.sfc`/`.smc` ROM (with `"verify": true` for post-load FNV-1a read-back), `.srm` save, and `.ss` save-state file
* `settings.json`: Reset Core, Save/Load State, State Slot, Region, Pseudo Transparency, Aspect Ratio, Interpolation, Scanlines, CRT Mask, and Mask Type

Assemble the SD card directory from `fpga/` with `make package` (or `python3 scripts/package_core.py --name snes --build-root build/snes --out <dir>`).

All cartridge setup is handled in FPGA hardware from the framework's file lifecycle commands:
* `RomHeaderAnalyzer` scores LoROM/HiROM/ExHiROM headers after ROM upload, configures `ROM_TYPE`, masks, `RAM_SIZE`, and region, detects 512-byte copier headers, and fails `SETUP_COMPLETE` for unsupported chips or ROMs > 16 MiB (result exposed at `0x0030`).
* `RomMirrorTable` handles non-power-of-two ROM mirroring and copier header offset translation on cache misses.
* `SramFill` fills BSRAM with `0xFF` during ROM upload and initializes WRAM after `SETUP_COMPLETE`, while `FILE_READ_START` returns the `.srm` write-back size from `RAM_SIZE`.
* `SaveStateProgramLoader` and `SaveStateSlotScanner` load `savestates.bin`, validate slots (status bits 14:11), clear stale slots beyond the loaded `.ss` size, and report the `.ss` write-back size.
* `SramHostAdapter` translates the framework's 32-bit host file transfers into 16-bit external SRAM accesses.

Compared to a dedicated firmware driver, unsupported cartridges show a generic core error instead of a chip-specific message, and truncated ROMs are not explicitly flagged. See [docs/snes-external-core.md](snes-external-core.md) for the full external-core interface details.

## Building

```sh
nix develop .#vivado   # Nix-packaged Vivado (see docs/building.md), or `nix develop` with Vivado on PATH
cd fpga
make snes package
```

Or invoke `build_core.py` directly:

```sh
python3 scripts/build_core.py --target gamebub_rev4 --name snes \
    --core-class net.gamebub.core.snes.HandheldSnes --build-root build/snes
```

Notes:
* `build_core.py` runs `fpga/verilog/snes/tools/gen_roms.py` automatically (place DSP-n/CX4 firmware images in `fpga/verilog/snes/roms/` first if you have them) and builds with Vivado via edalize.
* The SNES build uses Vivado's `Performance_Explore` implementation strategy because the default strategy falls slightly short of timing closure on paths from the S-CPU/SA-1 into the SRAM IOB registers. Timing margin is tight (~0.35 ns), so always check the timing summary (including the `sdram_clk_pin` and `sram_*` rows from `fpga/verilog/handheld/core_snes.xdc`) after adding logic.
* To syntax-check the VHDL without Vivado:

```sh
cd fpga/verilog/snes
ghdl -i --std=08 --workdir=/tmp/w --ieee=synopsys -frelaxed -fexplicit $(find rtl -name '*.vhd')
ghdl -m --std=08 --workdir=/tmp/w --ieee=synopsys -frelaxed -fexplicit SNES
```

(`CPU.vhd` has one case statement that VHDL-2008 rejects for incomplete coverage; Vivado's VHDL-93 mode accepts it.)

* `build_sim.py` (Verilator) cannot simulate this core because the upstream RTL is VHDL.

## TODO

* **ROM bandwidth**: A larger ROM cache (if BRAM allows) or wider SDRAM fetch would help Star Fox, where remaining stalls are all ROM misses from 3D geometry reads.
* **BSRAM cache**: Because the SA-1 samples one cycle after strobing, cache hit rate is the main bottleneck. Growing the cache while meeting timing requires moving byte-valid bits into distributed RAM.
* **PAL LCD testing**: Verify 50 Hz vertical-porch stretching on the rev4 LCD panel.
* **Additional chips**: SPC7110, BS-X, and Sufami Turbo remain disabled.
* **Out of scope**: Second controller/multitap, rumble, and physical cartridge support (Game Bub has no SNES slot).
