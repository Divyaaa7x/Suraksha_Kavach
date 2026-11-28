"""Operational workflow integration over the real pilot pack and existing signed event hub."""
from __future__ import annotations

import json
import uuid
from datetime import datetime, timezone
from pathlib import Path

from fastapi.testclient import TestClient

from kavach.api import create_app
from kavach.graph import STRICT, VERIFIED_OPEN, plan_route
from kavach.pack import demo_signing_key
from kavach.signing import sign_body

ROOT = Path(__file__).resolve().parents[1]
PACK = ROOT / "data" / "packs" / "kavach-osm-medak-kamareddy"
WARNING = ROOT / "data" / "fixtures" / "imd-2024-kamareddy-archived-warning.json"


def signed(body: dict, key_id: str) -> dict:
    return {**body, "signature": sign_body(body, demo_signing_key(key_id)), "key_id": key_id}


def test_archived_warning_to_verified_closure_recalculates_real_pilot_route(tmp_path):
    client = TestClient(create_app(PACK, tmp_path / "ops-hub.sqlite", demo_approvals_enabled=True))
    fixture = json.loads(WARNING.read_text(encoding="utf-8"))
    warning_response = client.post("/v1/ops/warnings/import", json=fixture)
    assert warning_response.status_code == 200, warning_response.text
    warning_id = warning_response.json()["warning_id"]

    replay_at = "2024-08-31T09:00:00+05:30"
    exposures = client.get("/v1/ops/exposure", params={"warning_id": warning_id, "at": replay_at}).json()
    crossing_id = "OSM-W712618127"
    assert crossing_id in {row["crossing_id"] for row in exposures}
    assert all(row["physical_status"] == "UNCONFIRMED" for row in exposures)
    assert client.get("/v1/ops/exposure", params={"warning_id": warning_id}).json() == []

    graph = client.app.state.hub.graph
    origin, facility = "n6149437508", "n10262075971"
    open_status = {key: VERIFIED_OPEN for key in graph.crossings}
    baseline = plan_route(graph, open_status, origin, facility, policy=STRICT)
    assert baseline["result"] == "ROUTE_CANDIDATE"
    assert crossing_id in {graph.edges[edge_id].crossing_id for edge_id in baseline["edges"]}

    # Seed the baseline with the signed approvals required for this real graph route.
    route_crossings = {graph.edges[edge_id].crossing_id for edge_id in baseline["edges"]
                       if graph.edges[edge_id].crossing_id}
    now = datetime.now(timezone.utc).isoformat()
    for asset in sorted(route_crossings):
        body = {"event_id": str(uuid.uuid4()), "kind": "APPROVAL", "asset_id": asset,
                "approved_status": "VERIFIED_OPEN", "approved_at": now,
                "rationale": "SYNTHETIC test baseline only", "source_class": "SIMULATED",
                "pack_version": client.app.state.hub.pack_version,
                "origin_key_id": "demo-supervisor-01"}
        result = client.post("/v1/events", json=signed(body, "demo-supervisor-01")).json()
        assert result["status"] == "accepted"
    assert client.post("/v1/routes/compute", json={
        "origin": origin, "destination": facility, "vehicle": "light", "policy": "strict",
    }).json()["result"] == "ROUTE_CANDIDATE"

    mission = client.post("/v1/ops/missions", json={
        "crossing_id": crossing_id, "squad_id": "SYN-SQUAD-01", "priority": 5,
        "warning_id": warning_id, "actor_role": "commander", "actor_id": "demo-commander-01",
        "idempotency_key": "e2e-mission-create-01",
    })
    assert mission.status_code == 200, mission.text
    mission_id = mission.json()["mission_id"]
    assert client.post("/v1/ops/missions", json={
        "crossing_id": crossing_id, "squad_id": "SYN-SQUAD-01", "priority": 5,
        "warning_id": warning_id, "actor_role": "commander", "actor_id": "demo-commander-01",
        "idempotency_key": "e2e-mission-create-01",
    }).json()["mission_id"] == mission_id
    for state in ("QUEUED", "SENT"):
        response = client.post(f"/v1/ops/missions/{mission_id}/transition", json={
            "to_state": state, "actor_role": "commander", "actor_id": "demo-commander-01",
            "idempotency_key": f"test:{mission_id}:{state}",
        })
        assert response.status_code == 200, response.text
    for state in ("DELIVERED", "READ", "ACKNOWLEDGED"):
        response = client.post(f"/v1/ops/missions/{mission_id}/receipts", json={
            "responder_id": "demo-responder-01", "receipt_type": state,
            "idempotency_key": f"test:{mission_id}:{state}",
        })
        assert response.status_code == 200, response.text
        assert response.json()["is_human_acknowledgement"] is (state == "ACKNOWLEDGED")
    assert client.get("/v1/ops/missions", params={"responder_id": "demo-responder-01"}).json()[0]["state"] == "ACKNOWLEDGED"
    started = client.post(f"/v1/ops/missions/{mission_id}/transition", json={
        "to_state": "IN_PROGRESS", "actor_role": "field_responder", "actor_id": "demo-responder-01",
        "idempotency_key": f"test:{mission_id}:IN_PROGRESS",
    })
    assert started.status_code == 200, started.text

    observation_id = str(uuid.uuid4())
    note = "SYNTHETIC field inspection for workflow verification"
    observed_at = datetime.now(timezone.utc).isoformat()
    position = {"lat": graph.nodes[origin].lat, "lon": graph.nodes[origin].lon, "accuracy_m": 8.0}
    observation_body = {"event_id": observation_id, "kind": "OBSERVATION", "asset_id": crossing_id,
        "observation_type": "CLOSED", "observed_at": observed_at, "position": position,
        "evidence_sha256": [], "source_class": "SIMULATED", "pack_version": client.app.state.hub.pack_version,
        "origin_key_id": "demo-responder-01", "note": note}
    observation = client.post(f"/v1/ops/missions/{mission_id}/observations", json={
        "actor_id": "demo-responder-01", "observed_status": "CLOSED", "observed_at": observed_at,
        "notes": note, "coordinates": position, "signed_event": signed(observation_body, "demo-responder-01"),
    })
    assert observation.status_code == 200, observation.text
    assert observation.json()["signed_event_id"] == observation_id
    assert observation.json()["hub_event_status"] == "accepted"
    assert any(event["event_id"] == observation_id for event in
               client.get("/v1/events", params={"kind": "OBSERVATION"}).json())

    decision = client.post(f"/v1/ops/observations/{observation_id}/verify", json={
        "decision": "VERIFIED", "actor_role": "supervisor", "actor_id": "demo-supervisor-01",
        "rationale": "SYNTHETIC supervisor verifies the task-linked report for the workflow demo",
    })
    assert decision.status_code == 200, decision.text
    assert decision.json()["graph_event"]["status"] == "accepted"
    assert client.get("/v1/status").json()[crossing_id]["status"] == "VERIFIED_CLOSED"
    route_after = client.post("/v1/routes/compute", json={
        "origin": origin, "destination": facility, "vehicle": "light", "policy": "strict",
    }).json()
    assert route_after["result"] == "NO_VERIFIED_ROUTE"
    assert client.get("/v1/ops/missions").json()[0]["state"] == "VERIFIED"
    assert client.get("/v1/ops/observations").json()[0]["state"] == "VERIFIED"


