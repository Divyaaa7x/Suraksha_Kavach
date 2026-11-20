"""Local command hub: FastAPI app serving the offline dashboard and /v1 API. No outbound calls."""
from __future__ import annotations

import json
import hmac
import os
import tempfile
import uuid
from typing import Any, Awaitable, Callable
from datetime import datetime, timezone
from pathlib import Path

from fastapi import FastAPI, HTTPException, Query
from fastapi.middleware.gzip import GZipMiddleware
from fastapi.responses import FileResponse, JSONResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field

from .service import Hub
from .pack import demo_signing_key
from .signing import sign_body
from .offline_map import OfflineMap

ROOT = Path(__file__).resolve().parents[2]
# Default is the REAL OpenStreetMap pack (ODbL). Set KAVACH_PACK_DIR to use another pack.
PACK_DIR = Path(os.environ.get("KAVACH_PACK_DIR", ROOT / "data" / "packs" / "kavach-osm-medak-kamareddy"))
DB_PATH = Path(os.environ.get("KAVACH_DB", ROOT / "data" / "hub.sqlite3"))
STATIC = ROOT / "server" / "static"
MAX_API_BODY_BYTES = 512 * 1024


class ApiBodyLimitMiddleware:
    """Bound request memory before FastAPI decodes JSON bodies."""

    def __init__(self, app: Callable[..., Awaitable[None]], max_bytes: int = MAX_API_BODY_BYTES):
        self.app = app
        self.max_bytes = max_bytes

    async def __call__(self, scope: dict[str, Any], receive: Callable[..., Awaitable[dict[str, Any]]],
                       send: Callable[..., Awaitable[None]]) -> None:
        if scope["type"] != "http" or not scope.get("path", "").startswith("/v1/"):
            await self.app(scope, receive, send)
            return

        headers = dict(scope.get("headers", []))
        try:
            declared_length = int(headers.get(b"content-length", b"0"))
        except ValueError:
            response = JSONResponse({"detail": "invalid content length"}, status_code=400)
            await response(scope, receive, send)
            return
        if declared_length > self.max_bytes:
            response = JSONResponse({"detail": "request body exceeds size limit"}, status_code=413)
            await response(scope, receive, send)
            return

        body = bytearray()
        while True:
            message = await receive()
            if message["type"] == "http.disconnect":
                return
            if message["type"] != "http.request":
                continue
            body.extend(message.get("body", b""))
            if len(body) > self.max_bytes:
                response = JSONResponse({"detail": "request body exceeds size limit"}, status_code=413)
                await response(scope, receive, send)
                return
            if not message.get("more_body", False):
                break

        cached_body = bytes(body)
        delivered = False

        async def replay_body() -> dict[str, Any]:
            nonlocal delivered
            if delivered:
                return {"type": "http.disconnect"}
            delivered = True
            return {"type": "http.request", "body": cached_body, "more_body": False}

        await self.app(scope, replay_body, send)


class HubTokenMiddleware:
    """Protect the LAN API and dashboard with a deployment-provided bearer token."""

    def __init__(self, app: Callable[..., Awaitable[None]], token: str):
        self.app = app
        self.expected = f"Bearer {token}".encode("utf-8")

    async def __call__(self, scope: dict[str, Any], receive: Callable[..., Awaitable[dict[str, Any]]],
                       send: Callable[..., Awaitable[None]]) -> None:
        path = scope.get("path", "")
        if (scope["type"] != "http" or path == "/v1/health" or path == "/"
                or path.startswith("/static/")):
            await self.app(scope, receive, send)
            return
        headers = dict(scope.get("headers", []))
        supplied = headers.get(b"authorization", b"")
        if not hmac.compare_digest(supplied, self.expected):
            response = JSONResponse({"detail": "hub authentication required"}, status_code=401,
                                    headers={"WWW-Authenticate": "Bearer"})
            await response(scope, receive, send)
            return
        await self.app(scope, receive, send)


class RouteRequest(BaseModel):
    origin: str = Field(max_length=32)
    destination: str = Field(max_length=32)
    vehicle: str = Field(default="light", max_length=16)
    policy: str = Field(default="strict", pattern="^(strict|unverified_warning)$")
    scenario_at_risk: list[str] = Field(default_factory=list, max_length=50)


class DemoApprovalRequest(BaseModel):
    asset_id: str = Field(min_length=1, max_length=128)
    approved_status: str = Field(pattern="^(VERIFIED_OPEN|VERIFIED_CLOSED|RESTRICTED)$")
    rationale: str = Field(min_length=1, max_length=512)


