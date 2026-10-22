"""Render a PSX VRAM dump as a PNG.

The dump is the PSX core's VRAM as the firmware saves it at a pause (a
`dumps` entry in the core's core.json): 1024 x 512
halfwords, little-endian, 15-bit color (red in bits 4-0). 24-bit display
areas show as noise; textures and CLUTs as what they are.

    python3 scripts/psx_vram_png.py vram.bin vram.png
    python3 scripts/psx_vram_png.py vram.bin left.png --crop 0,0,320,240 --scale 2
"""
import argparse
import struct
import zlib


def write_png(path: str, width: int, height: int, rows: list) -> None:
    def chunk(kind: bytes, data: bytes) -> bytes:
        c = struct.pack(">I", len(data)) + kind + data
        return c + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)

    raw = b"".join(b"\x00" + row for row in rows)
    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n")
        f.write(chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)))
        f.write(chunk(b"IDAT", zlib.compress(raw, 6)))
        f.write(chunk(b"IEND", b""))


def main() -> None:
    parser = argparse.ArgumentParser(description="Render a PSX VRAM dump as a PNG")
    parser.add_argument("dump", help="VRAM dump (1 MiB)")
    parser.add_argument("png", help="output PNG")
    parser.add_argument("--crop", default="0,0,1024,512", help="x,y,width,height (pixels)")
    parser.add_argument("--scale", type=int, default=1, help="integer zoom")
    args = parser.parse_args()

    vram = open(args.dump, "rb").read()
    if len(vram) != 1 << 20:
        raise SystemExit(f"{args.dump}: {len(vram)} bytes, expected 1 MiB")
    x0, y0, w, h = (int(v) for v in args.crop.split(","))
    s = args.scale
    rows = []
    for y in range(y0, y0 + h):
        row = bytearray()
        for x in range(x0, x0 + w):
            i = 2 * ((y & 511) * 1024 + (x & 1023))
            p = vram[i] | (vram[i + 1] << 8)
            r, g, b = (p & 31) << 3, ((p >> 5) & 31) << 3, ((p >> 10) & 31) << 3
            row += bytes((r, g, b)) * s
        rows.extend([bytes(row)] * s)
    write_png(args.png, w * s, h * s, rows)


if __name__ == "__main__":
    main()
