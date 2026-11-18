# PSX disc reader

The program of the RISC-V CPU (a PicoRV32, RV32IMC, with 16 KiB of RAM) in
the PSX core's glue, `psx.PsxDisc`. It does what the MiSTer's firmware does
for its PSX core, through the Game Bub framework's on-demand file requests
(`FILE_GET_SIZE`, `FILE_READ`), which any firmware that has them serves:

* finds the disc: the cue sheet named like the selected file (file 10 in
  `fpga/cores/psx/files.json`) with the image named like it (file 11), or
  the image alone;
* parses the cue sheet (one `BINARY` file; `AUDIO`, `MODE1/2352`,
  `MODE2/2352` and `MODE1/2048` tracks; `INDEX 00` / `01`, `PREGAP`);
* reads the region from the license sector while the core is held in reset;
* gives the core its track table (MiSTer's `disk_t`) each time it leaves
  reset, and after a disc change;
* reads each sector the core asks for into the sector buffer, which the
  glue streams into the core.

`src/lib.rs` is the part that knows about images (tested on the host);
`src/main.rs` the part that talks to the hardware (its register map is at
the top, and in `PsxDisc.scala`). `psx.PsxDiscSpec` runs the program on the
CPU in simulation against a model of the firmware.

## Building

    make          # cargo build, then ../../src/main/resources/psx_disc.bin
    make test     # the image logic's tests, on the host

The glue embeds `psx_disc.bin` (committed) in the CPU's RAM, so building a
bitstream needs no RISC-V toolchain; rebuild it after changing the program
and commit it with the change. The program needs Rust's
`riscv32imc-unknown-none-elf` target (`rust-toolchain.toml` asks rustup
for it; the commands run through `nix develop` unless cargo is on PATH).
