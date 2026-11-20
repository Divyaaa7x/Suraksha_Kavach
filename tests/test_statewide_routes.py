import hashlib
import json
import sqlite3
from pathlib import Path

import pytest

from kavach.statewide_routes import MAX_RESULT_POINTS, StatewideRoutes, sample_route_points
from kavach.api import create_app
from kavach.pack import build_pack
from fastapi.testclient import TestClient

ROOT = Path(__file__).resolve().parents[1]


def route_db(path):
    with sqlite3.connect(path) as db:
        db.executescript("""
          CREATE TABLE route_nodes(osm_id INTEGER PRIMARY KEY,lat REAL,lon REAL);
          CREATE TABLE route_edges(way_id INTEGER,segment_index INTEGER,u INTEGER,v INTEGER,length_m REAL,
            speed_kmh REAL,oneway INTEGER,vehicle_mask INTEGER,crossing_way_id INTEGER,
            PRIMARY KEY(way_id,segment_index)) WITHOUT ROWID;
          CREATE TABLE route_crossings(way_id INTEGER PRIMARY KEY,kind TEXT);
          CREATE TABLE route_places(place_id INTEGER PRIMARY KEY,kind TEXT,name TEXT,lat REAL,lon REAL,node_id INTEGER,snap_distance_m REAL);
          CREATE TABLE route_metadata(key TEXT PRIMARY KEY,value TEXT);
          CREATE INDEX route_edges_from ON route_edges(u);
          CREATE INDEX route_edges_to ON route_edges(v);
          CREATE INDEX route_edges_crossing ON route_edges(crossing_way_id);
          CREATE INDEX route_places_name ON route_places(name COLLATE NOCASE);
        """)
        db.executemany("INSERT INTO route_metadata VALUES(?,?)", [("schema", "1"), ("source_sha256", "abc")])
        db.executemany("INSERT INTO route_nodes VALUES(?,?,?)", [
            (1, 17.0, 78.0), (2, 17.0, 78.01), (3, 17.01, 78.0), (4, 17.01, 78.01),
        ])
        db.executemany("INSERT INTO route_edges VALUES(?,?,?,?,?,?,?,?,?)", [
            (10, 0, 1, 2, 100, 50, 0, 7, 100),  # crossing shortcut
            (11, 0, 1, 3, 300, 50, 0, 7, None),
            (12, 0, 3, 2, 300, 50, 0, 7, None),
            (13, 0, 2, 4, 100, 50, 1, 1, None), # light-only one-way
            (14, 0, 3, 4, 100, 50, -1, 7, None), # reverse-only direction
        ])
        db.execute("INSERT INTO route_crossings VALUES(100,'bridge')")
        db.execute("INSERT INTO route_places VALUES(1,'town','Alpha',17,78,1,2)")


@pytest.fixture
def routes(tmp_path):
    path = tmp_path / "routes.sqlite"
    route_db(path)
    return StatewideRoutes(path)


def test_search_returns_only_snapped_places(routes):
    assert routes.search_places("alp")[0]["node_id"] == "1"
    assert routes.search_places("missing") == []


def test_unknown_crossing_is_blocked_in_strict_and_flagged_in_warning(routes):
    strict = routes.compute("1", "2")
    assert strict["result"] == "ROUTE_CANDIDATE"
    assert "R10.0" not in strict["edges"]
    assert strict["uncertain_crossings"] == []
    warning = routes.compute("1", "2", policy="unverified_warning")
    assert warning["result"] == "ROUTE_CANDIDATE"
    assert warning["uncertain_crossings"] == ["OSM-W100"]
    assert "UNKNOWN status" in warning["caveat"]


def test_closed_and_restricted_statuses_remain_hard_blocks_in_warning(routes):
    for status in ("VERIFIED_CLOSED", "RESTRICTED"):
        result = routes.compute("1", "2", policy="unverified_warning", statuses={"OSM-W100": status})
        assert result["result"] == "ROUTE_CANDIDATE"
        assert "R10.0" not in result["edges"]


