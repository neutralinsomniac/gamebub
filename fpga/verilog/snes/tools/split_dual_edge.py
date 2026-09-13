#!/usr/bin/env python3
"""
Split VHDL processes that have both a `falling_edge(CLK)` and a
`rising_edge(CLK)` branch into two single-edge processes (Vivado: "else
clause after check for clock not supported"). The reset branch is
duplicated, keeping only the assignments to signals each half drives. The
signal sets of the two branches must be disjoint (checked).

Usage: split_dual_edge.py FILE...
"""
import re
import sys

ASSIGN = re.compile(r"^\s*([A-Za-z_][A-Za-z0-9_]*)(\([^<]*\))?\s*<=")


def assigned(lines):
    return {m.group(1) for line in lines if (m := ASSIGN.match(line))}


def split_file(path):
    src = open(path).read().split("\n")
    out = []
    i = 0
    count = 0
    while i < len(src):
        line = src[i]
        if re.match(r"^\s*process\s*\(", line):
            # Collect the process
            j = i
            while not re.match(r"^\s*end process", src[j]):
                j += 1
            proc = src[i:j + 1]
            text = "\n".join(proc)
            if "falling_edge(CLK)" in text and "rising_edge(CLK)" in text:
                out.extend(split_process(proc))
                count += 1
            else:
                out.extend(proc)
            i = j + 1
        else:
            out.append(line)
            i += 1
    open(path, "w").write("\n".join(out))
    return count


def split_process(proc):
    def find(pat, start=0):
        for k in range(start, len(proc)):
            if re.match(pat, proc[k]):
                return k
        raise ValueError(f"pattern {pat!r} not found in process starting {proc[0]!r}")

    header_end = find(r"^\s*begin\s*$")                # process(...) [variables] begin
    reset_start = find(r"^\s*if RST_N = '0' then", header_end)
    fall_start = find(r"^\s*elsif falling_edge\(CLK\) then", reset_start)
    rise_start = find(r"^\s*elsif rising_edge\(CLK\) then", fall_start)
    # The process ends with "end if;" (closing the reset if) then "end process;"
    assert re.match(r"^\s*end if;", proc[-2]), proc[-2]
    header = proc[:header_end + 1]
    reset = proc[reset_start + 1:fall_start]
    fall = proc[fall_start + 1:rise_start]
    rise = proc[rise_start + 1:-2]
    tail = proc[-2:]
    fall_sigs, rise_sigs = assigned(fall), assigned(rise)
    both = fall_sigs & rise_sigs
    assert not both, f"signals assigned on both edges: {both}"

    def reset_for(sigs, default):
        kept = []
        for line in reset:
            m = ASSIGN.match(line)
            if m is None:
                kept.append(line)  # blank lines / comments
            elif m.group(1) in sigs or (default and m.group(1) not in fall_sigs | rise_sigs):
                kept.append(line)
        return kept

    def process(edge, body, sigs, default):
        return (header
                + [proc[reset_start]]
                + reset_for(sigs, default)
                + [proc[fall_start].replace("falling_edge", edge)]
                + body
                + tail)

    return (process("falling_edge", fall, fall_sigs, False)
            + [""]
            + process("rising_edge", rise, rise_sigs, True))


if __name__ == "__main__":
    for path in sys.argv[1:]:
        print(f"{path}: split {split_file(path)} process(es)")
