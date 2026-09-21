#!/usr/bin/env python3
"""Compares cross-platform parity reports.

Each argument is NAME=FILE, where FILE holds the report as a platform printed it:
one `court-parity k:habeas=4:40888a1e,…;…` line (macOS probe, iOS), or one entry
per line (Android logcat). Scores are raw f32 bit patterns. For every query the
script says whether the platforms agree on the ranking, and on the scores bit for
bit; where scores differ it prints the largest absolute difference.
"""
import re, struct, sys


def load(path):
    text = open(path, errors="replace").read()
    entries = {}
    for key, value in re.findall(r"([kms]:[^=;\n]*)=([0-9a-f:,]*)", text):
        entries[key.strip()] = [(int(r), int(b, 16)) for r, b in (h.split(":") for h in value.split(",") if h)]
    return entries


def as_float(bits):
    return struct.unpack(">f", struct.pack(">I", bits))[0]


reports = {name: load(path) for name, path in (arg.split("=", 1) for arg in sys.argv[1:])}
names = list(reports)
keys = [k for k in reports[names[0]] if all(k in r for r in reports.values())]
missing = {n: sorted(set(reports[names[0]]) - set(r)) for n, r in reports.items() if set(reports[names[0]]) - set(r)}
print(f"platforms: {', '.join(names)}; queries compared: {len(keys)}")
for name, lost in missing.items():
    print(f"  {name} is missing: {lost}")
same_rank = same_bits = 0
worst = 0.0
for key in keys:
    rankings = {n: [row for row, _ in reports[n][key]] for n in names}
    bits = {n: [b for _, b in reports[n][key]] for n in names}
    rank_ok = len({tuple(v) for v in rankings.values()}) == 1
    bits_ok = rank_ok and len({tuple(v) for v in bits.values()}) == 1
    same_rank += rank_ok; same_bits += bits_ok
    note = ""
    if rank_ok and not bits_ok:
        delta = max(abs(as_float(a) - as_float(b)) for n in names[1:] for a, b in zip(bits[names[0]], bits[n]))
        worst = max(worst, delta); note = f"  max |delta| {delta:.3g}"
    elif not rank_ok:
        note = "  " + " vs ".join(f"{n}:{rankings[n][:5]}" for n in names)
    print(f"  {'bit-identical' if bits_ok else 'same ranking ' if rank_ok else 'DIFFERENT    '}  {key}{note}")
print(f"same ranking: {same_rank}/{len(keys)}   bit-identical scores: {same_bits}/{len(keys)}   worst score difference: {worst:.3g}")
sys.exit(0 if same_rank == len(keys) else 1)
