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
from .operations import OperationsStore, demo_warning_fixture

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


class StatewideRouteRequest(BaseModel):
    origin: str = Field(min_length=1, max_length=20)
    destination: str = Field(min_length=1, max_length=20)
    vehicle: str = Field(default="light", pattern="^(light|medium|heavy)$")
    policy: str = Field(default="strict", pattern="^(strict|unverified_warning)$")


class DemoApprovalRequest(BaseModel):
    asset_id: str = Field(min_length=1, max_length=128)
    approved_status: str = Field(pattern="^(VERIFIED_OPEN|VERIFIED_CLOSED|RESTRICTED)$")
    rationale: str = Field(min_length=1, max_length=512)


class OpsMissionRequest(BaseModel):
    crossing_id: str = Field(min_length=1, max_length=128)
    squad_id: str = Field(min_length=1, max_length=128)
    priority: int = Field(ge=1, le=5)
    warning_id: str | None = Field(default=None, max_length=128)
    actor_role: str = Field(default="commander", pattern="^(commander|supervisor)$")
    actor_id: str = Field(min_length=1, max_length=128)
    idempotency_key: str | None = Field(default=None, min_length=1, max_length=128)


class OpsCapImportRequest(BaseModel):
    xml: str = Field(min_length=1, max_length=450_000)
    source_url: str = Field(min_length=1, max_length=2048)
    source_class: str = Field(default="SIMULATED", pattern="^(ARCHIVED_OFFICIAL|SIMULATED)$")


class OpsTransitionRequest(BaseModel):
    to_state: str = Field(min_length=1, max_length=32)
    actor_role: str = Field(min_length=1, max_length=32)
    actor_id: str = Field(min_length=1, max_length=128)
    idempotency_key: str = Field(min_length=1, max_length=128)


class OpsReceiptRequest(BaseModel):
    responder_id: str = Field(min_length=1, max_length=128)
    receipt_type: str = Field(pattern="^(DELIVERED|READ|ACKNOWLEDGED)$")
    idempotency_key: str = Field(min_length=1, max_length=128)


class OpsInspectionRequest(BaseModel):
    actor_id: str = Field(min_length=1, max_length=128)
    observed_status: str = Field(pattern="^(OPEN|CLOSED|WATER|BLOCKAGE|RESTRICTED|UNKNOWN)$")
    observed_at: str = Field(min_length=1, max_length=64)
    notes: str = Field(min_length=1, max_length=2048)
    coordinates: dict[str, float] | None = None
    evidence: list[dict[str, Any]] = Field(default_factory=list, max_length=20)
    signed_event: dict[str, Any]


class OpsVerifyRequest(BaseModel):
    decision: str = Field(pattern="^(VERIFIED|REJECTED)$")
    actor_role: str = Field(min_length=1, max_length=32)
    actor_id: str = Field(min_length=1, max_length=128)
    rationale: str = Field(min_length=1, max_length=512)


