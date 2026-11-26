"""Land borders as India officially draws them, from Natural Earth's India point-of-view countries.

Usage: python scripts/build_india_borders.py ne_10m_admin_0_countries_ind.geojson
Source: Natural Earth v5.1.2 (public domain). Writes the app asset atlas/borders.geojson.
A border is any ring segment shared by two countries, so coastlines are left out.
This is a cartographic dataset, not a Survey of India certified boundary: verify before any release.
"""
import json
import sys
from collections import defaultdict
from pathlib import Path

OUT = Path(__file__).resolve().parents[1] / "android/app/src/main/assets/atlas/borders.geojson"
# Places that must fall inside India in this view.
MUST_BE_INDIA = {"Muzaffarabad": (73.47, 34.37), "Gilgit": (74.31, 35.92), "Skardu": (75.63, 35.30),
                 "Aksai Chin": (79.20, 35.20), "Tawang": (91.87, 27.59), "Srinagar": (74.80, 34.08)}


def rings(geometry):
    polys = geometry["coordinates"] if geometry["type"] == "MultiPolygon" else [geometry["coordinates"]]
    for poly in polys:
        yield from poly


def inside(point, geometry):
    x, y = point
    hit = False
    for ring in rings(geometry):
        for (x1, y1), (x2, y2) in zip(ring, ring[1:]):
            if (y1 > y) != (y2 > y) and x < x1 + (y - y1) / (y2 - y1) * (x2 - x1):
                hit = not hit
    return hit


def simplify(line, tolerance=0.004):
    if len(line) < 3:
        return line
    keep = {0, len(line) - 1}
    stack = [(0, len(line) - 1)]
    while stack:
        a, b = stack.pop()
        (ax, ay), (bx, by) = line[a], line[b]
        length = ((bx - ax) ** 2 + (by - ay) ** 2) ** 0.5
        worst, index = 0.0, None
        for i in range(a + 1, b):
            px, py = line[i]
            d = (abs((bx - ax) * (ay - py) - (ax - px) * (by - ay)) / length) if length else \
                ((px - ax) ** 2 + (py - ay) ** 2) ** 0.5
            if d > worst:
                worst, index = d, i
        if worst > tolerance:
            keep.add(index)
            stack += [(a, index), (index, b)]
    return [line[i] for i in sorted(keep)]


def main(path):
    features = json.loads(Path(path).read_text(encoding="utf-8"))["features"]
    india = next(f for f in features if f["properties"].get("ADMIN") == "India")
    for name, point in MUST_BE_INDIA.items():
        assert inside(point, india["geometry"]), f"{name} is not inside India in this dataset"
    owners = defaultdict(set)
    for n, feature in enumerate(features):
        for ring in rings(feature["geometry"]):
            pts = [(round(x, 5), round(y, 5)) for x, y in ring]
            for a, b in zip(pts, pts[1:]):
                if a != b:
                    owners[(min(a, b), max(a, b))].add(n)
    graph = defaultdict(set)
    for (a, b), who in owners.items():
        if len(who) > 1:
            graph[a].add(b)
            graph[b].add(a)
    lines = []

    def walk(start):
        line = [start]
        while graph[line[-1]]:
            here = line[-1]
            nxt = graph[here].pop()
            graph[nxt].discard(here)
            line.append(nxt)
            if len(graph[nxt]) != 1:
                break
        return line

    for node in [n for n in list(graph) if len(graph[n]) != 2]:
        while graph[node]:
            lines.append(walk(node))
    for node in list(graph):  # remaining closed loops
        while graph[node]:
            lines.append(walk(node))
    out = {"type": "FeatureCollection", "features": [
        {"type": "Feature", "properties": {},
         "geometry": {"type": "LineString", "coordinates": [list(p) for p in simplify(line)]}} for line in lines]}
    OUT.write_text(json.dumps(out, separators=(",", ":")), encoding="utf-8")
    print(f"{len(lines)} border lines, {OUT.stat().st_size // 1024} KB; all {len(MUST_BE_INDIA)} check places are in India")


if __name__ == "__main__":
    main(sys.argv[1])
