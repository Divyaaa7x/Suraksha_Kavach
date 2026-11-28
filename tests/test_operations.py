import pytest

from kavach.fixtures import golden_graph
from kavach.graph import graph_from_dict, isolated_settlements, plan_route, STRICT
from kavach.operations import OperationsStore, _valid_geometry, demo_warning_fixture


@pytest.fixture
def ops(tmp_path):
    store = OperationsStore(tmp_path / "ops.db", graph_from_dict(golden_graph()), demo_mode=True)
    store.seed_demo()
    yield store
    store.db.close()


def polygon(lon=78.261, lat=18.052, d=.01):
    return {"type":"Polygon","coordinates":[[[lon-d,lat-d],[lon+d,lat-d],[lon+d,lat+d],[lon-d,lat+d],[lon-d,lat-d]]]}


def warning_payload(**changes):
    p={"warning_id":"rain-2024","provider":"Test archive","source_url":"https://example.test/archive",
       "source_class":"SIMULATED","original_alert_id":"source-id-1","issued_at":"2024-08-30T10:00:00+00:00",
       "valid_from":"2024-08-31T00:00:00+00:00","valid_until":"2024-09-01T00:00:00+00:00",
       "geometry":polygon(),"original":{"summary":"SIMULATED test warning"}}
    return {**p,**changes}


def cap_alert(identifier="cap-1", sent="2026-08-01T10:00:00Z", msg_type="Alert", references="", include_area=True, headline="SIMULATED rain test"):
    area=("<area><areaDesc>SIMULATED test area</areaDesc><polygon>18.060,78.250 18.060,78.270 18.040,78.270 18.040,78.250</polygon></area>" if include_area else "")
    info=(f"<info><category>Met</category><event>Rainfall</event><urgency>Expected</urgency><severity>Moderate</severity><certainty>Likely</certainty><effective>{sent}</effective><onset>{sent}</onset><expires>2026-08-02T10:00:00Z</expires><headline>{headline}</headline><description>SIMULATED CAP fixture, not an official warning.</description>{area}</info>" if include_area else "")
    ref=f"<references>{references}</references>" if references else ""
    return f'<?xml version="1.0" encoding="UTF-8"?><alert xmlns="urn:oasis:names:tc:emergency:cap:1.2"><identifier>{identifier}</identifier><sender>urn:example:simulator</sender><sent>{sent}</sent><status>Test</status><msgType>{msg_type}</msgType><scope>Public</scope>{ref}{info}</alert>'


def test_warning_provenance_revision_expiry_and_cancel(ops):
    warning=ops.import_warning(warning_payload())
    assert warning["source_url"] == "https://example.test/archive"
    assert warning["original_alert_id"] == "source-id-1"
    assert ops.exposure("rain-2024", "2024-08-31T12:00:00+00:00")
    assert ops.exposure("rain-2024", "2024-09-02T00:00:00+00:00") == []
    assert ops.exposure("rain-2024", "2024-09-01T00:00:00+00:00") == []
    updated=ops.import_warning(warning_payload(warning_id="rain-2024-r2",original_alert_id="source-id-2",update_of="rain-2024"))
    assert not ops.list_warnings("rain-2024")[0]["active"]
    assert ops.warning_history("rain-2024-r2")[0]["revision_type"] == "UPDATE"
    cancelled=ops.import_warning(warning_payload(warning_id="rain-2024-cancel",original_alert_id="cancel-id",cancel_of="rain-2024-r2"))
    assert not cancelled["active"] and not ops.list_warnings("rain-2024-r2")[0]["active"]
    assert ops.exposure("rain-2024-r2", "2024-08-31T12:00:00+00:00") == []


def test_warning_repeat_import_is_idempotent_and_conflicts_cannot_overwrite(ops):
    p=warning_payload()
    ops.import_warning(p)
    ops.import_warning(p)
    assert len(ops.warning_history("rain-2024")) == 1
    with pytest.raises(ValueError,match="different content"):
        ops.import_warning(warning_payload(original={"summary":"changed payload"}))
    assert len(ops.warning_history("rain-2024")) == 1


def test_exposure_is_grouped_and_ranked_only_by_signed_structural_scores(ops):
    ops.graph.precomputed_criticality={"C1":3,"C2":3,"C3":None,"C4":1}
    p=warning_payload(geometry=polygon(lon=78.27,lat=18.05,d=.03))
    ops.import_warning(p)
    rows=ops.exposure("rain-2024","2024-08-31T12:00:00+00:00")
    ids=[row["crossing_id"] for row in rows]
    assert len(ids)==len(set(ids))
    assert ids[:2]==["C1","C2"]
    assert rows[0]["rank"]==1 and rows[1]["rank"]==2
    unknown=next(row for row in rows if row["crossing_id"]=="C3")
    assert unknown["settlements_cut_off_if_closed"] is None
    assert "No signed structural criticality score" in unknown["rank_reason"]


