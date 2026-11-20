"""Read-only runtime for the optional OSM statewide route database.

OSM crossing tags carry no passability status. This runtime therefore treats every
mapped crossing as UNKNOWN until a separately trusted field workflow exists.
"""
from __future__ import annotations

import heapq
import hashlib
import math
import sqlite3
from pathlib import Path

from . import graph as G

VEHICLE_BITS = {"light": 1, "medium": 2, "heavy": 4}
MAX_SPEED_KMH = 90.0
MAX_RESULT_POINTS = 50_000


def sample_route_points(points: list[list[float]]) -> tuple[list[list[float]], bool]:
    if len(points) <= MAX_RESULT_POINTS:
        return points, False
    stride = math.ceil((len(points) - 1) / (MAX_RESULT_POINTS - 1))
    sampled = points[::stride]
    if sampled[-1] != points[-1]:
        sampled.append(points[-1])
    return sampled, True


class StatewideRoutes:
    def __init__(self, path: str | Path, expected_sha256: str | None = None):
        self.path = Path(path)
        if not self.path.is_file():
            raise FileNotFoundError(self.path)
        if expected_sha256:
            digest = hashlib.sha256()
            with self.path.open("rb") as source:
                for block in iter(lambda: source.read(1024 * 1024), b""):
                    digest.update(block)
            if digest.hexdigest() != expected_sha256:
                raise ValueError("statewide route database hash does not match the signed pack")
        self.db = sqlite3.connect(f"file:{self.path.as_posix()}?mode=ro", uri=True, check_same_thread=False)
        self.db.row_factory = sqlite3.Row
        required = {"route_nodes", "route_edges", "route_crossings", "route_places", "route_metadata"}
        found = {r[0] for r in self.db.execute("SELECT name FROM sqlite_master WHERE type='table'")}
        if not required <= found:
            self.db.close()
            raise ValueError(f"statewide route database missing tables: {sorted(required - found)}")
        self.metadata = dict(self.db.execute("SELECT key,value FROM route_metadata"))
        if not self.metadata.get("source_sha256") or self.metadata.get("schema") != "1":
            self.db.close()
            raise ValueError("statewide route database metadata is incomplete")
        indexes = {r[0] for r in self.db.execute("SELECT name FROM sqlite_master WHERE type='index'")}
        expected_indexes = {"route_edges_from", "route_edges_to", "route_edges_crossing", "route_places_name"}
        if not expected_indexes <= indexes:
            self.db.close()
            raise ValueError(f"statewide route database missing indexes: {sorted(expected_indexes - indexes)}")

    def search_places(self, query: str, limit: int = 20) -> list[dict]:
        query = query.strip()
        if not query:
            return []
        prefix = query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        rows = self.db.execute(
            "SELECT place_id,kind,name,lat,lon,node_id,snap_distance_m FROM route_places "
            "WHERE node_id IS NOT NULL AND name LIKE ? ESCAPE '\\' COLLATE NOCASE "
            "ORDER BY CASE WHEN name=? COLLATE NOCASE THEN 0 ELSE 1 END,name,place_id LIMIT ?",
            (prefix, query, min(max(limit, 1), 50)),
        ).fetchall()
        return [{"place_id": str(r["place_id"]), "node_id": str(r["node_id"]), "kind": r["kind"],
                 "name": r["name"], "lat": r["lat"], "lon": r["lon"],
                 "snap_distance_m": r["snap_distance_m"]} for r in rows]

    @staticmethod
    def _heuristic(a: sqlite3.Row, b: sqlite3.Row) -> float:
        p1, p2 = math.radians(a["lat"]), math.radians(b["lat"])
        dp, dl = p2 - p1, math.radians(b["lon"] - a["lon"])
        h = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
        return 2 * 6_371_000 * math.asin(math.sqrt(h)) / (MAX_SPEED_KMH / 3.6)

    def compute(self, origin: str, destination: str, vehicle: str = "light", policy: str = G.STRICT,
                statuses: dict[str, str] | None = None) -> dict:
        if vehicle not in VEHICLE_BITS:
            return {"error": "vehicle must be light, medium or heavy"}
        if policy not in (G.STRICT, G.UNVERIFIED_WARNING):
            return {"error": "unsupported route policy"}
        try:
            start_id, end_id = int(origin), int(destination)
        except (TypeError, ValueError):
            return {"error": "origin and destination must be snapped route node IDs"}
        start = self.db.execute("SELECT osm_id,lat,lon FROM route_nodes WHERE osm_id=?", (start_id,)).fetchone()
        end = self.db.execute("SELECT osm_id,lat,lon FROM route_nodes WHERE osm_id=?", (end_id,)).fetchone()
        if start is None or end is None:
            return {"error": "unknown statewide route node"}
        if start_id == end_id:
            return {"result": "ROUTE_CANDIDATE", "path": [str(start_id)], "edges": [],
                    "geometry": [[start["lat"], start["lon"]]], "travel_time_s": 0.0,
                    "uncertain_edges": [], "policy": policy, "vehicle": vehicle,
                    "caveat": "No travel needed. OSM coverage is not a field inspection."}

        bit = VEHICLE_BITS[vehicle]
        statuses = statuses or {}
        best = {start_id: 0.0}
        previous: dict[int, tuple[int, sqlite3.Row]] = {}
        queue = [(self._heuristic(start, end), 0.0, start_id)]
        expanded = 0
        blocked_crossing_edges: set[tuple[int, int]] = set()
        while queue:
            _, cost, node = heapq.heappop(queue)
            if cost != best.get(node):
                continue
            if node == end_id:
                break
            expanded += 1
            # Each tagged crossing segment is UNKNOWN; strict rejects it, warning labels it.
            outgoing = self.db.execute(
                "SELECT e.*,c.kind FROM route_edges e LEFT JOIN route_crossings c ON c.way_id=e.crossing_way_id "
                "WHERE e.u=? AND e.oneway != -1 AND (e.vehicle_mask & ?) != 0 "
                "UNION ALL SELECT e.*,c.kind FROM route_edges e LEFT JOIN route_crossings c ON c.way_id=e.crossing_way_id "
                "WHERE e.v=? AND e.oneway != 1 AND (e.vehicle_mask & ?) != 0",
                (node, bit, node, bit),
            )
            for edge in outgoing:
                crossed = edge["crossing_way_id"] is not None
                if crossed:
                    crossing_id = f"OSM-W{edge['crossing_way_id']}"
                    # No statewide crossing IDs are trusted by the current event workflow.
                    eligible, _, _ = G.edge_policy_decision(
                        statuses.get(crossing_id, G.UNKNOWN), vehicle,
                        G.Crossing(crossing_id, edge["kind"] or "crossing"), policy,
                    )
                    if not eligible:
                        blocked_crossing_edges.add((edge["way_id"], edge["segment_index"]))
                        continue
                neighbor = edge["v"] if edge["u"] == node else edge["u"]
                travel = edge["length_m"] / (edge["speed_kmh"] / 3.6)
                candidate = cost + travel
                if candidate < best.get(neighbor, math.inf):
                    best[neighbor] = candidate
                    previous[neighbor] = (node, edge)
                    point = self.db.execute("SELECT osm_id,lat,lon FROM route_nodes WHERE osm_id=?", (neighbor,)).fetchone()
                    heapq.heappush(queue, (candidate + self._heuristic(point, end), candidate, neighbor))
        if end_id not in best:
            return {"result": "NO_VERIFIED_ROUTE" if policy == G.STRICT else "NO_ROUTE",
                    "path": [], "edges": [], "travel_time_s": None, "policy": policy, "vehicle": vehicle,
                    "expanded_nodes": expanded, "unverified_segments_excluded": len(blocked_crossing_edges),
                    "caveat": "No route satisfies the crossing-status, access and direction policy."}

        path_edges, node_ids = [], [end_id]
        current = end_id
        while current != start_id:
            prior, edge = previous[current]
            path_edges.append(edge)
            current = prior
            node_ids.append(current)
        node_ids.reverse()
        path_edges.reverse()
        geometry = []
        for node_id in node_ids:
            point = self.db.execute("SELECT lat,lon FROM route_nodes WHERE osm_id=?", (node_id,)).fetchone()
            geometry.append([point["lat"], point["lon"]])
        geometry, geometry_truncated = sample_route_points(geometry)
        path_uncertain = sorted({f"OSM-W{edge['crossing_way_id']}" for edge in path_edges
                                 if edge["crossing_way_id"] is not None and
                                 G.edge_policy_decision(
                                     statuses.get(f"OSM-W{edge['crossing_way_id']}", G.UNKNOWN), vehicle,
                                     G.Crossing(f"OSM-W{edge['crossing_way_id']}", edge["kind"] or "crossing"),
                                     policy,
                                 )[2]})
        caveat = ("Route includes OSM-tagged crossings with UNKNOWN status; human verification is required. "
                  "OSM tags do not establish passability or structural safety." if path_uncertain else
                  "OSM coverage is not a field inspection; human review is required.")
        if geometry_truncated:
            caveat += " Display geometry was downsampled to 50,000 points; it is not turn-by-turn navigation."
        return {"result": "ROUTE_CANDIDATE", "path": list(map(str, node_ids)),
                "edges": [f"R{e['way_id']}.{e['segment_index']}" for e in path_edges],
                "geometry": geometry, "travel_time_s": round(best[end_id], 1),
                "uncertain_crossings": path_uncertain, "uncertain_edges": path_uncertain,
                "geometry_truncated": geometry_truncated,
                "expanded_nodes": expanded, "unverified_segments_excluded": len(blocked_crossing_edges),
                "policy": policy, "vehicle": vehicle, "caveat": caveat}
