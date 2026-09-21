#!/usr/bin/env python3
"""Reference for how the apps cut an opinion into paragraphs and sentences.

The Swift and Kotlin implementations must reproduce this exactly: the heatmap's
passage vectors depend on where each unit begins and ends, and the platform tests
assert the unit count this script prints. ASCII rules only, on purpose, so that
no platform's Unicode tables can make them disagree.
"""
import json, re, sys

ABBREVIATIONS = {"v", "vs", "no", "nos", "cir", "inc", "co", "corp", "ltd", "llc", "supp", "stat", "sec", "art",
                 "cf", "id", "ibid", "see", "mr", "mrs", "ms", "dr", "hon", "jr", "sr", "st", "ch", "para", "p",
                 "pp", "n", "al", "ed", "op", "cit", "app", "mass", "cal", "ins", "cas", "assoc", "bros", "mfg",
                 "dist", "div", "dep't", "gov't", "ass'n", "int'l", "nat'l", "e.g", "i.e", "u.s", "u.s.c", "r.i"}
PAGE_MARKER = re.compile(r"-\s?\d+\s?-")
MIN_UNIT = 40            # shorter sentences are merged into their neighbour
OPENERS = "\"'([“‘"
CLOSERS = "\"')]”’"


def paragraphs(body):
    out, current = [], []
    for line in body.split("\n"):
        text = " ".join(line.split())
        if not text or PAGE_MARKER.fullmatch(text):
            continue
        if len(line) - len(line.lstrip(" ")) >= 6 and current:
            out.append(" ".join(current)); current = []
        current.append(text)
    if current:
        out.append(" ".join(current))
    return out


def sentences(paragraph):
    cuts, n, i = [], len(paragraph), 0
    while i < n:
        if paragraph[i] in ".?!":
            j = i + 1
            while j < n and paragraph[j] in CLOSERS:
                j += 1
            if j < n and paragraph[j] == " " and j + 1 < n:
                nxt = paragraph[j + 1]
                starts = ("A" <= nxt <= "Z") or nxt in OPENERS
                k = i
                while k > 0 and paragraph[k - 1] != " ":
                    k -= 1
                word = paragraph[k:i].lstrip(OPENERS).lower()
                abbreviation = paragraph[i] == "." and (len(word) <= 1 or word in ABBREVIATIONS)
                if starts and not abbreviation:
                    cuts.append(j)
            i = j
        else:
            i += 1
    parts, start = [], 0
    for cut in cuts:
        parts.append(paragraph[start:cut]); start = cut + 1
    parts.append(paragraph[start:])
    merged = []
    for part in parts:
        if merged and (len(part) < MIN_UNIT or len(merged[-1]) < MIN_UNIT):
            merged[-1] = merged[-1] + " " + part
        else:
            merged.append(part)
    return merged


if __name__ == "__main__":
    rows = [json.loads(line) for line in open(sys.argv[1])]
    units = [s for r in rows for p in paragraphs(r["body"]) for s in sentences(p)]
    embedded = [u for u in units if len(u) >= MIN_UNIT]
    lengths = sorted(len(u) for u in embedded)
    print(f"paragraphs={sum(len(paragraphs(r['body'])) for r in rows)} units={len(units)} embedded={len(embedded)} "
          f"median={lengths[len(lengths)//2]} p90={lengths[int(len(lengths)*.9)]} max={lengths[-1]}")
    if len(sys.argv) > 2:
        for s in sentences(paragraphs(rows[6]["body"])[int(sys.argv[2])])[:8]:
            print("  |", s[:150])
