# GIS, Road Graph and Flood Intelligence Audit

Audit target: downloaded `main` source at commit `1c8ae9eb4ed3b6e76948c70dc1ad4eda901c752d`.

Follow-up: the one-way reachability bug below has since been fixed in both Python and Android, with regression tests. Other GIS gaps remain open.

## Current implementation

- `server/kavach/graph.py` contains vehicle-aware A* routing, verified/unknown crossing policies, settlement-to-facility isolation, per-crossing counterfactual criticality, and an inspection ranking. The graph and status model are deterministic and have unit tests in `tests/test_graph.py`.
- `server/kavach/statewide_routes.py` implements a read-only SQLite place/edge route runtime with one-way and vehicle restrictions, hash verification, bounded search/output, and strict versus uncertain crossing treatment. `tests/test_statewide_routes.py` exercises the small fixture database and API packaging path.
- Android has an offline SQLite Telangana renderer (`ui/TelanganaOfflineMap.kt`), a separate online MapLibre India map (`ui/AtlasMap.kt`), a pilot road graph (`graph/RoadGraph.kt` and `graph/Engine.kt`), and an optional statewide route store (`graph/OfflineRouteStore.kt`). The web dashboard has an offline Telangana canvas and route/isolated-settlement/inspection panels.
- The checked-in OSM graph `data/packs/kavach-osm-medak-kamareddy/graph.json` has 65,458 nodes, 68,046 edges, 96 modeled crossings, 473 settlement nodes, and 132 facility nodes; 3,708 edges are one-way. Only 2 settlement nodes have a population estimate. The 96 crossings in this pack are bridges; it contains no modeled culvert or causeway rows.
- The full offline display database and optional statewide route archive are separate from this small checked-in routing graph. README and verification report state the approximately 297 MB compressed statewide route archive is not distributed by the normal repository/release build. Thus “statewide offline map display” and “statewide offline route calculation” are different capabilities; the former is present, while the latter is optional and unavailable in a standard checkout.

## Material gap and correctness risk

1. **Resolved: one-way connectivity had been computed as undirected.** Before the fix, `isolated_settlements` unioned edge endpoints without direction, so a one-way road from a facility toward a village could falsely imply a return route. The graph has 3,708 one-way edges. Both engines now use reverse reachability from facilities, and Python/Kotlin regression tests cover one-way edges toward and away from facilities. Embedded criticality was recomputed and verified against the directed algorithm. Tests for multiple facilities and blocked crossings remain useful.

2. **Flood exposure is not calculated.** No GIS intersection links warning polygons, observed water levels, rainfall/flood extents, or warning validity to road edges/crossings. `PREDICTED_RISK` is a status-policy value and the hub can apply a scenario overlay to manually named pilot crossing IDs, but there is no official-warning ingestion or geospatial matching workflow in the graph/API. Do not present scenario overlays as live or official.

3. **Criticality is a narrow topology score.** `criticality` removes one crossing at a time with every other crossing assumed open and counts settlements newly disconnected from any facility. `inspection_ranking` sorts only by that count. It does not account for current status, estimated population (largely absent), facility type, warning severity/freshness, route detour cost, inspection effort/time, uncertainty, or mission timing. It is an explainable baseline, not the V5 consequence-aware operational score.

4. **No integrated operational loop.** There are report/approval events and route/status views, but no persistent mission/squad/task entities or warning → exposure → isolation → assignment → acknowledgement → inspection → authorisation → graph update flow. An authorised crossing approval can influence pilot status/routing, but statewide OSM crossing IDs are not represented in the pack trust workflow and remain unknown.

5. **No evidence for a statewide road-safety map.** OSM coverage is map-source coverage, not a field survey. Crossing tagging is incomplete and its tags cannot establish current passability or structural safety. Current outputs correctly include caveats, but cannot be treated as certified rescue navigation.

## Test coverage and gaps

- Graph unit tests cover route status policy, vehicle access, isolation, criticality/ranking and status resolution. Statewide route tests cover lookup, crossing uncertainty, hard-closed/restricted policy, one-way routing, hash validation, output geometry limits, and API integration using synthetic fixture DBs.
- Tests do **not** presently verify one-way settlement-to-facility reachability, directed bridge/articulation behavior, warning geometry/time matching, exposure aggregation, detour/consequence ranking, populations or emergency-facility weights, or persistence of an operational flood-response workflow.
- The docs report Android demo/production unit tests and browser E2E historically, but these are not GIS acceptance evidence for live official data or real field passability.

## Smallest useful next implementation

First correct directed settlement reachability: build reverse adjacency of eligible directed edges and traverse outward from all facility nodes, so a node is reachable exactly when it can reach a facility in the road's permitted direction. Keep the existing output contract and add directed regression tests. This is a small, high-consequence correctness fix and makes existing isolation/criticality/ranking output more defensible.

Then add one clearly bounded offline analysis API over the existing graph: accept a versioned set of scenario-affected crossing IDs (not a fabricated official alert), compute isolation before/after and facility access loss, and return reasons/uncertainty. Add a browser/API integration test. Official alert adapters and flood geometry intersection should remain separate follow-up work until permitted, stable source data and licenses are verified.

## Evidence sources

- `server/kavach/graph.py`: `plan_route`, `_eligible_edges`, `isolated_settlements`, `criticality`, `inspection_ranking`.
- `server/kavach/statewide_routes.py`: read-only optional SQLite route runtime and unknown-crossing handling.
- `server/kavach/service.py`: pilot crossing status/event integration and optional statewide route wiring.
- `server/kavach/api.py`: route, isolated-settlement and inspection endpoints; no official warning/mission resource endpoints.
- `android/app/src/main/java/org/kavach/ui/{AtlasMap,TelanganaOfflineMap,KavachViewModel}.kt` and `android/app/src/main/java/org/kavach/graph/{Engine,RoadGraph,OfflineRouteStore}.kt`.
- `README.md` and `docs/test-report.md`: source/data scope, omitted statewide archive, performance and safety limitations.
