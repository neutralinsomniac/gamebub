#!/usr/bin/env python3
"""Analyze an SNES audio debug capture (HandheldSnes.DebugAudio) dumped over serial.

Requires numpy. `samples` mode decodes raw DSP samples (config bit 6 clear),
`periods` mode decodes the clocks between samples (config bit 6 set).

Parses `CAP xxxx b0 b1 ...` lines (between CAPTURE begin / end), rebuilds the
4096 stereo samples in chronological order and reports basic statistics:
DC/RMS, dominant tones, and an envelope-modulation spectrum (the 'flutter'
detector). Also writes a WAV for listening.
"""
import re
import struct
import sys
import wave

import numpy as np

CLK = 945e6 / 44  # system clock, 21.477 MHz
NOMINAL = 128 * 21477270 / 4096000  # 671.16 core clocks per DSP sample
FS = CLK / NOMINAL  # DSP sample rate, 32 kHz


def parse(path):
    captures = []
    cur = None
    oldest = 0
    for line in open(path, errors="replace"):
        m = re.search(r"CAPTURE begin samples=(\d+) oldest=(\d+)", line)
        if m:
            cur = {}
            oldest = int(m.group(2))
            continue
        if "CAPTURE end" in line and cur is not None:
            data = bytearray()
            for off in sorted(cur):
                data += cur[off]
            captures.append((oldest, bytes(data)))
            cur = None
            continue
        m = re.search(r"CAP ([0-9a-f]{4})((?: [0-9a-f]{2})+)", line)
        if m and cur is not None:
            cur[int(m.group(1), 16)] = bytes.fromhex(m.group(2).replace(" ", ""))
    return captures


def decode(oldest, data):
    n = len(data) // 4
    words = struct.unpack(f">{n}I", data[: n * 4])
    left = np.array([(w >> 16) & 0xFFFF for w in words], dtype=np.int32)
    right = np.array([w & 0xFFFF for w in words], dtype=np.int32)
    left[left >= 0x8000] -= 0x10000
    right[right >= 0x8000] -= 0x10000
    left = np.roll(left, -oldest)
    right = np.roll(right, -oldest)
    return left.astype(np.float64), right.astype(np.float64)


