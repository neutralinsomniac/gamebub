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
* Fast boot, dithering off

## Not supported

* HDMI output
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