def test_verified_open_crossing_can_be_used(routes):
    result = routes.compute("1", "2", statuses={"OSM-W100": "VERIFIED_OPEN"})
    assert result["result"] == "ROUTE_CANDIDATE"
    assert result["edges"] == ["R10.0"]
    assert result["uncertain_crossings"] == []


def test_vehicle_mask_and_oneway_are_enforced(routes):
    assert routes.compute("1", "4", vehicle="heavy")["result"] == "NO_VERIFIED_ROUTE"
    assert routes.compute("1", "4", vehicle="light")["result"] == "ROUTE_CANDIDATE"
    reverse = routes.compute("4", "2", policy="unverified_warning")
    assert reverse["result"] == "ROUTE_CANDIDATE"
    assert "R13.0" not in reverse["edges"]
    reverse_only = routes.compute("4", "3")
    assert reverse_only["result"] == "ROUTE_CANDIDATE"
    assert reverse_only["edges"] == ["R14.0"]


def test_database_hash_must_match_signed_hash(tmp_path):
    path = tmp_path / "routes.sqlite"
    route_db(path)
    with pytest.raises(ValueError, match="signed pack"):
        StatewideRoutes(path, "0" * 64)


def test_database_accepts_compact_adjacency_index_names(tmp_path):
    path = tmp_path / "compact-routes.sqlite"
    route_db(path)
    with sqlite3.connect(path) as db:
        db.executescript("""
          DROP INDEX route_edges_from;
          DROP INDEX route_edges_to;
          DROP INDEX route_edges_crossing;
          CREATE INDEX route_edges_compact_from ON route_edges(u);
          CREATE INDEX route_edges_compact_to ON route_edges(v);
          CREATE INDEX route_edges_compact_crossing ON route_edges(crossing_way_id);
        """)
    routes = StatewideRoutes(path)
    assert routes.compute("1", "2", policy="unverified_warning")["result"] == "ROUTE_CANDIDATE"


def test_invalid_vehicle_fails_closed(routes):
    assert "error" in routes.compute("1", "2", vehicle="tractor")


def test_long_route_geometry_is_bounded_and_keeps_both_endpoints():
    points = [[float(i), float(i)] for i in range(MAX_RESULT_POINTS + 10)]
    sampled, truncated = sample_route_points(points)
    assert truncated is True
    assert len(sampled) <= MAX_RESULT_POINTS
    assert sampled[0] == points[0] and sampled[-1] == points[-1]


def test_signed_pack_hash_enables_search_and_web_route_endpoints(tmp_path):
    route_path = tmp_path / "routes.sqlite"
    route_db(route_path)
    digest = hashlib.sha256(route_path.read_bytes()).hexdigest()
    source_pack = ROOT / "data" / "packs" / "kavach-demo-synthetic"
    signed_pack = tmp_path / "pack"
    build_pack(signed_pack,
               json.loads((source_pack / "graph.json").read_text()),
               json.loads((source_pack / "policy.json").read_text()),
               "STATE-ROUTE-TEST", (source_pack / "LICENSES.txt").read_text(),
               extra_files={"offline_map.json": json.dumps({"routes_database_sha256": digest}).encode()},
               source_class="SIMULATED")
    client = TestClient(create_app(signed_pack, tmp_path / "hub.sqlite", routes_db_path=route_path))
    assert client.get("/v1/assets").json()["statewide_routes_available"] is True
    assert client.get("/v1/routes/places", params={"q": "alp"}).json()[0]["node_id"] == "1"
    route = client.post("/v1/statewide/routes/compute", json={
        "origin": "1", "destination": "2", "policy": "unverified_warning",
    }).json()
    assert route["result"] == "ROUTE_CANDIDATE"
    assert route["uncertain_crossings"] == ["OSM-W100"]
