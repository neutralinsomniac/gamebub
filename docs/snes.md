# SNES core port (work in progress)

Game Bub's SNES support is a port of the MiSTer SNES core
([MiSTer-devel/SNES_MiSTer](https://github.com/MiSTer-devel/SNES_MiSTer), by
srg320 et al., GPLv3). The core is vendored in `fpga/verilog/snes/` and wrapped
by Chisel glue in `fpga/src/main/scala/net/gamebub/core/snes/HandheldSnes.scala`.

**Status (2026-08-29):** runs on rev4 hardware with correct video, audio, input
and saves. Base cartridges, DSP-n, S-DD1, CX4, Super FX and SA-1 are
synthesized (DSP-n and CX4 games additionally need the coprocessor ROM images).
Measured run rates (core cycles / (core cycles + stall cycles), logged by the
firmware when a game is paused):

| Game | Cartridge | Run rate | Worst 49 ms window |
| --- | --- | --- | --- |
| Chrono Trigger, Final Fantasy VI | base (HiROM) | 99.99 % | |
| Star Fox | Super FX | 99.7 % | 1.1 %, all ROM misses |
| Yoshi's Island | Super FX 2 | 99.60 % | 1.0 % |
| Super Mario RPG | SA-1 | 99.80 % | 2.5 % |

Vivado 2025.1, build of 2026-09-14: 45.1 k LUTs (71 %), 21.5 k registers,
130 of 135 block RAM tiles (96 %); timing met at 21.477 MHz core /
42.955 MHz SDRAM (outputs 4.4 ns of setup slack at the chip, read data
8.2 ns, captured in IOB flops). Block RAM is the tight resource.

## Design

### Clocking

The MiSTer core runs entirely on the SNES master clock (21.477 MHz, `MCLK`),
with internal clock enables for the CPU, PPU, SMP and DSP. The framework's
default 939.583 MHz MMCM cannot produce that frequency: the closest integer
divider gives 939.583 / 44 = 21.354 MHz (0.57 % slow, and audio pitch and
frame rate scale with it because the DSP's `CEGen` assumes the nominal
frequency), the fractional divider 43.75 is out because the SDRAM clock must
be an integer multiple of the system clock (`PipelineMemoryBurstCdc` is a
synchronous crossing) and only MMCM output 0 is fractional, and no single MMCM
setting (multiplier in 1/8 steps) produces 945/44 MHz together with a 2x or
4x SDRAM clock.

Each core builds its own clock tree (`ClocksV0`), so `HandheldSnes` puts a
PLL stage (`xilinx.PLL`) behind its MMCM: MMCM 50 / 1 x 23.625 = 1181.25 MHz,
output /11 = 107.386 MHz into a PLL x12 = 1288.636 MHz, divided by 60 for the
system clock (exactly 21.4773 MHz) and by 30 for the SDRAM clock (42.955 MHz;
a third PLL output, the same clock delayed by 14 ns, is the SDRAM chip's
clock, see "SDRAM interface timing"). The display and host SPI clocks are divided from the
MMCM's 1181.25 MHz VCO: the display clock is the lowest the revision's display
driver accepts, like the other cores (26.25 MHz on rev4), and the SPI clock
the closest to 200 MHz below it (196.9 MHz).

### SDRAM interface timing

The SDRAM runs at 2x the core clock (42.955 MHz, 23.28 ns). The chip's
clock is a third PLL output, the controller's clock delayed by 14 ns
(`HandheldSnes.SdramClockPhaseNs`), forwarded through a BUFG and the pin.
`BurstSdramController` launches commands, address and write data from plain
flops on the rising edge of its clock, so the chip samples them 4-13 ns
after they settle and 8-12 ns before they change again, across the corners.
Read data is captured on the falling edge of the controller clock
(`readCaptureFalling`) in a register placed in the IOB, which is within
1.5 ns of the centre of the data window: the chip drives a beat from the
clock edge after the READ (CAS latency 2: valid at the second edge), so
the beats change within the first quarter cycle after the controller's
rising edge, the first one in the second cycle after the READ, and the
capture sits 5-8 ns inside the window at the corners. The burst runs one
beat past the two wanted and freezes on it (clock suspend), and a burst
continuation resumes on that beat, as the controller assumes. The budget
is a PC133-class chip: setup 2 ns, hold 1 ns, clock-to-data 6 ns, data
hold 2.5 ns. The interface does not work at 4x: the routed pin paths spread
over most of an 11.64 ns period across the corners, so no clock phase
gives every pin the same edge.

`fpga/verilog/handheld/core_snes.xdc` (picked up by `build_core.py` as the
per-core constraints file) declares the pin clock and those budgets, and
Vivado's default edge relationships are the intended ones, so the
sdram_clk_pin rows of the timing summary's inter-clock table are the
check to make after every build. The cost of the 2x clock is half the
SDRAM bandwidth, which the ROM cache's prefetch hides for everything but
the miss latency.

The firmware reads the whole ROM back after loading (`VERIFY_ROM` in
`bitstream/snes/mod.rs`, per-chunk checksums) and logs the result; a
mismatch there means the upload or the interface is corrupting data. It
uploads to the SDRAM at 10 MB/s (`SDRAM_TRANSFER_SPEED`): a host write
takes 4 core cycles through the CDC at the 2x clock and the controller only
refreshes in gaps of 8 SDRAM cycles, so a faster stream would defer the
refreshes and overflow the 512-word SPI request FIFO, which the firmware
does not check.

`BurstSdramControllerSpec` checks the controller against a cycle-level SDRAM
model (JEDEC CAS latency semantics) in both timings (the rising-edge one
the other cores use and this one), and the whole chain through
`PipelineMemoryBurstCdc` at the 2x ratio, with both clocks derived from
one counter (see `SnesRomPathSpec` below for why). The CDC holds the
initiator off while its 4-entry request fifo is full, and drops a prefetch
that completes after a write was pushed (it read the memory before the
write reached it).

### Memory map

| Memory | Where | Notes |
| --- | --- | --- |
| Cartridge ROM (<= 16 MiB) | SDRAM, byte 0 | The ROM file as loaded by the MCU (a copier header included). Read through a 32 KiB 2-way cache with 16-byte lines and next-line prefetch (`LineReadCache`), fronted by a 16-line register buffer that answers hits in the request cycle and pulls the next line in the background (`LineBuffer`); the cache's SDRAM requests go through `RomMirrorTable`, a 512-entry table of 32 KiB blocks (distributed RAM) that mirrors a non-power-of-two ROM up to the next power of two the way a cartridge's partial address decoding does, and adds the copier header offset. The table is rebuilt from the file size at the end of the ROM transfer. (The firmware driver still mirrors by duplicating data too, which is redundant; a ROM size that is not a multiple of 32 KiB gets an identity table and relies on it.) |
| BSRAM (save RAM, <= 256 KiB) | SRAM, byte 0x00000 | Filled with 0xFF by the glue when the ROM transfer starts, then loaded/saved by the MCU as `<rom>.srm`; 2-way 2 KiB word cache in the bridge |
| WRAM (128 KiB) | SRAM, byte 0x40000 | Initialized by the glue with the MiSTer power-on pattern after SetupComplete (`SramFill`) |
| VRAM (2 x 32 KiB) | block RAM | |
| ARAM (64 KiB) | block RAM | |
| Framebuffer | block RAM | 256 x 240 x 15 bpp, double buffered (framework) |

### Stalling the core for slow memory

On the MiSTer the core sees ROM, WRAM and BSRAM as memory that responds within
its own cycle (SDRAM at 4x the core clock, or block RAM). On Game Bub, ROM
lives in SDRAM behind an arbiter, a clock-domain crossing and a burst
controller, and WRAM/BSRAM live in the external asynchronous SRAM, which takes
two cycles. A single-cycle SRAM read is not possible from the core: the routed
path from the core's address registers to the `sram_a` pins is ~47 ns, the
whole cycle, before the SRAM's own access time. (An MCU-path self-test passes
because its addresses come from registers next to the arbiter.)

