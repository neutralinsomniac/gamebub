#!/usr/bin/env python3
"""
Generate the coprocessor ROM entities used by the SNES core.

The upstream MiSTer core initializes the DSP-n (DSP-1/1B/2/3/4, ST010) and
CX4 ROMs from Quartus .mif files. Those images are Nintendo/Capcom firmware
and are not distributed with Game Bub, so this script builds equivalent VHDL
ROM entities from files the user supplies, and falls back to zero-filled
ROMs (DSP/CX4 games will not work, everything else will) when they are absent.

Inputs are looked up in fpga/verilog/snes/roms/ (git-ignored), either as:

  * The MiSTer .mif files:  dsp11b23410_p.mif, dsp11b23410_d.mif, drom.mif
  * Or raw ROM dumps (big-endian, 3 bytes per 24-bit program word, 2 bytes
    per 16-bit data word), named:
        dsp1.program.rom / dsp1.data.rom
        dsp1b.program.rom / dsp1b.data.rom
        dsp2.program.rom / dsp2.data.rom
        dsp3.program.rom / dsp3.data.rom
        dsp4.program.rom / dsp4.data.rom
        st010.program.rom / st010.data.rom
        cx4.data.rom

Output: rtl/generated/coprocessor_roms.vhd defining entities
  dspn_prog_rom (8096 x 24), dspn_data_rom (7168 x 16), cx4_data_rom (1024 x 24)
each with ports (clock, address, q) and a one-cycle registered read.
"""

import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ROMS_DIR = ROOT / "roms"
OUT_DIR = ROOT / "rtl" / "generated"

# Layout of the combined DSP-n images (from the upstream .mif headers and
# the PROG_ROM_ADDR / DATA_ROM_ADDR muxes in DSPn.vhd).
DSP_PROG_DEPTH = 8096
DSP_DATA_DEPTH = 7168
DSP_PROG_LAYOUT = [
    ("dsp1", 0x0000),
    ("dsp1b", 0x04F2),
    ("dsp2", 0x09E5),
    ("dsp3", 0x10B8),
    ("dsp4", 0x16BD),
    ("st010", 0x1D8B),
]
DSP_DATA_LAYOUT = [
    ("dsp1", 0x0000),
    ("dsp1b", 0x0400),
    ("dsp2", 0x0800),
    ("dsp3", 0x0C00),
    ("dsp4", 0x1000),
    ("st010", 0x1400),
]
CX4_DATA_DEPTH = 1024


def parse_mif(path: Path, depth: int) -> list[int]:
    """Parse a Quartus Memory Initialization File into a list of ints."""
    text = path.read_text()
    text = re.sub(r"%.*?%", "", text, flags=re.S)
    text = re.sub(r"--.*", "", text)
    header, _, body = text.partition("BEGIN")
    radix = {"HEX": 16, "DEC": 10, "BIN": 2, "OCT": 8, "UNS": 10}
    addr_radix = 16
    data_radix = 16
    for key, val in re.findall(r"(\w+)\s*=\s*(\w+)\s*;", header):
        if key.upper() == "ADDRESS_RADIX":
            addr_radix = radix[val.upper()]
        elif key.upper() == "DATA_RADIX":
            data_radix = radix[val.upper()]
        elif key.upper() == "DEPTH" and int(val) != depth:
            raise SystemExit(f"{path}: DEPTH={val}, expected {depth}")
    body = body.split("END")[0]
    words = [0] * depth
    for entry in body.split(";"):
        entry = entry.strip()
        if not entry:
            continue
        lhs, _, rhs = entry.partition(":")
        values = [int(v, data_radix) for v in rhs.split()]
        lhs = lhs.strip()
        m = re.match(r"\[\s*(\w+)\s*\.\.\s*(\w+)\s*\]", lhs)
        if m:
            start, end = int(m.group(1), addr_radix), int(m.group(2), addr_radix)
            for i, a in enumerate(range(start, end + 1)):
                words[a] = values[i % len(values)]
        else:
            start = int(lhs, addr_radix)
            for i, v in enumerate(values):
                words[start + i] = v
    return words


def read_raw(path: Path, bytes_per_word: int) -> list[int]:
    data = path.read_bytes()
    return [
        int.from_bytes(data[i : i + bytes_per_word], "big")
        for i in range(0, len(data) - bytes_per_word + 1, bytes_per_word)
    ]


