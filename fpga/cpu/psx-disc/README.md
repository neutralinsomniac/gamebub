# PSX disc reader

Firmware for the embedded RISC-V CPU (PicoRV32, RV32IMC, 16 KiB RAM) inside `psx.PsxDisc`. It handles the disc duties that MiSTer's HPS firmware performs for the upstream PSX core, reading disc files through the Game Bub framework's on-demand file interface (`FILE_GET_SIZE`, `FILE_READ`):

* Locates the matching `.cue` sheet (file 10 in `fpga/cores/psx/files.json`) and `.bin` image (file 11), or a standalone `.bin` image
* Parses single-file `BINARY` cue sheets (`AUDIO`, `MODE1/2352`, `MODE2/2352`, and `MODE1/2048` tracks; `INDEX 00`/`01` and `PREGAP`)
* Reads the disc region from the license sector while the PSX core is held in reset
* Sends the track table (MiSTer's `disk_t`) to the core on reset release and after disc swaps
* Reads requested sectors into the sector buffer for the glue to stream into the core

`src/lib.rs` implements disc and cue parsing (unit-tested on the host), and `src/main.rs` handles the hardware registers (documented at the top of `src/main.rs` and in `PsxDisc.scala`). `psx.PsxDiscSpec` simulates the compiled binary on the PicoRV32 against a firmware model.

## Building

```sh
make       # builds the binary and updates ../../src/main/resources/psx_disc.bin
make test  # runs host unit tests for src/lib.rs
```

`psx_disc.bin` is checked into the repository and embedded directly into the CPU's RAM during FPGA synthesis, so bitstream builds do not require a RISC-V toolchain. Rebuild and commit `psx_disc.bin` whenever this code changes.

Building requires the `riscv32imc-unknown-none-elf` Rust target (configured in `rust-toolchain.toml`). The Makefile runs commands through `nix develop` if `cargo` is not already on `PATH`.