def create_app(pack_dir: Path = PACK_DIR, db_path: Path = DB_PATH,
               demo_approvals_enabled: bool | None = None,
               hub_token: str | None = None) -> FastAPI:
    if demo_approvals_enabled is None:
        demo_approvals_enabled = os.environ.get("KAVACH_DEMO_MODE", "").lower() in {"1", "true", "yes"}
    if hub_token is None:
        hub_token = os.environ.get("KAVACH_HUB_TOKEN", "")
    hub_token = hub_token.strip()
    if hub_token and len(hub_token.encode("utf-8")) < 32:
        raise ValueError("KAVACH_HUB_TOKEN must contain at least 32 UTF-8 bytes")
    db_path.parent.mkdir(parents=True, exist_ok=True)
    hub = Hub(db_path, pack_dir)
    app = FastAPI(title="SURAKSHA KAVACH local hub", version="0.2.0-prototype")
    app.state.hub = hub
    map_meta = Path(pack_dir) / "offline_map.json"
    map_asset = ROOT / "android" / "app" / "src" / "main" / "assets" / "maps" / "telangana-map.mapdata"
    map_cache = Path(os.environ.get("KAVACH_MAP_CACHE", Path(tempfile.gettempdir()) / "suraksha-kavach-map"))
    app.state.offline_map = OfflineMap(map_asset, map_meta, map_cache) if map_meta.is_file() and map_asset.is_file() else None
    app.add_middleware(GZipMiddleware, minimum_size=1024)
    app.add_middleware(ApiBodyLimitMiddleware)
    if hub_token:
        app.add_middleware(HubTokenMiddleware, token=hub_token)

    @app.get("/v1/health")
    def health():
        return {**hub.health(), "demo_approvals_enabled": demo_approvals_enabled,
                "hub_auth_enabled": bool(hub_token)}

    @app.post("/v1/demo/approvals")
    def demo_approval(req: DemoApprovalRequest):
        if not demo_approvals_enabled:
            return JSONResponse({"status": "disabled", "reason": "set KAVACH_DEMO_MODE=1 to enable synthetic approvals"},
                                status_code=403)
        key_id = "demo-supervisor-01"
        body = {
            "event_id": str(uuid.uuid4()),
            "kind": "APPROVAL",
            "asset_id": req.asset_id,
            "approved_status": req.approved_status,
            "approved_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
            "rationale": req.rationale.strip(),
            "source_class": "SIMULATED",
            "pack_version": hub.pack_version,
            "origin_key_id": key_id,
        }
        if not body["rationale"]:
            return JSONResponse({"status": "rejected", "reason": "rationale required"}, status_code=422)
        event = {**body, "signature": sign_body(body, demo_signing_key(key_id)), "key_id": key_id}
        return hub.submit(event)

    @app.get("/v1/assets")
    def assets():
        g = hub.graph
        return {
            "source_class": g.source_class,
            "crossings": [{"crossing_id": c.crossing_id, "kind": c.kind, "restrict_to": sorted(c.restrict_to)}
                          for c in g.crossings.values()],
            "nodes": [n.__dict__ for n in g.nodes.values()],
            "edges": [e.__dict__ for e in g.edges.values()],
        }

    @app.get("/v1/features")
    def features():
        return JSONResponse(json.loads((hub.pack_dir / "features.json").read_text(encoding="utf-8")))

    @app.get("/v1/india-overview")
    def india_overview():
        # Use the signed-pack copy when available; synthetic test packs use the checked-in snapshot.
        path = hub.pack_dir / "india_admin1.json"
        if not path.is_file():
            path = ROOT / "data" / "raw" / "india-admin1-overview.json"
        return JSONResponse(json.loads(path.read_text(encoding="utf-8")))

    @app.get("/v1/offline-map")
    def offline_map(west: float = Query(ge=76.9, le=82.1), south: float = Query(ge=15.4, le=20.4),
                    east: float = Query(ge=76.9, le=82.1), north: float = Query(ge=15.4, le=20.4),
                    zoom: int = Query(ge=6, le=16)):
        source = app.state.offline_map
        if source is None:
            raise HTTPException(404, "offline Telangana display map is not installed")
        if west >= east or south >= north or east - west > 5.2 or north - south > 5.2:
            raise HTTPException(422, "map viewport is outside the supported Telangana area")
        return JSONResponse(source.query(west, south, east, north, zoom))

    @app.get("/v1/status")
    def status(scenario: str = Query(default="", max_length=500)):
        at_risk = [s for s in scenario.split(",") if s]
        return hub.statuses(at_risk)

    @app.post("/v1/events", status_code=200)
    def events(payload: dict):
        return hub.submit(payload)

    @app.post("/v1/approvals", status_code=200)
    def approvals(payload: dict):
        return hub.submit(payload)

    @app.get("/v1/events")
    def list_events(kind: str | None = Query(default=None, pattern="^(OBSERVATION|APPROVAL)$")):
        return hub.events(kind)

    @app.post("/v1/routes/compute")
    def compute(req: RouteRequest):
        return hub.route(req.origin, req.destination, req.vehicle, req.policy, req.scenario_at_risk)

    @app.get("/v1/isolated-settlements")
    def isolated(vehicle: str = "light", policy: str = Query(default="strict", pattern="^(strict|unverified_warning)$"),
                 scenario: str = Query(default="", max_length=500)):
        return hub.isolated(vehicle, policy, [s for s in scenario.split(",") if s])

    @app.get("/v1/inspection-ranking")
    def inspection(scenario: str = Query(default="", max_length=500)):
        return hub.inspection([s for s in scenario.split(",") if s])

    @app.get("/v1/sync/inventory")
    def inventory():
        return {"event_ids": hub.inventory(), "pack_version": hub.pack_version}

    @app.post("/v1/sync/batch")
    def batch(payload: list[dict]):
        return hub.sync_batch(payload)

    if STATIC.exists():
        app.mount("/static", StaticFiles(directory=STATIC), name="static")

        @app.get("/")
        def index():
            return FileResponse(STATIC / "index.html")

    return app
