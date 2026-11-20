from pathlib import Path
import sqlite3
import sys


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
from osm_routing import allowed_vehicles, oneway_direction, vehicle_mask
import build_telangana_map


def test_osm_access_precedence_filters_vehicle_classes():
    assert allowed_vehicles({"access": "no"}) == []
    assert allowed_vehicles({"access": "no", "motorcar": "yes"}) == ["light"]
    assert allowed_vehicles({"access": "no", "hgv": "yes"}) == ["medium", "heavy"]
    assert allowed_vehicles({"motor_vehicle": "private", "motorcar": "yes"}) == ["light"]
    assert allowed_vehicles({"access": "destination"}) == []
    assert allowed_vehicles({"access": "delivery"}) == []
    assert allowed_vehicles({"access:conditional": "no @ (Mo-Fr 08:00-10:00)"}) == []
    assert allowed_vehicles({"access": "emergency"}) == []
    assert allowed_vehicles({}) is None
    assert vehicle_mask({"access": "no", "hgv": "yes"}) == 6
    assert vehicle_mask({}) == 7


def test_osm_oneway_direction_handles_reverse_roundabouts_and_overrides():
    assert oneway_direction({"oneway": "-1"}) == -1
    assert oneway_direction({"junction": "roundabout"}) == 1
    assert oneway_direction({"junction": "roundabout", "oneway": "no"}) == 0
    assert oneway_direction({"oneway": "yes", "oneway:motor_vehicle": "no"}) == 0
    assert oneway_direction({"oneway": "reversible"}) is None
    assert oneway_direction({"oneway:conditional": "no @ (Mo-Fr 08:00-10:00)"}) is None


def test_route_place_snap_uses_nearest_node_and_maximum_distance():
    candidates = [(11, 17.0, 78.0), (12, 17.01, 78.01)]
    assert build_telangana_map.nearest_route_node(17.009, 78.009, candidates)[0] == 12
    assert build_telangana_map.nearest_route_node(17.2, 78.2, candidates) is None
    assert build_telangana_map.nearest_route_node(
        17.0, 78.0, [(12, 17.0, 78.0), (11, 17.0, 78.0)]
    )[0] == 11


def test_route_schema_keeps_indexed_adjacency_without_temporary_snap_cells():
    db = sqlite3.connect(":memory:")
    build_telangana_map.create_route_schema(db)
    db.executemany("INSERT INTO route_nodes VALUES(?,?,?)", [(1, 17.0, 78.0), (2, 17.1, 78.1)])
    db.execute("INSERT INTO route_edges VALUES(1,0,1,2,1000,30,1,7,NULL)")
    build_telangana_map.finalize_route_schema(db, vacuum=False)
    plan = " ".join(row[3] for row in db.execute(
        "EXPLAIN QUERY PLAN SELECT way_id FROM route_edges WHERE u=? OR (v=? AND oneway=0)", (1, 1)
    ))
    assert "route_edges_from" in plan and "route_edges_to" in plan
    assert db.execute("SELECT name FROM sqlite_master WHERE name='route_node_cells'").fetchone() is None
