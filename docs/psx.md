# PlayStation core

A port of the MiSTer PSX core
([MiSTer-devel/PSX_MiSTer](https://github.com/MiSTer-devel/PSX_MiSTer), by
Robert Peip, GPLv2), running as the external core `jeremy.PSX`. The core is
vendored in `fpga/verilog/psx/` (its README lists the local changes), the glue
is in `fpga/src/main/scala/net/gamebub/core/psx/HandheldPsx.scala` and
`fpga/src/main/scala/psx/`, the descriptors in `fpga/cores/psx/`.

## Features

* Discs from the microSD card: `.cue` sheets with their `.bin` files, or a lone `.bin`
* Region and NTSC / PAL from the disc, or set by hand
* Memory cards: one per game (shared by its discs) and one shared by all games, as `.mcd`
  files; MiSTer's are compatible (rename its `.sav` to `.mcd`)
* Save states: four slots per disc (not compatible with MiSTer's)
* Disc swapping from the pause menu
* Digital, Analog and DualShock controllers, with rumble
* CRT look: scanlines, mask, soft horizontal blur
* TV output through the Game Bub Dock (720 x 480 at 4:3, with the CRT look as set)
* The dock's gamepads as full controllers (both sticks, L2 / R2, L3 / R3), the second one as player 2

## Not supported

* Rumble on the dock's gamepads (the dock has no way to pass it on); it is the handheld's own, off while docked
* 50 Hz TV output: PAL games run on the dock's 60 Hz output, every fifth frame shown twice
* `.iso`, CHD and libcrypt-protected discs (`.sbi`)
* Board revisions before 4 (the core uses both SDRAM chips)

## Installing

1. Flash the firmware from the same release (or this tree): the core needs it to read discs.
2. Copy `cores/jeremy.PSX/` to the SD card's `cores/`, from a release zip or built with
   `cd fpga && make psx package-psx` (into `fpga/build/package/cores/`).
3. Add a PlayStation BIOS image for your discs' region to `cores/jeremy.PSX/`:
   `bios_us.bin`, `bios_jp.bin` or `bios_eu.bin`.
4. Put discs (a `.cue` with its `.bin` files) anywhere under `roms/`.

## Controls

* Face buttons as on a PlayStation: Cross = B, Circle = A, Square = Y, Triangle = X
* L / R = L1 / R1; Select + L / Select + R = L2 / R2
* Dock gamepads: the first one plays on port 1 (with the handheld's buttons), the second on port 2, buttons by
  position as on the handheld; a pulled trigger counts as L2 / R2
* The sticks need the Controller setting at Analog or DualShock. A DualShock starts in digital mode, with the left
  stick as the D-pad, until the game switches it to analog, or L3 + R3 + Up does (L3 + R3 + Down switches back),
  as the Analog button would; on a Digital controller the left stick is the D-pad
* Select + Start is a DualShock's Analog button, on the handheld and on each dock gamepad: it toggles that controller
  between digital and analog mode (unless the game has locked the mode), and the game sees neither button. With L1 / R1
  / L2 / R2 already held, the game gets Select + Start instead (the soft reset many games have)
