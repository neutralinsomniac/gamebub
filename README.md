<p align="center">
  <a href="https://gamebub.net/">
    <img src="./assets/logo.svg" width="480" alt="Game Bub logo">
  </a>
</p>

[![Game Bub trailer](./assets/video-poster.jpg)](https://www.youtube.com/watch?v=f16E5J6qljw)

**Game Bub** is an open-source FPGA retro emulation handheld, with support for Game Boy, Game Boy Color, and Game Boy Advance games.

You can buy your own, prebuilt Game Bub from **[Crowd Supply](https://www.crowdsupply.com/second-bedroom/game-bub)**!

You can find the [user guide and documentation here](https://docs.gamebub.net/).

Want to chat about Game Bub? Feel free to [join the Discord](https://discord.gg/T5xrYpMfN7).

Check out the [announcement blog post](https://eli.lipsitz.net/posts/introducing-gamebub/) for an in-depth look at the development process!


## Features
* Play physical Game Boy / Color / Advance cartridges
* Load and play ROM files from a microSD card (with built-in support for rumble, clock, accelerometer, gyroscope)
* Multiplayer link cable functionality
* Custom, from-scratch Game Boy and Game Boy Advance FPGA cores with great game compatibility
* Experimental SNES core, ported from [MiSTer](https://github.com/MiSTer-devel/SNES_MiSTer) (see [docs/snes.md](docs/snes.md))
* Experimental PlayStation core, ported from [MiSTer](https://github.com/MiSTer-devel/PSX_MiSTer) (see [docs/psx.md](docs/psx.md))
* 14+ hour battery life 
* Video output to TV or monitor via Game Bub Dock
* Extensible hardware, designed for future improvements

## Additional Cores

This fork adds two external cores for revision 4 hardware. Both work on stock firmware (`v1.1.0-beta.5` or newer) as well as this repo's firmware:

* **SNES (`jeremy.SNES`)**: MiSTer SNES port with Super FX, SA-1, CX4, DSP-1/2/3/4, and S-DD1 support, save states, and CRT filter — see [docs/snes.md](docs/snes.md) (and [docs/snes-external-core.md](docs/snes-external-core.md) for the external-core interface).
* **PlayStation (`jeremy.PSX`)**: MiSTer PlayStation port with on-demand `.cue`/`.bin` disc reading, memory cards, save states, CRT filter, and dock/gamepad support — see [docs/psx.md](docs/psx.md).

## Firmware Modifications

Compared to upstream `v1.1-beta` firmware, this repo's firmware adds:

* **Larger SD transfer buffer**: Enlarges the file-transfer scratch buffer from 16 KiB to 64 KiB for faster large microSD reads and writes on FAT32 cards with 32 KiB clusters (word-/sector-aligned SDMMC DMA, in-place save overwrites, and the larger FatFs fast-seek cluster map have been merged into upstream `v1.1-beta`).
* **Multi-disc games**: Shares saves and memory cards across discs of the same game (for example, `Game (USA) (Disc 2).cue` uses `Game (USA).mcd`, while per-disc files like save states stay tied to each disc) and adds pause-menu disc swapping (**Change Disc**).
* **Save-on-pause and blank save templates**: Saves memory cards and save states on pause and immediately after Save State (stock firmware writes them on exit), leaves unchanged files untouched when a core reports 0 bytes to save instead of truncating them, and loads pre-formatted blank templates (such as a formatted PlayStation memory card) for missing or empty saves.
* **Pause-menu action feedback**: Automatically closes the pause menu after actions such as Load State or Reset, and polls core result registers to display status notifications.
* **Diagnostics**: Logs core run/stall statistics and free heap on pause, logs SD card bus and FAT cluster size at mount, records failed allocations in crash reports, and supports optional post-load FNV-1a read-back verification and pause-time memory dumps in core descriptors.

## Building

Building a Game Bub handheld requires manufacturing PCBs, 3D printing the shell and buttons, and assembling components from a variety of sources. For information on manufacturing and assembling your own, see [here](https://github.com/elipsitz/gamebub/blob/v0.1/docs/building.md). Note that this information is for the older, vertical revision 2. Updated building information will be available in the Game Bub Docs soon.

For other inquiries, contact us at support@gamebub.net.

## Architecture

For an in-depth description of the project architecture, as of revision 2, see [here](https://github.com/elipsitz/gamebub/blob/v0.1/docs/architecture.md).

A Nix flake (`flake.nix`) provides the complete FPGA and firmware toolchain: `nix develop`.

The Game Bub handheld consists of a Xilinx XC7A100T FPGA to do the main emulation and I/O, and an ESP32-S3 microcontroller to do auxiliary tasks (configuring the FPGA, rendering the UI, loading ROM files from a microSD card and sending it to the FPGA).

### Directory Structure

* `fpga`: FPGA source code (HDL), written in [Chisel](https://github.com/chipsalliance/chisel)
* `firmware`: Microcontroller firmware

## License

Unless otherwise specified:

* Firmware (in `firmware/`) and scripts (in `scripts/`) are licensed under GPLv3 (`GPL-3.0-only`).
* FPGA source code (in `fpga/`) is licensed under the CERN Open Hardware License Version 2 - Strongly Reciprocal (`CERN-OHL-S-2.0`)
* PCB (schematic and layout), mechanical, and hardware design files are licensed under the CERN Open Hardware License Version 2 - Strongly Reciprocal (`CERN-OHL-S-2.0`)

At a high level, this means that you can copy, share, and modify the source code, as long as you provide proper attribution and share your source code / design files with the same license. However, the "Game Bub" name and logo are trademarked, and you may not use them for your product without permission.
