"""Road graph engine: crossing status policy, constrained A* routing, isolation and criticality.

Deterministic and dependency-free so the same logic can be mirrored on Android (Kotlin)
and checked with shared golden fixtures (dossier sections 12, 14, 35, 36).
"""
from __future__ import annotations

import hashlib
import heapq
import json
import math
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from typing import Iterable

# Crossing states (dossier section 36)
UNKNOWN = "UNKNOWN"
REPORTED_OPEN = "REPORTED_OPEN"
REPORTED_CLOSED = "REPORTED_CLOSED"
VERIFIED_OPEN = "VERIFIED_OPEN"
VERIFIED_CLOSED = "VERIFIED_CLOSED"
RESTRICTED = "RESTRICTED"
DISPUTED = "DISPUTED"
STALE = "STALE"
PREDICTED_RISK = "PREDICTED_RISK"

# Routing policy
STRICT = "strict"                       # conservative: only VERIFIED_OPEN / permitted RESTRICTED
UNVERIFIED_WARNING = "unverified_warning"  # include unknown crossings but flag them as uncertain

MAX_SPEED_KMH = 80.0  # used by the admissible A* heuristic


@dataclass(frozen=True)
class Node:
    node_id: str
    kind: str  # junction | settlement | facility
    lat: float
    lon: float
    name: str = ""
    population: int | None = None  # settlement only; estimate, not verified count


@dataclass(frozen=True)
class Edge:
    edge_id: str
    u: str
    v: str
    length_m: float
    speed_kmh: float = 30.0
    crossing_id: str | None = None
    oneway: bool = False
    allowed_vehicles: frozenset[str] = frozenset()


@dataclass
class Crossing:
    crossing_id: str
    kind: str  # culvert | causeway | bridge
    restrict_to: frozenset[str] = frozenset()  # vehicle classes allowed when RESTRICTED


@dataclass
class RoadGraph:
    nodes: dict[str, Node]
    edges: dict[str, Edge]
    crossings: dict[str, Crossing] = field(default_factory=dict)
    source_class: str = "SIMULATED"
    pinned_version: str | None = None  # set by load_pack: hash of the signed graph.json bytes
    precomputed_criticality: dict | None = None  # set by load_pack from the signed criticality.json

    def version(self) -> str:
        if self.pinned_version:
            return self.pinned_version
        blob = json.dumps(
            {
                "nodes": sorted((n.node_id, n.kind, n.lat, n.lon) for n in self.nodes.values()),
                "edges": sorted((e.edge_id, e.u, e.v, e.length_m, e.speed_kmh, e.crossing_id, e.oneway,
                                 sorted(e.allowed_vehicles))
                                for e in self.edges.values()),
            },
            sort_keys=True,
        ).encode()
        return "g-" + hashlib.sha256(blob).hexdigest()[:12]

    def adjacency(self, blocked: frozenset[str] = frozenset()):
        adj: dict[str, list[Edge]] = {n: [] for n in self.nodes}
        for e in self.edges.values():
            if e.edge_id in blocked:
                continue
            adj[e.u].append(e)
            if not e.oneway:
                adj[e.v].append(e)
        return adj


def travel_time_s(e: Edge) -> float:
    return e.length_m / (e.speed_kmh / 3.6)


def haversine_m(a: Node, b: Node) -> float:
    r = 6371000.0
    p1, p2 = math.radians(a.lat), math.radians(b.lat)
    dp = p2 - p1
    dl = math.radians(b.lon - a.lon)
    h = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(h))


# ---------------------------------------------------------------- status policy

