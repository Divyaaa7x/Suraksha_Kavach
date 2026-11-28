# Project Audit

**Audited revision:** `1c8ae9eb4ed3b6e76948c70dc1ad4eda901c752d` (`main`, 28 November 2026). The checkout was clean before the audit; no user edits were overwritten. This is a code and prototype audit, not a field-safety assessment or penetration test.

## What this project does

SURAKSHA KAVACH is an offline-first Android field prototype and a FastAPI/SQLite coordinator hub for road access decisions in rural Telangana. The Android app combines a MapLibre India map with a local Telangana map, reports, pilot routing, analysis, LAN sync and Nearby Connections. The website is a single HTML/CSS/JavaScript dashboard backed by the same FastAPI service.

## Repository shape

- `android/`: single Kotlin/Compose app, demo and production flavors, local graph and map pack, optional route database, local events and communications.
- `server/kavach/`: FastAPI app, signed pack/event handling, SQLite hub, route/isolation/ranking services and optional statewide route runtime.
- `server/static/index.html`: dashboard, canvas maps and API client; no React/TypeScript frontend.
- `data/`: OSM pilot pack, raw OSM inputs, synthetic fixture and Natural Earth reference overview.
- `scripts/`: OSM/pack/India boundary builders and route-data packaging helpers.
- `tests/`, `android/app/src/test/`, `tests/e2e/`: Python, Kotlin unit and browser E2E tests.
- `docs/`: safety, test and demo documentation.

## Findings

1. **Fixed in this change:** settlement-to-facility isolation used undirected connected components, despite route edges carrying one-way direction. A one-way road pointing from the facility toward a village could therefore make analysis claim the village could reach the facility. Python and Kotlin now use reverse traversal from facilities, respecting eligible direction, vehicle, crossing status and blocked crossings. Regression tests cover both directions.
2. **V5 operating loop is absent:** there is no official warning ingestion, spatial exposure analysis, mission/squad/task store, assignment/delivery/read/acknowledgement lifecycle, or persistent inspection-verification workflow.
3. **Default route graph is a pilot:** standard release data includes a Medak/Kamareddy routing graph. Telangana map display is broader, but statewide route search needs a separately generated, signed and distributed ~297 MB route archive.
4. **Inspection ranking is a topology baseline:** it counts settlements newly disconnected when one crossing is removed with all others assumed open. It does not account for current warning polygons/time, travel effort, reliable population (only 2 of 473 graph settlements have estimates), facility consequence weights or uncertainty.
5. **Operational trust is not provisioned:** production responder signing, Android Keystore identity, organizational enrollment/revocation and independent production pack-signing keys are absent. Demo identities are synthetic.
6. **Deployment is prototype-level:** API auth is optional shared bearer-token protection, defaults off; bearer credentials and location data need TLS and real identity access control for remote deployment. Cloudflare Pages hosts only the static dashboard shell; it does not host the FastAPI/SQLite API.
7. **Offline communications are not fully validated:** Nearby device pairing/transfer requires a second phone; GPS freshness, kill/restart durability, airplane-mode startup and background radio behavior are not fully checked.
8. **Hardware/performance gates remain:** Pixel 8a runs the app, but does not validate 2 GB devices. Earlier statewide-route testing exceeded ~200 MB process memory and long strict searches can hit `SEARCH_LIMIT`.
9. **UI verification is incomplete:** the existing visual identity and map-first UI are present. No Compose UI automation/screenshot regression harness exists; not every sheet, theme, offline download and accessibility state was exercised.

## Build and expected load

The project documents local/private-LAN prototype use. No public multi-user production scale target is established. Python and Android build/test evidence is in `TEST_EXECUTION_REPORT.md`. Full OSM data generation and 2 GB hardware acceptance are not part of a routine build.

## Detailed reviews

- UI: `FRONTEND_PRESERVATION_SPEC.md`.
- GIS and road graph: `GIS_AUDIT.md`.
- Backend/security: `BACKEND_SECURITY_AUDIT.md`.