def test_cap_xml_import_provenance_polygon_axis_and_referenced_revisions(ops):
    first_xml=cap_alert()
    first=ops.import_cap_xml(first_xml,"https://archive.example/cap.xml")
    assert first["warning_id"] == "CAP:urn:example:simulator:cap-1"
    assert first["source_class"] == "SIMULATED"
    assert first["issued_at"] == "2026-08-01T10:00:00+00:00"
    assert first["original"]["original_xml"] == first_xml
    assert first["geometry"]["type"] == "MultiPolygon"
    assert first["geometry"]["coordinates"][0][0][0] == [78.25,18.06]
    assert ops.import_cap_xml(first_xml,"https://archive.example/cap.xml")["warning_id"] == first["warning_id"]
    assert len(ops.warning_history(first["warning_id"])) == 1
    ref="urn:example:simulator,cap-1,2026-08-01T10:00:00Z"
    update=ops.import_cap_xml(cap_alert("cap-2","2026-08-01T10:15:00Z","Update",ref,False),"https://archive.example/cap.xml")
    assert update["update_of"] == first["warning_id"] and update["geometry"] == first["geometry"]
    assert not ops.list_warnings(first["warning_id"])[0]["active"]
    cancel_ref="urn:example:simulator,cap-2,2026-08-01T10:15:00Z"
    cancelled=ops.import_cap_xml(cap_alert("cap-3","2026-08-01T10:20:00Z","Cancel",cancel_ref,False),"https://archive.example/cap.xml")
    assert cancelled["cancel_of"] == update["warning_id"] and not cancelled["active"]
    assert not ops.list_warnings(update["warning_id"])[0]["active"]


@pytest.mark.parametrize("payload,reason",[("<!DOCTYPE alert [<!ENTITY x SYSTEM 'file:///secret'>]><alert/>","DTD"),("<alert><", "invalid CAP XML")])
def test_cap_rejects_xml_declarations_and_malformed_documents(ops,payload,reason):
    with pytest.raises(ValueError,match=reason):
        ops.import_cap_xml(payload,"https://example.test")


def test_cap_rejects_oversize_and_unknown_references(ops):
    with pytest.raises(ValueError,match="1 MB"):
        ops.import_cap_xml(" "*1_000_001,"https://example.test")
    with pytest.raises(ValueError,match="not found"):
        ops.import_cap_xml(cap_alert("cap-update","2026-08-01T10:00:00Z","Update","missing,some-id,2026-08-01T09:00:00Z"),"https://example.test")


def test_historical_fixture_has_official_sources_but_approximate_boundary(ops):
    p=demo_warning_fixture()
    assert p["source_class"] == "ARCHIVED_OFFICIAL"
    assert p["source_url"].startswith("https://mausam.imd.gov.in/")
    assert p.get("issued_at") is None and p["original_alert_id"] == "NOT_PUBLISHED_IN_SOURCE"
    assert p["original"]["geometry_source"].startswith("GeoSOI/IUDX")
    assert "not a rainfall footprint or flood-inundation polygon" in p["original"]["geometry_note"]
    assert _valid_geometry(p["geometry"])
    imported=ops.import_warning(p)
    assert imported["warning_id"] == "ARCHIVE-IMD-2024-08-31-KAMAREDDY"


def test_polygon_validation_rejects_open_and_self_crossing_rings():
    with pytest.raises(ValueError,match="closed"):
        _valid_geometry({"type":"Polygon","coordinates":[[[0,0],[1,0],[1,1],[0,1]]]})
    with pytest.raises(ValueError,match="self-intersects"):
        _valid_geometry({"type":"Polygon","coordinates":[[[0,0],[1,1],[0,1],[1,0],[0,0]]]})


