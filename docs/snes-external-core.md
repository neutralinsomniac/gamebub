# Plan: the SNES core as an external core

Goal: ship the SNES core as a directory under `/sdcard/cores/` (bitstream +
`core.json` / `files.json` / `settings.json`) that runs on stock 1.1 firmware,
with no SNES-specific firmware code. Everything the built-in driver
(`firmware/handheld/src/bitstream/snes/`) does today either moves into the
FPGA glue (`HandheldSnes.scala`) or is expressed in the JSON descriptors.

Status (2026-09-14): sequencing step 1 is implemented in `HandheldSnes.scala`
and `ColorCorrection.scala` (items 1, 4, 5 and 8: four command words with a
busy state, file sizes latched, BSRAM / WRAM fills by an `SramFill` engine on
the host's SRAM port, save size answered on FileReadStart, config bit 2
expanded per cartridge type, config bit 15 = region from the header, colors
pass through the color correction until a table is loaded). Built and
verified on hardware with the existing firmware (Lufia, Yoshi's Island);
committed as "fpga: Let the SNES glue do the driver's memory setup".
Sequencing step 2 (item 3, `RomMirrorTable`) is built, hardware-tested
(Lufia) and committed, with the firmware driver's own mirroring left in
place for now. Sequencing step 3 (item 2, `RomHeaderAnalyzer`) is
built, hardware-validated (the driver's log says the glue's analysis
agrees for Lufia 2, Yoshi's Island and Mega Man X2) and committed. Item 6
(`SaveStateProgramLoader`) is built, hardware-tested (Yoshi's Island save
and load) and committed. Item 7 (`SaveStateSlotScanner`, the slot register,
the hardware timeout, load gating) is implemented and unit-tested; the
driver logs whether the glue's slot scan agrees with its own. What is left
is the packaging and the removal of the driver.

Findings from that step, folded into the text below:

- A setting's `mask` names the bits the firmware *keeps*: it writes
  `(read & mask) | value` (`core/settings.rs`), so a field's mask is the
  complement of the field and `value` is pre-shifted.
- The firmware sends the initial settings and the built-in driver's
  `on_before_run` register writes *before* `SETUP_COMPLETE`, so anything the
  glue derives at `SETUP_COMPLETE` overrides the driver. The glue therefore
  only fills memories there for now; the analyzer (item 2) will have to write
  the ROM registers no later than `FILE_WRITE_END` of the ROM, or the driver
  must stop writing them.
- The color-correction tables have no reset value (black video without a
  driver); fixed with pass-through until the first table write.
- The framework only enables the vblank interrupt for the GB / GBA drivers;
  nothing to do for an external core.

## What the framework already gives an external core

Verified against `firmware/handheld/src/core/{mod,info}.rs` at v1.1-beta:

- `core.json` (id, name, author, bitstreams per target, `hardware.cartridge_enable`),
  `files.json` (a list of `CoreFile`) and `settings.json` (a list of
  `CoreSetting`) are read from `/sdcard/cores/<id>/`. The bitstream filename
  must end in `.bit` (uncompressed; `.bit.hs` is rejected by `get_core`).
- Files are transferred straight into the address the descriptor names, with
  the descriptor's transfer speed and word size. A missing optional file with
  `initialize: true` is cleared to 0xFF over `max_size`.
- The host sends `FILE_WRITE_START <id>` before and
  `FILE_WRITE_END <id> <size> 0` after each transfer, so the core learns the
  file size (the glue only latches two command words today; see item 1).
- On exit the host sends `FILE_READ_START <id>` and reads the size to write
  back from command word 0. An external core therefore controls the `.srm`
  and `.ss` sizes from hardware.
- The states file is loaded with the ROM and written back at exit or power-off.
- Settings are register writes (address, mask, value); `type: action` writes a
  fixed value, `checkbox` and `list` write the chosen value. External cores'
  settings are persisted under `/sdcard/settings/<id>.json`.
- Timeouts the glue must respect: 10 ms for a command to complete
  (`NOTIFY_TIMEOUT`, used for `FILE_WRITE_END` and `SETUP_COMPLETE`), 100 ms
  for `FILE_WRITE_START` / `FILE_READ_START` and for the post-setup poll until
  the core reports `CoreHalt` (`SETUP_TIMEOUT`).

## Responsibility map

| Today (driver)                               | External version                                                    |
|----------------------------------------------|---------------------------------------------------------------------|
| Registry entry, files, settings              | `core.json`, `files.json`, `settings.json`                          |
| Copier header skip (seek 512)                | Glue: `romOffset` = 512 when file size & 512, added on the ROM miss path |
| Header analysis (`header.rs`)                | Glue: header analyzer FSM after `FILE_WRITE_END` of the ROM         |
| `check_supported` (SPC7110, BS-X, ...)       | Glue: error on `SETUP_COMPLETE`                                     |
| Mirroring during transfer                    | Glue: 4 KiB block translation table on the ROM miss path            |
| `config_value` (stall mode per chip, region) | Glue derives from `romTypeReg` + header region; settings write raw bits |
| WRAM init pattern, BSRAM 0xFF fill           | Glue: SRAM fill engine                                              |
| Save-state program written above the ROM     | Glue: copy from a BRAM ROM into SDRAM at `SETUP_COMPLETE`           |
| Save/Load/Slot actions, 5 s timeout          | Settings write regs 0x14/0x18; glue has the timeout counter         |
| Slot validity, states file size              | Glue scans slot headers on `FILE_READ_START`                        |
| `.srm` size                                  | Glue answers `FILE_READ_START` from the RAM size code               |
| User notifications, ROM verify, stall stats  | Dropped (stats stay readable over SPI for debugging)                |
| System clock rate for SPI ceiling            | Default 8 MiHz assumption; only slows register access               |

## Work items (FPGA glue, `fpga/src/main/scala/net/gamebub/core/snes/`)

### 1. Command interface: latch four words, add busy handling (done, minus the engines of items 2, 3, 6, 7)

`regCommandHost` was `Vec(2)`; `FILE_WRITE_END` sends four words and word 2
(the size) was lost. Now four registers, and a `busy` state so the file and
setup commands can take cycles: the handler sets
`commandHostState := busy`, kicks the relevant engine, and moves to `done`
(or `error`) when the engine reports completion. Everything kicked from a
command must finish inside the host timeout for that command (10 ms for
`FILE_WRITE_END` / `SETUP_COMPLETE`, 100 ms for the `_START` commands).

Per command:

- `FILE_WRITE_START 0` (ROM): start the BSRAM 0xFF fill (item 5) in the
  background; complete immediately.
- `FILE_WRITE_END 0 size`: latch `romFileSize`, run the header analyzer
  (item 2), then build the mirror table (item 3). Hold busy until done
  (well under 1 ms).
- `FILE_WRITE_END 1 size`: latch `saveLoadedSize` (informational; the fill
  from item 5 already covered the tail).
- `FILE_WRITE_END 2 size`: latch `statesLoadedSize`; zero the 16-byte header
  of every slot beyond the loaded size so stale states from a previous game
  cannot validate (item 7).
- `SETUP_COMPLETE`: copy the save-state program into SDRAM (item 6), run the
  WRAM fill (item 5), write the derived core registers, then set
  `regCoreSetup`. Answer `error` when the analyzer flagged an unsupported
  cartridge or a ROM larger than 16 MiB; the firmware surfaces this as a
  core error and does not run the core. Keep reporting `StatusSetup` from
  `GET_STATUS` until the fills have finished (the firmware polls for
  `CoreHalt` for up to 100 ms).
- `FILE_READ_START 1`: answer with the save size in bytes
  (`min(1 KiB << ramSizeCode, 256 KiB)`, 0 when the code is 0).
- `FILE_READ_START 2`: answer with `slotsUsed * 1 MiB` (item 7).
- `FILE_READ_END`: nothing.

### 2. Header analyzer FSM (done: `RomHeaderAnalyzer`)

A port of `header::analyze` and `score_header` to a state machine reading
the SDRAM through the save-state port's side of the low-priority mux
(idle during setup). Inputs: the file size from the `FILE_WRITE_END`
command word. Outputs: `romTypeReg`, `romMaskReg`, `ramMaskReg`,
`ramSizeReg`, `headerPal`, `unsupported`, `headerFound`, exposed read-only
at register 0x0030; the driver logs whether its own analysis agrees
(`check_glue_analysis`), which is how the port gets validated across a ROM
library before the driver goes. Some seventy single-word reads, a few
thousand cycles, held busy in `FILE_WRITE_END`.

Steps, all 32-bit reads relative to `romOffset`:

1. `romSize = fileSize - romOffset`; `romSizeCode` from the highest set bit
   of `romSize - 1` (same loop as the Rust, as a priority encoder).
2. For each candidate header (0x7FC0, 0xFFC0, 0x40FFC0) with
   `romSize >= addr + 64`: read the 64-byte header and the 16 bytes before
   it into a small register file; read the byte at
   `(addr & ~0x7FFF) | (resetVector & 0x7FFF)`; compute the score exactly as
   `score_header` does (reset vector >= 0x8000, opcode classes, mapper byte
   plausibility, checksum/complement, size-vs-header-code checks). Keep the
   scores and pick the header with the same tie-breaking as `find_header`
   (ExHiROM gets +4 when non-zero).
3. Read the 64 bytes at 0x7FC0 for the BS-X BIOS / competition-cart string
   compares and the first 14 bytes for "BANDAI SFC-ADX". These are constant
   compares against ROM strings in the glue.
4. Derive `romType`, `ramSizeCode` and `pal` with the same decision table as
   `analyze` (DSP-n, OBC1, ST01x, SPC7110, S-RTC, CX4, S-DD1, SA-1, GSU,
   Sufami, CC92/PF94). Write this as a lookup on (mapper, romType byte,
   company, headerAddr) so it stays readable next to the Rust.
5. `unsupported` when the chip is SPC7110, BS-X, Sufami, CC92/PF94, an
   unknown mapper, or `paddedRomSize > 16 MiB`. Keep the set in sync with
   `SnesCoreConfig`.
6. Masks: `romMask = paddedRomSize - 1`, `ramMask = ramSize - 1` (as the
   driver's `rom_mask()` / `ram_mask()`).

No header found: LoROM, size code from the file, RAM 0, NTSC (the driver
logs a warning and continues; the glue just continues).

Keep the existing host registers 0x0004..0x0010 writable so the built-in
driver and debugging over SPI keep working; the analyzer writes them once at
`SETUP_COMPLETE` and later host writes override.

### 3. Mirroring: block translation table on the ROM miss path (done)

Address translation instead of data duplication: `RomMirrorTable`. The
core's ROM address space is 16 MiB; a table of 512 entries indexed by ROM
address bits 23:15 holds the 9-bit file block for each 32 KiB block (block
RAM was 96 % used, so distributed RAM, which also reads asynchronously;
every real dump is a multiple of 32 KiB). Entry `b` is
`mirror_address(b << 15, romSize) >> 15` for `romSize <= b << 15 < padded`
and `b` otherwise. The iterative `mirror_address` runs per block in a small
FSM at `FILE_WRITE_END` of the ROM, which is held busy meanwhile (about a
thousand cycles); the table is identity after reset.

The translation sits on the cache's request path (`romCache.io.out` to the
low-priority mux), so cache tags stay in the core's address space and hits
are untouched: `{table[addr[23:15]] + carry, (addr[14:0] + romOffset)[14:0]}`
where `romOffset` is 512 when the file size has bit 9 set (copier header)
and `carry` is the 15-bit add's carry-out (the file is linear, so the next
file block follows). Each entry also carries a flag "within the padded ROM":
blocks above it (the save-state program at 0xFF0000, written to the SDRAM
directly) get neither offset nor carry, and bit 24 (save-state slots) passes
through. The cache's
eager issue is combinational from its input, so the lookup adds a
distributed-RAM read plus a 9-bit increment to that path; to be checked in
the routed timing of the next build.

A ROM size that is not a multiple of 32 KiB gets an identity table and
`supported` low; the firmware driver still duplicates data for such ROMs
today, and the analyzer (item 2) will refuse them once the driver is gone.
A 16 MiB ROM with a copier header would overflow into the save-state slots
and is likewise for the analyzer to refuse.

### 4. Config register: derive the stall mode and region in hardware

Today the driver composes bits 7..10 (GSU RAM / SA-1 ROM / SA-1 BWRAM hiding)
and bit 2 (base hiding) from the chip, and bit 0 (PAL) from the header or
the forced region. Done: the register can be written by settings directly:

- bit 2: the glue expands it to the per-chip set from `romTypeReg` (GSU: RAM
  hiding only; SA-1: ROM + BWRAM; CX4: none; else base). Bits 7, 9, 10 still
  select the modes explicitly (what the driver writes), bit 8 stays explicit
  only.
- bit 15 `regionAuto`: `core.io.PAL` takes `headerPal` (written by the
  analyzer, item 2; false until then) instead of bit 0. The Region list
  setting writes 0x8000 (Auto), 0 (NTSC) or 1 (PAL).
- Blend, ROM path and BSRAM cache bits keep their positions.

Persisted settings are re-applied by the firmware before the core runs, so
the glue must treat `configReg` writes at any time as live (it already does).

### 5. SRAM fill engine (done)

`SramFill`, multiplexed onto the SRAM arbiter's host port while it runs (a
fourth arbiter port would lengthen the target mux on the core's critical
path to the SRAM controller): fills `[base, base+len)` with either 0xFFFF or
the WRAM pattern (`((a>>8) ^ (a>>2)) & 1 ? 0x66 : 0x99` per byte, i.e. word
offset bit 7 xor bit 1), three cycles per word. Uses:

- BSRAM 0xFF, 256 KiB (18 ms at 21.5 MHz), kicked at `FILE_WRITE_START 0`,
  runs in the background behind the ROM transfer (which goes to the SDRAM);
  `FILE_WRITE_START` of any other file is held busy until the engine is idle,
  so the `.srm` transfer and the firmware's clearing of a missing one never
  overlap it. This covers a short `.srm`.
- WRAM pattern, 128 KiB (9 ms), queued at `SETUP_COMPLETE` (started when the
  engine is idle); `GET_STATUS` reports `StatusSetup` until done.

Also fill WRAM again on the framework reset action (register 0x2000)? No:
the driver does not either, and a warm reset on hardware keeps RAM.

### 6. Save-state program from a ROM in the glue (done: `SaveStateProgramLoader`)

`savestates.bin` (3558 bytes, linked into the Chisel resources as
`snes_savestates.bin`) is a `VecInit` ROM in the glue, written to SDRAM
0xFF0000 after `SETUP_COMPLETE` (890 words through the SDRAM mux's side
port, after the analyzer; `GET_STATUS` stays "setup" meanwhile). Not
written when the padded ROM reaches the program's address (size code above
13, i.e. 16 MiB); `SS_AVAIL` in the status word is cleared then, as the
driver's `save_states` flag does today.

