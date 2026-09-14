# Building

Building a Game Bub unit is a fairly involved process:

1. Manufacturing the PCB
2. Assembling the PCB
3. Printing the shell (and buttons)
4. Assembling the whole device
5. Building the FPGA bitstreams
6. Setting up the microSD card
7. Flashing the MCU firmware

## 1. Manufacturing the PCB

Fab the PCB (in `/pcb/handheld_rev2`) using your manufacturer of choice. The board was initially designed to be fabricated and assembled by JLCPCB.

The board has 6 layers, and rectangular dimensions of 90.1 mm by 135 mm. Due to the fine pitch (0.8mm) BGA FPGA package, the smallest vias are 0.4/0.25mm, with a minimum track width of 0.1mm. The board must have an ENIG finish, as HASL is too uneven for fine-pitch BGA components. 

The board should have a thickness of 1.6mm -- this isn't essential, but the enclosure was designed for this thickness. Only the JLCPCB default 6-layer `JLC06161H-3313` stackup has been tested, but other stackups are likely to work too.

## 2. Assembling the PCB

The board is primarily one-sided, and the vast majority of the components can be machine assembled. The board design files have LCSC component IDs (for easy JLCPCB assembly).

The remaining components are specialty or otherwise difficult to machine assemble:

Front:
* 3.5mm headphone jack: Same Sky SJ-43614-SMT-TR
* Rumble motor: generic 10mm diameter rumble motor
* Optional PMOD connector: 6-pin female 0.1 inch right angle SMD header

Rear:
* Generic GBA cartridge slot
* Generic GBA link port
* Cartridge slot shape detector: JJOV0UL650NONPRBK
* Battery connector: S2B-PH-SM4-TB (or generic 2-pin SMD JST-PH receptacle)
* Shoulder (L and R) buttons: C&K PTS645VN13SMTR92LFS (x2)

Solder each of these components (see the PCB design files if needed).

## 3. Printing the shell

The shell consists of two pieces: the front and rear. Both have a wall thickness of 1.5mm, with some small features of 1.0mm or 0.8mm.

FDM (filament) printing is almost certainly not suitable: this should be printed with a high-precision technology such as SLA (photosensitive resin).

Additionally, all of the buttons are custom and need to be printed as well:
* 4x Large front buttons (A, B, X, and Y)
* D-pad
* 3x Small front buttons (Start, Select, Home)
* 3x Side buttons

## 4. Assembling the whole device

### Parts needed
* ER-TFT035IPS-6 LCD module
* LCD Cover glass (tempered glass or transparent acrylic)
* 2x Speakers (CMS-160903-18S-X8)
* 9x M2.5x4mm heat-set inserts
* CA glue to glue the inserts
* A set of Nintendo DSi button membranes (ABXY and D-pad)
* M2.5 screws:
    * 5x 5mm (three PCB screws, 2 top screws)
    * 4x 14mm (middle and bottom screws)
* A set of torsion springs (1x left, 1x right):
    * Required: >1.5mm interior diameter
    * Suggested: 0.3mm wire diameter, 5 turns, 135 degree angle
* 2x 1.5mm diameter dowel pins, 8mm to 9mm long.
* 755068 size flat Lipo battery with a JST-PH connector (verify polarity)

### Steps
Before assembling, consider testing that the PCB works properly.

1. Attach the LCD cover glass to the front shell with adhesive glue or tape
2. Place the front shell face down on a surface
3. Carefully align and press the LCD module onto the cover glass (ensure flex is facing the correct direction)
4. Glue all 9 heat-set inserts into place, and let the glue harden
5. Insert the speakers into the shell
6. Place the face buttons into the shell, followed by the D-pad and ABXY membranes
7. Open the LCD flex connector on the PCB
8. Carefully set the PCB face down onto the shell, putting the LCD module flex into place on the PCB connector, and close it.
9. Ensure the PCB is aligned well onto the front shell
8. Use the 5mm screws to attach the PCB to the front shell (triangle of screw holes in the middle, towards the bottom half of the PCB). Tighten carefully.
9. Insert the three side buttons into the front shell.
10. Attach the battery to the rear shell with adhesive
11. Assemble the shoulder buttons: align each shoulder button with the rear shell. Place the correct spring in line (inserting one end of the spring into the button), and then insert a dowel pin to hold the assembly together. Hook the other end of the spring into the hook in the rear shell.
12. Plug the battery into the battery connector on the PCB
13. Place the rear shell onto the front shell
14. Use 6 screws (5mm on top, 14mm on the middle and bottom) to close up the shell

## 5. Building the FPGA bitstreams

1. Install Xilinx Vivado with support for Artix-7 devices (2023.2 or newer). Either install it
   yourself and point `XILINX_VIVADO` at it, or let Nix package it (see below).
