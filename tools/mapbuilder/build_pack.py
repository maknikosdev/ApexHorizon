#!/usr/bin/env python3
"""
Builds an Apex Horizon offline map pack (.apxm) from OpenStreetMap data.

    python3 build_pack.py --input greece-latest.osm.pbf --bbox 37.6,23.2,38.45,24.1 \
        --out attica.apxm --data-version 20261125

Input may be .osm.pbf or .osm (XML). Requires: pip install osmium numpy

Pack layout (little-endian int32 unless noted; see MapPack.kt for the reader):
  header   16 ints: magic 'APXM', version, N nodes, E edges, G geom points, M names,
                    D display-blob bytes, K key-blob bytes, bbox (minLat,minLon,maxLat,maxLon) e7,
                    dataVersion, 3 reserved
  nodeLat[N] nodeLon[N] edgeStart[N+1] edgeTo[E] edgeLen[E] (decimetres)
  edgeInfo[E] (bits 0-7 speed km/h, 8-10 class, 11 reverse flag, 12-29 geometry point count, 30 no-car flag)
  edgeGeomOff[E] edgeTwin[E] (-1 if none) geomLat[G] geomLon[G]
  nameType[M] nameLat[M] nameLon[M] nameDispOff[M+1] nameKeyOff[M+1]
  display blob (UTF-8, D bytes, padded to 4)  key blob (UTF-8, K bytes)
Geometry of an edge excludes its end nodes; the reverse flag means "stored in opposite direction".
"""
import argparse
import math
import re
import struct
import sys
import unicodedata
import xml.etree.ElementTree as ET
from array import array

import numpy as np

MAGIC = 0x4D585041  # 'APXM' little-endian
VERSION = 1

# class ids: 0 motorway, 1 trunk, 2 primary, 3 secondary, 4 tertiary, 5 minor, 6 service, 7 pedestrian-only
# Every road has a twin edge; for one-way roads the twin has the no-car flag, so walkers may use it.
HIGHWAYS = {
    "motorway": (0, 110), "motorway_link": (0, 50),
    "trunk": (1, 90), "trunk_link": (1, 50),
    "primary": (2, 80), "primary_link": (2, 50),
    "secondary": (3, 70), "secondary_link": (3, 50),
    "tertiary": (4, 60), "tertiary_link": (4, 50),
    "unclassified": (5, 50), "residential": (5, 40), "living_street": (5, 20),
    "service": (6, 20),
}
PED_HIGHWAYS = {"footway", "path", "pedestrian", "steps", "track", "cycleway", "bridleway", "corridor"}
PLACES = {"city": 0, "town": 1, "village": 2, "suburb": 3, "hamlet": 3, "island": 3}
STREET_TYPE = 4


def norm(s: str) -> str:
    """Search normalisation; MUST match MapPack.normalize() in Kotlin."""
    s = unicodedata.normalize("NFD", s)
    s = "".join(c for c in s if unicodedata.category(c) != "Mn")
    s = s.lower().replace("ς", "σ")
    s = "".join(c if c.isalnum() else " " for c in s)
    return " ".join(s.split())


def e7(v: float) -> int:
    return int(round(v * 1e7))


def haversine_m(lat1, lon1, lat2, lon2):
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp = p2 - p1
    dl = math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * 6371000.0 * math.asin(math.sqrt(a))


def parse_speed(tag, default):
    if not tag:
        return default
    m = re.match(r"^\s*(\d+)\s*(mph)?\s*$", tag)
    if not m:
        return default
    v = int(m.group(1))
    if m.group(2):
        v = int(v * 1.609)
    return max(5, min(130, v))