def create_app(pack_dir: Path = PACK_DIR, db_path: Path = DB_PATH,
               demo_approvals_enabled: bool | None = None,
               hub_token: str | None = None,
               routes_db_path: Path | None = None) -> FastAPI:
    if demo_approvals_enabled is None:
        demo_approvals_enabled = os.environ.get("KAVACH_DEMO_MODE", "").lower() in {"1", "true", "yes"}
    if hub_token is None:
        hub_token = os.environ.get("KAVACH_HUB_TOKEN", "")
    hub_token = hub_token.strip()
    if hub_token and len(hub_token.encode("utf-8")) < 32:
        raise ValueError("KAVACH_HUB_TOKEN must contain at least 32 UTF-8 bytes")
    db_path.parent.mkdir(parents=True, exist_ok=True)
    pack_dir = Path(pack_dir)
    offline_meta_path = pack_dir / "offline_map.json"
    offline_meta = json.loads(offline_meta_path.read_text(encoding="utf-8")) if offline_meta_path.is_file() else {}
    expected_routes_hash = offline_meta.get("routes_database_sha256")
    if routes_db_path is None:
        configured = os.environ.get("KAVACH_TELANGANA_ROUTES_DB")
        routes_db_path = Path(configured) if configured else ROOT / "data" / "raw" / "telangana-routes.sqlite"
    if not Path(routes_db_path).is_file():
        routes_db_path = None
    hub = Hub(db_path, pack_dir, routes_db_path if expected_routes_hash else None,
              expected_routes_hash if routes_db_path and expected_routes_hash else None)
    operations = OperationsStore(db_path, hub.graph, demo_mode=demo_approvals_enabled)
    if demo_approvals_enabled:
        operations.seed_demo()
    app = FastAPI(title="SURAKSHA KAVACH local hub", version="0.2.0-prototype")
    app.state.hub = hub
    app.state.operations = operations
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

    def ops_call(fn, *args, **kwargs):
        try:
            return fn(*args, **kwargs)
        except PermissionError as exc:
            raise HTTPException(403, str(exc)) from exc
        except KeyError as exc:
            raise HTTPException(404, str(exc)) from exc
        except ValueError as exc:
            raise HTTPException(422, str(exc)) from exc

    @app.get("/v1/ops/warnings")
    def ops_warnings():
        return operations.list_warnings()

    @app.get("/v1/ops/warnings/fixture")
    def ops_warning_fixture():
        return demo_warning_fixture()

    @app.get("/v1/ops/warnings/{warning_id}/history")
    def ops_warning_history(warning_id: str):
        return ops_call(operations.warning_history, warning_id)

    @app.post("/v1/ops/warnings/import")
    def ops_import_warning(payload: dict):
        if not operations.demo_mode:
            raise HTTPException(403, "operations demo mode is disabled; set KAVACH_DEMO_MODE=1")
        return ops_call(operations.import_warning, payload)

    @app.post("/v1/ops/warnings/import-cap")
    def ops_import_cap(req: OpsCapImportRequest):
        if not operations.demo_mode:
            raise HTTPException(403, "operations demo mode is disabled; set KAVACH_DEMO_MODE=1")
        return ops_call(operations.import_cap_xml, req.xml, req.source_url, req.source_class)

    @app.get("/v1/ops/exposure")
    def ops_exposure(warning_id: str = Query(min_length=1, max_length=128),
                     at: str | None = Query(default=None, max_length=64)):
        return ops_call(operations.exposure, warning_id, at)

    @app.get("/v1/ops/squads")
    def ops_squads():
        return operations.list_squads()

    @app.get("/v1/ops/missions")
    def ops_missions(responder_id: str | None = Query(default=None, max_length=128)):
        missions = operations.list_missions()
        if responder_id is None:
            return missions
        responder = operations.db.execute(
            "SELECT squad_id FROM responders WHERE responder_id=? AND active=1", (responder_id,)
        ).fetchone()
        if responder is None:
            raise HTTPException(403, "unknown or inactive responder identity")
        return [m for m in missions if m["squad_id"] == responder["squad_id"]]

    @app.post("/v1/ops/missions")
    def ops_create_mission(req: OpsMissionRequest):
        return ops_call(operations.create_mission, req.crossing_id, req.squad_id, req.priority,
                        req.warning_id, req.actor_role, req.actor_id, req.idempotency_key)

    @app.post("/v1/ops/missions/{mission_id}/transition")
    def ops_transition(mission_id: str, req: OpsTransitionRequest):
        return ops_call(operations.transition, mission_id, req.to_state, req.actor_role,
                        req.idempotency_key, req.actor_id)

    @app.post("/v1/ops/missions/{mission_id}/receipts")
    def ops_receipt(mission_id: str, req: OpsReceiptRequest):
        if not operations.demo_mode:
            raise HTTPException(403, "synthetic operations are disabled; set KAVACH_DEMO_MODE=1 for the prototype")
        mission = ops_call(operations.get_mission, mission_id)
        if req.receipt_type == "DELIVERED":
            expected_state = "DELIVERED"
        elif req.receipt_type == "READ":
            expected_state = "READ"
        else:
            expected_state = "ACKNOWLEDGED"
        receipt = ops_call(operations.record_delivery, mission_id, req.responder_id,
                           req.receipt_type, req.idempotency_key, "field_responder", req.responder_id)
        # Delivery/reading/acknowledgement are explicit responder actions; sync itself creates none.
        if mission["state"] != expected_state:
            receipt["mission"] = ops_call(operations.transition, mission_id, expected_state,
                                           "field_responder", req.idempotency_key,
                                           req.responder_id)
        return receipt

    @app.post("/v1/ops/missions/{mission_id}/observations")
    def ops_submit_inspection(mission_id: str, req: OpsInspectionRequest):
        mission = ops_call(operations.get_mission, mission_id)
        if mission["state"] != "IN_PROGRESS":
            raise HTTPException(409, "mission must be IN_PROGRESS before submitting inspection")
        if not operations.db.execute(
            "SELECT 1 FROM responders WHERE responder_id=? AND squad_id=? AND active=1",
            (req.actor_id, mission["squad_id"]),
        ).fetchone():
            raise HTTPException(403, "responder is not an active member of the assigned squad")
        signed = req.signed_event
        event_body = {k: v for k, v in signed.items() if k not in ("signature", "key_id", "received_at")}
        if (event_body.get("kind") != "OBSERVATION" or
                event_body.get("asset_id") != mission["crossing_id"] or
                event_body.get("origin_key_id") != req.actor_id or
                event_body.get("observation_type") != req.observed_status or
                event_body.get("note") != req.notes):
            raise HTTPException(422, "signed observation must match the assigned crossing, responder, status and notes")
        if req.coordinates is not None:
            position = event_body.get("position") or {}
            if any(position.get(k) != req.coordinates.get(k) for k in ("lat", "lon", "accuracy_m")
                   if k in req.coordinates):
                raise HTTPException(422, "inspection coordinates do not match the signed observation")
        # The immutable observation envelope is independently verified and stored by the original hub.
        accepted = hub.submit(signed)
        if accepted["status"] not in {"accepted", "duplicate"}:
            raise HTTPException(422, "signed observation rejected: " + accepted.get("reason", "invalid event"))
        payload = {"observed_status": req.observed_status, "observed_at": req.observed_at,
                   "notes": req.notes, "coordinates": req.coordinates, "evidence": req.evidence,
                   "observation_id": event_body["event_id"]}
        if req.coordinates is None:
            payload.pop("coordinates")
        result = ops_call(operations.submit_inspection, mission_id, req.actor_id, payload,
                          signed_event_id=event_body["event_id"])
        result["hub_event_status"] = accepted["status"]
        return result

    @app.get("/v1/ops/observations")
    def ops_observations():
        return operations.list_observations()

    @app.post("/v1/ops/observations/{observation_id}/verify")
    def ops_verify_inspection(observation_id: str, req: OpsVerifyRequest):
        observation = ops_call(operations.get_observation, observation_id)
        if req.decision == "VERIFIED" and observation["observed_status"] == "UNKNOWN":
            raise HTTPException(422, "an UNKNOWN observation cannot change the operational crossing state")
        result = ops_call(operations.verify_inspection, observation_id, req.decision,
                          req.actor_role, req.rationale, req.actor_id)
        if req.decision == "VERIFIED":
            approved_status = {"OPEN": "VERIFIED_OPEN", "CLOSED": "VERIFIED_CLOSED",
                               "WATER": "VERIFIED_CLOSED", "BLOCKAGE": "VERIFIED_CLOSED",
                               "RESTRICTED": "RESTRICTED"}[observation["observed_status"]]
            event_id = str(uuid.uuid5(uuid.NAMESPACE_URL, "suraksha-kavach:ops-approval:" + observation_id))
            body = {"event_id": event_id, "kind": "APPROVAL", "asset_id": observation["crossing_id"],
                    "approved_status": approved_status,
                    "approved_at": result["decision_at"],
                    "rationale": req.rationale.strip(), "source_class": "SIMULATED",
                    "pack_version": hub.pack_version, "origin_key_id": req.actor_id}
            key = demo_signing_key(req.actor_id)
            event = {**body, "signature": sign_body(body, key), "key_id": req.actor_id}
            result["graph_event"] = hub.submit(event)
            if result["graph_event"]["status"] not in {"accepted", "duplicate"}:
                raise HTTPException(503, "decision saved; route/audit event needs retry: " + result["graph_event"].get("reason", "rejected"))
        return result

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
            "statewide_routes_available": hub.statewide_routes is not None,
        }

    @app.get("/v1/features")
    def features():
        path = hub.pack_dir / "features.json"
        if not path.is_file():
            return JSONResponse({"source": "none", "features": []})
        return JSONResponse(json.loads(path.read_text(encoding="utf-8")))

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

    @app.get("/v1/routes/places")
    def route_places(q: str = Query(min_length=1, max_length=80), limit: int = Query(default=20, ge=1, le=50)):
        if hub.statewide_routes is None:
            raise HTTPException(404, "signed statewide route database is not installed")
        return hub.search_statewide_places(q, limit)

    @app.post("/v1/statewide/routes/compute")
    def compute_statewide(req: StatewideRouteRequest):
        if hub.statewide_routes is None:
            raise HTTPException(404, "signed statewide route database is not installed")
        return hub.route_statewide(req.origin, req.destination, req.vehicle, req.policy)

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