2. Install the remaining tools. The easiest way is the Nix flake in the repository root,
   which provides the JDK, [mill](https://mill-build.org/), Python with edalize and
   heatshrink2, Verilator and GHDL:

   ```sh
   $ nix develop          # `nix develop .#fhs` on NixOS if you use your own Vivado install
   ```

   Without Nix, install JDK 17+, Python 3 with `edalize` and `heatshrink2`, and make sure
   `vivado` is on your `PATH` (the `fpga/mill` launcher downloads mill itself).

### Packaging Vivado with Nix (optional)

The flake can build a Vivado package (via [xilinx-nix-utils](https://github.com/DLR-FT/xilinx-nix-utils))
from AMD's offline installer, which you have to download yourself:

1. Download the *Unified Installer SDI* tar for the version listed in `nix/vivado.nix`
   from the [AMD downloads page](https://www.amd.com/en/support/downloads/adaptive-socs-and-fpgas/development-tools.html)
   (requires an AMD account).
2. Add it to the Nix store and check that its hash matches `nix/vivado.nix`:

   ```sh
   $ nix store add-file --name FPGAs_AdaptiveSoCs_Unified_SDI_2025.1_0530_0145.tar ~/Downloads/FPGAs_AdaptiveSoCs_Unified_SDI_2025.1_0530_0145.tar
   $ nix hash file ~/Downloads/FPGAs_AdaptiveSoCs_Unified_SDI_2025.1_0530_0145.tar
   ```

   To use a different installer version, update `version`, `name` and `hash` in `nix/vivado.nix`;
   if the installer rejects `nix/vivado-install-config.txt`, regenerate a template with
   `nix build .#vivado-install-config-template` and copy the relevant lines.
3. Enter the shell that includes Vivado (the first time this runs the installer inside the
   sandbox, which takes a while and needs ~100 GB of scratch space to unpack the tar; pass
   `--extra-sandbox-paths /dev/fuse` to mount it instead of unpacking):

   ```sh
   $ nix develop .#vivado
   ```

Only Vivado ML Standard with Artix-7 device support is installed (`nix/vivado-install-config.txt`,
generated from the installer's own template); it still takes about 60 GB in the Nix store and
~10 minutes to install.
The wrapped `vivado` runs inside an FHS environment, so this also works on NixOS.

From the `/fpga` directory, build each bitstream with `scripts/build_core.py` (replace
`gamebub_rev4` with your board revision):

```sh
$ python3 scripts/build_core.py --target gamebub_rev4 --name boot    --core-class net.gamebub.core.boot.HandheldBoot    --build-root build/boot
$ python3 scripts/build_core.py --target gamebub_rev4 --name gameboy --core-class net.gamebub.core.gameboy.HandheldGameboy --build-root build/gameboy
$ python3 scripts/build_core.py --target gamebub_rev4 --name gba     --core-class net.gamebub.core.gba.HandheldGba     --build-root build/gba
$ python3 scripts/build_core.py --target gamebub_rev4 --name snes    --core-class net.gamebub.core.snes.HandheldSnes    --build-root build/snes
```

Each build produces `<name>.bit` and the heatshrink-compressed `<name>.bit.hs` in its build
root. The SNES core is experimental; see [docs/snes.md](snes.md).

## 6. Setting up the microSD card

Format a good quality microSD card with FAT32, then create the following directory structure:

```
system/
  boot.bit.hs
  gameboy.bit.hs
  gameboy.bios-dmg.bin
  gameboy.bios-cgb.bin
  gba.bit.hs
  gba.bios.bin
cores/
  Game-Bub.SNES/
    core.json
    files.json
    settings.json
    snes_rev4.bit
roms/
```

* `boot.bit.hs`, `gameboy.bit.hs` and `gba.bit.hs` are the compressed
  bitstreams built previously.
* `cores/Game-Bub.SNES/` is the SNES, an external core (optional): assemble
  it from its build with, in `fpga/`,
  `python3 scripts/package_core.py --name snes --build-root build/snes --out <sdcard>`,
  which copies the descriptors from `fpga/cores/snes/` and the uncompressed
  bitstream (see `docs/snes.md`).
* `gameboy.bios-dmg.bin` and `gameboy.bios-cgb.bin` should be the bootrom files for the original Game Boy and Game Boy Color, or open-source alternatives (e.g. from [SameBoy](https://github.com/LIJI32/SameBoy)).
* `gba.bios.bin` should be the Game Boy Advance bootrom. Either the official one, extracted from a GBA (best compatibility), or a free alternative ([e.g. this one](https://github.com/Cult-of-GBA/BIOS)).
* `roms/` should be a directory containing ROM files (if desired), with `.gb`, `.gbc`, `.gba`, `.sfc`, and `.smc` extensions. This directory can be further organized into more directories.

## 7. Flashing the MCU firmware

### Setup

1. Install Rust for ESP32 by [following these instructions (Xtensa targets)](https://docs.esp-rs.org/book/installation/index.html).
   With the Nix flake, `nix develop` provides `espup`, `espflash`, `ldproxy` and the ESP-IDF
   build prerequisites; run `espup install` once to fetch the Xtensa toolchain (1.98.0.0 is
   known to work). Note: the Slint widget style is forced to `cosmic` in `build.rs` because
   the Xtensa LLVM backend mishandles the `fluent` ListView animations (esp 1.93 produces a
   firmware that hangs on the ROM list, esp >= 1.94 fails to compile it).
2. Install [espflash](https://github.com/esp-rs/espflash)

### Building and installing

Connect the device to your computer with a USB-C cable. Hold the "Home" button (in the center) while turning the device on (by pressing the power button in the top right).

From `/firmware/handheld`, run the following command to build and flash the firmware
(replace `rev4` with your board revision; see the features in `Cargo.toml`):

```sh
$ cargo run --release --features=rev4
```

Then, flash the device-specific "factory" data:

```sh
$ python3 flash_nvs.py --serial serialno --revision 2
```

The device should automatically reboot into the main menu.
