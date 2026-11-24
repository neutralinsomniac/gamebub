#!/usr/bin/env python3
"""Assemble an external core package for the SD card.

An external core is a directory under /sdcard/cores/ named after the core's
id, holding core.json, files.json and settings.json (see fpga/cores/<name>/),
any other files there (e.g. a blank save named by files.json), and the
uncompressed bitstream(s) the core.json names. This copies them to
<out>/cores/<id>/, taking the bitstream from a build_core.py build root.

    python3 scripts/package_core.py --name snes --build-root build/snes --out build/package

With --id, the core's id is replaced (in core.json and the directory name),
e.g. to install it next to a built-in core of the same id while testing.
With --version, metadata.version in the packaged core.json is set to that
value. With --flat, files go to <out>/<id>/ rather than <out>/cores/<id>/
(for single-core release zips).
"""

import argparse
import json
import shutil
import sys
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--name", required=True, help="core directory under fpga/cores/")
    parser.add_argument("--build-root", required=True, type=Path, help="build_core.py --build-root of the bitstream")
    parser.add_argument("--out", required=True, type=Path, help="output root (the SD card's root, or a staging directory)")
    parser.add_argument("--id", help="override the core id (core.json metadata.id and the directory name)")
    parser.add_argument("--version", help="override metadata.version in the packaged core.json (max 16 UTF-8 bytes)")
    parser.add_argument("--flat", action="store_true", help="write directly into <out>/<id>/ instead of <out>/cores/<id>/")
    args = parser.parse_args()

    fpga_root = Path(__file__).resolve().parent.parent
    core_dir = fpga_root / "cores" / args.name
    if not core_dir.is_dir():
        sys.exit(f"no core descriptors at {core_dir}")

    core = json.loads((core_dir / "core.json").read_text())
    if args.id:
        core["metadata"]["id"] = args.id
    if args.version:
        version = args.version.strip()
        if not version or len(version.encode("utf-8")) > 16:
            sys.exit(f"invalid --version {args.version!r}: must be 1-16 UTF-8 bytes")
        core["metadata"]["version"] = version
    core_id = core["metadata"]["id"]

    out_dir = (args.out / core_id) if args.flat else (args.out / "cores" / core_id)
    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / "core.json").write_text(json.dumps(core, indent=2) + "\n")
    for source in sorted(core_dir.iterdir()):
        if source.is_file() and source.name != "core.json":
            shutil.copyfile(source, out_dir / source.name)

    for bitstream in core["bitstreams"]:
        filename = bitstream["filename"]
        if not filename.endswith(".bit"):
            sys.exit(f"{filename}: the firmware only loads external bitstreams named *.bit")
        # build_core.py leaves <name>.bit next to the compressed <name>.bit.hs.
        candidates = [args.build_root / filename, args.build_root / f"{args.name}.bit"]
        source = next((c for c in candidates if c.exists()), None)
        if source is None:
            sys.exit(f"no bitstream for {bitstream['target']}: tried {', '.join(map(str, candidates))}")
        shutil.copyfile(source, out_dir / filename)
        print(f"{source} -> {out_dir / filename}")

    print(f"packaged {core_id} in {out_dir}")


if __name__ == "__main__":
    main()
