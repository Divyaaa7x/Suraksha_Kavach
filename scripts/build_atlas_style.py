"""Build the app's map styles from the MIT-licensed MapLibre style by dddevid, recoloured for Kavach.

Usage: python scripts/build_atlas_style.py <folder with apple-maps-dark.json and apple-maps-white.json>
Tiles, glyphs: OpenFreeMap (OpenMapTiles schema, data (c) OpenStreetMap contributors).
Elevation for hill shading: AWS Terrain Tiles (terrarium).
"""
import json
import sys
from pathlib import Path

OUT = Path(__file__).resolve().parents[1] / "android/app/src/main/assets/atlas"
NIGHT = {  # layer id -> colour, chosen by eye from a night-mode reference screenshot
    "background": "#467A61", "landcover-ice": "#8C9C9E", "landcover-sand": "#7E7F65", "landcover-grass": "#4B7F63",
    "landcover-wood": "#336B60", "landcover-wetland": "#2F6E70", "landuse-residential": "#47706A",
    "landuse-commercial": "#3A4A58", "landuse-industrial": "#39495A", "landuse-park": "#3F7F58",
    "landuse-hospital": "#4A4750", "landuse-school": "#46504C", "landuse-cemetery": "#2D5446",
    "landuse-military": "#44504A", "waterway-river": "#223A7A", "waterway-stream": "#223A7A", "water": "#14214C",
    "water-outline": "#2A3F7C", "building": "#445A79", "aeroway-apron": "#3C4D60", "aeroway-runway": "#4A5A70",
    "aeroway-taxiway": "#46566A", "road-area-pedestrian": "#42535F", "road-path": "#55707A", "road-rail": "#5C7280",
    "road-rail-hatching": "#5C7280", "road-service-casing": "#243B40", "road-minor-casing": "#233A40",
    "road-secondary-casing": "#223840", "road-primary-casing": "#20333D", "road-trunk-casing": "#1F313B",
    "road-motorway-casing": "#1D2E38", "road-service": "#51636F", "road-minor": "#586A78",
    "road-secondary": "#637389", "road-primary": "#6A788F", "road-trunk": "#74829A", "road-motorway": "#7F8CA6",
    "boundary-country": "#8FA6A8", "boundary-state": "#6C5C80",
}


