# SNES external core architecture

The SNES core ships as a standalone external core under `/sdcard/cores/jeremy.SNES/` (uncompressed bitstream plus `core.json`, `files.json`, and `settings.json`) with no SNES-specific code in the firmware. Cartridge detection, ROM mirroring, RAM initialization, and save-state management are all implemented in the FPGA glue (`fpga/src/main/scala/net/gamebub/core/snes/`).

## Firmware external-core interface

Reference behavior from `firmware/handheld/src/core/{mod,info,settings}.rs`:

* **Descriptors**: Loaded from `/sdcard/cores/<id>/` (the directory name must match `metadata.id` in `core.json`). Bitstream filenames must end in `.bit` (uncompressed; `.bit.hs` is rejected by `get_core`).
* **File transfers**: Files transfer directly to the address in `files.json` at the configured `max_transfer_speed`, always in 32-bit words. Optional files with `initialize: true` that are missing on the SD card are filled with `0xFF` up to `max_size`.
* **File commands**:
  * Before upload: `FILE_WRITE_START <id>`
  * After upload: `FILE_WRITE_END <id> <size> 0`
  * On exit/save: `FILE_READ_START <id>`; the core returns the byte count to write back in command word 0, followed by `FILE_READ_END <id>`
* **Settings**: Persisted per core under `/sdcard/settings/<id>.json` and applied before `SETUP_COMPLETE`. Each setting writes `(read & mask) | value`, where `mask` specifies the bits to *preserve* (the inverse of the field mask) and `value` is pre-shifted. Action settings can optionally declare `resume_after` and a `result` register to display status notifications.
* **Optional extensions**: `verify: true` on a file entry runs an FNV-1a read-back check over SPI after transfer. `statistics` in `core.json` lists extra registers logged and cleared on pause. Firmware versions that do not recognize these keys ignore them.
* **Command timeouts**:
  * `NOTIFY_TIMEOUT` (10 ms): `FILE_WRITE_END` and `SETUP_COMPLETE`
  * `SETUP_TIMEOUT` (100 ms): `FILE_WRITE_START`, `FILE_READ_START`, and post-setup polling until the core reports `CoreHalt`

## Command handling in `HandheldSnes`

`HandheldSnes` latches all four host command words (`regCommandHost`) and uses a `busy` state while internal FSMs run, transitioning to `done` or `error` on completion.

| Command | Hardware action |
| --- | --- |
| `FILE_WRITE_START 0` (ROM) | Starts a background 256 KiB `0xFF` fill of BSRAM (`SramFill`) and returns immediately |
| `FILE_WRITE_END 0 <size>` | Latches `romFileSize`, runs `RomHeaderAnalyzer`, then builds `RomMirrorTable` (holds busy, < 1 ms) |
| `FILE_WRITE_START 1/2` | Waits if `SramFill` is still running so `.srm` transfers never overlap the BSRAM fill |
| `FILE_WRITE_END 1 <size>` | Latches `saveLoadedSize` (`SramFill` already filled any unwritten tail with `0xFF`) |
| `FILE_WRITE_END 2 <size>` | Latches `statesLoadedSize`, zeros headers of slots beyond `<size>`, and scans slot validity |
| `SETUP_COMPLETE` | Returns `error` if the cartridge is unsupported or > 16 MiB; otherwise copies `savestates.bin` to SDRAM, queues the 128 KiB WRAM fill, writes derived core registers, and keeps `GET_STATUS` in `StatusSetup` until fills finish |
| `FILE_READ_START 1` (Save) | Returns `min(1 KiB << ramSizeCode, 256 KiB)` in command word 0 (0 if `ramSizeCode == 0`) |
| `FILE_READ_START 2` (States) | Returns `(last_valid_slot + 1) * 1 MiB` in command word 0 (0 if no slots are valid) |
| `FILE_READ_END` | No-op |

## Hardware subsystems

### `RomHeaderAnalyzer`

