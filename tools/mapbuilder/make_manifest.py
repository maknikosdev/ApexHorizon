#!/usr/bin/env python3
"""Builds manifest.json for a folder of .apxm packs (sizes + SHA-256 + data version)."""
import argparse, hashlib, json, os

ap = argparse.ArgumentParser()
ap.add_argument("--dir", required=True)
ap.add_argument("--regions", required=True, help="maps/regions.json")
ap.add_argument("--data-version", type=int, required=True)
a = ap.parse_args()

names = {r["id"]: r["name"] for r in json.load(open(a.regions, encoding="utf-8"))["regions"]}
out = []
for f in sorted(os.listdir(a.dir)):
    if not f.endswith(".apxm"):
        continue
    rid = f[:-5]
    h = hashlib.sha256()
    with open(os.path.join(a.dir, f), "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    out.append({
        "id": rid, "name": names.get(rid, rid), "file": f,
        "bytes": os.path.getsize(os.path.join(a.dir, f)),
        "sha256": h.hexdigest(), "dataVersion": a.data_version,
    })
json.dump({"format": 1, "regions": out}, open(os.path.join(a.dir, "manifest.json"), "w", encoding="utf-8"), indent=2, ensure_ascii=False)
print("manifest with", len(out), "regions")
