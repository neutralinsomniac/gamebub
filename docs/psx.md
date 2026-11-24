# PlayStation core

Port of the MiSTer PlayStation core ([MiSTer-devel/PSX_MiSTer](https://github.com/MiSTer-devel/PSX_MiSTer), by Robert Peip, GPLv2), packaged as the external core `jeremy.PSX`.

* Vendored RTL: `fpga/verilog/psx/` (see its README for local patches)
* Chisel glue: `fpga/src/main/scala/net/gamebub/core/psx/HandheldPsx.scala` and `fpga/src/main/scala/psx/`
* RISC-V disc reader: `fpga/cpu/psx-disc/`
* Core descriptors: `fpga/cores/psx/`

## Features

* Disc images from the microSD card (`.cue`/`.bin` pair or standalone `.bin`), read directly by the core using the firmware's on-demand file support (works on stock firmware v1.1.0-beta.5 or later)
* Automatic or manual region and NTSC/PAL selection
* Two memory cards stored as `.mcd` files: one per game (shared across multi-disc games with this repo's firmware) and one shared across all games; compatible with MiSTer memory cards (rename `.sav` to `.mcd`). Missing cards are created blank (on stock firmware, new cards start unformatted; format them in the BIOS menu or in-game)
* Four save state slots per disc (not compatible with MiSTer save states)
* Disc swapping from the pause menu (requires this repo's firmware)
* Digital, Analog, and DualShock controller modes, with handheld rumble
* CRT filter (scanlines, aperture-grille/shadow mask, soft horizontal blur)
* TV output via the Game Bub Dock (720x480 at 4:3, with the configured CRT filter)
* Full dock gamepad support (dual analog sticks, L2/R2, L3/R3), with a second gamepad mapped to player 2

## Limitations

* No rumble on dock gamepads (the dock cannot send rumble back to controllers); rumble only works on the handheld and is disabled while docked
* No 50 Hz TV output: PAL games run on the dock's 60 Hz output by duplicating every fifth frame
* Multi-bin cue sheets (such as Redump dumps with one `.bin` per track): the core can only open a `.cue` and `.bin` sharing the same base filename. Merge tracks into a single `.bin` (for example with `binmerge`), or launch track 1's `.bin` directly (without CD audio)
* No `.iso`, `.chd`, or libcrypt-protected discs (`.sbi`)
* Requires board revision 4 or newer (the core uses both SDRAM chips)

## Installing

1. Use firmware with on-demand file support (stock firmware v1.1.0-beta.5 or newer, or this repo's firmware). Compared to stock firmware, this repo's firmware adds:
   * Faster microSD card reads and writes (larger word-aligned SD transfers and a larger FatFs fast-seek cluster map for fragmented disc images)
   * Shared memory cards across multi-disc games (`Game (USA) (Disc 2).cue` uses `Game (USA).mcd`; stock firmware creates one card per disc)
   * Pause-menu disc swapping
   * Saving memory cards and save states on pause and immediately after Save State (stock firmware writes them on exit)
   * Core statistics logging
2. Copy `jeremy.PSX/` into `cores/` on your microSD card (from `jeremy.PSX-<ver>.zip` in a release, or build it with `cd fpga && make psx package-psx`, which writes `fpga/build/package/cores/jeremy.PSX/`).
3. Place PlayStation BIOS images for your disc regions in `cores/jeremy.PSX/`: `bios_us.bin`, `bios_jp.bin`, or `bios_eu.bin`.
4. Put disc images anywhere under `roms/`: either a matching `.cue`/`.bin` pair (`Game.cue` and `Game.bin`) or a standalone `.bin`. You can launch either file.

## Controls

* Face buttons match the PlayStation layout: Cross = B, Circle = A, Square = Y, Triangle = X.
* L/R map to L1/R1; Select + L and Select + R map to L2/R2.
* Dock gamepads use the same physical button positions as the handheld, with analog triggers mapped to L2/R2. When docked, gamepad 1 controls port 1 (replacing the handheld buttons) and gamepad 2 controls port 2.
* Analog sticks require the Controller setting to be set to **Analog** or **DualShock**. In **Digital** mode, the left stick acts as the D-pad.
* A **DualShock** controller starts in digital mode (left stick mapped to the D-pad) until the game enables analog mode or you toggle it manually.
* Press **Select + Start** (on the handheld or a dock gamepad) to toggle between digital and analog mode, acting as the DualShock Analog button unless the game has locked the mode. On dock gamepads, **L3 + R3 + Up** also enables analog mode and **L3 + R3 + Down** switches back. When Select + Start triggers the toggle, neither button is sent to the game; hold any shoulder button (L1, R1, L2, or R2) first to pass Select + Start through to the game (for soft resets).