The core has no usable global pause input (`ENABLE` does not stop its clock
dividers), so `HandheldSnes` feeds it a gated clock (`BUFGCE`): whenever an
external access is outstanding, the clock is stopped and the core sees
single-cycle memory, exactly as on the MiSTer. The glue (memory bridges, video
capture, statistics) runs on the ungated system clock; pulses from the core
domain are consumed with the `tick` qualifier.

The memory path is built to keep those stalls rare:

* `BytePortBridge` (WRAM and BSRAM) issues a request in the cycle the core
  asserts a strobe and forwards read data in the cycle it arrives, so an SRAM
  read is done two cycles after the strobe. Writes are posted through an
  in-order queue (4 deep for WRAM, 8 for BSRAM: the Super FX flushes its pixel
  cache as a burst of read-modify-writes) and only stall the core when the
  queue is nearly full. `pendingRead` counts demand reads only, not queued
  writes or prefetches, so a burst of stores never stalls the coprocessor
  issuing them.
* The BSRAM bridge has a 2-way 2 KiB word cache in distributed RAM
  (asynchronous read, so hits are answered in the strobe cycle; byte-valid
  bits in registers so `invalidate` clears it at once). It is write-through
  and write-allocate per byte, so a decompressor reading back what it just
  wrote hits, and it prefetches the next word only when an access continues a
  sequential run, so random reads do not double the SRAM load.