def place(words: list[int], layout: list[tuple[str, int]], depth: int, suffix: str, bytes_per_word: int) -> bool:
    """Fill `words` from raw dumps according to `layout`. Returns True if any found."""
    found = False
    for i, (name, offset) in enumerate(layout):
        path = ROMS_DIR / f"{name}.{suffix}.rom"
        if not path.exists():
            continue
        found = True
        limit = (layout[i + 1][1] if i + 1 < len(layout) else depth) - offset
        rom = read_raw(path, bytes_per_word)
        if len(rom) > limit:
            print(f"note: {path.name} truncated to {limit} words (upstream layout)", file=sys.stderr)
        for j, w in enumerate(rom[:limit]):
            words[offset + j] = w
    return found


def load_dsp() -> tuple[list[int], list[int], bool]:
    prog = [0] * DSP_PROG_DEPTH
    data = [0] * DSP_DATA_DEPTH
    mif_p = ROMS_DIR / "dsp11b23410_p.mif"
    mif_d = ROMS_DIR / "dsp11b23410_d.mif"
    if mif_p.exists() and mif_d.exists():
        return parse_mif(mif_p, DSP_PROG_DEPTH), parse_mif(mif_d, DSP_DATA_DEPTH), True
    found_p = place(prog, DSP_PROG_LAYOUT, DSP_PROG_DEPTH, "program", 3)
    found_d = place(data, DSP_DATA_LAYOUT, DSP_DATA_DEPTH, "data", 2)
    return prog, data, found_p or found_d


def load_cx4() -> tuple[list[int], bool]:
    mif = ROMS_DIR / "drom.mif"
    if mif.exists():
        return parse_mif(mif, CX4_DATA_DEPTH), True
    raw = ROMS_DIR / "cx4.data.rom"
    if raw.exists():
        rom = read_raw(raw, 3)[:CX4_DATA_DEPTH]
        return rom + [0] * (CX4_DATA_DEPTH - len(rom)), True
    return [0] * CX4_DATA_DEPTH, False


def emit_rom(name: str, words: list[int], width: int, addr_width: int) -> str:
    depth = len(words)
    digits = (width + 3) // 4
    mask = (1 << width) - 1
    lines = [
        f"-- {name}: {depth} x {width} bits. GENERATED by tools/gen_roms.py, do not edit.",
        "LIBRARY ieee;",
        "USE ieee.std_logic_1164.all;",
        "USE ieee.numeric_std.all;",
        "",
        f"ENTITY {name} IS",
        "\tPORT (",
        "\t\tclock   : in  std_logic;",
        f"\t\taddress : in  std_logic_vector({addr_width - 1} downto 0);",
        f"\t\tq       : out std_logic_vector({width - 1} downto 0)",
        "\t);",
        f"END {name};",
        "",
        f"ARCHITECTURE SYN OF {name} IS",
        f"\ttype rom_t is array (0 to {depth - 1}) of std_logic_vector({width - 1} downto 0);",
        "\tconstant ROM : rom_t := (",
    ]
    per_line = 8
    for i in range(0, depth, per_line):
        chunk = ", ".join(f'x"{(w & mask):0{digits}X}"' for w in words[i : i + per_line])
        sep = "," if i + per_line < depth else ""
        lines.append(f"\t\t{chunk}{sep}")
    lines += [
        "\t);",
        f"\tsignal q0 : std_logic_vector({width - 1} downto 0) := (others => '0');",
        "BEGIN",
        "\tq <= q0;",
        "\tprocess(clock)",
        "\t\tvariable idx : integer;",
        "\tbegin",
        "\t\tif rising_edge(clock) then",
        "\t\t\tidx := to_integer(unsigned(address));",
        f"\t\t\tif idx < {depth} then",
        "\t\t\t\tq0 <= ROM(idx);",
        "\t\t\telse",
        "\t\t\t\tq0 <= (others => '0');",
        "\t\t\tend if;",
        "\t\tend if;",
        "\tend process;",
        "END SYN;",
        "",
    ]
    return "\n".join(lines)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--out", type=Path, default=OUT_DIR / "coprocessor_roms.vhd")
    args = parser.parse_args()

    prog, data, have_dsp = load_dsp()
    cx4, have_cx4 = load_cx4()
    if not have_dsp:
        print(f"warning: no DSP-n ROMs found in {ROMS_DIR}; DSP-1/2/3/4 and ST010 games will not work", file=sys.stderr)
    if not have_cx4:
        print(f"warning: no CX4 data ROM found in {ROMS_DIR}; CX4 games will not work", file=sys.stderr)

    out = "\n".join(
        [
            emit_rom("dspn_prog_rom", prog, 24, 13),
            emit_rom("dspn_data_rom", data, 16, 13),
            emit_rom("cx4_data_rom", cx4, 24, 10),
        ]
    )
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(out)
    print(f"wrote {args.out} (dsp: {'ok' if have_dsp else 'EMPTY'}, cx4: {'ok' if have_cx4 else 'EMPTY'})")


if __name__ == "__main__":
    main()
