"""Build Telangana display-map and experimental routable OSM SQLite databases.

Requires pyosmium and Shapely. The South India PBF is a build input and is not
copied into the repository. The display database is packaged; the route database is not yet consumed.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import sqlite3
import struct
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "server"))
sys.path.insert(0, str(ROOT / "scripts"))

from osm_routing import ROAD_SPEED_KMH, allowed_vehicles, haversine_m, oneway_direction, vehicle_mask

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
GRID = 0.1


def spatial_cells(layer: str, min_zoom: int, item_id: int, west: float, south: float, east: float, north: float):
    """Cover a feature bbox with small indexed cells; unlike RTree, works in Android SQLite."""
    x0, x1 = math.floor(west / GRID), math.floor(east / GRID)
    y0, y1 = math.floor(south / GRID), math.floor(north / GRID)
    return ((layer, min_zoom, x, y, item_id)
            for x in range(x0, x1 + 1) for y in range(y0, y1 + 1))


def encode_points(points: list[tuple[float, float]]) -> bytes:
    return b"".join(struct.pack("<ff", point[-2], point[-1]) for point in points)


def nearest_route_node(lat: float, lon: float, candidates: list[tuple[int, float, float]], max_m: float = 1500.0):
    closest = min(candidates, key=lambda n: (haversine_m(lat, lon, n[1], n[2]), n[0]), default=None)
    if closest is None:
        return None
    distance = haversine_m(lat, lon, closest[1], closest[2])
    return (closest[0], distance) if distance <= max_m else None


def create_route_schema(db: sqlite3.Connection) -> None:
    db.executescript("""
      PRAGMA journal_mode=OFF;
      PRAGMA synchronous=OFF;
      PRAGMA page_size=4096;
      CREATE TABLE route_nodes(osm_id INTEGER PRIMARY KEY, lat REAL NOT NULL, lon REAL NOT NULL);
      CREATE TABLE route_edges(way_id INTEGER NOT NULL, segment_index INTEGER NOT NULL, u INTEGER NOT NULL,
        v INTEGER NOT NULL, length_m REAL NOT NULL, speed_kmh REAL NOT NULL, oneway INTEGER NOT NULL,
        vehicle_mask INTEGER NOT NULL, crossing_way_id INTEGER,
        PRIMARY KEY(way_id,segment_index)) WITHOUT ROWID;
      CREATE TABLE route_crossings(way_id INTEGER PRIMARY KEY, kind TEXT NOT NULL);
      CREATE TABLE route_node_cells(cell_x INTEGER NOT NULL, cell_y INTEGER NOT NULL, osm_id INTEGER NOT NULL,
        PRIMARY KEY(cell_x,cell_y,osm_id)) WITHOUT ROWID;
      CREATE TABLE route_places(place_id INTEGER PRIMARY KEY, kind TEXT NOT NULL, name TEXT NOT NULL,
        lat REAL NOT NULL, lon REAL NOT NULL, node_id INTEGER, snap_distance_m REAL);
      CREATE TABLE route_metadata(key TEXT PRIMARY KEY, value TEXT NOT NULL);
    """)


def finalize_route_schema(db: sqlite3.Connection, vacuum: bool = True) -> None:
    db.executescript("""
      CREATE INDEX route_edges_from ON route_edges(u);
      CREATE INDEX route_edges_to ON route_edges(v);
      CREATE INDEX route_edges_crossing ON route_edges(crossing_way_id);
      CREATE INDEX route_places_name ON route_places(name COLLATE NOCASE);
      DROP TABLE route_node_cells;
    """)
    if vacuum:
        db.execute("VACUUM")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("pbf", type=Path, nargs="?", default=ROOT / "data/raw/southern-zone-latest.osm.pbf")
    parser.add_argument("--output", type=Path, default=ROOT / "data/raw/telangana-map.sqlite")
    parser.add_argument("--routes-output", type=Path, default=ROOT / "data/raw/telangana-routes.sqlite")
    args = parser.parse_args()
    if not args.pbf.is_file():
        parser.error(f"OSM PBF not found: {args.pbf}")
    if args.output.resolve() == args.routes_output.resolve():
        parser.error("--output and --routes-output must name separate databases")

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
    args.routes_output.parent.mkdir(parents=True, exist_ok=True)
    args.routes_output.unlink(missing_ok=True)
    route_db = sqlite3.connect(args.routes_output)
    db.executescript("""
      PRAGMA journal_mode=OFF;
      PRAGMA synchronous=OFF;
      PRAGMA page_size=4096;
      CREATE TABLE roads(id INTEGER PRIMARY KEY, highway TEXT NOT NULL, name TEXT NOT NULL,
        min_zoom INTEGER NOT NULL, in_state INTEGER NOT NULL, geometry BLOB NOT NULL);
      CREATE INDEX roads_zoom ON roads(min_zoom);
      CREATE TABLE waterways(id INTEGER PRIMARY KEY, kind TEXT NOT NULL, name TEXT NOT NULL,
        geometry BLOB NOT NULL);
      CREATE TABLE places(id INTEGER PRIMARY KEY, kind TEXT NOT NULL, name TEXT NOT NULL,
        lat REAL NOT NULL, lon REAL NOT NULL, min_zoom INTEGER NOT NULL, in_state INTEGER NOT NULL);
      CREATE INDEX places_zoom_lat ON places(min_zoom, lat);
      CREATE TABLE boundaries(id INTEGER PRIMARY KEY, admin_level INTEGER NOT NULL,
        name TEXT NOT NULL, min_zoom INTEGER NOT NULL, geometry BLOB NOT NULL);
      CREATE TABLE spatial_cells(layer TEXT NOT NULL, min_zoom INTEGER NOT NULL,
        cell_x INTEGER NOT NULL, cell_y INTEGER NOT NULL, item_id INTEGER NOT NULL,
        PRIMARY KEY(layer,min_zoom,cell_x,cell_y,item_id)) WITHOUT ROWID;
      CREATE TABLE map_metadata(key TEXT PRIMARY KEY, value TEXT NOT NULL);
    """)
    create_route_schema(route_db)
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
            self.route_ways = self.route_edges = self.route_nodes = self.excluded_route_ways = 0
            self.next_progress = 20_000
            self.cursor = db.cursor()
            self.route_cursor = route_db.cursor()

        @staticmethod
        def points(way):
            points = []
            for node in way.nodes:
                if node.location.valid():
                    points.append((node.ref, node.location.lat, node.location.lon))
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
            lats = [p[1] for p in points]
            lons = [p[2] for p in points]
            w, s, e, n = min(lons), min(lats), max(lons), max(lats)
            if e < bounds[0] or w > bounds[2] or n < bounds[1] or s > bounds[3]:
                return
            in_state = contains_xy(polygon, lons, lats).tolist() if highway in ROAD_ZOOM else None
            if highway in ROAD_ZOOM:
                inside = any(in_state)
                self.cursor.execute(
                    "INSERT INTO roads VALUES(?,?,?,?,?,?)",
                    (way.id, highway, tags.get("name", ""), ROAD_ZOOM[highway], int(inside),
                     encode_points([(p[1], p[2]) for p in points])),
                )
                self.cursor.executemany("INSERT INTO spatial_cells VALUES(?,?,?,?,?)",
                    spatial_cells("road", ROAD_ZOOM[highway], way.id, w, s, e, n))
                self.roads += 1
                self.inside_roads += inside
            if highway in ROAD_SPEED_KMH:
                direction = oneway_direction(tags)
                access = allowed_vehicles(tags)
                if direction is None or access == []:
                    self.excluded_route_ways += 1
                else:
                    pairs = [(i, points[i], points[i + 1], in_state[i], in_state[i + 1])
                             for i in range(len(points) - 1)]
                    if direction == -1:
                        pairs = [(i, b, a, ib, ia) for i, a, b, ia, ib in reversed(pairs)]
                    crossing_kind = next((kind for kind in ("bridge", "culvert", "ford")
                                          if tags.get(kind) not in (None, "no")), None)
                    crossing_id = f"OSM-W{way.id}" if crossing_kind else None
                    crossing_added = False
                    for original_i, a, b, a_inside, b_inside in pairs:
                        if not (a_inside or b_inside):
                            continue
                        aid, alat, alon = a
                        bid, blat, blon = b
                        for osm_id, lat, lon in ((aid, alat, alon), (bid, blat, blon)):
                            self.route_cursor.execute("INSERT OR IGNORE INTO route_nodes VALUES(?,?,?)",
                                (osm_id, lat, lon))
                            if self.route_cursor.rowcount:
                                self.route_cursor.execute("INSERT INTO route_node_cells VALUES(?,?,?)",
                                    (math.floor(lon / 0.01), math.floor(lat / 0.01), osm_id))
                                self.route_nodes += 1
                        length = haversine_m(alat, alon, blat, blon)
                        if length <= 0:
                            continue
                        mask = vehicle_mask(tags)
                        self.route_cursor.execute("INSERT OR IGNORE INTO route_edges VALUES(?,?,?,?,?,?,?,?,?)",
                            (way.id, original_i, aid, bid, length, ROAD_SPEED_KMH[highway],
                             int(direction != 0), mask, way.id if crossing_id else None))
                        self.route_edges += self.route_cursor.rowcount
                        if crossing_id and not crossing_added:
                            self.route_cursor.execute("INSERT OR IGNORE INTO route_crossings VALUES(?,?)",
                                (way.id, crossing_kind))
                            crossing_added = True
                    self.route_ways += 1
            if water in WATER:
                self.cursor.execute("INSERT INTO waterways VALUES(?,?,?,?)",
                    (way.id, water, tags.get("name", ""), encode_points(points)))
                self.cursor.executemany("INSERT INTO spatial_cells VALUES(?,?,?,?,?)",
                    spatial_cells("water", 0, way.id, w, s, e, n))
                self.waterways += 1
            if admin and admin_level in {"4", "5", "6", "7", "8"}:
                level = int(admin_level)
                self.cursor.execute("INSERT OR REPLACE INTO boundaries VALUES(?,?,?,?,?)",
                    (way.id, level, tags.get("name", ""), {4: 6, 5: 7, 6: 9, 7: 10, 8: 11}[level], encode_points(points)))
                self.cursor.executemany("INSERT OR REPLACE INTO spatial_cells VALUES(?,?,?,?,?)",
                    spatial_cells("boundary", {4: 6, 5: 7, 6: 9, 7: 10, 8: 11}[level], way.id, w, s, e, n))
                self.boundaries += 1
            if self.roads + self.waterways >= self.next_progress:
                db.commit()
                route_db.commit()
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
            if inside:
                self.route_cursor.execute(
                    "INSERT OR REPLACE INTO route_places(place_id,kind,name,lat,lon) VALUES(?,?,?,?,?)",
                    (node.id, place or amenity, tags.get("name", ""), lat, lon),
                )

    handler = Handler()
    handler.apply_file(str(args.pbf), locations=True, idx="sparse_mem_array")
    db.commit()
    route_db.commit()
    db.execute("CREATE INDEX places_lon ON places(lon)")
    db.execute("PRAGMA optimize")
    db.commit()
    db.close()
    route_cursor = route_db.cursor()
    for place_id, lat, lon in route_cursor.execute(
        "SELECT place_id,lat,lon FROM route_places").fetchall():
        x, y = math.floor(lon / 0.01), math.floor(lat / 0.01)
        candidates = route_cursor.execute(
            "SELECT n.osm_id,n.lat,n.lon FROM route_node_cells c JOIN route_nodes n ON n.osm_id=c.osm_id "
            "WHERE c.cell_x BETWEEN ? AND ? AND c.cell_y BETWEEN ? AND ?",
            (x - 2, x + 2, y - 2, y + 2),
        ).fetchall()
        snapped = nearest_route_node(lat, lon, candidates)
        if snapped:
            route_cursor.execute("UPDATE route_places SET node_id=?,snap_distance_m=? WHERE place_id=?",
                                 (snapped[0], snapped[1], place_id))
    finalize_route_schema(route_db)
    route_db.executemany("INSERT INTO route_metadata VALUES(?,?)", [
        ("schema", "1"), ("source_timestamp", source_as_of), ("source_sha256", source_hash.hexdigest()),
        ("attribution", "© OpenStreetMap contributors, ODbL 1.0"),
        ("scope", "Telangana road segments with at least one endpoint inside the state boundary"),
    ])
    route_db.commit()
    route_db.close()
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
        "route_ways": handler.route_ways,
        "route_edges": handler.route_edges,
        "route_nodes": handler.route_nodes,
        "route_crossings": route_db_count(args.routes_output, "route_crossings"),
        "route_places_snapped": route_db_count(args.routes_output, "route_places", "node_id IS NOT NULL"),
        "route_places_total": route_db_count(args.routes_output, "route_places"),
        "route_access_or_direction_excluded_ways": handler.excluded_route_ways,
        "routes_database_bytes": args.routes_output.stat().st_size,
        "routes_output": str(args.routes_output),
    }, indent=2))


def route_db_count(path: Path, table: str, where: str = "1=1") -> int:
    with sqlite3.connect(path) as route_db:
        return route_db.execute(f"SELECT count(*) FROM {table} WHERE {where}").fetchone()[0]


if __name__ == "__main__":
    main()
