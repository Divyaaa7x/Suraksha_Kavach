"""Build the REAL offline pack from OpenStreetMap data (ODbL).

Input : data/raw/osm-medak-kamareddy-2026-11-19.json   (Overpass API extract, saved as downloaded)
Output: data/packs/kavach-osm-medak-kamareddy/          (signed pack used by the phone and the hub)

What it does
- Road graph: every consecutive node pair of each road way becomes a directed/undirected edge.
  Speed is set by highway class (conservative defaults, not measured speeds).
- Crossings: every road way tagged bridge=*, culvert=* or ford=* becomes one crossing. Its status is
  UNKNOWN until an authorised inspection or report says otherwise. The OSM tag is NOT an inspection.
- Settlements (place=village|town|city|hamlet) and hospitals/clinics (amenity) are linked to the
  nearest road node within LINK_RADIUS_M by an assumed access spur (see policy.json assumptions).
  Places with no road within that radius are listed as unlinked, not invented into the network.
- Rivers/streams/canals are written to features.json for the map only; they are not routed.

Attribution: (c) OpenStreetMap contributors, ODbL 1.0. Keep it on every screen that shows the map.
"""
from __future__ import annotations

import datetime as dt
import gzip
import hashlib
import json
import math
import os
import shutil
import sys
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "server"))

from kavach.graph import criticality, graph_from_dict  # noqa: E402
from kavach.pack import build_pack  # noqa: E402
from build_india_overview import OUTPUT as INDIA_OVERVIEW  # noqa: E402

RAW = ROOT / "data" / "raw" / "osm-medak-kamareddy-2026-11-19.json"
OUT = ROOT / "data" / "packs" / "kavach-osm-medak-kamareddy"
PACK_VERSION = "OSM-MK-2026-11-20"
TELANGANA_MAP = Path(os.environ.get("KAVACH_TELANGANA_MAP_DB", ROOT / "data" / "raw" / "telangana-map.sqlite"))
ANDROID_MAP = ROOT / "android" / "app" / "src" / "main" / "assets" / "maps" / "telangana-map.mapdata"
LINK_RADIUS_M = 1500.0
SPUR_SPEED_KMH = 20.0
ROAD_SPEED_KMH = {
    "motorway": 80, "trunk": 60, "primary": 50, "secondary": 40, "tertiary": 30,
    "unclassified": 25, "residential": 20,
}
PLACE_KINDS = {"village", "town", "city", "hamlet"}
HEALTH_KINDS = {"hospital", "clinic"}
WATER_KINDS = {"river", "stream", "canal", "drain"}
CROSSING_TAGS = ("bridge", "culvert", "ford")

LICENSES = """REAL DATA PACK, built from OpenStreetMap.
(c) OpenStreetMap contributors. Data is available under the Open Database License (ODbL) 1.0:
https://www.openstreetmap.org/copyright  — keep this attribution with any map or export.
Extract: Overpass API, bbox 17.95,78.05 to 18.45,78.55 (Medak / Kamareddy area), retrieved 2026-11-19.
Offline display map: Southern Zone Geofabrik extract downloaded 2026-11-20 (OSM data through
2026-11-18), spatially indexed to Telangana and nearby border context. The statewide display map
does not expand the signed routing graph. Source download: https://download.geofabrik.de/asia/india/southern-zone.html
Crossing tags (bridge=*, culvert=*, ford=*) are mapping data, not inspection results.
Nothing in this pack was produced by a field inspection. Every crossing starts as UNKNOWN.

India overview boundaries: Natural Earth Admin 1 States and Provinces (1:10m), filtered to India,
source https://www.naturalearthdata.com/downloads/10m-cultural-vectors/10m-admin-1-states-provinces/.
Natural Earth data is public domain. These reference boundaries are not authoritative legal limits.
"""


def haversine_m(lat1, lon1, lat2, lon2) -> float:
    r = 6371000.0
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp = p2 - p1
    dl = math.radians(lon2 - lon1)
    h = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(h))


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


