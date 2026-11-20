"""Checks on the REAL OpenStreetMap pack (ODbL). These tests protect the data rules, not the map looks."""
from pathlib import Path

from fastapi.testclient import TestClient

from kavach.api import create_app
from kavach.graph import UNKNOWN
from kavach.pack import load_pack

ROOT = Path(__file__).resolve().parents[1]
REAL = ROOT / "data" / "packs" / "kavach-osm-medak-kamareddy"


def test_real_pack_verifies_and_is_osm_sourced():
    p = load_pack(REAL)
    assert p["manifest"]["source_class"] == "OSM"
    assert p["graph"].source_class == "OSM"
    assert p["graph"].crossings, "OSM bridge/culvert/ford tags must produce crossings"


def test_real_pack_carries_attribution():
    text = (REAL / "LICENSES.txt").read_text(encoding="utf-8")
    assert "OpenStreetMap contributors" in text and "ODbL" in text


def test_every_crossing_starts_unknown_not_safe(tmp_path):
    p = load_pack(REAL)
    assert all(c.crossing_id.startswith("OSM-W") for c in p["graph"].crossings.values())
    client = TestClient(create_app(REAL, tmp_path / "test-real.sqlite3"))
    st = client.get("/v1/status").json()
    assert st and all(v["status"] == UNKNOWN for v in st.values())


def test_criticality_is_precomputed_and_used():
    p = load_pack(REAL)
    assert p["graph"].precomputed_criticality is not None
    assert set(p["graph"].precomputed_criticality) == set(p["graph"].crossings)


def test_features_endpoint_serves_rivers(tmp_path):
    client = TestClient(create_app(REAL, tmp_path / "test-real.sqlite3"))
    body = client.get("/v1/features").json()
    assert body["features"] and "OpenStreetMap" in body["source"]


def test_apk_pack_is_byte_identical_and_verified():
    """The app and hub must use the same signed pack, not independently edited copies."""
    from hashlib import sha256
    import json

    apk_pack = ROOT / "android" / "app" / "src" / "main" / "assets" / "pack"
    manifest = json.loads((REAL / "manifest.json").read_bytes())
    assert (apk_pack / "manifest.json").read_bytes() == (REAL / "manifest.json").read_bytes()
    assert (apk_pack / "signature.json").read_bytes() == (REAL / "signature.json").read_bytes()
    for name, expected in manifest["files"].items():
        android_bytes = (apk_pack / name).read_bytes()
        assert sha256(android_bytes).hexdigest() == expected
        assert android_bytes == (REAL / name).read_bytes()


def test_signed_telanganamap_asset_is_complete_and_spatially_indexed(tmp_path):
    import gzip
    import hashlib
    import json
    import sqlite3

    pack = load_pack(REAL)
    metadata = json.loads((REAL / "offline_map.json").read_bytes())
    archive = ROOT / "android/app/src/main/assets/maps/telangana-map.mapdata"
    assert pack["manifest"]["files"]["offline_map.json"]
    assert hashlib.sha256(archive.read_bytes()).hexdigest() == metadata["archive_sha256"]
    database = tmp_path / "telangana-map.sqlite"
    with gzip.open(archive, "rb") as source, database.open("wb") as target:
        while chunk := source.read(1024 * 1024):
            target.write(chunk)
    assert database.stat().st_size == metadata["database_bytes"]
    assert hashlib.sha256(database.read_bytes()).hexdigest() == metadata["database_sha256"]
    db = sqlite3.connect(database)
    assert db.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
    roads, in_state = db.execute("SELECT count(*),sum(in_state) FROM roads").fetchone()
    assert roads > 500_000 and in_state > 500_000
    assert db.execute("SELECT count(*) FROM road_index").fetchone()[0] == roads
    assert db.execute("SELECT count(*) FROM waterways").fetchone()[0] > 9_000
    assert db.execute("SELECT count(*) FROM places").fetchone()[0] > 30_000
    assert db.execute("SELECT count(*) FROM boundaries").fetchone()[0] > 0
    assert db.execute("SELECT value FROM map_metadata WHERE key='source_timestamp'").fetchone()[0].startswith("2026-11-19")
    db.close()


def test_dashboard_offline_map_endpoint_returns_viewport_features(tmp_path, monkeypatch):
    monkeypatch.setenv("KAVACH_MAP_CACHE", str(tmp_path / "map-cache"))
    client = TestClient(create_app(REAL, tmp_path / "map-api.sqlite3"))
    response = client.get("/v1/offline-map", params={
        "west": 78.0, "south": 17.0, "east": 79.0, "north": 18.5, "zoom": 10,
    })
    assert response.status_code == 200
    body = response.json()
    assert body["roads"] and body["waterways"] and body["places"] and body["boundaries"]
    assert all("coords" in item for item in body["roads"])
    assert client.get("/v1/offline-map", params={
        "west": 79.0, "south": 17.0, "east": 78.0, "north": 18.5, "zoom": 10,
    }).status_code == 422