def edge_policy_decision(status: str, vehicle: str, crossing: Crossing | None, policy: str) -> tuple[bool, str, bool]:
    """Return (eligible, reason, uncertain) for one edge crossing its status."""
    if crossing is None:
        return True, "no crossing", False
    if status == VERIFIED_CLOSED:
        return False, "HARD: verified closed", False
    if status == VERIFIED_OPEN:
        return True, "verified open", False
    if status == RESTRICTED:
        if vehicle in crossing.restrict_to:
            return True, f"restricted: {vehicle} permitted", False
        return False, f"HARD: restricted, {vehicle} not permitted", False
    if status == PREDICTED_RISK:
        return True, "warning only: predicted risk", True
    # UNKNOWN, DISPUTED, REPORTED_*, STALE
    if policy == UNVERIFIED_WARNING:
        return True, f"unverified ({status}): included with warning", True
    return False, f"CONSERVATIVE: status {status} is not verified", False


# ---------------------------------------------------------------- routing

def _astar(graph: RoadGraph, origin: str, dest: str, allowed: dict[str, bool]):
    adj = graph.adjacency()
    dnode = graph.nodes[dest]
    max_ms = MAX_SPEED_KMH / 3.6

    def h(n: str) -> float:
        return haversine_m(graph.nodes[n], dnode) / max_ms

    best = {origin: 0.0}
    prev: dict[str, tuple[str, Edge]] = {}
    pq = [(h(origin), 0.0, origin)]
    while pq:
        _, g, n = heapq.heappop(pq)
        if n == dest:
            path_edges: list[Edge] = []
            cur = dest
            while cur != origin:
                pn, pe = prev[cur]
                path_edges.append(pe)
                cur = pn
            path_edges.reverse()
            return g, path_edges
        if g > best.get(n, math.inf):
            continue
        for e in adj[n]:
            if not allowed.get(e.edge_id, False):
                continue
            m = e.v if e.u == n else e.u
            ng = g + travel_time_s(e)
            if ng < best.get(m, math.inf):
                best[m] = ng
                prev[m] = (n, e)
                heapq.heappush(pq, (ng + h(m), ng, m))
    return None


def _node_sequence(origin: str, path_edges: list[Edge]) -> list[str]:
    seq = [origin]
    cur = origin
    for e in path_edges:
        cur = e.v if e.u == cur else e.u
        seq.append(cur)
    return seq


def plan_route(graph: RoadGraph, statuses: dict[str, str], origin: str, dest: str,
               vehicle: str = "light", policy: str = STRICT) -> dict:
    """Constrained route. Hard exclusions first, then the unknown policy (dossier section 14)."""
    allowed: dict[str, bool] = {}
    excluded: list[dict] = []
    uncertain: list[dict] = []
    for e in graph.edges.values():
        crossing = graph.crossings.get(e.crossing_id) if e.crossing_id else None
        status = statuses.get(e.crossing_id, UNKNOWN) if e.crossing_id else None
        if e.allowed_vehicles and vehicle not in e.allowed_vehicles:
            ok, reason, unc = False, f"HARD: vehicle access denied for {vehicle}", False
        else:
            ok, reason, unc = edge_policy_decision(status or UNKNOWN, vehicle, crossing, policy)
        allowed[e.edge_id] = ok
        if not ok:
            excluded.append({"edge_id": e.edge_id, "crossing_id": e.crossing_id, "reason": reason})
        elif unc:
            uncertain.append({"edge_id": e.edge_id, "crossing_id": e.crossing_id, "reason": reason})

    result = _astar(graph, origin, dest, allowed)
    base = {
        "graph_version": graph.version(),
        "policy": policy,
        "vehicle": vehicle,
        "excluded_edges": sorted(excluded, key=lambda x: x["edge_id"]),
        "uncertain_edges": sorted(uncertain, key=lambda x: x["edge_id"]),
        "source_class": graph.source_class,
    }
    if result is None:
        return {**base, "result": "NO_VERIFIED_ROUTE", "path": [], "travel_time_s": None,
                "caveat": "No route satisfies the policy. Request verification; do not navigate."}
    cost, path_edges = result
    seq = _node_sequence(origin, path_edges)
    caveat = "Route uses unverified crossings; human review required." if uncertain and policy == UNVERIFIED_WARNING \
        else "Passability is not structural safety; human review required."
    return {**base, "result": "ROUTE_CANDIDATE", "path": seq,
            "edges": [e.edge_id for e in path_edges], "travel_time_s": round(cost, 1), "caveat": caveat}