class Source:
    """Collected raw ways and places from the OSM file."""

    def __init__(self, bbox):
        self.bbox = bbox
        self.ways = []   # (ids, lats(e7), lons(e7), cls, speed, oneway, name, name_en, nocar)
        self.places = []  # (name, name_en, lat, lon, type)

    def inside(self, lat, lon):
        b = self.bbox
        return b[0] <= lat <= b[2] and b[1] <= lon <= b[3]

    def add_way(self, tags, ids, lats, lons):
        hw = tags.get("highway")
        if hw not in HIGHWAYS and hw not in PED_HIGHWAYS:
            return
        if not any(self.inside(la / 1e7, lo / 1e7) for la, lo in zip(lats, lons)):
            return
        foot_ok = tags.get("foot") in ("yes", "permissive", "designated")
        if tags.get("access") in ("no", "private") and not foot_ok:
            return
        nocar = 0
        if hw in PED_HIGHWAYS:
            if hw in ("track",) and tags.get("foot") == "no":
                return
            if tags.get("foot") == "no":
                return
            cls, speed, nocar, oneway = 7, 5, 1, 0
        else:
            cls, speed = HIGHWAYS[hw]
            if hw in ("motorway", "motorway_link"):
                pass
            if tags.get("access") in ("no", "private") or tags.get("motor_vehicle") in ("no", "private"):
                if tags.get("motor_vehicle") not in ("yes", "permissive", "destination"):
                    nocar = 1   # walkable, but closed to cars
            if hw == "service" and tags.get("service") in ("parking_aisle", "driveway", "drive-through"):
                nocar = 1
            speed = parse_speed(tags.get("maxspeed"), speed)
            ow = tags.get("oneway", "")
            if ow in ("yes", "true", "1"):
                oneway = 1
            elif ow == "-1":
                oneway = -1
            elif tags.get("junction") in ("roundabout", "circular") or hw in ("motorway", "motorway_link"):
                oneway = 1 if ow != "no" else 0
            else:
                oneway = 0
        self.ways.append((list(ids), list(lats), list(lons), cls, speed, oneway,
                          tags.get("name"), tags.get("name:en"), nocar))

    def add_place(self, tags, lat, lon):
        p = tags.get("place")
        if p in PLACES and tags.get("name") and self.inside(lat / 1e7, lon / 1e7):
            self.places.append((tags["name"], tags.get("name:en"), lat, lon, PLACES[p]))


def read_osm(path, src: Source):
    if path.endswith(".osm") or path.endswith(".xml"):
        read_xml(path, src)
        return
    import osmium

    class H(osmium.SimpleHandler):
        def node(self, n):
            if "place" in n.tags and n.location.valid():
                src.add_place({t.k: t.v for t in n.tags}, e7(n.location.lat), e7(n.location.lon))

        def way(self, w):
            if "highway" not in w.tags:
                return
            try:
                ids = [n.ref for n in w.nodes]
                lats = [e7(n.location.lat) for n in w.nodes]
                lons = [e7(n.location.lon) for n in w.nodes]
            except osmium.InvalidLocationError:
                return
            src.add_way({t.k: t.v for t in w.tags}, ids, lats, lons)

    H().apply_file(path, locations=True, idx="flex_mem")


def read_xml(path, src: Source):
    coords = {}
    for _, el in ET.iterparse(path, events=("end",)):
        if el.tag == "node":
            lat, lon = e7(float(el.get("lat"))), e7(float(el.get("lon")))
            coords[int(el.get("id"))] = (lat, lon)
            tags = {t.get("k"): t.get("v") for t in el.findall("tag")}
            if "place" in tags:
                src.add_place(tags, lat, lon)
            el.clear()
        elif el.tag == "way":
            tags = {t.get("k"): t.get("v") for t in el.findall("tag")}
            refs = [int(n.get("ref")) for n in el.findall("nd")]
            if all(r in coords for r in refs) and "highway" in tags:
                src.add_way(tags, refs, [coords[r][0] for r in refs], [coords[r][1] for r in refs])
            el.clear()