A hardware port of MiSTer's `Main_MiSTer/support/snes/snes.cpp` header scoring logic. Runs on `FILE_WRITE_END 0`, reading SDRAM through the save-state side of `PipelineMemoryLowPriorityMux` (~70 single-word reads, a few thousand cycles).

1. Computes `romOffset = 512` if `fileSize & 512` (copier header), `romSize = fileSize - romOffset`, and `romSizeCode` from the highest set bit of `romSize - 1`.
2. Scores candidate headers at `0x7FC0`, `0xFFC0`, and `0x40FFC0` (when within `romSize`) by checking the reset vector (`>= 0x8000`), reset opcode plausibility, mapper byte, checksum/complement, and ROM size code. Applies the standard +4 tie-breaker for non-zero ExHiROM scores.
3. Checks `0x7FC0` for BS-X BIOS / competition-cart signatures and the first 14 bytes for `"BANDAI SFC-ADX"`.
4. Derives `romType`, `ramSizeCode`, `headerPal`, `romMask`, and `ramMask` from `(mapper, romTypeByte, company, headerAddr)`. Falls back to LoROM, 0 RAM, and NTSC if no header scores positively.
5. Sets `unsupported` for SPC7110, BS-X, Sufami Turbo, competition carts (CC92/PF94), unknown mappers, ROM sizes not a multiple of 32 KiB, or padded ROM sizes exceeding 16 MiB (including 16 MiB + 512-byte copier header, which would collide with `0xFF0000`).

Outputs are written to the core registers and exposed read-only at register `0x0030` for inspection. Registers `0x0004-0x0010` remain writable over SPI for debugging.

### `RomMirrorTable`

Replaces host-side ROM duplication with address translation on cache misses (`romCache.io.out` to `PipelineMemoryLowPriorityMux`). Because translation sits behind the cache, cache tags remain in the core's address space and hit paths are unaffected.

The table has 512 entries in distributed RAM indexed by `addr[23:15]` (each entry covers a 32 KiB block, storing a 9-bit file block index and an in-range flag). Built at `FILE_WRITE_END 0` (~1,000 cycles) using the standard iterative power-of-two mirroring rule. Translated addresses are:

```text
{ table[addr[23:15]] + carry, (addr[14:0] + romOffset)[14:0] }
```

where `carry` is the 15-bit addition's carry-out. Blocks outside the padded ROM (such as the save-state program at `0xFF0000`) and bit 24 (save-state slots at `0x1000000`) pass through unmodified.

### Config register derivation (`0x0000`)

`configReg` writes take effect live:
* **Bit 2**: Automatically expands to the safe latency-hiding policy for `romTypeReg` (GSU: RAM hiding only; SA-1: ROM + BW-RAM hiding; CX4: conservative; others: base latency hiding). Explicit override bits (7, 8, 9, 10) remain usable over SPI.
* **Bit 15 (`regionAuto`)**: Uses `headerPal` from `RomHeaderAnalyzer` when set, or bit 0 (0 = NTSC, 1 = PAL) when clear.

### `SramFill`

Multiplexed onto the SRAM arbiter's host port while active (avoiding a fourth arbiter port on the critical path to SRAM). Writes at 3 cycles per 16-bit word:
* **BSRAM**: 256 KiB of `0xFFFF` (~18 ms at 21.477 MHz), run in the background during ROM upload.
* **WRAM**: 128 KiB of the MiSTer power-on pattern (`((addr >> 8) ^ (addr >> 2)) & 1 ? 0x66 : 0x99` per byte, ~9 ms), run after `SETUP_COMPLETE`. Warm resets (`0x2000`) do not re-fill WRAM.

### `SramHostAdapter`

Because external core file transfers are always 32-bit while the external SRAM is 16 bits wide, `SramHostAdapter` splits each 32-bit host read/write on the `0x40000000` window into two 16-bit SRAM accesses.

### Save-state management

