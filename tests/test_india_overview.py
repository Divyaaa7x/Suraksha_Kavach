import hashlib
import json
from pathlib import Path

import pytest

from scripts import build_india_overview


def test_build_overview_filters_and_normalizes_admin1_geometry(monkeypatch):
    source = json.dumps({"features": [
        {"properties": {"adm0_a3": "IND", "name": "Telangana", "iso_3166_2": "IN-TG"},
         "geometry": {"type": "Polygon", "coordinates": [
             [[78.0, 17.0], [79.0, 17.0], [79.0, 18.0], [78.0, 17.0]],
             [[78.2, 17.2], [78.3, 17.2], [78.3, 17.3], [78.2, 17.2]],
         ]}},
        {"properties": {"adm0_a3": "USA", "name": "Elsewhere", "iso_3166_2": "US-X"},
         "geometry": {"type": "Polygon", "coordinates": [[[0, 0], [1, 0], [1, 1], [0, 0]]]}}
    ]}, separators=(",", ":")).encode()
    monkeypatch.setattr(build_india_overview, "SOURCE_SHA256", hashlib.sha256(source).hexdigest())
    result = json.loads(build_india_overview.build_overview(source, expected_areas=1))
    assert [(area["name"], area["code"]) for area in result["features"]] == [("Telangana", "IN-TG")]
    assert result["features"][0]["rings"] == [[[78.0, 17.0], [79.0, 17.0], [79.0, 18.0], [78.0, 17.0]]]


def test_build_overview_rejects_changed_upstream_hash():
    with pytest.raises(ValueError, match="source hash"):
        build_india_overview.build_overview(b"{}")


def test_checked_in_india_overview_is_signed_pack_input():
    root = Path(__file__).resolve().parents[1]
    data = json.loads((root / "data/raw/india-admin1-overview.json").read_text(encoding="utf-8"))
    assert len(data["features"]) == 36
    assert {f["code"] for f in data["features"]} >= {"IN-TG", "IN-LA", "IN-AN", "IN-LD"}
    assert all(ring[0] == ring[-1] and len(ring) >= 4
               for feature in data["features"] for ring in feature["rings"])