Alternative: map ROM addresses 0xFF0000..0xFF0FFF straight to the BRAM in
front of the cache. Rejected for now: it touches the hit path and the
copy is trivial.

### 7. Save-state control and slot bookkeeping in hardware (done)

- New register 0x0018, the slot (2 bits). Register 0x0014 keeps its meaning
  (bit 0 save, bit 1 load, slot in bits 3:2); with bit 4 set the slot comes
  from 0x0018 instead, so a descriptor's actions are the fixed values 0x11
  (save) and 0x12 (load). The built-in driver's writes still work.
- Timeout: a counter running with `ssRunPending`; at 5 s of the 21.5 MHz
  system clock (107 M cycles) the requests and `ssRunPending` are cleared,
  as the driver's cancel write does.
- Slot validity (`SaveStateSlotScanner`, on the SDRAM mux's side port like
  the analyzer): a slot is valid when its header has the "SNES" magic at
  byte 8 and a size of at least 4 words at bytes 4..7. Scanned on
  `FILE_WRITE_END 2` (held busy; slots the file did not cover get their
  size and magic words zeroed, so a stale state cannot pass the core's own
  check either) and again after every save has finished. Exposed as status
  bits 14:11; the driver logs whether its own check agrees.
- `FILE_READ_START 2` answers `(last valid slot + 1) * 1 MiB`, 0 when none;
  the firmware then writes an empty `.ss`, exactly like today.