* Two consumers can share a port (the S-CPU and the SA-1 on BW-RAM, the S-CPU
  and the GSU on the Super FX's RAM), so the bridge's read data follows the
  address on the port whenever the cache has it, and a demand read that
  completes after a newer hit is discarded instead of replacing the hit's data
  (`BytePortBridgeSpec` covers both cases).
* The SRAM controller and arbiter accept a *different* request in an access's
  `done` cycle, so back-to-back accesses run every two cycles instead of
  three. An identical request held through `done` is taken to be the completed
  one and waits a cycle. The SNES's arbiter is round-robin (`fair`) so the
  S-CPU's WRAM traffic cannot hold up the SA-1's BW-RAM reads.
* A ROM miss fetches the requested word first (the rest of the line follows,
  wrapping around), issues it in the cache's lookup cycle and shows every word
  on `fillWord*` as it arrives; the `LineBuffer` answers a pending miss from
  that (`early*`) and installs the whole line when the cache's regular
  response follows. A random read therefore costs the SDRAM round trip
  (~5 cycles) rather than the whole line (~12). Config bits 11 and 12 switch
  the two halves off for A/B tests.

#### Stall policies

Which accesses may stall the core is chosen per cartridge through config
register bits; the choice depends on which chips read ROM and BSRAM and
whether the core exports their sampling instants. The firmware driver sets
the mode's bits explicitly; bit 2 alone asks the glue for the mode that is
safe for the cartridge type in `ROM_TYPE` (so a settings descriptor can
offer it as one checkbox).

* *Conservative* (all bits clear): stall while any ROM or BSRAM read is
  outstanding. Correct for every coprocessor; costs a cycle per BSRAM read and
  per ROM access that misses the line buffer. Used for CX4 cartridges.
* *Latency hiding* (bit 2): only stall if a read is still outstanding when the
  S-CPU is about to latch data (`SYSCLKF_CE`). Hides all memory latency for
  the S-CPU; safe only when nothing else reads ROM or BSRAM. Used for base
  cartridges, DSP-n and S-DD1. For a Super FX, SA-1 or CX4 cartridge (per
  `ROM_TYPE`) the glue turns bit 2 into the modes below (bit 7; bits 9 and
  10; none) instead.
* *Super FX RAM latency hiding* (bit 7): BSRAM reads stall at `SYSCLKF_CE` or
  at the GSU's own sampling instant. `GSU.vhd` gained three outputs for this:
  `RAM_SAMPLE` (the cycle in which `RAM_ACCESS_CNT` reaches zero in a fetching
  state; the GSU fetching code from RAM samples one cycle after its strobe, so
  a fixed window would not do), `RAM_OWNED` (`GSU_RAM_ACCESS`: the S-CPU
  cannot read BSRAM then, so its `SYSCLKF_CE` is ignored) and `RAM_RD_N`,
  which `GSUMap` now uses as `BSRAM_OE_N`. The original strobe implied a read
  whenever CE was asserted with WE high, which the GSU does for a cycle before
  every store byte and again in the pixel cache write phase; the bridge took
  those as reads (10.5 M reads for 4.6 M writes in Star Fox) and a store cost
  the SRAM four cycles per byte. GSU ROM reads stay conservative: the GSU
  strobes `ROM_RD_N` every other cycle with whatever address it currently
  shows and relies on the MiSTer SDRAM's fixed latency, so a pending miss can
  be superseded by a strobe for another address before the GSU samples. Bit 8
  enables the experimental ROM variant; Star Fox shows a black screen with it.
* *SA-1 ROM latency hiding* (bit 9): ROM reads stall at `SYSCLKF_CE` or at the
  SA-1 side's sampling instant (`ROM_SAMPLE` = `EN and not SNES_ROM_SEL`, every
  SA-1 clock in which the 65C816, its DMA or the variable-length bit processor
  owns the ROM bus). The SA-1 changes the ROM address only in its `CLK_CE`
  cycle and consumes the data one cycle later, so, unlike the GSU, a request
  is never superseded before it is sampled. A cache hit two cycles after the
  request is one too many for the SA-1, which is why the `LineBuffer` issues
  misses in the request cycle and has 16 lines (with 4, two instruction
  streams and their data thrashed it: Super Mario RPG ran at 87 %).