def test_ops_writes_require_demo_mode_and_responder_filter_is_enforced(tmp_path):
    client = TestClient(create_app(PACK, tmp_path / "ops-off.sqlite", demo_approvals_enabled=False))
    warning = json.loads(WARNING.read_text(encoding="utf-8"))
    assert client.post("/v1/ops/warnings/import", json=warning).status_code == 403
    assert client.get("/v1/ops/missions", params={"responder_id": "not-enrolled"}).status_code == 403


def test_cap_xml_endpoint_preserves_source_and_rejects_unsafe_declarations(tmp_path):
    client = TestClient(create_app(PACK, tmp_path / "cap.sqlite", demo_approvals_enabled=True))
    xml = ('<?xml version="1.0"?><alert xmlns="urn:oasis:names:tc:emergency:cap:1.2">'
           '<identifier>sim-cap-1</identifier><sender>urn:example:sim</sender>'
           '<sent>2026-08-01T10:00:00Z</sent><msgType>Alert</msgType>'
           '<info><event>SIMULATED rain</event><effective>2026-08-01T10:00:00Z</effective>'
           '<expires>2026-08-02T10:00:00Z</expires><area><areaDesc>Test area</areaDesc>'
           '<polygon>18.060,78.250 18.060,78.270 18.040,78.270 18.040,78.250</polygon>'
           '</area></info></alert>')
    response = client.post("/v1/ops/warnings/import-cap", json={
        "xml": xml, "source_url": "https://example.test/simulated.xml", "source_class": "SIMULATED",
    })
    assert response.status_code == 200, response.text
    assert response.json()["source_class"] == "SIMULATED"
    assert response.json()["original"]["original_xml"] == xml
    assert response.json()["geometry"]["coordinates"][0][0][0] == [78.25, 18.06]
    unsafe = client.post("/v1/ops/warnings/import-cap", json={
        "xml": '<!DOCTYPE alert [<!ENTITY x SYSTEM "file:///secret">]><alert/>',
        "source_url": "https://example.test/bad.xml", "source_class": "SIMULATED",
    })
    assert unsafe.status_code == 422 and "DTD" in unsafe.json()["detail"]