def test_mission_delivery_is_not_ack_and_duplicate_transition_is_idempotent(ops):
    created=ops.create_mission("C4","SYN-SQUAD-01",1,actor_id="demo-commander-01",idempotency_key="create-c4")
    assert ops.create_mission("C4","SYN-SQUAD-01",1,actor_id="demo-commander-01",idempotency_key="create-c4")["mission_id"] == created["mission_id"]
    m=ops.list_missions()[0]; mid=m["mission_id"]
    for state, role, actor in [("QUEUED","commander","demo-commander-01"),("SENT","commander","demo-commander-01"),
                               ("DELIVERED","field_responder","demo-responder-01"),("READ","field_responder","demo-responder-01"),
                               ("ACKNOWLEDGED","field_responder","demo-responder-01"),("IN_PROGRESS","field_responder","demo-responder-01")]:
        ops.transition(mid,state,role,idempotency_key=f"retry-{state}",actor_id=actor)
    ops.transition(mid,"ACKNOWLEDGED","field_responder",idempotency_key="retry-ACKNOWLEDGED",actor_id="demo-responder-01")
    mission=ops.get_mission(mid)
    assert mission["state"] == "IN_PROGRESS"
    assert [e["state"] for e in mission["events"]].count("ACKNOWLEDGED") == 1
    assert mission["events"][3]["state"] == "DELIVERED"


def test_invalid_transition_and_actor_role_spoofing_rejected(ops):
    with pytest.raises(PermissionError):
        ops.create_mission("C4","SYN-SQUAD-01",1,actor_role="commander",actor_id="attacker")
    mid=ops.create_mission("C4","SYN-SQUAD-01",1)["mission_id"]
    with pytest.raises(ValueError,match="invalid transition"):
        ops.transition(mid,"ACKNOWLEDGED","field_responder",actor_id="demo-responder-01")
    with pytest.raises(PermissionError):
        ops.transition(mid,"QUEUED","commander",actor_id="attacker")


def test_inspection_requires_assigned_responder_and_supervisor_changes_graph_status(ops):
    mid=ops.create_mission("C4","SYN-SQUAD-01",1)["mission_id"]
    for state in ("QUEUED","SENT"):
        ops.transition(mid,state,"commander",actor_id="demo-commander-01")
    for state in ("DELIVERED","READ","ACKNOWLEDGED","IN_PROGRESS"):
        ops.transition(mid,state,"field_responder",actor_id="demo-responder-01")
    payload={"observation_id":"device-observation-1","observed_status":"CLOSED","observed_at":"2026-11-28T10:00:00+00:00","notes":"SIMULATED inspection","coordinates":{"lat":18.05,"lon":78.28},"evidence":[],"signed_event_id":"signed-hub-event-1"}
    with pytest.raises(PermissionError):
        ops.submit_inspection(mid,"unassigned",payload)
    observation=ops.submit_inspection(mid,"demo-responder-01",payload)
    assert ops.submit_inspection(mid,"demo-responder-01",payload)["observation_id"] == observation["observation_id"]
    assert observation["signed_event_id"] == "signed-hub-event-1"
    assert ops.get_mission(mid)["state"] == "SUBMITTED"
    assert ops.list_observations()[0]["state"] == "SUBMITTED"
    with pytest.raises(PermissionError):
        ops.verify_inspection(observation["observation_id"],"VERIFIED",actor_id="attacker",rationale="bad")
    ops.verify_inspection(observation["observation_id"],"VERIFIED",rationale="SIMULATED review accepted")
    statuses=ops.current_crossing_status({"C4":"UNKNOWN"})
    assert statuses["C4"] == "VERIFIED_CLOSED"
    graph=ops.graph
    isolated=isolated_settlements(graph,statuses)
    assert any(row["settlement_id"]=="C" for row in isolated)
    assert plan_route(graph,statuses,"C","H",policy=STRICT)["result"] == "NO_VERIFIED_ROUTE"


def test_demo_mode_is_opt_in(tmp_path):
    store=OperationsStore(tmp_path/"off.db",graph_from_dict(golden_graph()))
    with pytest.raises(PermissionError):
        store.seed_demo()
    with pytest.raises(PermissionError):
        store.create_mission("C4","SYN-SQUAD-01",1)


def test_sqlite_schema_and_task_data_survive_restart(tmp_path):
    path=tmp_path/"restart.db"
    first=OperationsStore(path,graph_from_dict(golden_graph()),demo_mode=True)
    first.seed_demo()
    mission=first.create_mission("C4","SYN-SQUAD-01",1,idempotency_key="durable-create")
    first.db.close()
    second=OperationsStore(path,graph_from_dict(golden_graph()),demo_mode=True)
    assert second.db.execute("PRAGMA user_version").fetchone()[0] == 2
    assert second.get_mission(mission["mission_id"])["state"] == "CREATED"
    assert second.create_mission("C4","SYN-SQUAD-01",1,idempotency_key="durable-create")["mission_id"] == mission["mission_id"]
