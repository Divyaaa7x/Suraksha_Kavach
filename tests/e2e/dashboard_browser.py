"""Browser-level coordinator dashboard checks against the synthetic test hub."""
from __future__ import annotations

import socket
import threading
import time
from pathlib import Path

import pytest
import uvicorn
from playwright.sync_api import sync_playwright

from kavach.api import create_app

ROOT = Path(__file__).resolve().parents[2]
PACK = ROOT / "data" / "packs" / "kavach-demo-synthetic"


@pytest.fixture(scope="module")
def dashboard_url(tmp_path_factory):
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        port = probe.getsockname()[1]
    app = create_app(PACK, tmp_path_factory.mktemp("dashboard") / "hub.sqlite3",
                     demo_approvals_enabled=True)
    config = uvicorn.Config(app, host="127.0.0.1", port=port, log_level="error")
    server = uvicorn.Server(config)
    thread = threading.Thread(target=server.run, daemon=True)
    thread.start()
    deadline = time.monotonic() + 10
    while not server.started and time.monotonic() < deadline:
        time.sleep(0.05)
    assert server.started, "test hub failed to start"
    try:
        yield f"http://127.0.0.1:{port}"
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
