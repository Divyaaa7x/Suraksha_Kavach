"""Golden tests on the SYNTHETIC fixture (dossier Figure 3, tests T-03, T-04, T-16)."""
from datetime import datetime, timedelta, timezone

from kavach import graph as G
from kavach.fixtures import golden_graph

GRAPH = G.graph_from_dict(golden_graph())
UNK = {c: G.UNKNOWN for c in GRAPH.crossings}


def st(**over):
    return {**UNK, **over}


def test_baseline_shortest_path_ignores_status():
    r = G.shortest_path(GRAPH, "A", "H")
    assert r["path"] == ["A", "J1", "H"]
    assert r["edges"] == ["E1", "E2"]


def test_vehicle_access_is_enforced_in_planning_baseline_and_isolation():
    data = __import__("copy").deepcopy(__import__("kavach.fixtures", fromlist=["golden_graph"]).golden_graph())
    data["edges"][0]["allowed_vehicles"] = ["heavy"]
    graph = G.graph_from_dict(data)
    assert G.shortest_path(graph, "A", "H", vehicle="light")["edges"] == ["E3", "E4", "E5"]
    assert G.shortest_path(graph, "A", "H", vehicle="heavy")["edges"] == ["E1", "E2"]
    light = G.plan_route(graph, st(C1=G.VERIFIED_OPEN), "A", "H", vehicle="light")
    assert "E1" in {edge["edge_id"] for edge in light["excluded_edges"]}
    assert "vehicle access denied" in next(edge["reason"] for edge in light["excluded_edges"] if edge["edge_id"] == "E1")


def test_unknown_crossings_are_excluded_under_strict_policy():
    r = G.plan_route(GRAPH, UNK, "A", "H")
    assert r["result"] == "NO_VERIFIED_ROUTE"
    excluded = {x["crossing_id"] for x in r["excluded_edges"]}
    assert {"C1", "C2", "C3"} <= excluded


def test_t03_verified_closed_crossing_excluded_with_reason():
    r = G.plan_route(GRAPH, st(C1=G.VERIFIED_CLOSED), "A", "H")
    reasons = {x["crossing_id"]: x["reason"] for x in r["excluded_edges"]}
    assert reasons["C1"] == "HARD: verified closed"


def test_t04_no_verified_route_when_every_route_blocked():
    r = G.plan_route(GRAPH, st(C1=G.VERIFIED_CLOSED), "A", "H")
    assert r["result"] == "NO_VERIFIED_ROUTE"
    assert r["path"] == []


def test_verified_open_route_found():
    r = G.plan_route(GRAPH, st(C1=G.VERIFIED_OPEN), "A", "H")
    assert r["result"] == "ROUTE_CANDIDATE"
    assert r["edges"] == ["E1", "E2"]
    assert r["travel_time_s"] == 600.0


def test_closed_primary_reroutes_over_verified_alternative():
    r = G.plan_route(GRAPH, st(C1=G.VERIFIED_CLOSED, C2=G.VERIFIED_OPEN, C3=G.VERIFIED_OPEN), "A", "H")
    assert r["edges"] == ["E3", "E4", "E5"]


def test_unverified_warning_policy_includes_but_flags():
    r = G.plan_route(GRAPH, UNK, "A", "H", policy=G.UNVERIFIED_WARNING)
    assert r["result"] == "ROUTE_CANDIDATE"
    assert r["uncertain_edges"], "unverified crossings must be flagged"


def test_isolation_detects_settlements_without_verified_path():
    iso = [s["settlement_id"] for s in G.isolated_settlements(GRAPH, UNK)]
    assert iso == ["A", "C"]  # B reaches the hospital with no crossing


def test_oneway_edge_away_from_facility_does_not_count_as_access():
    graph = G.RoadGraph(
        nodes={
            "F": G.Node("F", "facility", 18.0, 78.0),
            "V": G.Node("V", "settlement", 18.1, 78.1, "Village"),
        },
        edges={"E": G.Edge("E", "F", "V", 100.0, oneway=True, crossing_id="C")},
        crossings={"C": G.Crossing("C", "bridge")},
    )
    assert [s["settlement_id"] for s in G.isolated_settlements(graph, {"C": G.VERIFIED_OPEN})] == ["V"]
    assert G.criticality(graph)["C"] == 0  # it cannot newly isolate a village already disconnected


def test_criticality_identifies_single_path_crossing():
    crit = G.criticality(GRAPH)
    assert crit["C4"] == 1  # village C depends only on C4
    assert crit["C1"] == 0  # village A has a second route
    assert crit["C2"] == 0


def test_inspection_ranking_puts_critical_unknown_first():
    rows = G.inspection_ranking(GRAPH, UNK)
    assert rows[0]["crossing_id"] == "C4"
    assert rows[0]["settlements_cut_off_if_closed"] == 1


def test_verified_status_not_overwritten_by_later_report():
    now = datetime.now(timezone.utc)
    obs = [{"asset_id": "C1", "observation_type": "OPEN", "received_at": (now + timedelta(hours=1)).isoformat()}]
    apps = [{"asset_id": "C1", "approved_status": "VERIFIED_CLOSED", "approved_at": now.isoformat(),
             "received_at": now.isoformat(), "event_id": "APP1", "origin_key_id": "demo-supervisor-01"}]
    view = G.resolve_status(obs, apps, now)
    assert view["C1"]["status"] == G.VERIFIED_CLOSED
    assert view["C1"]["conflict_with_reports"] is True


def test_conflicting_reports_without_approval_are_disputed():
    now = datetime.now(timezone.utc).isoformat()
    obs = [{"asset_id": "C2", "observation_type": "BLOCKAGE", "received_at": now, "event_id": "a"},
           {"asset_id": "C2", "observation_type": "OPEN", "received_at": now, "event_id": "b"}]
    assert G.resolve_status(obs, [], datetime.now(timezone.utc))["C2"]["status"] == G.DISPUTED


def test_stale_verified_open_is_demoted():
    old = (datetime.now(timezone.utc) - timedelta(days=90)).isoformat()
    apps = [{"asset_id": "C3", "approved_status": "VERIFIED_OPEN", "approved_at": old,
             "received_at": old, "event_id": "X", "origin_key_id": "demo-supervisor-01"}]
    assert G.resolve_status([], apps, datetime.now(timezone.utc))["C3"]["status"] == G.STALE
