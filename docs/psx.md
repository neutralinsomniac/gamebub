# PlayStation core port (work in progress)

Game Bub's PlayStation support is a port of the MiSTer PSX core
([MiSTer-devel/PSX_MiSTer](https://github.com/MiSTer-devel/PSX_MiSTer), by
Robert Peip, GPLv2). The core is vendored in `fpga/verilog/psx/` (its README
lists the local changes) and wrapped by Chisel glue in
`fpga/src/main/scala/net/gamebub/core/psx/HandheldPsx.scala` and
`fpga/src/main/scala/psx/`. It ships as the external core `jeremy.PSX`
(`fpga/cores/psx/`) and needs a rev 4 board: it uses both SDRAM chips.

**Status (2026-10-18):** builds and meets timing; not yet run on hardware.
The first milestone is the BIOS shell (memory card manager / CD player) on the
LCD, with sound and input. Not there yet: discs (CD images), memory cards,
save states, HDMI.

Vivado 2025.1: 40.1 k LUTs (63 %), 62.5 of 135 block RAM tiles, 98 DSPs;
timing met at 33.85 / 67.71 / 101.56 MHz.

## Building and installing

    cd fpga && make psx package-psx

builds `build/psx/psx.bit` and stages `build/package/cores/jeremy.PSX/`; copy
that directory to the SD card's `cores/`. For now the core asks for the BIOS
image when it starts (a 512 KiB `.bin`, e.g. SCPH-1001); it is loaded into
SDRAM and read back to verify the transfer.

Controls: the PlayStation's face buttons are where the Game Bub's are (Cross
= B, Circle = A, Square = Y, Triangle = X), L / R are L1 / R1, and Select + L /
Select + R are L2 / R2 (Select alone is Select).

## Design

### What was cut

The XC7A100T holds the core with room to spare once options the port does
not offer are fixed to constants in the wrapper
(`fpga/verilog/psx/gamebub/psx_gamebub.vhd`): synthesis then removes the
logic behind them. Fixed off: the turbo modes (and the CPU data cache, which
only they use), 24-bit rendering, texture filtering, the 480p and widescreen
hacks, debug overlays, cheats, EXE loading, 8 MB RAM, multitap, mouse, light
guns, wheels, SNAC. One controller (digital, analog or DualShock) on port 1.

### Clocks

The core runs on three phase-aligned clocks, clk1x (the console's 33.8688
MHz), clk2x and clk3x, and moves signals between them without
synchronizers. One MMCM makes all of them: 50 MHz / 2 x 40.625 = 1015.625 MHz
VCO, divided by 30 / 15 / 10 (33.854 / 67.708 / 101.5625 MHz, 432 ppm slow;
no single MMCM setting gets closer with exact 1:2:3 ratios). clk1x is the
framework's system clock. The display and host SPI clocks come from the same
VCO, and a sixth output, clk3x delayed by 1.6 ns, feeds the SDRAM clock pin.

### Memory

| What | Where |
| --- | --- |
| Main RAM (2 MiB), BIOS (512 KiB at 8 MiB) | SDRAM chip 0 |
| VRAM (1 MiB at 0), memory cards (1 and 2 MiB), save states (16 MiB) | SDRAM chip 1 |
| SPU RAM (512 KiB) | SRAM |

Both SDRAM chips sit on one bus with their own chip selects. `psx.SdramEngine`
runs it at clk3x with two-beat bursts (CAS latency 2), one 32-bit word per
burst and a burst every other cycle, and leaves a row open in each of the
eight banks. `psx.PsxMemory` arbitrates the core's main RAM interface (with
the timing of the MiSTer's `sdram.sv`, which the core's CPU and DMA rely on:
instruction cache lines are written as they arrive, DMA words are handed over
one per clk1x cycle), its DMA write FIFO, its DDR3 port (VRAM etc., at clk2x,
through FIFOs; long VRAM reads are cut into runs of 16 words so the CPU does
not wait behind a whole line), the display scanout and the host.

The pins are registered in their I/O blocks (the data pins' output and
tristate registers as primitives: the pair must share its control signals);
the chips' clock is clk3x inverted and 1.6 ns late, which centers the read
data window on the capturing clk3x edge. `fpga/verilog/handheld/core_psx.xdc`
has the budget.

### Video

The core's own video output reads VRAM a second time and produces a pixel
stream that would have to be buffered whole; instead, the core's video
output is off (`videoout_on`) and only its timing generator runs, and
`psx.PsxScanout` reads the display area out of VRAM for the framework's
on-demand video interface (`VideoPullV0`): the framework asks for each
screen pixel a few cycles ahead. The picture is 640 x 480 (4:3) in the
middle of the 800 x 480 LCD, scaled from the display area's size by nearest
neighbor. The LCD is mounted rotated and scans the picture column by column,
so VRAM is read in strips a few pixels wide and the display area's height
into a buffer of two strips: while the scan is in one, the next is fetched.
The LCD starts each frame just after the core's vertical blanking (the
framework's frame pacing), in the left border, where the first two strips are
fetched, so a game's buffer flip at vertical blanking never shows mid-frame.

Not yet: HDMI (row by row: black for now), the vertical display range (PAL
games' extra lines), interlaced games that draw one field at a time.

### Tests

`psx.SdramEngineSpec` (the engine against a model of the chips that checks
every timing rule), `psx.PsxMemorySpec` (the memory with its three clocks,
driven as the core and the host drive it) and `psx.PsxScanoutSpec` (frames
scanned out of a random VRAM, compared pixel for pixel):

    cd fpga && ./mill root.test.testOnly 'psx.*'
