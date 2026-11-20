"""Shared OSM motor-routing defaults used by the pilot and statewide builders."""
from __future__ import annotations

import math

VEHICLE_CLASSES = ("light", "medium", "heavy")
ROAD_SPEED_KMH = {
    "motorway": 90, "motorway_link": 60, "trunk": 70, "trunk_link": 50,
    "primary": 60, "primary_link": 40, "secondary": 50, "secondary_link": 35,
    "tertiary": 40, "tertiary_link": 30, "unclassified": 30, "residential": 25,
    "living_street": 10, "service": 15, "track": 10,
}


def oneway_direction(tags: dict) -> int | None:
    """Return direction, or None when a time-dependent/unknown direction is unsafe to route."""
    if "oneway:conditional" in tags or "oneway:motor_vehicle:conditional" in tags:
        return None
    value = tags.get("oneway:motor_vehicle", tags.get("oneway"))
    if value == "-1":
        return -1
    if value in ("yes", "true", "1"):
        return 1
    if value in ("no", "false", "0"):
        return 0
    if value is not None:
        return None
    return 1 if tags.get("junction") in ("roundabout", "circular") else 0


def allowed_vehicles(tags: dict) -> list[str] | None:
    """Return None when unrestricted; otherwise list allowed classes (possibly empty)."""
    if any(f"{key}:conditional" in tags for key in ("access", "vehicle", "motor_vehicle", "motorcar", "hgv")):
        return []
    denied = {"no", "private", "agricultural", "forestry", "destination", "delivery", "customers",
              "permit", "emergency", "use_sidepath"}
    permitted = []
    for vehicle in VEHICLE_CLASSES:
        specific = "motorcar" if vehicle == "light" else "hgv"
        keys = (specific, "motor_vehicle", "vehicle", "access")
        value = next((tags[k] for k in keys if k in tags), None)
        if value not in denied:
            permitted.append(vehicle)
    return None if len(permitted) == len(VEHICLE_CLASSES) else permitted


def vehicle_mask(tags: dict) -> int:
    """Pack light/medium/heavy access into the low three bits; 7 means all classes."""
    allowed = allowed_vehicles(tags)
    return 7 if allowed is None else sum(1 << i for i, vehicle in enumerate(VEHICLE_CLASSES) if vehicle in allowed)


def haversine_m(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    radius = 6_371_000.0
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp = p2 - p1
    dl = math.radians(lon2 - lon1)
    h = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * radius * math.asin(math.sqrt(h))
