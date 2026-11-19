"""Fetch and filter Natural Earth's India admin-1 geometry for the offline overview map.

The app uses this small, non-routable reference layer alongside a separate local OSM route pack.
The upstream world GeoJSON is pinned by content hash so an update cannot silently change the map.
"""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
from urllib.request import urlopen

ROOT = Path(__file__).resolve().parents[1]
SOURCE_URL = (
    "https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/"
    "geojson/ne_10m_admin_1_states_provinces.geojson"
)
SOURCE_SHA256 = "22d0e3ad85eb3e27f17cabf8ba2d50e554fbc27a87796ff891d958185da62fb5"
OUTPUT = ROOT / "data" / "raw" / "india-admin1-overview.json"


def build_overview(source: bytes, expected_areas: int = 36) -> bytes:
    if hashlib.sha256(source).hexdigest() != SOURCE_SHA256:
        raise ValueError("Natural Earth source hash does not match the pinned version")
    world = json.loads(source)
    states = []
    for feature in world["features"]:
        props = feature["properties"]
        if props.get("adm0_a3") != "IND":
            continue
        geometry = feature["geometry"]
        if geometry["type"] == "Polygon":
            polygons = [geometry["coordinates"]]
        elif geometry["type"] == "MultiPolygon":
            polygons = geometry["coordinates"]
        else:
            continue
        # Only the exterior rings are needed for the national overview and state selection.
        rings = [
            [[round(float(lon), 5), round(float(lat), 5)] for lon, lat in polygon[0]]
            for polygon in polygons if polygon and len(polygon[0]) >= 4
        ]
        if rings:
            states.append({"name": props["name"], "code": props["iso_3166_2"], "rings": rings})
    if len(states) != expected_areas:
        raise ValueError(f"expected {expected_areas} India admin-1 areas, found {len(states)}")
    document = {
        "source": "Natural Earth Admin 1 States and Provinces, 1:10m",
        "license": "Public domain; reference boundaries are not authoritative",
        "features": sorted(states, key=lambda item: item["name"]),
    }
    return (json.dumps(document, ensure_ascii=False, separators=(",", ":")) + "\n").encode("utf-8")


def main() -> None:
    with urlopen(SOURCE_URL, timeout=60) as response:
        source = response.read()
    result = build_overview(source)
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_bytes(result)
    print(f"wrote {OUTPUT} ({len(result)} bytes, SHA-256 {hashlib.sha256(result).hexdigest()})")


if __name__ == "__main__":
    main()
