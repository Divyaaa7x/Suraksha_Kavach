"""Browser-level coordinator dashboard checks against the synthetic test hub."""
from __future__ import annotations

import socket
import hashlib
import json
import sqlite3
import threading
import time
from pathlib import Path

import pytest
import uvicorn
from playwright.sync_api import sync_playwright

from kavach.api import create_app
from kavach.pack import build_pack

ROOT = Path(__file__).resolve().parents[2]
PACK = ROOT / "data" / "packs" / "kavach-demo-synthetic"


def _start_hub(tmp_path, token=None, pack_dir=PACK, routes_db_path=None):
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        port = probe.getsockname()[1]
    app = create_app(pack_dir, tmp_path / "hub.sqlite3", demo_approvals_enabled=True, hub_token=token,
                     routes_db_path=routes_db_path)
    config = uvicorn.Config(app, host="127.0.0.1", port=port, log_level="error")
    server = uvicorn.Server(config)
    thread = threading.Thread(target=server.run, daemon=True)
    thread.start()
    deadline = time.monotonic() + 10
    while not server.started and time.monotonic() < deadline:
        time.sleep(0.05)
    assert server.started, "test hub failed to start"
    return f"http://127.0.0.1:{port}", server, thread


@pytest.fixture(scope="module")
def dashboard_url(tmp_path_factory):
    url, server, thread = _start_hub(tmp_path_factory.mktemp("dashboard"))
    try:
        yield url
    finally:
        server.should_exit = True
        thread.join(timeout=5)


def test_dashboard_loads_and_computes_warned_route_on_mobile_viewport(dashboard_url):
    with sync_playwright() as playwright:
        browser = playwright.chromium.launch()
        page = browser.new_page(viewport={"width": 390, "height": 844})
        errors = []
        responses = {}
        page.on("pageerror", lambda error: errors.append(str(error)))
        page.on("response", lambda response: responses.update({response.url.rsplit("/", 1)[-1]: response.status}))
        page.goto(dashboard_url, wait_until="networkidle")

        assert page.locator("#hstat").inner_text().startswith("pack DEMO-SYN-001")
        assert page.locator("#origin option").count() == 4
        assert page.locator("#dest option").count() == 4
        assert page.locator("#map").is_visible()
        assert page.locator("#crossingPicker option").count() == 5
        assert responses.get("features") == 200

        page.locator("#mapSearch").fill("C1")
        page.get_by_role("button", name="C1 · culvert").click()
        assert page.locator("#placeCard").is_visible()
        assert page.locator("#placeStatus").inner_text() == "UNKNOWN"
        page.get_by_role("button", name="Close crossing details").click()

        page.get_by_role("button", name="Compute route").click()
        page.wait_for_function("document.querySelector('#routeOut').textContent.includes('Result: NO_VERIFIED_ROUTE')")
        assert "Unverified segments excluded:" in page.locator("#routeOut").inner_text()

        page.locator("#policy").select_option("unverified_warning")
        page.get_by_role("button", name="Compute route").click()
        page.wait_for_function("document.querySelector('#routeOut').textContent.includes('Result: ROUTE_CANDIDATE')")
        assert "Candidate segments highlighted:" in page.locator("#routeOut").inner_text()

        overflow = page.evaluate("""() => [...document.querySelectorAll('body *')]
          .filter(el => el.getBoundingClientRect().right > window.innerWidth + 1)
          .map(el => `${el.tagName}.${el.className}#${el.id}: ${el.getBoundingClientRect().right}`)""")
        assert not overflow, f"mobile horizontal overflow: {overflow}"
        assert not errors, f"browser errors: {errors}"
        browser.close()