- Load of an empty slot: the request is ignored (no notification path).
  Save failures likewise go unreported; the status register still exposes
  `SS_SAVE_DONE` for debugging.

### 8. Miscellany (done)

- Color correction: the tables had no reset value. `ColorCorrection.setup`
  now takes `passThroughUntilLoaded`; the SNES passes colors through (5-bit
  channels expanded by bit replication) until the host writes a table.
- The module vblank IRQ: only the GB / GBA drivers enable it. Nothing to do.
- Every existing host register is kept so the built-in driver keeps working
  during the transition. The driver's `prepare_run` writes come *before*
  `SETUP_COMPLETE`, so the analyzer (item 2) must write the ROM registers at
  `FILE_WRITE_END` of the ROM, which is also before them, and the driver's
  identical values then land on top.

## Packaging

Directory `/sdcard/cores/Game-Bub.SNES/` (id must differ from the built-in
one while both exist; use `Community.SNES` or similar during the transition):

```
core.json
files.json
settings.json
snes_rev4.bit
```

`core.json`:

```json
{
  "metadata": { "id": "Community.SNES", "name": "Super Nintendo", "author": "Game Bub" },
  "bitstreams": [ { "target": "gamebub_rev4", "filename": "snes_rev4.bit" } ],
  "hardware": { "cartridge_enable": "no" }
}
```

