# PlayStation core port (work in progress)

Game Bub's PlayStation support is a port of the MiSTer PSX core
([MiSTer-devel/PSX_MiSTer](https://github.com/MiSTer-devel/PSX_MiSTer), by
Robert Peip, GPLv2). The core is vendored in `fpga/verilog/psx/` (its README
lists the local changes) and wrapped by Chisel glue in
`fpga/src/main/scala/net/gamebub/core/psx/HandheldPsx.scala` and
`fpga/src/main/scala/psx/`. It ships as the external core `jeremy.PSX`
(`fpga/cores/psx/`) and needs a rev 4 board: it uses both SDRAM chips.

**Status (2026-10-19):** first hardware tests: Final Fantasy IX (US) boots
and plays its intro movie and battles. Discs are read from CD images on the
SD card (with firmware support, see "Discs"), memory cards are files on the
SD card (see "Memory cards"). Save states (see "Save states") are not yet
tested on hardware. Not there yet: HDMI.

Vivado 2025.1: 43.5 k LUTs (69 %), 92.5 of 135 block RAM tiles, 101 DSPs;
timing met at 33.85 / 67.71 / 101.56 MHz.

## Building and installing

    cd fpga && make psx package-psx

builds `build/psx/psx.bit` and stages `build/package/cores/jeremy.PSX/`; copy
that directory to the SD card's `cores/`, and put BIOS images (512 KiB) next to
the bitstream, named by region: `bios_us.bin` (e.g. SCPH-1001), `bios_jp.bin`,
`bios_eu.bin`. One is enough; a disc from another region needs that region's
BIOS. The firmware must be this tree's (it serves the disc reads).

Starting the core asks for a disc: a `.cue` sheet (with its `.bin` files, one
for the whole disc or one per track) or a lone `.bin`, of raw 2352-byte
sectors (the usual rips; `.iso` and CHD are not supported). The region (and
with it the BIOS and NTSC / PAL) is taken from the disc unless set in the
core's settings.

Changing discs (a game on several discs): the pause menu's Change Disc opens
the file browser on the disc in the drive; choose the next one. The game
goes on (it is not restarted): once the menu closes, the drive shows its lid
opened and closed again, then reads the new disc, which is what a game
asking for the next disc waits for. The memory cards stay; the save states are the new disc's
(see below). The next start offers the disc last put in.

Memory cards: slot 1 holds the game's own card, `<game>.mcd` next to the
disc's `.cue`, named after the game rather than the disc: a " (Disc N)" in the
name is left out, so all of a game's discs share it ("Final Fantasy IX (USA)
(Disc 2).cue" uses "Final Fantasy IX (USA).mcd"). Slot 2 holds one card for
every game, `card2.mcd` in the core's directory (the BIOS's memory card
screen copies saves between the two). Both are raw 128 KiB images, as most
emulators keep them (`.mcd`, `.mcr`, MiSTer's `.sav`: rename to `.mcd`). A
missing card starts out formatted and empty, and is only written once the
game saves to it. Cards are written to the SD card when the pause menu
opens and when the core exits, if the game has changed them.

Save states: the pause menu's Settings have Save State, Load State and State
Slot (1-4). Each slot is a file next to the disc's `.cue`, named after the
disc rather than the game (a state includes where the drive is on the disc,
so it only goes with that disc): "Final Fantasy IX (USA) (Disc 2).ss1" to
`.ss4`, 4 MiB each. They are read when the core starts, and when the disc is
changed (the old disc's are saved first); Save State writes its slot's file
right away, says so ("State 1 saved") and closes the menu (the game goes on
once the file is written); Load State closes the menu, and the game goes on
from the state.

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
| SPU RAM (512 KiB) | SRAM, except its capture area (4 KiB) and reverb area (the top 64 KiB): block RAM, as upstream |

Both SDRAM chips sit on one bus with their own chip selects. `psx.SdramEngine`
runs it at clk3x with two-beat bursts (CAS latency 2), one 32-bit word per
burst and a burst every other cycle, and leaves a row open in each of the
eight banks. `psx.PsxMemory` arbitrates the core's main RAM interface (with
the timing of the MiSTer's `sdram.sv`, which the core's CPU and DMA rely on:
instruction cache lines are written as they arrive, DMA words are handed over
one per clk1x cycle, through a ring that a DMA read must find room in before
it is taken: the reads come back faster than that), its DMA write FIFO, its DDR3 port (VRAM etc., at clk2x,
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
so the picture is read in strips a few pixels wide and the display area's
height into a buffer of two strips: while the scan is in one, the next is
fetched.

What it reads is not VRAM but a copy of the display, made line by line as
a TV shows it (`psx.PsxFrameGrab`): at the start of each line of the
picture, the display area's line goes from VRAM to one of two frames in
SDRAM chip 1 (at 8 and 9 MiB, laid out as VRAM; the memory system copies
it in chunks of 16 words, after the scanout's fetches in priority). When
the core's vertical blanking starts, that copy is complete and the LCD
starts its frame on it (the framework's frame pacing) while the next one
is made in the other: one frame of latency, as a framebuffer has. Reading
VRAM itself, column by column, the left strips (all their rows) are read
at the start of the frame, which showed whatever the game was still
drawing there, ahead of a TV's beam: Final Fantasy IX flips its buffers
some time into the blanking (its movies flickered) and draws its battle
menus after the flip, into the buffer being shown (they flickered).

In 480-line (interlaced) modes nothing is copied and the scanout reads
VRAM, but only the field the TV shows, each of its lines twice (bob, as a
TV shows it: a slight shimmer on fine horizontal detail). Games draw the
other field meanwhile, for the next one, so the shown field holds still
while it is read. Reading both fields from VRAM showed the other one half
drawn (Valkyrie Profile's title and the BIOS logos were striped). Taking
the other field from a copy made while it was shown (weave) cost about 40%
of the SDRAM's time, against about 13% for bob, and slowed the CPU and the
GPU enough that the BIOS's PlayStation logo ended up different in its two
fields.

Not yet: HDMI (row by row: black for now).

### Discs

The core asks for one 2352-byte sector at a time, by absolute LBA, and takes
the sector's 1176 halfwords through its `cd_hps_*` port (the MiSTer's HPS
interface); its track table comes through `trackinfo_*`. `psx.PsxDisc`
connects them to a firmware service through the framework's core -> host
request channel (`HostV0.commandCore`):

- The disc's file (ID 0) is marked `"disc": true` in `files.json`. The
  firmware (`firmware/handheld/src/core/disc.rs`) parses the `.cue` sheet,
  keeps the `.bin` files open, and writes the table of contents to the file's
  address (0x6000_0000, a block RAM in the glue) instead of the file: the
  track count, the lead-out, and per track its first and last LBA, its index
  1 LBA and position (BCD) and whether it is audio. LBAs are absolute: track
  1's index 1 is at 150 (00:02:00).
- For a sector, the glue sets the request words (0xF000_1000: `DISC_READ`
  0x0400, file ID, LBA, destination) and raises the request; the firmware
  (core request interrupt) reads the sector into the glue's sector buffer
  (0x7000_0000) and answers `done`; the glue acknowledges the core and
  streams the sector in.
- When the core is first started, the glue holds it in reset and reads the
  license sector (154): its text ends in "...Entertainment Amer  ica",
  "... Inc." or "...Euro pe", which gives the region. Each time the core leaves
  reset it gets the track table (MiSTer's `disk_t` layout, with that region).
- A disc change is the MiSTer's: the firmware writes the new disc's table of
  contents while the core runs (`FILE_WRITE_START` / `FILE_WRITE_END` for
  file 0, as at the start) and serves its sectors from then on, and the glue
  gives the core the new track table. The core's drive takes a track table
  out of reset as a new disc (`cd_top.vhd`'s `newCD`, which then ignores
  more track tables for 2 s): it stops, answers the command in hand with a
  "shell open" error, shows the lid open (status bit 4) for 0.38 s of
  running time (0.74 s at double speed), and spins the new disc up; the
  next Getstat shows the lid closed. The console is not reset (MiSTer resets it for a disc
  from another directory: a new game), and the region stays the first disc's.
  `LIDopen` (upstream: the OSD's CD Lid option) stays low.

The BIOS images are three optional files with fixed names (IDs 1-3, the
core's BIOS slots at 8 MiB + 512 KiB x region); the glue uses the region's,
or the first that was loaded, and fails `SETUP_COMPLETE` without any.

The firmware side is generic: a core whose `disc` file is `user_selected`
gets Change Disc in its pause menu. The file browser is the core's file
selection, shown on the game screen. A disc change first saves the files
`dependent_on_0` and `per_disc` (named after the disc), then loads them again
under the new disc's name; a missing one is loaded as nothing
(`FILE_WRITE_START`, then `FILE_WRITE_END` with 0 bytes) for the core to drop
the old disc's. Files named after the game (the memory card) stay.

Not supported: libcrypt (`.sbi` subchannel data; the protected games need
it), CHD.

### Memory cards

The core keeps the cards in its DDR3 space, as raw images (slot 1 at 1 MiB,
slot 2 at 2 MiB: chip 1, host 0x3210_0000 / 0x3220_0000), and its joypad
reads and writes 128-byte frames there. Upstream, `memcard.vhd` copies a card
between there and the MiSTer's HPS; that path is unused here: the cards are
files 4 and 5 in `files.json`, which the firmware loads straight into place
while the core is held and reads back from there. Firmware options this
uses (generic, for any core):

- `blank`: if the file cannot be opened, this file from the core's directory
  is loaded instead (`blank.mcd`: a formatted, empty card, the same as the
  MiSTer's `mcdheader.h` followed by zeros); the file is still saved to its
  own path.
- `save_on_pause`: the file is also saved when the core is paused.
- A file `dependent_on_0` on a disc image (`disc`) is named by
  `disc::save_path`, which drops " (Disc N)".
- A core that answers `FILE_READ_START` with 0 bytes has nothing to save:
  the firmware leaves the file as it is (the SNES core does so for a game
  without save RAM).

The glue inserts a card (`memcardN_available`) if its file was loaded, and
remembers, per card, whether the core has written to it (a DDR3 write in the
card's 128 KiB) since the firmware last saved it; `FILE_READ_START` answers
0 bytes otherwise. Writes to frame 63 do not count: the BIOS's card routines
write that frame (the write-test frame, a copy of the header) whenever a game
looks for a card, to clear the card's "new card" flag. So an unchanged card is not rewritten, and a game that
never saves leaves no file behind.

### Save states

The core's own save states (`savestates.vhd`): on a request it pauses the
console once it is somewhere it can stop (the pause menu has usually done
that already) and copies its registers, the CD controller's state, SPU RAM,
VRAM and main RAM into a 4 MiB slot in its DDR3 space; loading resets the
console and copies it all back. The four slots are at DDR3 224 MiB, which
the memory maps to chip 1 at 16 MiB (host 0x3300_0000, 0x3340_0000, ...).
Each slot is a file (6-9 in `files.json`) that the firmware loads into place
while the core is held, header and all: the layout of a MiSTer `.ss` file.

The glue takes requests in register 0x0014 (1 save, 2 load; the slot in
0x0018), which the settings' actions and list write, and keeps, per slot,
whether it holds a state (its file was loaded, 4 MiB, or the core has saved
into it) and whether the core has saved into it since the firmware last
wrote the file: `FILE_READ_START` answers 4 MiB for those and 0 for the
others, so only they are written. The Save State action has the firmware
save right after it (`save_after`), and `FILE_READ_START` waits for a save
in progress (up to 2 s); the files are also `save_on_pause`, as the cards,
should that fail. A save that a reset cuts short leaves its slot without a
state: part of the old one is overwritten, and the file keeps the old one.
The pause menu's pause is lifted while a save or load is in progress: the
core's save states stop the console only where it can stop (no CD sector,
DMA or GPU work in hand), and a console the menu has stopped somewhere else
never gets there (a save then waited for the menu to close). The core also checks each
slot's header (a size word) after every reset, and loads only from a slot
that passes both. A save takes the core's busy flag (`savestate_busy`, passed
out of `psx_top` for this) to finish. The core sees a slot's header only
after a reset, so a state file loaded without one (a disc change) is marked
as there through `validSStates_set` (a Game Bub addition to
`savestates.vhd`). Off: upstream's rewind, and its header
count, which the MiSTer's HPS watches to find new states (the core waits
about a second after each save while it copies them).

Firmware options this uses (generic): `per_disc`, a file `dependent_on_0`
on a disc image named after the disc instead of the game; `save_after` on a
settings action, which saves the `save_on_pause` files right after it and
shows a notification ("State 1 saved", or what went wrong); `resume_after`
on a settings action, which closes the pause menu after it (the state actions
and Reset Core); and
`FILE_READ_START` may take up to 5 s. Saves overwrite their file in place
(cut to size after) instead of truncating it first.

### Statistics

When the game is paused, the firmware logs the core's counters (see
`HandheldPsx`), each in decimal:

| Label | Meaning |
| --- | --- |
| run rate | clk1x cycles running vs. with a main RAM access outstanding (these overlap: compare the two counts; 32 bits, so a window over about 2 minutes wraps) |
| SDRAM row hits / conflicts | accesses to an open row vs. ones that had to close another |
| disc sectors, disc read errors | sectors the core read (and that the firmware failed to read) |
| worst disc wait | the longest wait for a sector, in clk1x cycles (the percentage is of a sector's time at 2x speed) |
| core errors | every error the core has flagged since reset, bit n for code n as MiSTer shows them: 1 CD, 2 CPU, 3 GPU, 4-6 GPU line / rectangle / polygon, 7 mask, 8 DMA chop, 9 GPU FIFO, 10 SPU timeout, 11 DMA during CPU, 12 DMA FIFO, 13 CPU, 14 timer, 15 bus width |
| CPU fetch address | where the CPU is (two pauses at the same address and no RAM traffic: it is stuck) |
| display | bit 27 disabled, 26 24-bit, 25 interlaced, 24 PAL, 23-21 horizontal mode, 20-11 height, 10-0 width |
| disc state | the disc glue's state machines (bits 3-0 region scan, 7-4 request, 11-8 sector) and the core's request (bit 15) |
| last disc LBA | the last sector asked for |
| vertical display range | GP1(07h): first line (bits 9-0), end (bits 19-10) |
| MDEC status | MDECSTAT |
| DMA state | bits 31-29 state, 28-26 active channel, 25 on, 24 autoread, 23 / 22 FIFO in / out empty, 21 paused, 20 to device, 19-12 blocks left, 11-0 words |
| CPU/DMA flags | bit 15 CPU paused, 14 DMA on, 13 DMA request, 12 DMA possible, 11 DMA stalls the CPU, 10 / 9 MDEC in / out request, 8 GPU, 7 SPU request, 6 memory mux idle, 5 CPU stall next |
| horizontal range | GP1(06h): start (bits 11-0), end (bits 23-12) |
| display position | the display area in VRAM: x (bits 9-0), y (bits 24-16) |
| scanout underruns | strips the LCD reached before they were fetched, since reset |
| save states | register 0x0014: bit 9 the core's save-state machine busy, 8 a request in progress, 7-4 slots saved into since their files were written, 3-0 slots holding a state |
| last save/load time | clk1x cycles from the request to the end of the last save or load (the percentage is of a second) |

A core.json can also list memory for the firmware to save to the core's
directory at each pause (`"dumps": [{"file": "vram.bin", "address":
"0x32000000", "size": "0x100000"}]`: VRAM, 1024 x 512 halfwords of 15-bit
color), to look at on a computer (`scripts/psx_vram_png.py` renders it);
the statistics' addresses can likewise be any host address (main RAM at
0x3000_0000), but each is cleared, written with zero, after it is read.
`scripts/console_log.py` records the console (the statistics) over USB.

### Tests

`psx.SdramEngineSpec` (the engine against a model of the chips that checks
every timing rule), `psx.PsxMemorySpec` (the memory with its three clocks,
driven as the core and the host drive it), `psx.PsxScanoutSpec` (frames
scanned out of a random VRAM, compared pixel for pixel) and `psx.PsxDiscSpec`
(the firmware and the core's drive played against the disc glue):

    cd fpga && ./mill root.test.testOnly 'psx.*'
