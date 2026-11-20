"""Build a viewport-queryable offline Telangana OSM map database.

Requires pyosmium and Shapely. The South India PBF is a build input and is not
copied into the repository; the compact map database is included in the signed pack.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sqlite3
import struct
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "server"))

ROAD_ZOOM = {
    "motorway": 6, "motorway_link": 7, "trunk": 6, "trunk_link": 7,
    "primary": 6, "primary_link": 7, "secondary": 8, "secondary_link": 9,
    "tertiary": 10, "tertiary_link": 10, "unclassified": 11,
    "residential": 11, "living_street": 12, "service": 13, "track": 13,
    "pedestrian": 14, "footway": 14, "cycleway": 14, "path": 14,
    "bridleway": 14, "steps": 15,
}
WATER = {"river", "stream", "canal", "drain", "ditch"}
PLACES = {"city", "town", "village", "hamlet", "suburb", "neighbourhood", "locality"}


def encode_points(points: list[tuple[float, float]]) -> bytes:
    return b"".join(struct.pack("<ff", lat, lon) for lat, lon in points)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("pbf", type=Path, nargs="?", default=ROOT / "data/raw/southern-zone-latest.osm.pbf")
    parser.add_argument("--output", type=Path, default=ROOT / "data/raw/telangana-map.sqlite")
    args = parser.parse_args()
    if not args.pbf.is_file():
        parser.error(f"OSM PBF not found: {args.pbf}")

    import osmium
    from shapely import contains_xy
    from shapely.geometry import Polygon

    overview = json.loads((ROOT / "data/raw/india-admin1-overview.json").read_text(encoding="utf-8"))
    tg = next(f for f in overview["features"] if f["code"] == "IN-TG")
    polygon = Polygon(tg["rings"][0])
    reader = osmium.io.Reader(str(args.pbf))
    source_as_of = reader.header().get("osmosis_replication_timestamp", "unknown")
    reader.close()
    source_hash = hashlib.sha256()
    with args.pbf.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            source_hash.update(block)
    min_lon, min_lat, max_lon, max_lat = polygon.bounds
    pad = 0.20  # nearby interstate roads give visual context at the state edge
    bounds = (min_lon - pad, min_lat - pad, max_lon + pad, max_lat + pad)

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.unlink(missing_ok=True)
    db = sqlite3.connect(args.output)
    db.executescript("""
      PRAGMA journal_mode=OFF;
      PRAGMA synchronous=OFF;
      PRAGMA page_size=4096;
      CREATE TABLE roads(id INTEGER PRIMARY KEY, highway TEXT NOT NULL, name TEXT NOT NULL,
        min_zoom INTEGER NOT NULL, in_state INTEGER NOT NULL, geometry BLOB NOT NULL);
      CREATE VIRTUAL TABLE road_index USING rtree(id, min_lon, max_lon, min_lat, max_lat);
      CREATE INDEX roads_zoom ON roads(min_zoom);
      CREATE TABLE waterways(id INTEGER PRIMARY KEY, kind TEXT NOT NULL, name TEXT NOT NULL,
        geometry BLOB NOT NULL);
      CREATE VIRTUAL TABLE water_index USING rtree(id, min_lon, max_lon, min_lat, max_lat);
      CREATE TABLE places(id INTEGER PRIMARY KEY, kind TEXT NOT NULL, name TEXT NOT NULL,
        lat REAL NOT NULL, lon REAL NOT NULL, min_zoom INTEGER NOT NULL, in_state INTEGER NOT NULL);
      CREATE INDEX places_zoom_lat ON places(min_zoom, lat);
      CREATE TABLE boundaries(id INTEGER PRIMARY KEY, admin_level INTEGER NOT NULL,
        name TEXT NOT NULL, min_zoom INTEGER NOT NULL, geometry BLOB NOT NULL);
      CREATE VIRTUAL TABLE boundary_index USING rtree(id, min_lon, max_lon, min_lat, max_lat);
      CREATE TABLE map_metadata(key TEXT PRIMARY KEY, value TEXT NOT NULL);
    """)
    db.executemany("INSERT INTO map_metadata VALUES(?,?)", [
        ("schema", "1"),
        ("source", "Geofabrik Southern Zone OSM PBF"),
        ("source_url", "https://download.geofabrik.de/asia/india/southern-zone.html"),
        ("source_timestamp", source_as_of),
        ("source_sha256", source_hash.hexdigest()),
        ("attribution", "© OpenStreetMap contributors, ODbL 1.0"),
    ])

    class Handler(osmium.SimpleHandler):
        def __init__(self):
            super().__init__()
            self.roads = self.waterways = self.places = self.inside_roads = self.boundaries = 0
            self.next_progress = 20_000
            self.cursor = db.cursor()

        @staticmethod
        def points(way):
            points = []
            for node in way.nodes:
                if node.location.valid():
                    points.append((node.location.lat, node.location.lon))
            return points

        def way(self, way):
            tags = way.tags
            highway = tags.get("highway")
            water = tags.get("waterway")
            admin = tags.get("boundary") == "administrative"
            admin_level = tags.get("admin_level", "")
            if highway not in ROAD_ZOOM and water not in WATER and not (admin and admin_level in {"4", "5", "6", "7", "8"}):
                return
            points = self.points(way)
            if len(points) < 2:
                return
            lats = [p[0] for p in points]
            lons = [p[1] for p in points]
            w, s, e, n = min(lons), min(lats), max(lons), max(lats)
            if e < bounds[0] or w > bounds[2] or n < bounds[1] or s > bounds[3]:
                return
            if highway in ROAD_ZOOM:
                inside = bool(contains_xy(polygon, lons, lats).any())
                self.cursor.execute(
                    "INSERT INTO roads VALUES(?,?,?,?,?,?)",
                    (way.id, highway, tags.get("name", ""), ROAD_ZOOM[highway], int(inside), encode_points(points)),
                )
                self.cursor.execute("INSERT INTO road_index VALUES(?,?,?,?,?)", (way.id, w, e, s, n))
                self.roads += 1
                self.inside_roads += inside
            if water in WATER:
                self.cursor.execute("INSERT INTO waterways VALUES(?,?,?,?)",
                    (way.id, water, tags.get("name", ""), encode_points(points)))
                self.cursor.execute("INSERT INTO water_index VALUES(?,?,?,?,?)", (way.id, w, e, s, n))
                self.waterways += 1
            if admin and admin_level in {"4", "5", "6", "7", "8"}:
                level = int(admin_level)
                self.cursor.execute("INSERT OR REPLACE INTO boundaries VALUES(?,?,?,?,?)",
                    (way.id, level, tags.get("name", ""), {4: 6, 5: 7, 6: 9, 7: 10, 8: 11}[level], encode_points(points)))
                self.cursor.execute("INSERT OR REPLACE INTO boundary_index VALUES(?,?,?,?,?)", (way.id, w, e, s, n))
                self.boundaries += 1
            if self.roads + self.waterways >= self.next_progress:
                db.commit()
                print(f"ways={self.roads:,} water={self.waterways:,}", flush=True)
                self.next_progress += 20_000

        def node(self, node):
            if not node.location.valid():
                return
            lat, lon = node.location.lat, node.location.lon
            if not (bounds[0] <= lon <= bounds[2] and bounds[1] <= lat <= bounds[3]):
                return
            tags = node.tags
            place = tags.get("place")
            amenity = tags.get("amenity")
            if place not in PLACES and amenity not in {"hospital", "clinic", "doctors"}:
                return
            if place in {"city", "town"}: zoom = 7
            elif place == "village": zoom = 9
            elif place == "hamlet": zoom = 11
            else: zoom = 12
            inside = bool(contains_xy(polygon, lon, lat))
            self.cursor.execute("INSERT OR REPLACE INTO places VALUES(?,?,?,?,?,?,?)",
                (node.id, place or amenity, tags.get("name", ""), lat, lon, zoom, int(inside)))
            self.places += 1

    handler = Handler()
    handler.apply_file(str(args.pbf), locations=True, idx="sparse_mem_array")
    db.commit()
    db.execute("CREATE INDEX places_lon ON places(lon)")
    db.execute("PRAGMA optimize")
    db.commit()
    db.close()
    print(json.dumps({
        "source": "OpenStreetMap, ODbL",
        "road_ways_in_context": handler.roads,
        "road_ways_with_Telangana_nodes": handler.inside_roads,
        "waterways": handler.waterways,
        "administrative_boundary_ways": handler.boundaries,
        "places_and_facilities": handler.places,
        "source_timestamp": source_as_of,
        "source_sha256": source_hash.hexdigest(),
        "bounds_wsen": [round(bounds[0], 5), round(bounds[1], 5), round(bounds[2], 5), round(bounds[3], 5)],
        "database_bytes": args.output.stat().st_size,
        "output": str(args.output),
    }, indent=2))


if __name__ == "__main__":
    main()
