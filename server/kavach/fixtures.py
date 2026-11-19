"""SYNTHETIC golden fixture modelled on dossier Figure 3 (C1 verified closed, C2 unknown, C3 to evaluate).

Every node, edge, population and coordinate here is INVENTED for testing. Nothing in this file is a
real village, road, bridge or population count. Do not present these as field data.
"""
from __future__ import annotations

SYNTHETIC_LICENSE = (
    "SYNTHETIC DEMO PACK. All geometry, names and populations are invented for testing.\n"
    "Real pilot packs must add OpenStreetMap attribution (c) OpenStreetMap contributors, ODbL,\n"
    "built from a permitted Geofabrik extract (not bulk tile downloads).\n"
)


def golden_graph() -> dict:
    return {
        "source_class": "SIMULATED",
        "nodes": [
            {"node_id": "A", "kind": "settlement", "lat": 18.060, "lon": 78.260, "name": "SYN Village A", "population": 1200},
            {"node_id": "B", "kind": "settlement", "lat": 18.040, "lon": 78.300, "name": "SYN Village B", "population": 800},
            {"node_id": "C", "kind": "settlement", "lat": 18.050, "lon": 78.280, "name": "SYN Village C", "population": 300},
            {"node_id": "J1", "kind": "junction", "lat": 18.052, "lon": 78.262, "name": "", "population": None},
            {"node_id": "J2", "kind": "junction", "lat": 18.048, "lon": 78.275, "name": "", "population": None},
            {"node_id": "J3", "kind": "junction", "lat": 18.045, "lon": 78.288, "name": "", "population": None},
            {"node_id": "H", "kind": "facility", "lat": 18.043, "lon": 78.292, "name": "SYN Hospital", "population": None},
        ],
        "edges": [
            {"edge_id": "E1", "u": "A", "v": "J1", "length_m": 2000, "speed_kmh": 30, "crossing_id": "C1", "oneway": False},
            {"edge_id": "E2", "u": "J1", "v": "H", "length_m": 3000, "speed_kmh": 30, "crossing_id": None, "oneway": False},
            {"edge_id": "E3", "u": "A", "v": "J2", "length_m": 2500, "speed_kmh": 30, "crossing_id": "C2", "oneway": False},
            {"edge_id": "E4", "u": "J2", "v": "J3", "length_m": 2000, "speed_kmh": 30, "crossing_id": None, "oneway": False},
            {"edge_id": "E5", "u": "J3", "v": "H", "length_m": 1500, "speed_kmh": 30, "crossing_id": "C3", "oneway": False},
            {"edge_id": "E6", "u": "B", "v": "H", "length_m": 4000, "speed_kmh": 30, "crossing_id": None, "oneway": False},
            {"edge_id": "E7", "u": "C", "v": "J2", "length_m": 1000, "speed_kmh": 30, "crossing_id": "C4", "oneway": False},
        ],
        "crossings": [
            {"crossing_id": "C1", "kind": "culvert", "restrict_to": []},
            {"crossing_id": "C2", "kind": "causeway", "restrict_to": []},
            {"crossing_id": "C3", "kind": "culvert", "restrict_to": ["light", "medium"]},
            {"crossing_id": "C4", "kind": "culvert", "restrict_to": []},
        ],
    }


DEMO_POLICY = {
    "default_policy": "strict",
    "freshness_days_verified": 30,
    "vehicle_classes": ["light", "medium", "heavy"],
    "note": "Human approval required for every access decision. Synthetic demo policy.",
}