def build(source: Path, name: str, colours: dict) -> None:
    style = json.loads(source.read_text(encoding="utf-8"))
    style.pop("sprite", None)
    style["sources"]["terrain"] = {
        "type": "raster-dem", "encoding": "terrarium", "tileSize": 256, "maxzoom": 13,
        "tiles": ["https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png"],
    }
    for layer in style["layers"]:
        kind, paint = layer["type"], layer.setdefault("paint", {})
        if layer["id"] in colours:
            paint[f"{kind}-color"] = colours[layer["id"]]
        if layer["id"] == "building-3d":
            paint["fill-extrusion-color"] = "#4D6486" if colours else "#E3E1DC"
            paint["fill-extrusion-height"] = ["coalesce", ["get", "render_height"], 6]
            paint["fill-extrusion-base"] = ["coalesce", ["get", "render_min_height"], 0]
            paint["fill-extrusion-opacity"] = 0.92
        if kind == "symbol":
            layout = layer.setdefault("layout", {})
            bold = layer["id"].startswith(("label-country", "label-city"))
            layout["text-font"] = ["Noto Sans Bold" if bold else "Noto Sans Regular"]
            if "{ref}" not in json.dumps(layout.get("text-field", "")):
                layout["text-field"] = ["coalesce", ["get", "name:en"], ["get", "name:latin"], ["get", "name"]]
            if colours:
                water = "water" in layer["id"]
                paint["text-color"] = "#8FB4F5" if water else "#9BE09B" if "park" in layer["id"] else "#EAF1F7"
                paint["text-halo-color"] = "#1D3336"
                paint["text-halo-width"] = 1.4
    shade = {"id": "hillshade", "type": "hillshade", "source": "terrain", "minzoom": 5,
             "paint": {"hillshade-exaggeration": 0.32, "hillshade-shadow-color": "#10262A" if colours else "#6B6B5A",
                       "hillshade-highlight-color": "#4E6E6C" if colours else "#FFFFFF"}}
    first_line = next(i for i, layer in enumerate(style["layers"]) if layer["type"] == "line")
    style["layers"].insert(first_line, shade)
    # Round, colour-coded place badges under the names, like a consumer map (plain dots, no vendor icons).
    badge = ["match", ["get", "class"],
             ["hospital", "pharmacy", "doctors", "clinic"], "kv-health", ["school", "college", "university"], "kv-school",
             ["park", "garden", "pitch", "stadium", "golf"], "kv-park", ["bus", "railway", "aerialway", "airport"], "kv-transit",
             ["restaurant", "cafe", "fast_food", "bar", "ice_cream"], "kv-food",
             ["shop", "grocery", "clothing_store", "bakery"], "kv-shop", ["fuel"], "kv-fuel",
             ["place_of_worship"], "kv-worship", ["police", "town_hall", "post", "bank"], "kv-civic", "kv-other"]
    first_poi = next(i for i, layer in enumerate(style["layers"]) if layer.get("source-layer") == "poi")
    style["layers"].insert(first_poi, {
        "id": "poi-badge", "type": "symbol", "source": "openmaptiles", "source-layer": "poi", "minzoom": 15,
        "filter": ["any", ["<=", ["get", "rank"], 8], [">=", ["zoom"], 17]],
        "layout": {"icon-image": badge, "icon-allow-overlap": False, "icon-padding": 2}})
    for layer in style["layers"]:
        if layer["type"] == "symbol" and layer.get("source-layer") == "poi":
            layer["layout"].update({"text-anchor": "top", "text-offset": [0, 0.8]})
    # India's official borders replace the tile borders (see build_india_borders.py).
    style["layers"] = [l for l in style["layers"] if l["id"] not in ("boundary-country", "boundary-country-bg")]
    hidden = ["Azad Kashmir", "Azad Jammu and Kashmir", "Gilgit-Baltistan", "Aksai Chin"]
    for layer in style["layers"]:
        if layer["id"] == "boundary-state":
            layer["filter"] = ["all", ["==", ["get", "admin_level"], 4], ["!=", ["get", "disputed"], 1],
                               ["!=", ["get", "maritime"], 1]]
        if layer.get("source-layer") == "place":
            keep = ["!", ["in", ["coalesce", ["get", "name:en"], ["get", "name"], ""], ["literal", hidden]]]
            if "filter" in layer:
                old_filter = layer["filter"]
                if old_filter[0] in ("==", "!=", "in", "!in") and isinstance(old_filter[1], str):
                    field = ["get", old_filter[1]]
                    old_filter = ([old_filter[0], field, old_filter[2]] if old_filter[0] in ("==", "!=")
                                  else ["in", field, ["literal", old_filter[2:]]])
                elif old_filter[0] == "all":
                    old_filter = ["all"] + [[f[0], ["get", f[1]], f[2]] if f[0] in ("==", "!=", "<=", ">=", "<", ">")
                                            and isinstance(f[1], str) else f for f in old_filter[1:]]
                layer["filter"] = ["all", old_filter, keep]
            else:
                layer["filter"] = keep
        if layer["id"] == "label-country":
            layer["paint"]["text-color"] = "#D2A9C0" if colours else "#8A5A7A"
            layer["layout"]["text-transform"] = "uppercase"
    style["sources"]["india-borders"] = {"type": "geojson", "data": "asset://atlas/borders.geojson"}
    first_symbol = next(i for i, l in enumerate(style["layers"]) if l["type"] == "symbol")
    style["layers"].insert(first_symbol, {
        "id": "india-borders", "type": "line", "source": "india-borders",
        "layout": {"line-join": "round", "line-cap": "round"},
        "paint": {"line-color": "#8A5A86" if colours else "#A0709C",
                  "line-width": ["interpolate", ["linear"], ["zoom"], 2, 0.7, 6, 1.3, 12, 2.4]}})
    # Relief shading colours at country scale, fading out before streets appear.
    style["sources"]["relief"] = {"type": "raster", "tileSize": 256, "maxzoom": 6,
                                  "tiles": ["https://tiles.openfreemap.org/natural_earth/ne2sr/{z}/{x}/{y}.png"]}
    style["layers"].insert(1, {"id": "relief", "type": "raster", "source": "relief", "maxzoom": 8, "paint": {
        "raster-opacity": ["interpolate", ["linear"], ["zoom"], 3, 0.38 if colours else 0.5, 7.5, 0],
        "raster-brightness-max": 0.62 if colours else 1.0, "raster-saturation": 0.15}})
    # City dots in front of names, like a consumer map.
    first_symbol = next(i for i, l in enumerate(style["layers"]) if l["type"] == "symbol")
    style["layers"].insert(first_symbol, {
        "id": "city-dot", "type": "circle", "source": "openmaptiles", "source-layer": "place", "minzoom": 4,
        "maxzoom": 10, "filter": ["all", ["==", ["get", "class"], "city"], ["<=", ["get", "rank"], 8]],
        "paint": {"circle-radius": 3.2, "circle-color": "#1B2333", "circle-stroke-color": "#FFFFFF",
                  "circle-stroke-width": 1.6}})
    for layer in style["layers"]:
        if layer["id"] in ("label-city-large", "label-city-medium"):
            layer["layout"].update({"text-anchor": "bottom-left", "text-offset": [0.35, -0.05]})
    # Country scale shows borders and city names, not road networks; the camera is set by the app.
    style.pop("center", None)
    style.pop("zoom", None)
    starts = {"road-motorway": 6.5, "road-motorway-casing": 6.5, "road-trunk": 7, "road-trunk-casing": 7,
              "road-primary": 8, "road-primary-casing": 8, "road-secondary": 9.5, "road-secondary-casing": 9.5,
              "boundary-state": 3.5}
    for layer in style["layers"]:
        if layer["id"] in starts:
            layer["minzoom"] = starts[layer["id"]]
        if layer["id"] in ("city-dot", "label-city-large"):
            layer["minzoom"] = 3
        if layer["id"] == "city-dot":
            # A dot only where the name is also shown at this zoom.
            layer["filter"] = ["all", ["==", ["get", "class"], "city"],
                               ["<=", ["get", "rank"], ["step", ["zoom"], 3, 6, 7]]]
    (OUT / name).write_text(json.dumps(style, separators=(",", ":")), encoding="utf-8")
    print(name, len(style["layers"]), "layers")


if __name__ == "__main__":
    src = Path(sys.argv[1])
    build(src / "apple-maps-dark.json", "night.json", NIGHT)
    build(src / "apple-maps-white.json", "day.json", {})
