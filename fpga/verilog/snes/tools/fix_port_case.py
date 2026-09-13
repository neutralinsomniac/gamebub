#!/usr/bin/env python3
"""
Rewrite named port connections in a Verilog file so that they match the exact
spelling of the ports of the VHDL entities being instantiated.

VHDL is case-insensitive, so upstream `main.v` connects e.g. `.mclk(...)` to a
VHDL port declared `MCLK`. Quartus accepts that; Vivado binds Verilog-to-VHDL
ports case-sensitively and silently leaves mismatches unconnected.

Usage: fix_port_case.py <verilog file> <vhdl file>...
Rewrites the Verilog file in place and prints what changed.
"""

import re
import sys
from pathlib import Path


def vhdl_entity_ports(text: str) -> dict[str, dict[str, str]]:
    """Map lowercase entity name -> {lowercase port -> exact port spelling}."""
    text = re.sub(r"--.*", "", text)
    entities = {}
    for m in re.finditer(r"\bentity\s+(\w+)\s+is(.*?)\bend\b", text, flags=re.S | re.I):
        name, body = m.group(1), m.group(2)
        pm = re.search(r"\bport\s*\((.*)\)\s*;", body, flags=re.S | re.I)
        if not pm:
            continue
        ports = {}
        for decl in pm.group(1).split(";"):
            if ":" not in decl:
                continue
            names = decl.split(":", 1)[0]
            for p in names.split(","):
                p = p.strip()
                if p:
                    ports[p.lower()] = p
        entities[name.lower()] = ports
    return entities


def main() -> None:
    verilog = Path(sys.argv[1])
    entities: dict[str, dict[str, str]] = {}
    for f in sys.argv[2:]:
        entities.update(vhdl_entity_ports(Path(f).read_text()))

    src = verilog.read_text()
    out = []
    pos = 0
    changes = 0
    # Instances: `<Module> [#(...)] <inst> ( ... );` — match on the module name
    # being a known VHDL entity, then rewrite `.port(` inside its body.
    inst_re = re.compile(r"^[ \t]*(\w+)\s*(#\s*\(.*?\)\s*)?(\w+)\s*\(", re.M | re.S)
    for m in inst_re.finditer(src):
        module = m.group(1).lower()
        if module not in entities:
            continue
        # find the matching closing paren of the instance body
        depth = 1
        i = m.end()
        while depth and i < len(src):
            if src[i] == "(":
                depth += 1
            elif src[i] == ")":
                depth -= 1
            i += 1
        body = src[m.end():i]
        ports = entities[module]

        def fix(pm: re.Match) -> str:
            nonlocal changes
            exact = ports.get(pm.group(1).lower())
            if exact is None or exact == pm.group(1):
                return pm.group(0)
            changes += 1
            return f".{exact}("

        new_body = re.sub(r"\.(\w+)\s*\(", fix, body)
        out.append(src[pos:m.end()])
        out.append(new_body)
        pos = i
    out.append(src[pos:])
    verilog.write_text("".join(out))
    print(f"{verilog}: {changes} port connections renamed")


if __name__ == "__main__":
    main()
