# Implementation Plan and Current Progress

This is a staged execution record, not a claim that the complete V5 scope has shipped.

| Stage | State | Evidence / next step |
|---|---|---|
| 1. Repository and skills audit | Done for current prototype | `PROJECT_AUDIT.md`, `FEATURE_IMPLEMENTATION_MATRIX.md`, `CODEX_SKILLS_INVENTORY.md`, and specialist audits. |
| 2. Architecture and data model | Documented at current-prototype level | See `ARCHITECTURE.md` and `DATA_MODEL.md`; future operational entities are explicitly separated from current tables. |
| 3. GIS correctness | Partial; a concrete direction bug fixed | Python and Kotlin isolation now respect directed routes; unit tests added. Next: actual warning exposure, facility loss/detour metrics, strategy comparison. |
| 4. Android responder workflows | Existing report, status, routing, sync and Nearby screens preserved | Mission/task assignment, location persistence and task-linked inspection remain absent. |
| 5. Backend and dashboard | Existing event hub and route/status API preserved | Warning and mission/task APIs plus schema migrations remain absent. |
| 6. Communications | Existing LAN/Nearby mechanisms preserved | Durable delivery/read/ack queue and two-device/poor-network validation remain absent. |
| 7. Integration | Existing demo flows share a signed pack/event model | V5 acceptance flow is not integrated. |
| 8. QA and release | Python and Android suites run; dashboard JS syntax checked | Browser E2E, device interactions, production signing, 2 GB hardware and deployment remain open. |

## File ownership used

- GIS specialist: `server/kavach/graph.py`, `tests/test_graph.py`.
- Primary Android implementation: `android/app/src/main/java/org/kavach/graph/Engine.kt`, `android/app/src/test/java/org/kavach/EngineGoldenTest.kt`.
- Signed data assets: `data/packs/kavach-osm-medak-kamareddy/` and mirrored `android/app/src/main/assets/pack/`.
- Specialist audit files were kept separate from application source.

## Integration contract

Python and Android continue using the existing crossing status and route policy vocabulary. Isolation is defined as a directed legal path from a settlement to any facility, using only edges permitted for the selected vehicle and status policy. Pack files mirrored into Android must stay byte-identical to hub pack files and the pack manifest/signature must verify.

## Remaining delivery boundary

The full V5 brief includes external warning integrations, role identities, migrations, operational tasks, message acknowledgements, device field trials, 2 GB validation and production hosting. Those are not delivered by the current code change. They need distinct schemas, interfaces, security review and real data/device acceptance; this checkout cannot honestly be called production-ready yet.