def test_dashboard_token_entry_unlocks_authenticated_api(tmp_path):
    token = "test-hub-token-12345678901234567890abc"
    url, server, thread = _start_hub(tmp_path, token)
    try:
        with sync_playwright() as playwright:
            browser = playwright.chromium.launch()
            page = browser.new_page()
            asset_statuses = []
            page.on("response", lambda response: asset_statuses.append(response.status)
                    if response.url.endswith("/v1/assets") else None)
            page.goto(url, wait_until="networkidle")
            assert "hub unreachable:" in page.locator("#hstat").inner_text()

            page.locator("details").first.locator("summary").click()
            page.locator("#hubToken").fill(token)
            page.locator("#saveHubToken").click()
            page.wait_for_function("document.querySelector('#hstat').textContent.startsWith('pack DEMO-SYN-001')")
            page.wait_for_function("document.querySelectorAll('#origin option').length === 4")

            assert 401 in asset_statuses and 200 in asset_statuses
            assert page.locator("#origin option").count() == 4
            browser.close()
    finally:
        server.should_exit = True
        thread.join(timeout=5)


def _statewide_demo(tmp_path):
    routes = tmp_path / "routes.sqlite"
    with sqlite3.connect(routes) as db:
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
        db.executemany("INSERT INTO route_nodes VALUES(?,?,?)", [(1,17.0,78.0),(2,17.01,78.01)])
        db.execute("INSERT INTO route_edges VALUES(10,0,1,2,1000,30,0,7,100)")
        db.execute("INSERT INTO route_crossings VALUES(100,'bridge')")
        db.executemany("INSERT INTO route_places VALUES(?,?,?,?,?,?,?)", [
            (1,"town","Alpha",17.0,78.0,1,10),(2,"town","Beta",17.01,78.01,2,10),
        ])
        db.executemany("INSERT INTO route_metadata VALUES(?,?)", [("schema","1"),("source_sha256","fixture")])
    digest = hashlib.sha256(routes.read_bytes()).hexdigest()
    source = ROOT / "data" / "packs" / "kavach-demo-synthetic"
    pack = tmp_path / "signed-pack"
    build_pack(pack, json.loads((source / "graph.json").read_text()),
               json.loads((source / "policy.json").read_text()), "STATE-E2E-001",
               (source / "LICENSES.txt").read_text(),
               extra_files={"offline_map.json": json.dumps({"routes_database_sha256":digest}).encode()},
               source_class="SIMULATED")
    return pack, routes


def test_dashboard_searches_and_draws_statewide_route(tmp_path):
    pack, routes = _statewide_demo(tmp_path)
    url, server, thread = _start_hub(tmp_path, pack_dir=pack, routes_db_path=routes)
    try:
        with sync_playwright() as playwright:
            browser = playwright.chromium.launch()
            page = browser.new_page(viewport={"width": 1200, "height": 900})
            errors = []
            page.on("pageerror", lambda error: errors.append(str(error)))
            page.goto(url, wait_until="networkidle")
            page.locator("#routeMode").select_option("statewide")

            for field, query, label in (("stateOrigin", "Al", "Alpha · town · 1"),
                                        ("stateDest", "Be", "Beta · town · 2")):
                page.locator(f"#{field}").fill(query)
                options_id = "stateOriginOptions" if field == "stateOrigin" else "stateDestOptions"
                page.wait_for_function(f"document.querySelector('#{options_id}').options.length === 1")
                page.locator(f"#{field}").fill(label)

            page.locator("#policy").select_option("unverified_warning")
            page.get_by_role("button", name="Compute route").click()
            page.wait_for_function("document.querySelector('#routeOut').textContent.includes('Result: ROUTE_CANDIDATE')")
            assert "Crossings with UNKNOWN status: 1." in page.locator("#routeOut").inner_text()
            assert "Statewide route segments: 1." in page.locator("#routeOut").inner_text()
            assert page.evaluate("routeGeometry.length") == 2
            assert page.locator("#map").is_visible()
            assert not errors, f"browser errors: {errors}"
            browser.close()
    finally:
        server.should_exit = True
        thread.join(timeout=5)
