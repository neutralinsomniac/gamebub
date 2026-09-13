{
  description = "Game Bub: open-source FPGA retro handheld (FPGA cores + ESP32-S3 firmware)";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    # Only used for ratarmount/rapidgzip (installer helpers), which are broken on unstable.
    nixpkgs-stable.url = "github:NixOS/nixpkgs/nixos-25.11";
    # Packages the proprietary Xilinx installer from a locally-provided tar.
    xlnx-utils.url = "github:DLR-FT/xilinx-nix-utils";
    xlnx-utils.inputs.nixpkgs.follows = "nixpkgs";
  };

  outputs = { self, nixpkgs, nixpkgs-stable, xlnx-utils }:
    let
      systems = [ "x86_64-linux" "aarch64-linux" ];
      forAllSystems = f: nixpkgs.lib.genAttrs systems (system: f (import nixpkgs {
        inherit system;
        config.allowUnfree = true; # Vivado
        overlays = [
          # requireFile'd installer tars may be fetched from a binary cache
          (final: prev: {
            requireFile = args: (prev.requireFile args).overrideAttrs (_: { allowSubstitutes = true; });
          })
          # rapidgzip/ratarmount (used by the Xilinx installer derivation to
          # mount/unpack the installer tar) do not build on current unstable.
          (final: prev:
            let
              stable = import nixpkgs-stable {
                inherit system;
                overlays = [
                  (sfinal: sprev: {
                    # ratarmountcore's nested-archive tests fail in the sandbox.
                    pythonPackagesExtensions = sprev.pythonPackagesExtensions ++ [
                      (pfinal: pprev: {
                        ratarmountcore = pprev.ratarmountcore.overridePythonAttrs (_: { doCheck = false; });
                        # 1.1.2 lists mfusepy, which nixpkgs 25.11 substitutes with fusepy.
                        ratarmount = pprev.ratarmount.overridePythonAttrs (_: { doCheck = false; dontCheckRuntimeDeps = true; });
                      })
                    ];
                  })
                ];
              };
            in
            { inherit (stable) rapidgzip ratarmount; })
          xlnx-utils.overlays.xilinx-unified
        ];
      }));

      # Vivado, built from the installer tar described in nix/vivado.nix
      # (x86_64-linux only; the tar has to be added to the Nix store first).
      vivadoCfg = import ./nix/vivado.nix;
      vivadoTar = pkgs: pkgs.requireFile {
        inherit (vivadoCfg) name hash;
        url = "https://www.amd.com/en/support/downloads/adaptive-socs-and-fpgas/development-tools.html";
      };
      mkVivadoUnwrapped = pkgs:
        (pkgs.xilinx-unified-utils.install {
          baseName = "vivado";
          inherit (vivadoCfg) version installConfig;
          installTar = vivadoTar pkgs;
        }).overrideAttrs (old: {
          # xsetup's exit status is not propagated; make sure it actually installed something.
          postInstall = (old.postInstall or "") + ''
            if ! ls -d "$out"/*/Vivado >/dev/null 2>&1; then
              echo "error: Vivado was not installed (xsetup rejected nix/vivado-install-config.txt?)" >&2
              exit 1
            fi
          '';
        });
      mkVivado = pkgs: pkgs.xilinx-unified-utils.wrap { inputDerivation = mkVivadoUnwrapped pkgs; };

      # Runs the installer's ConfigGen for the "Vivado" product (menu answers
      # "2" = Vivado, "1" = first edition) to get a template install_config.txt
      # for the installer version in nix/vivado.nix.
      mkVivadoConfigTemplate = pkgs:
        let
          script = pkgs.writeShellScript "vivado-configgen" ''
            if [ -e /dev/fuse ]; then
              mkdir tar-write-overlay
              ratarmount --write-overlay ./tar-write-overlay $src ./unpack
            fi
            unpack=$(find ./unpack -mindepth 1 -maxdepth 1 -type d)
            printf '2\n1\n' | "$unpack/xsetup" --agree 3rdPartyEULA,XilinxEULA --batch ConfigGen
            if [ -e /dev/fuse ]; then
              ratarmount -u ./unpack
            fi
          '';
        in
        (pkgs.xilinx-unified-utils.install {
          baseName = "vivado-config-template";
          inherit (vivadoCfg) version installConfig;
          installTar = vivadoTar pkgs;
        }).overrideAttrs (_: {
          installPhase = ''
            runHook preInstall
            mkdir .Xilinx $out
            xilinx-installer-fhs ${script}
            cp .Xilinx/install_config.txt $out/
            runHook postInstall
          '';
        });
    in
    {
      packages = forAllSystems (pkgs: nixpkgs.lib.optionalAttrs (pkgs.stdenv.hostPlatform.isx86_64) {
        vivado = mkVivado pkgs;
        # `nix build .#vivado-install-config-template` -> result/install_config.txt
        vivado-install-config-template = mkVivadoConfigTemplate pkgs;
      });
      devShells = forAllSystems (pkgs:
        let
          # Python used by fpga/scripts/build_core.py and build_sim.py.
          python = pkgs.python3.withPackages (ps: with ps; [
            edalize # drives Vivado / Verilator
            heatshrink2 # compresses bitstreams (*.bit.hs)
            pyserial # scripts/usb_tool.py, flash_system_data.py
          ]);

          # Everything needed to elaborate the Chisel design and build/run the
          # Verilator simulators. Vivado itself is proprietary and must be
          # installed separately; see `vivadoHook` below.
          fpgaTools = with pkgs; [
            jdk17
            mill
            python
            verilator
            gnumake
            gcc
            pkg-config
            SDL2
            ccache
            git
            gtkwave
            ghdl # VHDL analysis of the vendored SNES core (fpga/verilog/snes)
          ];

          # Tools for the ESP32-S3 firmware. The Xtensa Rust toolchain is not
          # packaged in nixpkgs; `espup install` fetches it into ~/.rustup as the
          # `esp` channel that firmware/handheld/rust-toolchain.toml selects.
          # esp-idf-sys then downloads ESP-IDF v5.4.3 into ~/.espressif on first
          # build (ESP_IDF_TOOLS_INSTALL_DIR=global in .cargo/config.toml).
          firmwareTools = with pkgs; [
            rustup
            espup
            espflash
            ldproxy
            cmake
            ninja
            python3
            git
            flex
            bison
            gperf
            dfu-util
            libusb1
            pkg-config
            openssl
          ];

          # Locate a Vivado installation and put it on PATH (unless the
          # nix-packaged one is already there, see the `vivado` shell).
          vivadoHook = ''
            if command -v vivado >/dev/null 2>&1; then
              :
            elif [ -z "$XILINX_VIVADO" ]; then
              for d in /opt/Xilinx/Vivado/2023.2 /opt/Xilinx/Vivado/* /tools/Xilinx/Vivado/*; do
                if [ -x "$d/bin/vivado" ]; then export XILINX_VIVADO="$d"; break; fi
              done
            fi
            if command -v vivado >/dev/null 2>&1; then
              :
            elif [ -n "$XILINX_VIVADO" ] && [ -x "$XILINX_VIVADO/bin/vivado" ]; then
              export PATH="$XILINX_VIVADO/bin:$PATH"
            else
              echo "note: Vivado not found: use 'nix develop .#vivado' (see docs/building.md) or set XILINX_VIVADO" >&2
            fi
          '';

          espHook = ''
            # Set up by `espup install` (Xtensa Rust toolchain + clang).
            if [ -f "$HOME/export-esp.sh" ]; then
              . "$HOME/export-esp.sh"
              # espup's prebuilt libclang (for bindgen) and xtensa gcc are dynamically
              # linked; give them libstdc++/zlib since there is no global /lib here.
              export LD_LIBRARY_PATH="${pkgs.stdenv.cc.cc.lib}/lib:${pkgs.zlib}/lib''${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
            else
              echo "note: run 'espup install' once to fetch the Xtensa Rust toolchain" >&2
            fi
          '';

          banner = ''
            echo "Game Bub dev shell"
            echo "  FPGA:     cd fpga && python3 scripts/build_sim.py gba --build-root build/sim-gba"
            echo "            cd fpga && python3 scripts/build_core.py --target gamebub_rev4 --name gba --core-class net.gamebub.core.gba.HandheldGba --build-root build/gba"
            echo "  Firmware: cd firmware/handheld && cargo build --release --features=rev4"
          '';

          # Libraries that binaries downloaded by Vivado / ESP-IDF expect to find
          # in an FHS layout. Only relevant to the `fhs` shell.
          fhsLibs = pkgs: with pkgs; [
            zlib
            ncurses5
            ncurses
            libxcrypt-legacy
            xorg.libX11
            xorg.libXext
            xorg.libXrender
            xorg.libXtst
            xorg.libXi
            xorg.libxcb
            fontconfig
            freetype
            glib
            gtk3
            libGL
            libusb1
            libudev-zero
            stdenv.cc.cc.lib
          ];
        in
        rec {
          # Chisel + simulator + firmware tooling (pure nix; Vivado/espup optional).
          default = pkgs.mkShell {
            packages = fpgaTools ++ firmwareTools;
            JAVA_HOME = pkgs.jdk17;
            # mill downloads its own JDK; the boot core renders logo.png via AWT,
            # which needs X libraries unless the JVM runs headless.
            JAVA_TOOL_OPTIONS = "-Djava.awt.headless=true";
            shellHook = vivadoHook + espHook + banner;
          };

          fpga = pkgs.mkShell {
            packages = fpgaTools;
            JAVA_HOME = pkgs.jdk17;
            JAVA_TOOL_OPTIONS = "-Djava.awt.headless=true";
            shellHook = vivadoHook + banner;
          };

          # FPGA tools plus the nix-packaged Vivado. Requires the installer
          # tar in the Nix store (see nix/vivado.nix and docs/building.md).
          vivado = pkgs.mkShell {
            packages = fpgaTools ++ nixpkgs.lib.optional pkgs.stdenv.hostPlatform.isx86_64 (mkVivado pkgs);
            JAVA_HOME = pkgs.jdk17;
            JAVA_TOOL_OPTIONS = "-Djava.awt.headless=true";
            shellHook = vivadoHook + banner;
          };

          firmware = pkgs.mkShell {
            packages = firmwareTools;
            shellHook = espHook + banner;
          };

          # FHS-compatible shell for NixOS hosts. Vivado and the ESP-IDF
          # prebuilt toolchains are dynamically linked against /lib paths, so
          # they need this (or nix-ld) on NixOS. On non-NixOS distros use
          # `default` instead.
          fhs = (pkgs.buildFHSEnv {
            name = "gamebub-fhs";
            targetPkgs = pkgs: fpgaTools ++ firmwareTools ++ fhsLibs pkgs;
            multiPkgs = pkgs: [ pkgs.zlib ];
            profile = ''
              export JAVA_HOME=${pkgs.jdk17}
            '' + vivadoHook + espHook + banner;
            runScript = "bash";
          }).env;
        });

      formatter = forAllSystems (pkgs: pkgs.nixpkgs-fmt);
    };
}
