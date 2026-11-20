"""Hub API tests: signatures, roles, replay, tamper, pack integrity (T-06, T-10, T-11, T-14)."""
import json
import shutil
import uuid
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from pathlib import Path

import pytest
import httpx
from fastapi.testclient import TestClient

from kavach.api import MAX_API_BODY_BYTES, create_app
from kavach.pack import PackError, demo_signing_key, load_pack
from kavach.signing import sign_body

ROOT = Path(__file__).resolve().parents[1]
PACK = ROOT / "data" / "packs" / "kavach-demo-synthetic"
PV = "DEMO-SYN-001"


def signed(body: dict, key_id: str) -> dict:
    return {**body, "signature": sign_body(body, demo_signing_key(key_id)), "key_id": key_id}


def obs(asset="C1", otype="OPEN", key="demo-responder-01", event_id=None):
    body = {"event_id": event_id or str(uuid.uuid4()), "kind": "OBSERVATION", "asset_id": asset,
            "observation_type": otype, "observed_at": datetime.now(timezone.utc).isoformat(),
            "position": {"lat": 18.05, "lon": 78.26, "accuracy_m": 12}, "evidence_sha256": [],
            "source_class": "SIMULATED", "pack_version": PV, "origin_key_id": key, "note": "synthetic"}
    return signed(body, key)


def approval(asset="C1", status="VERIFIED_CLOSED", key="demo-supervisor-01"):
    body = {"event_id": str(uuid.uuid4()), "kind": "APPROVAL", "asset_id": asset,
            "approved_status": status, "approved_at": datetime.now(timezone.utc).isoformat(),
            "rationale": "synthetic test approval", "source_class": "SIMULATED",
            "pack_version": PV, "origin_key_id": key}
    return signed(body, key)


@pytest.fixture()
def client(tmp_path):
    return TestClient(create_app(PACK, tmp_path / "hub.sqlite3"))


def test_health_is_offline_and_labelled(client):
    h = client.get("/v1/health").json()
    assert h["pack_version"] == PV
    assert h["source_class"] == "SIMULATED"
    assert "no field verification" in h["source_class_note"]


def test_api_rejects_oversized_declared_body_before_json_parsing(client):
    response = client.post("/v1/events", content=b" " * (MAX_API_BODY_BYTES + 1))
    assert response.status_code == 413