`files.json` mirrors `Snes::get_core_info` (ids 0 ROM, 1 Save, 2 States;
addresses 0x30000000, 0x40000000, 0x31000000; `max_size` 16 MiB + 512, 256 KiB,
4 MiB; speeds 10000 KB/s; word sizes 32/16/32; the same optional /
dependent_on_0 / initialize flags). Check the exact JSON field names and the
`cartridge_enable` enum spelling against the serde derives in `info.rs`
before writing them.

`settings.json` (`mask` = the bits to keep, i.e. everything but the field;
values pre-shifted): Reset Core (action, 0x2000, value 1); Save State
(action, 0x0014, value 1); Load State (action, 0x0014, value 2); State Slot
(list, 0x0018, mask 0xFFFFFFFC, values 0..3); Region (list, 0x0000, mask
0xFFFF7FFE: Auto 0x8000, NTSC 0, PAL 1); Pseudo Transparency (checkbox,
0x0000, mask 0xFFFFFFFD, value 2); Memory Latency Hiding (checkbox, 0x0000,
mask 0xFFFFFFFB, value 4, default 4); Debug: ROM Miss Path (list, mask
0xFFFFE7FF, values 0x1800 / 0x800 / 0x1000 / 0); Debug: BSRAM Cache (list,
mask 0xFFFF9FFF, values 0 / 0x4000 / 0x2000).

Build: `build_core.py --name snes_rev4 ...` already produces `<name>.bit`
next to the `.hs`; the external package takes the uncompressed `.bit`
(about 2 MB, a second or two longer to program). Add a `build/release`
target that assembles the directory.

## Firmware changes

None required. Two small, upstreamable conveniences:

- Accept `.bit.hs` for external core bitstreams (`get_core` checks
  `ends_with(".bit")`; `program_fpga` already handles `.hs`).
- A longer `SETUP_TIMEOUT`, only if the post-setup fills cannot be hidden
  behind `StatusSetup` (they can; keep this as a fallback).

The built-in driver can stay until the external core is verified, then be
deleted along with its registry entries.

## What is lost

- Error messages for unsupported cartridges become a generic core error;
  truncated ROMs are not detected at all (the core runs on what arrived).
- No "state saved / failed / slot empty" notifications.
- ROM read-back verification and the stall-statistics log (registers remain).
- Settings are persisted (a behaviour change, arguably an improvement);
  "Debug" switches persist too, so consider dropping them from
  `settings.json` for the release package.
- SPI register access runs at the default clock ceiling.

## Verification

1. Chisel unit tests: header analyzer against the Rust port on a corpus of
   header windows (reuse `header.rs` test vectors; LoROM/HiROM/ExHiROM,
   copier header, every coprocessor, no-header ROM). Mirror table against
   `mirror_address` for the sizes that matter (1.5, 2.5, 3, 5, 6, 10, 12 MiB
   plus a few odd ones).
2. Elaboration + build for rev4; check `core_snes.xdc` timing on the ROM
   miss path after the table insertion.
3. Hardware, with the built-in driver disabled from mirroring/prepare_run
   first (register-compatible path), then as the external package:
   Chrono Trigger (HiROM), Super Mario World (LoROM), Yoshi's Island (GSU),
   Super Mario RPG (SA-1), Star Ocean (S-DD1), Mega Man X2 (CX4), a 2.5 MiB
   and a 6 MiB ROM for mirroring, a `.smc` with copier header, a PAL ROM
   with Region auto and forced, save states in every slot with power-off
   persistence, `.srm` size for a 2 KiB and a 32 KiB game.

## Sequencing

1. Items 1, 4, 5 and 8 (command words, config derivation, fills): small,
   self-contained, and the built-in driver keeps working. About a day.
2. Item 3 (mirror table) with the driver's mirroring switched off: exercises
   the miss path change alone. Half a day plus timing checks.
3. Item 2 (analyzer) with the driver's register writes switched off. The
   bulk of the work: two to three days including the tests.
4. Items 6 and 7 (save states in hardware). One day.
5. Packaging, hardware pass, delete the driver. One day.

## Open points to check before continuing

- Exact serde field names / enum spellings for the three JSON files.
- The SDRAM arbiter has two ports (host, core); adding the analyzer / DMA as
  a third low-priority port versus multiplexing it onto the host port while
  no host transfer is active (the SRAM fill took the second route).
- Timing after step 1: the host SRAM port now has a mux in front of it; check
  the routed timing summary of the next build.

Resolved: mask semantics, the `SETUP_COMPLETE` ordering, the color
correction and vblank defaults (see the status at the top).