def shortest_path(graph: RoadGraph, origin: str, dest: str, vehicle: str = "light") -> dict:
    """Baseline: ignores crossing status entirely (dossier H2 comparison)."""
    allowed = {e.edge_id: (not e.allowed_vehicles or vehicle in e.allowed_vehicles) for e in graph.edges.values()}
    result = _astar(graph, origin, dest, allowed)
    if result is None:
        return {"result": "NO_ROUTE", "path": [], "travel_time_s": None}
    cost, path_edges = result
    return {"result": "ROUTE_CANDIDATE", "path": _node_sequence(origin, path_edges),
            "edges": [e.edge_id for e in path_edges], "travel_time_s": round(cost, 1)}


# ---------------------------------------------------------------- isolation

class _DSU:
    def __init__(self, items: Iterable[str]):
        self.p = {i: i for i in items}

    def find(self, x: str) -> str:
        while self.p[x] != x:
            self.p[x] = self.p[self.p[x]]
            x = self.p[x]
        return x

    def union(self, a: str, b: str) -> None:
        self.p[self.find(a)] = self.find(b)


def _eligible_edges(graph: RoadGraph, statuses: dict[str, str], vehicle: str, policy: str,
                    blocked: frozenset[str] = frozenset()) -> set[str]:
    out = set()
    for e in graph.edges.values():
        if e.edge_id in blocked:
            continue
        if e.allowed_vehicles and vehicle not in e.allowed_vehicles:
            continue
        if e.crossing_id:
            crossing = graph.crossings.get(e.crossing_id)
            ok, _, _ = edge_policy_decision(statuses.get(e.crossing_id, UNKNOWN), vehicle, crossing, policy)
            if not ok:
                continue
        out.add(e.edge_id)
    return out


def isolated_settlements(graph: RoadGraph, statuses: dict[str, str], vehicle: str = "light",
                         policy: str = STRICT, blocked_crossings: frozenset[str] = frozenset()) -> list[dict]:
    """Settlements with no eligible path to any facility (topology isolation, dossier section 35)."""
    blocked_edges = frozenset(e.edge_id for e in graph.edges.values() if e.crossing_id in blocked_crossings)
    usable = _eligible_edges(graph, statuses, vehicle, policy, blocked_edges)
    dsu = _DSU(graph.nodes.keys())
    for eid in usable:
        e = graph.edges[eid]
        dsu.union(e.u, e.v)
    facility_roots = {dsu.find(n.node_id) for n in graph.nodes.values() if n.kind == "facility"}
    out = []
    for n in sorted(graph.nodes.values(), key=lambda x: x.node_id):
        if n.kind == "settlement" and dsu.find(n.node_id) not in facility_roots:
            out.append({"settlement_id": n.node_id, "name": n.name, "population_estimate": n.population,
                        "reason": "no eligible route to any facility"})
    return out


def criticality(graph: RoadGraph) -> dict[str, int]:
    """Per crossing: settlements that lose facility access if this crossing alone is removed,
    with every other crossing assumed passable (pure network structure, not current status)."""
    all_open = {c: VERIFIED_OPEN for c in graph.crossings}
    base = {s["settlement_id"] for s in isolated_settlements(graph, all_open)}
    scores = {}
    for c in sorted(graph.crossings):
        cut = {s["settlement_id"] for s in isolated_settlements(graph, all_open, blocked_crossings=frozenset([c]))}
        scores[c] = len(cut - base)
    return scores


