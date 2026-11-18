#!/usr/bin/env python3
"""Write the loadable segments of a 32-bit little-endian ELF as a flat image
from address 0 (the sub-CPU's RAM), checking that it fits.

    elf2bin.py <elf> <bin> [ram bytes]
"""
import struct
import sys

elf_path, bin_path = sys.argv[1:3]
ram = int(sys.argv[3], 0) if len(sys.argv) > 3 else 16 * 1024
data = open(elf_path, "rb").read()
if data[:4] != b"\x7fELF" or data[4] != 1 or data[5] != 1:
    sys.exit("not a 32-bit little-endian ELF")
phoff, = struct.unpack_from("<I", data, 28)
phentsize, phnum = struct.unpack_from("<HH", data, 42)
image = bytearray()
for i in range(phnum):
    p_type, p_offset, p_vaddr, _, p_filesz, p_memsz, _, _ = struct.unpack_from("<8I", data, phoff + i * phentsize)
    if p_type != 1 or p_filesz == 0:  # PT_LOAD with contents
        continue
    if p_vaddr + p_memsz > ram:
        sys.exit(f"segment at {p_vaddr:#x} ({p_memsz} bytes) does not fit in {ram} bytes")
    if len(image) < p_vaddr + p_filesz:
        image.extend(bytes(p_vaddr + p_filesz - len(image)))
    image[p_vaddr:p_vaddr + p_filesz] = data[p_offset:p_offset + p_filesz]
image.extend(bytes(-len(image) % 4))
open(bin_path, "wb").write(image)
print(f"{bin_path}: {len(image)} bytes")
