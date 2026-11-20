import importlib.util
from pathlib import Path
import sys


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
SPEC = importlib.util.spec_from_file_location("build_osm_pack", ROOT / "scripts" / "build_osm_pack.py")
BUILDER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BUILDER)


def test_osm_access_precedence_filters_vehicle_classes():
    assert BUILDER.allowed_vehicles({"access": "no"}) == []
    assert BUILDER.allowed_vehicles({"access": "no", "motorcar": "yes"}) == ["light"]
    assert BUILDER.allowed_vehicles({"access": "no", "hgv": "yes"}) == ["medium", "heavy"]
    assert BUILDER.allowed_vehicles({"motor_vehicle": "private", "motorcar": "yes"}) == ["light"]
    assert BUILDER.allowed_vehicles({}) is None


def test_osm_oneway_direction_handles_reverse_roundabouts_and_overrides():
    assert BUILDER.oneway_direction({"oneway": "-1"}) == -1
    assert BUILDER.oneway_direction({"junction": "roundabout"}) == 1
    assert BUILDER.oneway_direction({"junction": "roundabout", "oneway": "no"}) == 0
    assert BUILDER.oneway_direction({"oneway": "yes", "oneway:motor_vehicle": "no"}) == 0
