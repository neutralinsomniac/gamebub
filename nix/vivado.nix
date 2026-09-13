# Which Xilinx Unified installer to package (see docs/building.md).
#
# Download the "Unified Installer SDI" tar from AMD, add it to the store with
#   nix store add-file --name <name> <path-to-tar>
# and make sure `name` and `hash` below match (`nix hash file <tar>`).
{
  # Version string as it appears in the installer file name.
  version = "2025.1_0530_0145";
  name = "FPGAs_AdaptiveSoCs_Unified_SDI_2025.1_0530_0145.tar";
  hash = "sha256-9LASrgJAzRczREYpaXxg9qwVmP9SwMYDwrUyWK1SMqw=";
  # Batch install configuration (Vivado Standard, Artix-7 devices only).
  installConfig = ./vivado-install-config.txt;
}