class GridIndex:
    """Nearest-point lookup on a 0.01 degree grid (about 1 km cells)."""

    CELL = 0.01

    def __init__(self, points: dict[str, tuple[float, float]]):
        self.cells: dict[tuple[int, int], list[str]] = defaultdict(list)
        self.points = points
        for pid, (lat, lon) in points.items():
            self.cells[self._key(lat, lon)].append(pid)

    def _key(self, lat, lon):
        return (math.floor(lat / self.CELL), math.floor(lon / self.CELL))

    def nearest(self, lat, lon, max_m):
        kx, ky = self._key(lat, lon)
        best, best_d = None, float("inf")
        rings = int(max_m / 1100) + 2
        for dx in range(-rings, rings + 1):
            for dy in range(-rings, rings + 1):
                for pid in self.cells.get((kx + dx, ky + dy), ()):
                    plat, plon = self.points[pid]
                    d = haversine_m(lat, lon, plat, plon)
                    if d < best_d:
                        best, best_d = pid, d
        return (best, best_d) if best is not None and best_d <= max_m else (None, best_d)


def main() -> None:
    data = json.loads(RAW.read_text(encoding="utf-8"))
    els = data["elements"]
    coords = {f"n{e['id']}": (e["lat"], e["lon"]) for e in els if e["type"] == "node"}
    ways = [e for e in els if e["type"] == "way"]

    road_ways = [w for w in ways if "highway" in w.get("tags", {})]
    water_ways = [w for w in ways if w.get("tags", {}).get("waterway") in WATER_KINDS]

    # ---- road network -------------------------------------------------------
    nodes: dict[str, dict] = {}
    edges: list[dict] = []
    crossings: dict[str, dict] = {}
    road_node_ids: set[str] = set()

    for w in road_ways:
        tags = w["tags"]
        hw = tags["highway"]
        if hw not in ROAD_SPEED_KMH:
            continue
        refs = [f"n{i}" for i in w["nodes"]]
        oneway = tags.get("oneway") in ("yes", "true", "1")
        crossing_id, crossing_kind = None, None
        for key in CROSSING_TAGS:
            if tags.get(key) not in (None, "no"):
                crossing_kind = key
                crossing_id = f"OSM-W{w['id']}"
                crossings[crossing_id] = {"crossing_id": crossing_id, "kind": key, "restrict_to": []}
                break
        for i in range(len(refs) - 1):
            u, v = refs[i], refs[i + 1]
            if u == v or u not in coords or v not in coords:
                continue
            road_node_ids.update((u, v))
            edges.append({
                "edge_id": f"R{w['id']}.{i}",
                "u": u, "v": v,
                "length_m": round(haversine_m(*coords[u], *coords[v]), 1),
                "speed_kmh": float(ROAD_SPEED_KMH[hw]),
                "crossing_id": crossing_id,
                "oneway": oneway,
            })

    for nid in road_node_ids:
        lat, lon = coords[nid]
        nodes[nid] = {"node_id": nid, "kind": "junction", "lat": lat, "lon": lon, "name": "", "population": None}

    # ---- settlements and facilities linked to the road network --------------
    index = GridIndex({nid: coords[nid] for nid in road_node_ids})
    unlinked: list[str] = []
    spur_count = 0
    for e in els:
        if e["type"] != "node":
            continue
        tags = e.get("tags", {})
        kind = None
        if tags.get("place") in PLACE_KINDS:
            kind = "settlement"
        elif tags.get("amenity") in HEALTH_KINDS:
            kind = "facility"
        if kind is None:
            continue
        name = tags.get("name", "")
        nid = f"n{e['id']}"
        target, dist = index.nearest(e["lat"], e["lon"], LINK_RADIUS_M)
        if target is None:
            unlinked.append(f"{kind}:{name or nid}")
            continue
        pop = None
        if kind == "settlement" and tags.get("population", "").isdigit():
            pop = int(tags["population"])
        nodes[nid] = {"node_id": nid, "kind": kind, "lat": e["lat"], "lon": e["lon"],
                      "name": name or nid, "population": pop}
        edges.append({
            "edge_id": f"SPUR-{nid}", "u": nid, "v": target,
            "length_m": round(max(dist, 1.0), 1), "speed_kmh": SPUR_SPEED_KMH,
            "crossing_id": None, "oneway": False,
        })
        spur_count += 1

    # Keep only the part of the network reachable from a hospital or settlement? No: keep everything,
    # isolation is computed by the engine. Only drop nodes no edge touches.
    used = {x for ed in edges for x in (ed["u"], ed["v"])}
    nodes = {k: v for k, v in nodes.items() if k in used}

    # ---- features for the map (rivers, streams, canals, drains) -------------
    features = []
    for w in water_ways:
        pts = [coords[f"n{i}"] for i in w["nodes"] if f"n{i}" in coords]
        if len(pts) < 2:
            continue
        step = max(1, len(pts) // 60)  # simplify for the phone
        simplified = pts[::step] + ([pts[-1]] if pts[-1] not in pts[::step] else [])
        features.append({
            "kind": w["tags"]["waterway"],
            "name": w["tags"].get("name", ""),
            "coords": [[round(a, 6), round(b, 6)] for a, b in simplified],
        })
    features_json = json.dumps({"source": "OpenStreetMap, ODbL", "features": features},
                               separators=(",", ":"), ensure_ascii=False).encode("utf-8")
    if not INDIA_OVERVIEW.is_file():
        raise FileNotFoundError(
            f"{INDIA_OVERVIEW} missing; run `python scripts/build_india_overview.py` first"
        )
    india_overview = INDIA_OVERVIEW.read_bytes()

    graph = {
        "source_class": "OSM",
        "nodes": sorted(nodes.values(), key=lambda n: n["node_id"]),
        "edges": sorted(edges, key=lambda e: e["edge_id"]),
        "crossings": sorted(crossings.values(), key=lambda c: c["crossing_id"]),
    }

    policy = {
        "default_policy": "strict",
        "freshness_days_verified": 30,
        "vehicle_classes": ["light", "medium", "heavy"],
        "assumptions": [
            "Road geometry and tags are OpenStreetMap data; they are not a survey and may be incomplete.",
            "Every bridge, culvert and ford starts as UNKNOWN. Passability is never inferred from a tag.",
            f"Places and health facilities are linked to the nearest road node within {int(LINK_RADIUS_M)} m "
            "by a straight access spur. The spur is an assumption, not a mapped road.",
            "Speeds are class defaults for travel-time comparison only. They are not measured.",
            "Settlement populations come from OSM 'population' tags where present, else are unknown. They are estimates.",
            "The extract is clipped to a bounding box; roads that leave the box may look cut off.",
        ],
        "unlinked_places": sorted(unlinked),
        "counts": {
            "road_edges": len(edges) - spur_count,
            "access_spurs": spur_count,
            "crossings_from_osm_tags": len(crossings),
            "settlements": sum(1 for n in nodes.values() if n["kind"] == "settlement"),
            "facilities": sum(1 for n in nodes.values() if n["kind"] == "facility"),
            "features": len(features),
        },
        "note": "Human approval required for every access decision. Real map data; status data is not real yet.",
    }

    # Criticality is a pure function of the graph. It takes about 40 s on this extract, so it is
    # computed once here and shipped inside the signed pack instead of on every request.
    crit = criticality(graph_from_dict(graph))
    crit_json = json.dumps(crit, sort_keys=True, separators=(",", ":")).encode("utf-8")

    if not TELANGANA_MAP.is_file():
        raise FileNotFoundError(
            f"{TELANGANA_MAP} missing; build it with `python scripts/build_telangana_map.py <south-zone.osm.pbf>`"
        )
    ANDROID_MAP.parent.mkdir(parents=True, exist_ok=True)
    with TELANGANA_MAP.open("rb") as source, ANDROID_MAP.open("wb") as target:
        with gzip.GzipFile(filename="", mode="wb", fileobj=target, mtime=0, compresslevel=6) as compressed:
            shutil.copyfileobj(source, compressed, length=1024 * 1024)
    map_metadata = json.dumps({
        "schema": 1,
        "archive_sha256": sha256_file(ANDROID_MAP),
        "database_sha256": sha256_file(TELANGANA_MAP),
        "database_bytes": TELANGANA_MAP.stat().st_size,
        "archive_bytes": ANDROID_MAP.stat().st_size,
        "source": "OpenStreetMap South India Geofabrik extract, ODbL",
    }, sort_keys=True, separators=(",", ":")).encode("utf-8")

    out = build_pack(OUT, graph, policy, pack_version=PACK_VERSION, licenses_text=LICENSES,
                     extra_files={"features.json": features_json, "criticality.json": crit_json,
                                  "india_admin1.json": india_overview, "offline_map.json": map_metadata},
                     source_class="OSM")
    # The hub reads data/packs while the APK reads its own assets. Keep both copies on
    # the exact same signed bytes whenever the source pack is rebuilt.
    android_pack = ROOT / "android" / "app" / "src" / "main" / "assets" / "pack"
    android_pack.mkdir(parents=True, exist_ok=True)
    for path in OUT.iterdir():
        if path.is_file():
            shutil.copyfile(path, android_pack / path.name)
    print(json.dumps(policy["counts"], indent=2))
    print(f"unlinked places/facilities: {len(unlinked)}")
    print(f"built {out}")
    print(f"synced signed APK assets to {android_pack}")


if __name__ == "__main__":
    main()