* *SA-1 BW-RAM latency hiding* (bit 10): BSRAM reads stall at `SYSCLKF_CE`
  while the S-CPU owns the BW-RAM bus (`BWRAM_OWNED` = `SNES_BWRAM_SEL`) or at
  the SA-1 side's sampling instant (`BWRAM_SAMPLE` = `EN and not
  SNES_BWRAM_SEL`; every SA-1 side consumer of `BWRAM_DI` advances on `EN`).
  The strobe-to-sample histogram (stats 0x1064-0x1070) shows the SA-1 sampling
  one core cycle after its address appears (461 K of 464 K misses) while the
  SRAM read takes two, so no latency trick wins that cycle and the BSRAM cache
  hit rate is the lever: Super Mario RPG's BW-RAM hit rate went from 80 %
  (16-word buffer) to 85.6 % (1 KiB direct-mapped) to 89 % (4 KiB
  direct-mapped, +9 k LUTs of byte-valid registers and their mux). 2-way
  2 KiB reaches 87.9 % for 5 k LUTs less and is what is built; 2 x 1024 words
  does not make timing on the strobe-cycle issue path, and a bigger cache
  would need the byte-valid bits moved into the distributed RAM. The misses
  left are capacity misses (the SA-1 streams through more BW-RAM than fits).
  Yoshi's Island's GSU samples two cycles after its strobe, so a free SRAM
  makes it in time and its 2.2 M misses (61 % hit rate) cost 0.3 stall cycles
  each.

WRAM reads are latency-hidden in every mode: only the S-CPU and its DMA access
WRAM, and they latch on `SYSCLKF_CE`. A nearly full SRAM write queue stalls
the core in every mode.

#### Statistics

`HandheldSnes` counts run and stall cycles, splits the stalls by port (ROM /
WRAM / BSRAM / queue full), by what the BSRAM bridge had at the SRAM (write /
prefetch / demand read / nothing) and by S-CPU latch versus coprocessor
sample, and keeps the stall count of the worst 2^20-cycle (49 ms) window since
the last clear, because the totals average brief slowdowns (an area
transition, a menu) away while the ear picks them up as a pitch dip. The
firmware reads and clears them when a game is paused (`REG_STAT_*` in
`firmware/handheld/src/bitstream/snes/mod.rs`).

#### Pitfalls met along the way

* An early answer lets the core move on to the next word while the line is
  still on its way. A request landing in the cycle the cache delivers the line
  must be a hit (the line is installed at the end of that cycle), and a new
  request supersedes an older miss: the buffer never reports `hit` and
  `respValid` together, and the glue clears `romPending` on either. The first
  build without this crashed every base cartridge within a second.
* `SnesRomPathSpec` runs the whole ROM chain (`LineBuffer`, `LineReadCache`,
  arbiter, burst CDC, SDRAM controller, behavioural SDRAM) with both clocks
  derived from one counter so their common edges fall in the same simulation
  delta. A slow clock made from a fast-domain register fires a delta late, and
  the slow domain then sees post-edge fast values, which made the CDC
  misbehave in ways the hardware never does.
* A `main.v` generate `else` without `begin`/`end` left the SA-1 strobe tied
  to ground: a blank screen, and the only clue was Vivado's "multi-driven net
  ... constant driver preserved" critical warning. Grep `runme.log` for it
  after touching `main.v`.
* If the core's `RESET_N` is generated in the *system* clock domain and
  released on `HVCNT_ATZERO`, Vivado silently optimizes every coprocessor away
  (their reset is `RESET_N` gated by the mapper select) while the SNES itself
  survives, so the bitstream looks fine until a coprocessor cartridge is
  loaded. `HandheldSnes` generates `RESET_N` in the core's gated clock domain.
  Check the utilization report's hierarchy (`GSUMap`, `SA1Map`, ... should be
  thousands of cells) after touching anything around the reset.
* Vivado rejects the GSU's dual-edge processes; `tools/split_dual_edge.py`
  splits them.

### Video and audio

The PPU outputs one pixel per `DOTCLK` rising edge (DOTCLK is a 50 % clock at
the pixel rate). The glue latches R/G/B and blanking on each rising edge,
counts pixels per line, and drops odd pixels in 512-pixel (hi-res) modes. The
framebuffer is 256 x 240 (scaled 2x to 512 x 480 on the rev4 LCD): the PPU's
224 visible lines go to rows 8-231 and its 239 lines (overscan mode, `$2133`
bit 2) to rows 0-238, matching where a TV would show them, with black padding
rows written by the glue during the vertical blank so a mode switch leaves no
stale rows (`HandheldSnes` generates its own `hblank` / `vblank` for the
framework's row counter). Interlace is shown as one frame per field (bob
deinterlacing at half vertical resolution). PAL games run at the NTSC master
clock (312 lines, 50.5 Hz); the display drivers follow a slower source with
their vertical front porch (the rev4 ILI9806E driver can stretch a frame by
31 %, enough for PAL; the rev1-3 drivers cannot and refresh unsynchronized).
PAL is untested on the LCD; if the panel dislikes 50 Hz, frame-rate
conversion is the fallback.

Audio is the DSP's 16-bit stereo output, which updates every 671.16 cycles
(~32 kHz) of the *core* clock. Because the core clock is gated while memory
accesses are outstanding, and the stalls are bursty (they follow the CPU's
activity within the frame), the samples arrive with jitter in real time;
sampling them directly at the DAC rate frequency-modulates the audio at the
frame rate, audible as a fast warble on sustained notes (diagnosed on Chrono
Trigger's flute). The glue re-times them: a phase-locked replica of the DSP's
clock-enable generator marks each new sample, which is pushed into a FIFO and
read out on the ungated clock by a rate-servo'd, linearly interpolating reader
(`lib.audio.AudioRateAdapter`, ~4 ms latency, ~50 ms loop time constant).

Debug facilities, synthesized when `HandheldSnes.DebugAudio` is set (config
register bits 3-6, registers 0x0020 / 0x0024 and a capture buffer at host
address `0x1xxxxx`; 4 block RAMs): a 1 kHz test tone, playback of the capture
buffer through the audio path, and capture of either the raw DSP samples or
the clocks between them. The firmware has no driver for them (write the
registers from a debug build as needed). `fpga/scripts/snes_audio_capture.py`
decodes a capture dumped as `CAP` hex lines over serial and reports spectra,
glitches and jitter.

### Coprocessors

`snes.SnesCoreConfig` selects which of the core's coprocessors are
synthesized: DSP-n, S-DD1, CX4, Super FX (GSU) and SA-1 are on; SPC7110, BS-X
and Sufami Turbo are off; MSU-1 is removed. The firmware
refuses ROMs that need a disabled chip and picks the stall policy per chip (see
above). DSP-n and CX4 need firmware ROM images that are not distributed with
Game Bub; see `fpga/verilog/snes/README.md`.

### Save states

The MiSTer core's save states are software, not a hardware snapshot: on a
request, `rtl/savestates.sv` overrides the game's NMI (or, for games that
don't use NMI, IRQ) vector so that a 65816 program runs on the S-CPU. The
program (`fpga/verilog/snes/savestates/savestates.bin`, upstream's
`boot1.rom`; source `src/savestates*.asm` in the MiSTer repository) walks the
CPU, PPU, DMA and coprocessor registers, WRAM, VRAM, OAM, CGRAM, ARAM, the
SPC700 and S-DSP state and the BSRAM, and streams every byte through a
register at `$C06000`; the RTL packs 8 bytes into one 64-bit word of a
request / acknowledge memory port meant for the MiSTer's DDR3 (4 slots x
1 MiB, `SS_DDR_ADDR[21:3]`). A load runs the same program backwards. The
first 64-bit word of a slot holds the size (bytes / 4, bits 49:32) and a save
counter (bits 31:0); the data starts at byte 8 with the magic `SNES`, which
the RTL checks before it lets a load proceed. Plain cartridges, DSP-n, CX4,
Super FX and SA-1 are supported (`SS_AVAIL`); S-DD1 is not.

On Game Bub the program is fetched from the SDRAM like any ROM data (the core
addresses it at `0xFF0000`, above any real ROM; the firmware writes it there
after the ROM), and the 64-bit port is adapted to the SDRAM by
`snes.SaveStateMemoryPort` (two 32-bit accesses per word, little-endian) with
the slots at byte address `0x1000000`. The SDRAM is shared with the ROM cache
through `lib.mem.PipelineMemoryLowPriorityMux`, which gives the save-state
port the bus only when the cache is idle so that ROM miss latency is
unchanged. The MiSTer program assumes its memory answers within one CPU
access (it polls the port's busy flag only once, before the first read of a
load), so the glue stalls the core's clock for every transfer instead
(stat `0x1078`): a full state costs roughly 64k transfers, i.e. tens of
milliseconds of stalls on top of the second or so the program itself takes.

The firmware drives it through register `0x0014` (save / load request plus
slot) and the status register (`SS_AVAIL`, `SS_BUSY`, "save done", "request
pending", "in progress"). Requests come from the core's settings menu, i.e.
while the game is paused and the core has no focus, so the glue runs the
core's clock without focus (no input, no sound) from the request until the
program has finished, and the driver waits for that. Because the core only
acts at an NMI / IRQ, a game that is waiting with interrupts off never gets
there; the driver gives up after 5 s and cancels the request (a write with
both request bits clear), which stops the clock again - the request itself
stays armed in the core until the next reset. The four slots are one core
file, `<rom>.ss` next to the ROM (whole 1 MiB slots up to the last one in
use), which the core manager loads into the SDRAM with the ROM and writes
back when the core exits, like the `.srm` save: a state reaches the SD card
on "Exit Core" or power-off, not when it is taken.

### Firmware

`firmware/handheld/src/bitstream/snes/` contains the core handler (the core
is "Game-Bub.SNES" in the core list; the core manager transfers the ROM and
the `.srm` save file next to it into the core's SDRAM and SRAM windows).
`header.rs` is a port of MiSTer's ROM analysis
(`Main_MiSTer/support/snes/snes.cpp`): header scoring for LoROM/HiROM/ExHiROM,
coprocessor detection, ROM/RAM size codes and region, adapted to work from a
seekable file instead of an in-memory ROM. `.sfc` and `.smc` files (with or
without a 512-byte copier header) are accepted. The bitstream is expected at
`system/snes.bit.hs` on the SD card. Non-power-of-two ROMs are mirrored up to
the next power of two as they load. The core's settings (pause menu,
"Settings") are "Reset Core", "Save State" / "Load State" / "State Slot" (the
save states described above; the firmware embeds the 65816 program),
"Region" (auto-detected from the header, or forced), "Pseudo Transparency"
(the core's `BLEND`), "Memory Latency Hiding" and two debug switches for the
ROM miss path and the BSRAM cache (config register bits); they are not
persisted across runs.

Parts of the driver's job are also done by the glue, so that the core can
eventually ship as an external core with no firmware driver (the plan is in
`snes-external-core.md`): the framework's file commands are decoded (four
command words; the ROM and states file sizes are recorded), the ROM header
is analyzed by the glue at the end of the ROM transfer (`RomHeaderAnalyzer`,
a port of `header.rs`: it writes `ROM_TYPE`, the masks and `RAM_SIZE`, the
header's region for config bit 15, and fails SetupComplete for an
unsupported cartridge; register 0x0030 exposes its result and the driver
logs whether its own analysis agrees), non-power-of-two ROMs are mirrored by
address translation, the BSRAM is filled with 0xFF when the ROM transfer
starts and the WRAM with the power-on pattern after SetupComplete (the
status stays "setup" until then), the save file's write-back size is
answered from `RAM_SIZE`, config bit 2 selects the latency-hiding mode per
cartridge type, and colors pass through the color correction until a table
is loaded. The driver's own writes are redundant with these and still work.

## Building

```sh
nix develop .#vivado   # nix-packaged Vivado (see docs/building.md); or `nix develop` with your own Vivado on PATH
cd fpga
python3 scripts/build_core.py --target gamebub_rev4 --name snes \
    --core-class net.gamebub.core.snes.HandheldSnes --build-root build/snes
