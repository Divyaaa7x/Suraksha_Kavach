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
| Crossing criticality and inspection ranking | Partially implemented | Explainable structural count; no warning exposure, population/facility weighting, detour/inspection cost or uncertainty model. |
| Official IMD/NDMA/CWC warning ingestion | Missing | No source adapter, warning revision/cancellation/validity model or provenance-preserving cache. |
| Warning-to-crossing geospatial exposure | Missing | Scenario overlays accept manually supplied IDs; no polygon/road/crossing temporal intersection. |
| Responder, squad, mission and inspection task assignment | Missing | No domain tables, APIs or UI workflow. |
| Message delivery vs human acknowledgement states | Missing | Event sync acknowledges server receipt/event IDs, not responder receipt/read/ack workflow. |
| Structured inspection and authorized verification | Partially implemented | Signed observation/approval event model exists for pilot crossings; no task-linked structured workflow or statewide crossing trust. |
| Hub auth / event signature / duplicate protections | Implemented for prototype | Covered by tests, but hub token is optional, shared and not a production identity/session system. |
| LAN sync | Implemented but device-unverified | Server and Android client paths; current docs list live Android LAN sync check pending. |
| Nearby phone-to-phone transfer | Partially implemented | Android client and unit tests; physical two-device pairing and transfer not verified. |
| Coordinator dashboard | Implemented for prototype | Single HTML page and FastAPI API; browser E2E exists; static Cloudflare Pages URL lacks backend. |
| Production deployment/security | Missing | No production identities, TLS deployment, durable service hosting or role-scoped accounts. |
| 2 GB Android target | Unverified / performance risk | Pixel 8a is higher-memory; previous optional statewide run exceeded target and strict mode may time out. |
| Whole V5 end-to-end acceptance flow | Missing | No single persistent warning→assignment→ack→inspection→verification→graph-update flow. |
