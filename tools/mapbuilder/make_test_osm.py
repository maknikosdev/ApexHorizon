#!/usr/bin/env python3
"""Writes a small synthetic OSM XML (a 25x25 street grid near Larissa) for tests."""
import math
import sys

LAT0, LON0, STEP, SIZE = 39.6400, 22.4100, 0.002, 25


def main(path):
    out = ['<?xml version="1.0" encoding="UTF-8"?>', '<osm version="0.6" generator="test">']
    nid = lambda r, c: 1 + r * SIZE + c
    for r in range(SIZE):
        for c in range(SIZE):
            # a gentle bend so roads are not perfectly straight
            lat = LAT0 + r * STEP + 0.0003 * math.sin(c / 3.0)
            out.append('<node id="%d" lat="%.7f" lon="%.7f"/>' % (nid(r, c), lat, LON0 + c * STEP))
    base = 100000
    out.append('<node id="%d" lat="%.7f" lon="%.7f"><tag k="place" v="city"/><tag k="name" v="Λάρισα"/><tag k="name:en" v="Larissa"/></node>' % (base, LAT0 + 12 * STEP, LON0 + 12 * STEP))
    out.append('<node id="%d" lat="%.7f" lon="%.7f"><tag k="place" v="village"/><tag k="name" v="Τεστ Χωριό"/></node>' % (base + 1, LAT0 + 2 * STEP, LON0 + 22 * STEP))
    wid = 1000
    for r in range(SIZE):
        hw = "primary" if r == 12 else "residential"
        nds = "".join('<nd ref="%d"/>' % nid(r, c) for c in range(SIZE))
        out.append('<way id="%d">%s<tag k="highway" v="%s"/><tag k="name" v="Οδός Σειρά %d"/><tag k="name:en" v="Row Street %d"/></way>' % (wid, nds, hw, r, r))
        wid += 1
    for c in range(SIZE):
        nds = "".join('<nd ref="%d"/>' % nid(r, c) for r in range(SIZE))
        extra = '<tag k="oneway" v="yes"/>' if c == 5 else ""
        out.append('<way id="%d">%s<tag k="highway" v="residential"/><tag k="name" v="Οδός Στήλη %d"/>%s</way>' % (wid, nds, c, extra))
        wid += 1
    # A curvy tertiary road with shape nodes (not intersections): from grid (0,24) to grid (24,0).
    shape = []
    for i in range(1, 13):
        t = i / 13.0
        lat = LAT0 + 0 + t * 24 * STEP + 0.004 * math.sin(t * math.pi * 3)
        lon = LON0 + 24 * STEP - t * 24 * STEP + 0.004 * math.cos(t * math.pi * 3)
        sid = 200000 + i
        shape.append(sid)
        out.insert(1 + SIZE * SIZE + i, '<node id="%d" lat="%.7f" lon="%.7f"/>' % (sid, lat, lon))
    nds = '<nd ref="%d"/>' % nid(0, 24) + "".join('<nd ref="%d"/>' % s for s in shape) + '<nd ref="%d"/>' % nid(24, 0)
    out.append('<way id="%d">%s<tag k="highway" v="tertiary"/><tag k="name" v="Panoramic Road"/><tag k="maxspeed" v="70"/></way>' % (wid + 1, nds))
    # A footway shortcut (walkers only) between grid nodes (2,2) and (10,10).
    out.append('<way id="%d"><nd ref="%d"/><nd ref="%d"/><tag k="highway" v="footway"/></way>' % (wid + 2, nid(2, 2), nid(10, 10)))
    out.append("</osm>")
    open(path, "w", encoding="utf-8").write("\n".join(out))


if __name__ == "__main__":
    main(sys.argv[1])
