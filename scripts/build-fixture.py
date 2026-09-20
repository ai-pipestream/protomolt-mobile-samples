#!/usr/bin/env python3
"""Merges the CourtListener source metadata into the vector fixture.

Input 1: the NDJSON the Java court sample wrote (documents + potion-512 vectors).
Input 2: the sample's source JSONL (filing date, panel, author, status).
The docket number and court are read from each opinion's own caption text.
Vectors pass through byte-for-byte: this only adds display metadata.
"""
import json, re, sys

vectors_path, source_path, out_path = sys.argv[1:4]
source = [json.loads(line) for line in open(source_path) if line.strip()]
rows = [json.loads(line) for line in open(vectors_path) if line.strip()]
assert len(rows) <= len(source)

COURTS = [(r"For the First Circuit", "1st Cir.")]

with open(out_path, "w") as out:
    for row, meta in zip(rows, source):
        assert row["title"] == meta["case_name"], (row["title"], meta["case_name"])
        head = meta["plain_text"][:1500]
        docket = re.search(r"Nos?\.\s*(\d{2}-\d{3,5})", head)
        court = next((abbr for pattern, abbr in COURTS if re.search(pattern, head, re.I)), "")
        row.update(
            date_filed=meta["date_filed"],
            docket_number=docket.group(1) if docket else "",
            court=court,
            judges=meta.get("judges") or "",
            author=meta.get("author") or "",
            status=meta.get("precedential_status") or "",
        )
        row.pop("embedding_dims", None)
        out.write(json.dumps(row, ensure_ascii=False, separators=(",", ":")) + "\n")
print(f"{len(rows)} opinions -> {out_path}")