```

The SNES build asks Vivado for the `Performance_Explore` implementation
strategy (`build_core.py` prepends it to the run script edalize generates).
The core fills about 70% of the device and its longest paths run from the
S-CPU / SA-1 through the memory glue into the SRAM controller's output
registers, which the default strategy leaves a fraction of a nanosecond
short; Vivado has no seed parameter, so directives are the knob. Margin is
small (0.35 ns at the time of writing), so check the timing summary after
any change that adds logic.

The SDRAM interface constraints (`verilog/handheld/core_snes.xdc`, see
"SDRAM interface timing") are part of that summary; a build that fails them
may boot base cartridges and still corrupt Super FX games or crash others.

`build_core.py` runs `fpga/verilog/snes/tools/gen_roms.py` (place coprocessor
ROM images in `fpga/verilog/snes/roms/` first if you have them) and hands the
Chisel output plus the vendored VHDL/Verilog to Vivado via edalize.

To check the VHDL without Vivado:

```sh
cd fpga/verilog/snes
ghdl -i --std=08 --workdir=/tmp/w --ieee=synopsys -frelaxed -fexplicit $(find rtl -name '*.vhd')
ghdl -m --std=08 --workdir=/tmp/w --ieee=synopsys -frelaxed -fexplicit SNES
```

(`CPU.vhd` has one case statement that VHDL-2008 rejects for incomplete
coverage; Vivado's VHDL-93 mode accepts it.)

The Verilator simulator (`build_sim.py`) cannot run this core because it is
VHDL; a GHDL/CXXRTL flow or a Verilog translation would be needed.

## TODO

* ROM: a larger cache if block RAM allows, or a wider SDRAM fetch. Star Fox's
  remaining stalls are all ROM misses (the Super FX reading 3-D model data all
  over the cartridge).
* BSRAM: the SA-1's one-cycle sample makes the cache hit rate the only lever;
  a bigger cache needs the byte-valid bits in distributed RAM to make timing.
* PAL on the LCD is untested (see Video and audio).
* SPC7110, BS-X and Sufami Turbo remain off.
* Second controller / multitap, rumble and physical-cartridge support are out
  of scope (there is no SNES cartridge slot).
