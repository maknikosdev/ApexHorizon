# Map pack builder

Converts OpenStreetMap data into the compact `.apxm` offline map format read by the app.

    pip install osmium numpy
    python build_pack.py --input greece-latest.osm.pbf --bbox 37.60,23.20,38.45,24.10 --out attica.apxm --data-version 20261125
    python make_manifest.py --dir out --regions ../../maps/regions.json --data-version 20261125

`make_test_osm.py` writes a tiny synthetic map used by the unit tests (`app/src/test/resources/test.apxm`).
Format details are in the header comment of `build_pack.py` and `app/.../maps/MapPack.kt`.
Map data © OpenStreetMap contributors, ODbL.
