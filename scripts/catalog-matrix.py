#!/usr/bin/env python3
"""Build docs/acceptance-matrix.json from the pinned Wordcraft catalog.

Each catalog row becomes one acceptance row. Existing rows keep their manual
fields (status, referenceBehavior, missing, evidence, notes) so rerunning the
script after a catalog refresh only adds or retires rows.

Usage: scripts/catalog-matrix.py [--wordcraft DIR] [--out FILE]
"""
import argparse
import json
import os
import re
import subprocess

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
STATUSES = ("unverified", "missing", "partial", "certified", "reference-stub", "not-applicable")
MANUAL = ("status", "referenceBehavior", "missing", "evidence", "notes")


def catalog_rows(wordcraft):
    src = open(os.path.join(wordcraft, "crates/engine/src/catalog.rs"), encoding="utf-8").read()
    body = re.search(r'pub const CATALOG: &str = "\\\n(.*?)";', src, re.S).group(1)
    rows = []
    for line in body.splitlines():
        parts = line.strip().split("|")
        if len(parts) == 4:
            rows.append(dict(zip(("tab", "group", "feature", "command"), parts)))
    return rows


def app_command_ids():
    ids = set()
    pattern = re.compile(r'"([a-z]+\.[a-zA-Z]+)"')
    for module in ("automation", "editor", "cli", "app"):
        for dirpath, _, files in os.walk(os.path.join(ROOT, module, "src", "main")):
            for name in files:
                if name.endswith(".java"):
                    with open(os.path.join(dirpath, name), encoding="ascii", errors="replace") as f:
                        ids.update(pattern.findall(f.read()))
    return ids


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--wordcraft", default="/private/tmp/wordcraft-comparison")
    parser.add_argument("--out", default=os.path.join(ROOT, "docs", "acceptance-matrix.json"))
    args = parser.parse_args()

    head = subprocess.check_output(["git", "-C", args.wordcraft, "rev-parse", "HEAD"], text=True).strip()
    previous = {}
    if os.path.exists(args.out):
        previous = {r["command"]: r for r in json.load(open(args.out))["rows"]}
    app_ids = app_command_ids()

    rows = []
    for row in catalog_rows(args.wordcraft):
        old = previous.get(row["command"], {})
        row["appCommand"] = row["command"] if row["command"] in app_ids else None
        row["status"] = old.get("status", "unverified")
        row["referenceBehavior"] = old.get("referenceBehavior", "unknown")
        row["missing"] = old.get("missing", [])
        row["evidence"] = old.get("evidence", [])
        row["notes"] = old.get("notes", "")
        assert row["status"] in STATUSES, row
        rows.append(row)

    counts = {s: sum(1 for r in rows if r["status"] == s) for s in STATUSES}
    out = {
        "schema": 1,
        "wordcraftHead": head,
        "statuses": list(STATUSES),
        "counts": counts,
        "retired": sorted(set(previous) - {r["command"] for r in rows}),
        "rows": rows,
    }
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(out, f, indent=1)
        f.write("\n")
    print(len(rows), "rows;", counts, ";", sum(1 for r in rows if r["appCommand"]), "with matching app command id")


if __name__ == "__main__":
    main()
