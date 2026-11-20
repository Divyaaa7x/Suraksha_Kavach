from pathlib import Path
import sqlite3
import sys
import json
import pytest


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
from osm_routing import allowed_vehicles, oneway_direction, vehicle_mask
import build_telangana_map
import build_osm_pack


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


def test_signed_map_metadata_binds_matching_route_database_hashes(tmp_path):
    map_db, routes_db = tmp_path / "map.sqlite", tmp_path / "routes.sqlite"
    source_hash = "a" * 64
    with sqlite3.connect(map_db) as db:
        db.execute("CREATE TABLE map_metadata(key TEXT PRIMARY KEY,value TEXT NOT NULL)")
        db.executemany("INSERT INTO map_metadata VALUES(?,?)", [("source_sha256", source_hash), ("schema", "1")])
    with sqlite3.connect(routes_db) as db:
        db.execute("CREATE TABLE route_metadata(key TEXT PRIMARY KEY,value TEXT NOT NULL)")
        db.executemany("INSERT INTO route_metadata VALUES(?,?)", [("source_sha256", source_hash), ("source_timestamp", "today")])
    map_archive, route_archive = tmp_path / "map.mapdata", tmp_path / "routes.mapdata"
    metadata = json.loads(build_osm_pack.build_offline_map_metadata(map_db, map_archive, routes_db, route_archive))
    assert metadata["routes_database_bytes"] == routes_db.stat().st_size
    assert metadata["routes_archive_bytes"] == route_archive.stat().st_size
    assert metadata["routes_archive_sha256"] == build_osm_pack.sha256_file(route_archive)
    with sqlite3.connect(routes_db) as db:
        db.execute("UPDATE route_metadata SET value=? WHERE key='source_sha256'", ("b" * 64,))
    with pytest.raises(ValueError, match="different OSM extracts"):
        build_osm_pack.build_offline_map_metadata(map_db, map_archive, routes_db, route_archive)
    assert not route_archive.exists()


def test_map_metadata_builder_removes_stale_route_asset_when_no_route_database(tmp_path):
    map_db, map_archive = tmp_path / "map.sqlite", tmp_path / "map.mapdata"
    route_archive = tmp_path / "routes.mapdata"
    with sqlite3.connect(map_db) as db:
        db.execute("CREATE TABLE map_metadata(key TEXT PRIMARY KEY,value TEXT NOT NULL)")
        db.execute("INSERT INTO map_metadata VALUES('source_sha256',?)", ("a" * 64,))
    route_archive.write_bytes(b"stale")
    metadata = json.loads(build_osm_pack.build_offline_map_metadata(
        map_db, map_archive, tmp_path / "missing-routes.sqlite", route_archive,
    ))
    assert "routes_archive_sha256" not in metadata
    assert not route_archive.exists()


def test_pilot_pack_grid_index_finds_nearest_point():
    index = build_osm_pack.GridIndex({"near": (17.0, 78.0), "far": (18.0, 79.0)})
    assert index.nearest(17.001, 78.001, 1500)[0] == "near"
    assert index.nearest(17.0, 78.0, 100)[0] == "near"


def test_release_data_asset_path_is_opt_in(monkeypatch, tmp_path):
    monkeypatch.delenv("KAVACH_RELEASE_DATA_DIR", raising=False)
    assert build_osm_pack.routes_asset_path() == build_osm_pack.ANDROID_ROUTES
    release_data = tmp_path / "release-data"
    monkeypatch.setenv("KAVACH_RELEASE_DATA_DIR", str(release_data))
    assert build_osm_pack.routes_asset_path() == release_data / "maps" / "telangana-routes.mapdata"
