# Game Bub handheld firmware {VER} + SNES core (build {HASH})

Pre-built firmware and FPGA bitstreams for the **Game Bub handheld, board revision 4**.
Nothing needs to be compiled. The SNES core is experimental.

Source: https://github.com/neutralinsomniac/gamebub (branch `snes`, commit {HASH}).

## What is in this package

| File | What it is |
| --- | --- |
| `gamebub-handheld-rev4-{VER}.uf2` | MCU firmware plus the built-in Game Boy / GBA / boot bitstreams and free BIOS replacements. Flashed over USB. |
| `sdcard/cores/jeremy.SNES/` | The SNES core: its bitstream (`snes_rev4.bit`) and the three descriptor files the firmware reads (`core.json`, `files.json`, `settings.json`). It runs as an "external core" from the microSD card. |
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
   `cores/jeremy.SNES/` with four files in it. Delete any `cores/Test.SNES/` left from a test
   build.
4. If you use real Game Boy / GBA BIOS files, keep them in `system/` as before
   (`gameboy.bios-dmg.bin`, `gameboy.bios-cgb.bin`, `gba.bios.bin`). Free replacements are
   built into the firmware, so this is optional.
5. Put SNES ROMs (`.sfc` / `.smc`) anywhere under `roms/`.

Final layout:

```
cores/
  jeremy.SNES/
    core.json
    files.json
    settings.json
    snes_rev4.bit
system/
  (gameboy.bios-dmg.bin, gameboy.bios-cgb.bin, gba.bios.bin - optional)
roms/
  ...
```

## SNES core notes

* "Super Nintendo" appears in the core list once the card is in. Saves (`.srm`) are written
  next to the ROM.
* Save states: pause menu (Home) > Settings > Save State / Load State, four slots (State Slot).
  They are kept in `<rom>.ss` next to the ROM, written when you exit the core or power off.
* Settings (pause menu > Settings): Region (auto / NTSC / PAL), Pseudo Transparency, Memory
  Latency Hiding, Reset Core. They are remembered across runs.
* Supported enhancement chips: SA-1, Super FX (GSU), CX4, DSP-1/2/3/4, OBC-1 and S-DD1.
  SPC7110, Satellaview, Sufami Turbo and competition cartridges are not supported: loading
  one fails with a core error.
* Game speed is 100 % on plain cartridges; Super FX games run slightly below full speed.
* PAL games run at 50 Hz on revision 4 screens.

## Going back

Flash a previous UF2 the same way; its built-in bitstreams come with it. Make sure no
`*.bit.hs` from another firmware is left in `system/` on the SD card (they override the
built-in ones). The `cores/` directory is ignored by firmware older than 1.1.