* **`SaveStateProgramLoader`**: Embeds `snes_savestates.bin` (3,558 bytes) in a `VecInit` ROM and writes 890 words to SDRAM `0xFF0000` after `SETUP_COMPLETE`. Skipped (and `SS_AVAIL` cleared) if the padded ROM reaches `0xFF0000`.
* **Control registers**: `0x0018` stores the 2-bit slot index. Writing `0x11` (save) or `0x12` (load) to `0x0014` triggers a request using the slot from `0x0018` (bit 4 selects `0x0018`; bits 3:2 still work for direct SPI writes). Register `0x001C` reports the outcome (`1` = done, `2` = empty slot, `3` = timed out, `4` = unavailable) for firmware notifications.
* **Timeout**: A 5-second counter (~107M system clock cycles) cancels any request the game fails to service (e.g. interrupts disabled) and asserts `SS_CANCEL` to disarm `savestates.sv`.
* **`SaveStateSlotScanner`**: Runs on `FILE_WRITE_END 2` (zeroing headers of slots beyond `statesLoadedSize`) and after every save. Checks each slot for the `SNES` magic at byte 8 and a word size >= 4 at bytes 4-7, exposing valid slots in status bits `14:11`. Loads of empty slots are rejected immediately.

## Packaging and descriptors

Run from `fpga/`:

```sh
python3 scripts/package_core.py --name snes --build-root build/snes --out <dir>
```

This copies `fpga/cores/snes/{core,files,settings}.json` and the uncompressed `build/snes/snes.bit` (as `snes_rev4.bit`) into `<dir>/cores/jeremy.SNES/`.

### Files (`fpga/cores/snes/files.json`)

| ID | Label | Extensions | Address | Max size | Flags |
| --- | --- | --- | --- | --- | --- |
| 0 | ROM | `.sfc`, `.smc` | `0x30000000` | 16 MiB + 512 B | `read_only`, `user_selected`, `verify` |
| 1 | Save | `.srm` | `0x40000000` | 256 KiB | `optional`, `dependent_on_0`, `initialize` |
| 2 | States | `.ss` | `0x31000000` | 4 MiB | `optional`, `dependent_on_0`, `initialize` |

All transfers use `max_transfer_speed: 10000` (10 MB/s).

### Settings (`fpga/cores/snes/settings.json`)

| Setting | Type | Register | Mask (kept bits) | Values |
| --- | --- | --- | --- | --- |
| Reset Core | Action | `0x2000` | — | `1` (`resume_after`) |
| Save State | Action | `0x0014` | — | `0x11` (`resume_after`, result at `0x001C`) |
| Load State | Action | `0x0014` | — | `0x12` (`resume_after`, result at `0x001C`) |
| State Slot | List | `0x0018` | `0xFFFFFFFC` | `0`..`3` (Slots 1-4) |
| Region | List | `0x0000` | `0xFFFF7FFE` | `0x8000` (Auto), `0` (NTSC), `1` (PAL) |
| Pseudo Transparency | Checkbox | `0x0000` | `0xFFFFFFFD` | `2` |
| Aspect Ratio | List | `0x002C` | `0xFFFFFFFC` | `0` (4:3), `3` (4:3 Integer), `1` (8:7), `2` (8:7 Integer) |
| Interpolation | List | `0x0028` | `0xFFFFFFDF` | `0` (Nearest), `0x20` (Bilinear) |
| Scanlines | List | `0x0028` | `0xFFFFFFFC` | `0` (Off), `1` (50%), `2` (75%), `3` (100%) |
| CRT Mask | List | `0x0028` | `0xFFFFFFF3` | `0` (Off), `4` (25%), `8` (50%), `12` (75%) |
| Mask Type | List | `0x0028` | `0xFFFFFFEF` | `0` (RGB Stripes), `0x10` (Magenta/Green) |

Debug switches (ROM miss path, BSRAM cache) are intentionally omitted from `settings.json` because external core settings persist across sessions.