def build(src: Source, data_version: int):
    # Intersection detection: a node used by >1 way, or the end of a way.
    count = {}
    for ids, *_ in src.ways:
        for r in ids:
            count[r] = count.get(r, 0) + 1

    node_index = {}
    node_lat, node_lon = array("i"), array("i")

    def nid(ref, la, lo):
        i = node_index.get(ref)
        if i is None:
            i = len(node_lat)
            node_index[ref] = i
            node_lat.append(la)
            node_lon.append(lo)
        return i

    efrom, eto, elen, einfo, egoff, etwin = array("i"), array("i"), array("i"), array("i"), array("i"), array("i")
    glat, glon = array("i"), array("i")
    street_seen = set()
    names = []  # (display, key, lat, lon, type)

    def add_edge(a, b, length_dm, speed, cls, geom, reverse, twin, nocar=0):
        idx = len(efrom)
        efrom.append(a); eto.append(b); elen.append(length_dm)
        einfo.append((speed & 0xFF) | (cls << 8) | (reverse << 11) | (len(geom) << 12) | (nocar << 30))
        etwin.append(twin)
        return idx

    for ids, lats, lons, cls, speed, oneway, name, name_en, nocar in src.ways:
        if oneway == -1:
            ids, lats, lons = ids[::-1], lats[::-1], lons[::-1]
            oneway = 1
        n = len(ids)
        start = 0
        for i in range(1, n):
            if i == n - 1 or count[ids[i]] > 1:
                seg_lat, seg_lon = lats[start:i + 1], lons[start:i + 1]
                length = sum(
                    haversine_m(seg_lat[k] / 1e7, seg_lon[k] / 1e7, seg_lat[k + 1] / 1e7, seg_lon[k + 1] / 1e7)
                    for k in range(len(seg_lat) - 1))
                a = nid(ids[start], lats[start], lons[start])
                b = nid(ids[i], lats[i], lons[i])
                if length >= 0.5:
                    mid = list(zip(seg_lat[1:-1], seg_lon[1:-1]))
                    goff = len(glat)
                    for la, lo in mid:
                        glat.append(la); glon.append(lo)
                    ldm = int(round(length * 10))
                    fwd = add_edge(a, b, ldm, speed, cls, mid, 0, -1, nocar)
                    egoff.append(goff)
                    rev = add_edge(b, a, ldm, speed, cls, mid, 1, fwd, 1 if (oneway or nocar) else 0)
                    egoff.append(goff)
                    etwin[fwd] = rev
                    if name and cls <= 5:
                        m = len(seg_lat) // 2
                        la, lo = seg_lat[m], seg_lon[m]
                        for nm in {name, name_en} - {None}:
                            key = norm(nm)
                            cell = (key, la // 100000, lo // 100000)  # ~1 km
                            if key and cell not in street_seen:
                                street_seen.add(cell)
                                names.append((nm, key, la, lo, STREET_TYPE))
                start = i

    for nm, nm_en, la, lo, t in src.places:
        for x in {nm, nm_en} - {None}:
            key = norm(x)
            if key:
                names.append((x, key, la, lo, t))

    N, E, G, M = len(node_lat), len(efrom), len(glat), len(names)
    if N == 0 or E == 0:
        sys.exit("No drivable roads found in the given area.")

    # Sort edges by source node (CSR) and remap twin links.
    ef = np.frombuffer(efrom, dtype=np.int32)
    perm = np.argsort(ef, kind="stable")
    inv = np.empty(E, dtype=np.int32)
    inv[perm] = np.arange(E, dtype=np.int32)

    def take(arr):
        return np.frombuffer(arr, dtype=np.int32)[perm]

    et = np.frombuffer(etwin, dtype=np.int32)[perm]
    et = np.where(et >= 0, inv[np.maximum(et, 0)], -1).astype(np.int32)
    edge_start = np.zeros(N + 1, dtype=np.int32)
    edge_start[1:] = np.cumsum(np.bincount(ef, minlength=N)).astype(np.int32)

    disp = [x[0].encode("utf-8") for x in names]
    keys = [x[1].encode("utf-8") for x in names]
    disp_off = np.zeros(M + 1, dtype=np.int32)
    key_off = np.zeros(M + 1, dtype=np.int32)
    disp_off[1:] = np.cumsum([len(x) for x in disp])
    key_off[1:] = np.cumsum([len(x) for x in keys])
    disp_blob, key_blob = b"".join(disp), b"".join(keys)
    pad = (-len(disp_blob)) % 4

    b = src.bbox
    header = [MAGIC, VERSION, N, E, G, M, len(disp_blob), len(key_blob),
              e7(b[0]), e7(b[1]), e7(b[2]), e7(b[3]), data_version, 0, 0, 0]
    return dict(
        header=header,
        arrays=[
            np.frombuffer(node_lat, dtype=np.int32), np.frombuffer(node_lon, dtype=np.int32), edge_start,
            take(eto), take(elen), take(einfo), take(egoff), et,
            np.frombuffer(glat, dtype=np.int32) if G else np.zeros(0, np.int32),
            np.frombuffer(glon, dtype=np.int32) if G else np.zeros(0, np.int32),
            np.array([x[4] for x in names], dtype=np.int32), np.array([x[2] for x in names], dtype=np.int32),
            np.array([x[3] for x in names], dtype=np.int32), disp_off, key_off,
        ],
        blobs=(disp_blob + b"\0" * pad, key_blob),
        stats=(N, E, G, M),
    )


def write(pack, out):
    assert sys.byteorder == "little"
    with open(out, "wb") as f:
        f.write(struct.pack("<16i", *pack["header"]))
        for a in pack["arrays"]:
            f.write(np.ascontiguousarray(a, dtype="<i4").tobytes())
        f.write(pack["blobs"][0])
        f.write(pack["blobs"][1])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--input", required=True)
    ap.add_argument("--bbox", required=True, help="minLat,minLon,maxLat,maxLon")
    ap.add_argument("--out", required=True)
    ap.add_argument("--data-version", type=int, required=True, help="e.g. 20261125")
    a = ap.parse_args()
    bbox = tuple(float(x) for x in a.bbox.split(","))
    src = Source(bbox)
    read_osm(a.input, src)
    pack = build(src, a.data_version)
    write(pack, a.out)
    print("nodes=%d edges=%d geom=%d names=%d" % pack["stats"])


if __name__ == "__main__":
    main()
