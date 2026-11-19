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
