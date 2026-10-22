# Game Bub handheld firmware {VER} + SNES and PlayStation cores (build {HASH})

Pre-built firmware and FPGA bitstreams for the **Game Bub handheld, board revision 4**.
Nothing needs to be compiled. The SNES and PlayStation cores are experimental.

Source: https://github.com/neutralinsomniac/gamebub (branch `psx`, commit {HASH}).

## What is in this package

| File | What it is |
| --- | --- |
| `gamebub-handheld-rev4-{VER}.uf2` | MCU firmware plus the built-in Game Boy / GBA / boot bitstreams and free BIOS replacements. Flashed over USB. |
| `sdcard/cores/jeremy.SNES/` | The SNES core: its bitstream (`snes_rev4.bit`) and the three descriptor files the firmware reads (`core.json`, `files.json`, `settings.json`). It runs as an "external core" from the microSD card. |
| `sdcard/cores/jeremy.PSX/` | The PlayStation core: its bitstream (`psx_rev4.bit`), its descriptor files, and `blank.mcd` (a formatted, empty memory card). No BIOS: you supply it (see below). |
| `SHA256SUMS` | Checksums of the files above. |

## 1. Flash the firmware (UF2)

1. Turn the Game Bub off.
2. Hold the **Home** button (centre) and press the power button. Keep holding Home until the
   device shows up on your computer as a USB drive named **GAME BUB** (it contains `INFO_UF2.txt`).
3. Copy `gamebub-handheld-rev4-{VER}.uf2` onto that drive.
4. Wait for the copy to finish. The device reboots itself into the new firmware.

The bootloader, the DFU updater and your settings/saves are not touched. The UF2 is tagged for
board revision 4 and is ignored by other revisions.

## 2. Update the microSD card

1. Put the card in a computer.
2. In `system/`, **delete any old `*.bit.hs` files**, including `snes.bit.hs` from earlier
   SNES packages. Bitstreams from older firmware are incompatible with this firmware: the boot
   screen appears but the menu never does.
3. Copy the whole `sdcard/cores` directory to the root of the card, so that you have
   `cores/jeremy.SNES/` and `cores/jeremy.PSX/`. When updating, let the new files replace
   the old ones; your own files in `cores/jeremy.PSX/` (BIOS images, `card2.mcd`) stay.
   Delete any `cores/Test.SNES/` left from a test build.
4. **PlayStation BIOS** (required for the PlayStation core, not included): put at least one
   PlayStation BIOS image (512 KiB) in `cores/jeremy.PSX/`, named by region: `bios_us.bin`
   (e.g. SCPH-1001), `bios_jp.bin`, `bios_eu.bin`. A disc from another region needs that
   region's BIOS.
5. If you use real Game Boy / GBA BIOS files, keep them in `system/` as before
   (`gameboy.bios-dmg.bin`, `gameboy.bios-cgb.bin`, `gba.bios.bin`). Free replacements are
   built into the firmware, so this is optional.
6. Put SNES ROMs (`.sfc` / `.smc`) and PlayStation discs (`.cue` with its `.bin` files)
   anywhere under `roms/`.

Final layout:

```
cores/
  jeremy.SNES/
    core.json
    files.json
    settings.json
    snes_rev4.bit
  jeremy.PSX/
    blank.mcd
    core.json
    files.json
    settings.json
    psx_rev4.bit
    bios_us.bin          (yours; and/or bios_jp.bin, bios_eu.bin)
system/
  (gameboy.bios-dmg.bin, gameboy.bios-cgb.bin, gba.bios.bin - optional)
roms/
  ...
```

## SNES core notes

* "Super Nintendo" appears in the core list once the card is in. Saves (`.srm`) are written
  next to the ROM.
* Save states: pause menu (Home) > Settings > Save State / Load State, four slots (State Slot);
  a notification says how it went. They are kept in `<rom>.ss` next to the ROM, written when
  you exit the core or power off.
* Settings (pause menu > Settings): Region (auto / NTSC / PAL), Pseudo Transparency, Memory
  Latency Hiding, Reset Core. They are remembered across runs.
* Supported enhancement chips: SA-1, Super FX (GSU), CX4, DSP-1/2/3/4, OBC-1 and S-DD1.
  SPC7110, Satellaview, Sufami Turbo and competition cartridges are not supported: loading
  one fails with a core error.
* Game speed is 100 % on plain cartridges; Super FX games run slightly below full speed.
* PAL games run at 50 Hz on revision 4 screens.

## PlayStation core notes

* "PlayStation" appears in the core list once the card is in. Starting it asks for a disc: a
  `.cue` sheet (with its `.bin` files, one for the whole disc or one per track) or a lone
  `.bin`, of raw 2352-byte sectors (the usual rips). `.iso`, CHD and discs that need libcrypt
  data (`.sbi`) are not supported.
* The region (and with it the BIOS and NTSC / PAL) is taken from the disc unless set in the
  settings.
* Controls: the face buttons are where the PlayStation's are (Cross = B, Circle = A,
  Square = Y, Triangle = X), L / R are L1 / R1, Select + L / Select + R are L2 / R2. The
  Controller setting picks Digital, Analog or DualShock (rumble uses the vibration motor).
* Memory cards: slot 1 is the game's own card, `<game>.mcd` next to the `.cue`, shared by all
  of a game's discs (a " (Disc N)" in the name is left out); slot 2 is one card for every
  game, `cores/jeremy.PSX/card2.mcd`. Both are raw 128 KiB images (`.mcd`, `.mcr`, or a
  MiSTer `.sav` renamed to `.mcd`). A missing card starts out empty. Cards are written to the
  SD card when the pause menu opens and when the core exits, if the game changed them.
* Save states: pause menu > Settings > Save State / Load State, four slots (State Slot). Each
  is a file next to the `.cue`, named after the disc (`<disc>.ss1` to `.ss4`, 4 MiB each),
  written right after Save State. They are not compatible with MiSTer's save states.
* Games on several discs: pause menu > Change Disc, then choose the next disc. The game goes
  on; the memory cards stay, and the save states are the new disc's.
* CRT look (pause menu > Settings): Scanlines (off / 50 / 75 / 100 %, default 75 %), CRT Mask
  (off / 25 / 50 / 75 %, default 25 %), Mask Type (RGB stripes or magenta / green, default
  magenta / green) and Sharpness (sharp, or soft like a composite TV). High-resolution
  (480-line) screens get no scanlines.
* Other settings: Video (auto / NTSC / PAL), Reset Core.
* Not supported yet: HDMI output (the picture stays black on HDMI).

## Going back

Flash a previous UF2 the same way; its built-in bitstreams come with it. Make sure no
`*.bit.hs` from another firmware is left in `system/` on the SD card (they override the
built-in ones). The `cores/` directory is ignored by firmware older than 1.1, and the
PlayStation core needs this firmware (it serves the disc reads).