def inspection_ranking(graph: RoadGraph, statuses: dict[str, str]) -> list[dict]:
    """Crossings whose status is not verified, ranked by structural criticality (FR-08)."""
    crit = graph.precomputed_criticality or criticality(graph)
    unverified = {UNKNOWN, DISPUTED, REPORTED_OPEN, REPORTED_CLOSED, STALE}
    rows = []
    for c, score in crit.items():
        st = statuses.get(c, UNKNOWN)
        if st in unverified:
            rows.append({"crossing_id": c, "status": st, "settlements_cut_off_if_closed": score,
                         "reason": f"{score} settlement(s) depend on this crossing; status {st}"})
    rows.sort(key=lambda r: (-r["settlements_cut_off_if_closed"], r["crossing_id"]))
    return rows


# ---------------------------------------------------------------- loading

def graph_from_dict(d: dict) -> RoadGraph:
    nodes = {n["node_id"]: Node(**n) for n in d["nodes"]}
    edges = {e["edge_id"]: Edge(**{**e, "allowed_vehicles": frozenset(e.get("allowed_vehicles", []))})
             for e in d["edges"]}
    crossings = {c["crossing_id"]: Crossing(c["crossing_id"], c.get("kind", "culvert"),
                                            frozenset(c.get("restrict_to", []))) for c in d.get("crossings", [])}
    return RoadGraph(nodes=nodes, edges=edges, crossings=crossings,
                     source_class=d.get("source_class", "SIMULATED"))


def resolve_status(observations: list[dict], approvals: list[dict], now: datetime,
                   freshness_days: int = 30) -> dict:
    """Derive current crossing status from immutable events (dossier sections 12 and 36).

    - An authorised approval outranks any field report. A later report never overwrites it.
    - Conflicting material reports (open vs closed) with no approval -> DISPUTED.
    - Reports alone -> REPORTED_*. No evidence -> UNKNOWN.
    - Verified status older than freshness_days -> STALE.
    """
    by_asset: dict[str, dict] = {}
    for a in approvals:
        cur = by_asset.get(a["asset_id"])
        if cur is None or a["received_at"] > cur["received_at"]:
            by_asset[a["asset_id"]] = a
    obs_by_asset: dict[str, list[dict]] = {}
    for o in observations:
        obs_by_asset.setdefault(o["asset_id"], []).append(o)

    assets = set(by_asset) | set(obs_by_asset)
    out = {}
    for asset in assets:
        reports = [o for o in obs_by_asset.get(asset, []) if o["observation_type"] != "UNKNOWN"]
        closed_claims = {o["observation_type"] for o in reports if o["observation_type"] in ("WATER", "BLOCKAGE", "CLOSED")}
        open_claims = [o for o in reports if o["observation_type"] == "OPEN"]
        conflict = bool(closed_claims) and bool(open_claims)

        if asset in by_asset:
            ap = by_asset[asset]
            state = ap["approved_status"]
            approved_closed = state == VERIFIED_CLOSED
            contradicts = any((o["observation_type"] == "OPEN") == approved_closed for o in reports)
            if state == VERIFIED_OPEN:
                when = datetime.fromisoformat(ap["approved_at"])
                if now - when > timedelta(days=freshness_days):
                    state = STALE
            out[asset] = {"status": state, "conflict_with_reports": contradicts,
                          "basis": ap["event_id"], "approved_by": ap["origin_key_id"]}
            continue
        if conflict:
            out[asset] = {"status": DISPUTED, "conflict_with_reports": True, "basis": None, "approved_by": None}
        elif reports:
            latest = max(reports, key=lambda o: o["received_at"])
            st = REPORTED_CLOSED if latest["observation_type"] in ("WATER", "BLOCKAGE", "CLOSED") else REPORTED_OPEN
            out[asset] = {"status": st, "conflict_with_reports": False, "basis": latest["event_id"], "approved_by": None}
        else:
            out[asset] = {"status": UNKNOWN, "conflict_with_reports": False, "basis": None, "approved_by": None}
    return out