def test_api_rejects_oversized_chunked_body_before_json_parsing(client):
    class ChunkedBody(httpx.SyncByteStream):
        def __iter__(self):
            yield b" " * (MAX_API_BODY_BYTES // 2)
            yield b" " * (MAX_API_BODY_BYTES // 2 + 1)

    response = client.post("/v1/events", content=ChunkedBody())
    assert response.status_code == 413


def test_india_overview_api_has_reference_boundaries(client):
    data = client.get("/v1/india-overview").json()
    assert data["license"].startswith("Public domain")
    assert len(data["features"]) == 36
    assert any(area["code"] == "IN-TG" for area in data["features"])


def test_synthetic_coordinator_approval_is_disabled_by_default(client):
    response = client.post("/v1/demo/approvals", json={
        "asset_id": "C1", "approved_status": "VERIFIED_OPEN", "rationale": "demo review"
    })
    assert response.status_code == 403
    assert response.json()["status"] == "disabled"


def test_synthetic_coordinator_approval_requires_nonblank_rationale(tmp_path):
    client = TestClient(create_app(PACK, tmp_path / "demo.sqlite3", demo_approvals_enabled=True))
    response = client.post("/v1/demo/approvals", json={
        "asset_id": "C1", "approved_status": "VERIFIED_OPEN", "rationale": "   "
    })
    assert response.status_code == 422
    assert response.json()["status"] == "rejected"


def test_parallel_dashboard_event_reads_share_sqlite_safely(client):
    hub = client.app.state.hub
    with ThreadPoolExecutor(max_workers=12) as pool:
        results = list(pool.map(lambda _: hub.events("OBSERVATION"), range(100)))
    assert results == [[]] * 100


def test_valid_observation_accepted_and_duplicate_idempotent(client):
    e = obs()
    assert client.post("/v1/events", json=e).json()["status"] == "accepted"
    assert client.post("/v1/events", json=e).json()["status"] == "duplicate"


def test_reused_event_id_with_different_content_rejected(client):
    e = obs(event_id="fixed-id-1")
    client.post("/v1/events", json=e)
    changed = {k: v for k, v in e.items() if k not in ("signature", "key_id")}
    changed["observation_type"] = "CLOSED"
    assert client.post("/v1/events", json=signed(changed, "demo-responder-01")).json()["status"] == "rejected"


def test_forged_body_rejected(client):
    e = obs()
    e["observation_type"] = "CLOSED"  # changed after signing
    assert client.post("/v1/events", json=e).json()["status"] == "rejected"


def test_unknown_key_rejected(client):
    e = obs()
    e["key_id"] = "mallory"  # key id not in the trust registry
    assert client.post("/v1/events", json=e).json()["status"] == "rejected"


def test_responder_cannot_approve(client):
    assert client.post("/v1/approvals", json=approval(key="demo-responder-01")).json()["status"] == "rejected"


def test_oversize_payload_rejected(client):
    e = obs()
    body = {k: v for k, v in e.items() if k not in ("signature", "key_id")}
    body["note"] = "x" * 6000
    assert client.post("/v1/events", json=signed(body, "demo-responder-01")).json()["status"] == "rejected"


def test_unknown_asset_rejected(client):
    assert client.post("/v1/events", json=obs(asset="NOT-IN-PACK")).json()["status"] == "rejected"


def test_supervisor_approval_recomputes_route_and_audit_survives(client):
    assert client.post("/v1/routes/compute", json={"origin": "A", "destination": "H"}).json()["result"] == "NO_VERIFIED_ROUTE"
    client.post("/v1/events", json=obs("C1", "OPEN"))
    assert client.post("/v1/approvals", json=approval("C1", "VERIFIED_OPEN")).json()["status"] == "accepted"
    r = client.post("/v1/routes/compute", json={"origin": "A", "destination": "H"}).json()
    assert r["result"] == "ROUTE_CANDIDATE" and r["edges"] == ["E1", "E2"]
    assert r["baseline_shortest_path"]["path"] == ["A", "J1", "H"]


def test_t11_contradictory_open_report_cannot_make_closed_crossing_safe(client):
    client.post("/v1/approvals", json=approval("C1", "VERIFIED_CLOSED"))
    client.post("/v1/events", json=obs("C1", "OPEN"))
    st = client.get("/v1/status").json()["C1"]
    assert st["status"] == "VERIFIED_CLOSED"
    assert st["conflict_with_reports"] is True
    r = client.post("/v1/routes/compute", json={"origin": "A", "destination": "H"}).json()
    assert "E1" not in r.get("edges", [])


def test_conflicting_reports_show_disputed(client):
    client.post("/v1/events", json=obs("C2", "WATER"))
    client.post("/v1/events", json=obs("C2", "OPEN"))
    assert client.get("/v1/status").json()["C2"]["status"] == "DISPUTED"


def test_sync_batch_merge_is_idempotent(client):
    a, b = obs("C1", "OPEN"), obs("C4", "CLOSED")
    res = client.post("/v1/sync/batch", json=[a, b, a]).json()
    assert [r["status"] for r in res] == ["accepted", "accepted", "duplicate"]
    inv = client.get("/v1/sync/inventory").json()["event_ids"]
    assert a["event_id"] in inv and b["event_id"] in inv and len(inv) == 2


def test_isolated_and_inspection_endpoints(client):
    iso = client.get("/v1/isolated-settlements").json()
    assert [s["settlement_id"] for s in iso] == ["A", "C"]
    assert client.get("/v1/inspection-ranking").json()[0]["crossing_id"] == "C4"


def test_scenario_overlay_marks_unknown_as_predicted_risk(client):
    assert client.get("/v1/status", params={"scenario": "C2"}).json()["C2"]["status"] == "PREDICTED_RISK"


def test_t06_tampered_pack_rejected(tmp_path):
    dst = tmp_path / "pack"
    shutil.copytree(PACK, dst)
    g = json.loads((dst / "graph.json").read_text())
    g["edges"][0]["length_m"] = 1
    (dst / "graph.json").write_text(json.dumps(g))
    with pytest.raises(PackError, match="hash mismatch"):
        load_pack(dst)


def test_pack_downgrade_rejected():
    with pytest.raises(PackError, match="downgrade"):
        load_pack(PACK, min_created_at="2099-01-01T00:00:00+00:00")
