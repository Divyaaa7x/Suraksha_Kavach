# Feature Implementation Matrix

Status reflects code plus evidence available on 28 November 2026. A code path is not considered device-verified merely because it compiles.

| Feature | Status | Evidence / limitation |
|---|---|---|
| Map-first Android home, sheet, search and floating controls | Implemented but partially verified | Pixel 8a screenshot for installed Atlas v0.1.0; newer source UI not installed due package/signature issue in prior work. |
| Online India map and location/map controls | Implemented but unverified in this pass | MapLibre/OpenFreeMap styles and camera controls in `AtlasMap.kt`; external tiles/terrain required. |
| Offline Telangana display map | Implemented but unverified in this pass | Local SQLite map renderer and signed pack; mapped coverage is not a field survey. |
| India offline region download | Implemented but unverified | `IndiaOnline.kt`; README lists it as not yet checked on device. |
| Pilot route calculation and crossing policy | Implemented and tested | Python/Kotlin graph engines and route/status tests; route is decision support, not navigation/safety certification. |
| Statewide route runtime | Partially implemented | Optional SQLite/A* runtime and test fixtures exist; statewide route archive is not in standard repo/release build. |
| Settlement-to-facility isolation | Implemented and corrected/tested | New direction-aware reverse-reachability fix in Python and Kotlin; both one-way orientations covered. |
| Crossing criticality and warning exposure ranking | Implemented for pilot prototype | Time-aware polygon/crossing exposure is ranked by precomputed signed structural criticality. Population, facility consequence and inspection travel cost are not invented or included. |
| Archived official/CAP warning ingestion | Implemented for replay prototype | Provenance/revisions/validity/cancel handling, bundled historical IMD report and one-off CAP 1.2 XML import; no live IMD/NDMA/CWC polling. District polygon is a coarse administrative reference, not the rainfall footprint. |
| Warning-to-crossing geospatial exposure | Implemented and tested for pilot graph | Validated polygons and time-window check; unique geographic intersections are marked `GEOGRAPHICALLY_EXPOSED` and physical state remains `UNCONFIRMED`. No closure is inferred. |
| Responder, squad, mission and inspection task assignment | Implemented for synthetic demo | SQLite operational tables/API and dashboard workflow; demo identities and writes require `KAVACH_DEMO_MODE=1`. |
| Message delivery vs human acknowledgement states | Implemented for prototype | Explicit mission state machine plus distinct responder receipt records. LAN client/outbox is implemented; actual phone delivery/reconciliation is not verified on hardware. |
| Structured inspection and authorized verification | Implemented for pilot graph prototype | Task-linked signed observation, responder/supervisor role checks, separate submitted/verified status and signed Hub event updates crossing state/routes. Synthetic identity path is not production auth. |
| Hub auth / event signature / duplicate protections | Implemented for prototype | Covered by tests, but hub token is optional, shared and not a production identity/session system. |
| LAN sync | Implemented but device-unverified | Server and Android client paths; current docs list live Android LAN sync check pending. |
| Nearby phone-to-phone transfer | Partially implemented | Android client and unit tests; physical two-device pairing and transfer not verified. |
| Coordinator dashboard | Implemented for operational prototype | Existing single-page HTML/CSS/JS extended with warning import/replay, exposure map overlay, mission queue and supervisor review. Browser E2E covers existing dashboard flows; V5 UI smoke/E2E is not yet automated. Static Cloudflare Pages URL lacks backend. |
| Production deployment/security | Missing | No production identities, TLS deployment, durable service hosting or role-scoped accounts. |
| 2 GB Android target | Unverified / performance risk | Pixel 8a is higher-memory; previous optional statewide run exceeded target and strict mode may time out. |
| Warning→mission→inspection→verification→graph update | Implemented and integration-tested via API | Archived warning import, real pilot crossing exposure, mission lifecycle, responder acknowledgement, signed inspection, supervisor decision and route/status recalculation are covered by `tests/test_ops_api.py`. Full Android/offline physical walkthrough and reconnect reconciliation remain unverified. |