def report(name, x):
    n = len(x)
    print(f"--- {name}: {n} samples ({n / FS * 1000:.1f} ms)")
    print(f"  DC {x.mean():.1f}  RMS {x.std():.1f}  min {x.min():.0f}  max {x.max():.0f}")
    # Consecutive duplicates (would indicate missed/held samples)
    dup = np.sum(np.diff(x) == 0)
    print(f"  identical consecutive samples: {dup} ({100 * dup / (n - 1):.1f}%)")
    # Largest sample-to-sample jumps
    d = np.abs(np.diff(x))
    print(f"  max step {d.max():.0f}, 99.9th percentile step {np.percentile(d, 99.9):.0f}, median {np.median(d):.0f}")
    # Spectrum
    w = np.hanning(n)
    spec = np.abs(np.fft.rfft((x - x.mean()) * w))
    freqs = np.fft.rfftfreq(n, 1 / FS)
    peaks = np.argsort(spec)[::-1]
    print("  dominant tones (Hz, dB rel. max):")
    shown = []
    for i in peaks:
        if all(abs(freqs[i] - f) > 40 for f in shown):
            shown.append(freqs[i])
            print(f"    {freqs[i]:8.1f} Hz  {20 * np.log10(spec[i] / spec.max() + 1e-12):6.1f} dB")
        if len(shown) >= 8:
            break
    # Envelope modulation: |analytic signal| then spectrum of the envelope
    try:
        from numpy.fft import fft, ifft
        X = fft(x - x.mean())
        h = np.zeros(n)
        h[0] = 1
        h[1 : n // 2] = 2
        h[n // 2] = 1
        env = np.abs(ifft(X * h))
        env = env - env.mean()
        espec = np.abs(np.fft.rfft(env * w))
        efreqs = np.fft.rfftfreq(n, 1 / FS)
        sel = (efreqs > 20) & (efreqs < 2000)
        idx = np.argsort(espec[sel])[::-1][:5]
        print("  envelope modulation peaks 20-2000 Hz (Hz, dB rel. envelope max):")
        for i in idx:
            f = efreqs[sel][i]
            print(f"    {f:8.1f} Hz  {20 * np.log10(espec[sel][i] / espec.max() + 1e-12):6.1f} dB")
    except Exception as e:
        print("  envelope analysis failed:", e)




def main_samples(path):

    caps = parse(path)
    print(f"{len(caps)} capture(s) in {path}")
    for k, (oldest, data) in enumerate(caps):
        left, right = decode(oldest, data)
        print(f"=== capture {k} (oldest index {oldest}, {len(data)} bytes)")
        report("left", left)
        report("right", right)
        out = path + f".cap{k}.wav"
        with wave.open(out, "wb") as wf:
            wf.setnchannels(2)
            wf.setsampwidth(2)
            wf.setframerate(int(round(FS)))
            inter = np.empty(2 * len(left), dtype=np.int16)
            inter[0::2] = np.clip(left, -32768, 32767)
            inter[1::2] = np.clip(right, -32768, 32767)
            wf.writeframes(inter.tobytes())
        print(f"  wrote {out}")



def main_periods(path):
    caps = parse(path)
    print(f"{len(caps)} capture(s)")
    for k, (oldest, data) in enumerate(caps):
        n = len(data) // 4
        p = np.array(struct.unpack(f">{n}I", data[: n * 4]), dtype=np.float64)
        p = np.roll(p, -oldest)
        stall = p - NOMINAL
        print(f"=== capture {k}: {n} samples, {p.sum() / CLK * 1000:.1f} ms")
        print(f"  period: mean {p.mean():.2f} clk (nominal {NOMINAL:.2f}), min {p.min():.0f}, max {p.max():.0f}, std {p.std():.2f}")
        print(f"  stall per sample: mean {stall.mean():.2f} clk ({100 * stall.mean() / NOMINAL:.2f}%), max {stall.max():.0f} clk ({100 * stall.max() / NOMINAL:.1f}%)")
        # Distribution
        hist, edges = np.histogram(stall, bins=[-1, 0.5, 2, 5, 10, 20, 40, 80, 160, 1e9])
        print("  stall histogram (clk): " + ", ".join(f"{edges[i]:.0f}-{edges[i+1]:.0f}: {hist[i]}" for i in range(len(hist)) if hist[i]))
        # Time-domain: per-frame pattern. Build a time axis in real time.
        t = np.cumsum(p) / CLK
        # Spectrum of the instantaneous rate error (as a function of sample index ~ time)
        rate_err = stall / NOMINAL
        w = np.hanning(n)
        spec = np.abs(np.fft.rfft((rate_err - rate_err.mean()) * w))
        fs = 1 / (p.mean() / CLK)
        freqs = np.fft.rfftfreq(n, 1 / fs)
        sel = (freqs > 10) & (freqs < 4000)
        idx = np.argsort(spec[sel])[::-1][:8]
        print("  jitter spectrum peaks (Hz : peak-to-peak rate deviation %):")
        for i in idx:
            print(f"    {freqs[sel][i]:8.1f} Hz  {100 * 4 * spec[sel][i] / n:.3f}%")
        # Per-frame smoothing: rate deviation averaged over 1 ms windows
        win = int(round(fs / 1000))
        if win > 1:
            sm = np.convolve(rate_err, np.ones(win) / win, mode="valid")
            print(f"  1 ms-averaged rate deviation: min {100 * sm.min():.2f}%, max {100 * sm.max():.2f}% (p-p {100 * (sm.max() - sm.min()):.2f}%)")
        # Print a coarse timeline (every 0.5 ms)
        step = int(round(fs / 2000))
        line = []
        for i in range(0, n - step, step):
            line.append(f"{100 * rate_err[i:i + step].mean():5.1f}")
        print("  timeline (% slow per 0.5 ms):")
        for i in range(0, len(line), 32):
            print("   " + " ".join(line[i:i + 32]))



if __name__ == "__main__":
    if len(sys.argv) != 3 or sys.argv[1] not in ("samples", "periods"):
        sys.exit("usage: snes_audio_capture.py samples|periods <serial log>")
    (main_samples if sys.argv[1] == "samples" else main_periods)(sys.argv[2])
