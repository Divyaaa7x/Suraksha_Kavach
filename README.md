# SURAKSHA KAVACH

[![CI](https://github.com/Divyaaa7x/Suraksha_Kavach/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/Divyaaa7x/Suraksha_Kavach/actions/workflows/ci.yml)

Offline-first, human-approved decision support for flood-related road access in rural Telangana. The prototype combines an Android field app with a local command hub. It is not an emergency dispatch service or a road-safety certification system.

## Try the Android prototype

Download the demo APK from the [0.2.0 prototype prerelease](https://github.com/Divyaaa7x/Suraksha_Kavach/releases/tag/v0.2.0-prototype.1): [direct APK download](https://github.com/Divyaaa7x/Suraksha_Kavach/releases/download/v0.2.0-prototype.1/suraksha-kavach-0.2.0-demo.apk) (about 127 MB, Android 8.0 / API 26 or newer). It has the map-first interface and pilot-area routing. It does not include the statewide OSM route data; that is in the older [0.1.0 prerelease](https://github.com/Divyaaa7x/Suraksha_Kavach/releases/tag/v0.1.0-prototype.1) (about 381 MB).

This is a **demo/debug prototype**, not an operational or emergency-response app. It uses synthetic demo identities; statewide crossing statuses are unverified; route results need human field verification and are not navigation guidance. Long strict searches may time out, memory use exceeded 200 MB in testing, and 2 GB devices have not been validated. See the [release notes](https://github.com/Divyaaa7x/Suraksha_Kavach/releases/tag/v0.2.0-prototype.1) for the APK checksum and complete limitations.

## Kavach Maps interface

The Android app now opens on a full-screen map with floating glass controls and a three-stop bottom sheet. It is included from the 0.2.0 prerelease.

- **Online map:** MapLibre with OpenFreeMap vector tiles (OpenStreetMap data), 3D buildings, relief shading, rotate and tilt. Without a connection the app falls back to the offline Telangana canvas map. An India overview (zoom 0-8) can be downloaded from Regions.
- **Borders:** drawn from Natural Earth's India point of view. This is **not** a Survey of India certified boundary and must be verified before any official use.
- **Search:** pilot places offline; India-wide search online. Nearby results are ordered by straight-line distance.
- **Directions:** Medak/Kamareddy pilot area, with an animated route and camera preview. Times use road-class speeds, not live traffic.
- **Weather:** current temperature and US AQI from Open-Meteo.

**Outside services when online:** map tiles (OpenFreeMap), terrain (AWS Terrain Tiles), place search (the typed text and an approximate position go to `photon.komoot.io`), and weather (position rounded to about 1 km goes to Open-Meteo). None of these is needed for the offline report, routing and sync functions.

Not yet checked: offline India download, light theme, location dot and Share on a device.

## Current implementation

- **Android field app:** Compose screens for readiness, a pan/zoom India states overview, a statewide offline Telangana road map with waterways, settlements, facilities and mapped administrative boundaries, reports, route comparison, isolation/inspection analysis, private-LAN hub sync, Nearby Connections, and event audit. The app verifies its signed pack and map before showing data. No ML model is required.
- **Coordinator dashboard:** FastAPI dashboard/API with a local SQLite append-only event store, field-report review, route comparison, an India reference overview, and the same local road-detail map. It runs without external map tiles or other web assets.
- **V5 warning-to-inspection prototype:** The local hub can import an archived IMD rainfall warning and its sourced Kamareddy district reference geometry, or one supplied CAP 1.2 XML alert. It records provenance and revisions, evaluates time-aware geographic road exposure, and ranks pilot crossings using signed structural criticality. Exposure is a screening result; it does not mean a crossing is flooded or closed.
- **Mission and field loop:** With `KAVACH_DEMO_MODE=1`, the dashboard can create, queue and send an inspection mission to a synthetic squad. Android caches assigned missions and an append-only action outbox in Room, records separate delivery/read/human acknowledgement actions, and stores a signed task-linked observation offline before LAN sync. Supervisor verification writes a signed approval into the existing hub event log, which updates crossing status and route/isolation results. Demo identities are synthetic, visibly labelled and unsuitable for real response.
- **Decision engine:** Pilot A* route candidates, strict and warning policies, settlement reachability, crossing criticality, inspection ranking, conflict handling, and explicit no-route results. An optional indexed Telangana route database enables place search and statewide route candidates in Android and the web hub when the signed data is installed.
- **Evidence handling:** Ed25519-signed observations and approvals, offline trust registry, immutable events, duplicate/replay rejection, and signed pack/hash validation.
- **Map pack:** The India overview uses Natural Earth reference boundaries. Telangana detail is drawn from a spatially indexed OpenStreetMap Southern Zone snapshot, including 532,186 mapped road ways with Telangana nodes, nearby border context, waterways, places/facilities and available administrative boundary ways. Coverage reflects the source mapping and is not a field survey or a guarantee that every road is present. The checked-in app and hub still ship the Medak/Kamareddy pilot route graph because the 297 MB compressed statewide route database is not distributed in this repository. If a matching route database is generated and included in signed metadata, the app and hub can search snapped places and compute statewide candidates; unknown tagged crossings are blocked in strict mode or clearly flagged in warning mode. OSM tags do not establish passability.
- **Synthetic fixture:** A small invented graph used for deterministic golden tests and clearly labelled demo flows.

## Important limits

This is a research prototype, not an emergency-response deployment. `KAVACH_DEMO_MODE=1` enables synthetic mission identities and the synthetic approval path. The IMD record is a historical rainfall warning; CAP import accepts a user-supplied alert document but does not poll live agency feeds. The district polygon is administrative coverage, not a rainfall footprint or flood-inundation map. CWC ingestion is not implemented. Configure `KAVACH_HUB_TOKEN` to require bearer authentication for protected `/v1/*` routes; the dashboard shell remains accessible so a coordinator can enter the token. Without it, the API is unauthenticated and suitable only for a controlled local demo. A bearer token over HTTP does not provide transport confidentiality. Do not expose the hub publicly without TLS and production identity/authorization. Production signing/enrollment, a hosted durable backend, second-device Nearby validation, and 2 GB device performance remain unverified. See [the threat model](docs/threat-model.md) and [the test report](docs/test-report.md).

## Run the local hub

Requires Python 3.11+.

```powershell
python -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install -r server/requirements.txt
python scripts/build_demo_pack.py
\$env:KAVACH_DEMO_MODE = "1"
python -m pytest -q
python -m uvicorn kavach.api:create_app --factory --app-dir server --host 0.0.0.0 --port 8000
```

Open `http://127.0.0.1:8000/`. The default hub uses the OSM pack; the demo-pack build is for tests and explicit synthetic scenarios. For the demo pack, set `KAVACH_PACK_DIR=data/packs/kavach-demo-synthetic` before starting the hub. To protect a private-LAN demo, configure a random token before starting the server (PowerShell: `$env:KAVACH_HUB_TOKEN = [guid]::NewGuid().ToString('N')`). Tokens shorter than 32 UTF-8 bytes are rejected. Enter it in the dashboard's Hub connection security panel and Android Sync screen. Health remains public for diagnostics. Without a token, all data routes are unauthenticated. The sample command binds to all interfaces; do not expose it to the internet, and use TLS for remote access.

## Website hosting (Cloudflare Pages)

**Static dashboard preview:** [suraksha-kavach-prototype.pages.dev](https://suraksha-kavach-prototype.pages.dev). The page is uploaded on Cloudflare's free Pages tier; live coordinator data and actions are unavailable until the FastAPI API is hosted and connected.

The coordinator dashboard currently lives at `/` and its `/v1/*` API is served by the FastAPI app in the same process. Cloudflare Pages can host a static copy of the dashboard, but it does **not** deploy this FastAPI service or its SQLite event store. A Pages-only deployment is therefore a static preview unless an API is separately deployed and configured; do not point it at the unauthenticated local hub or expose bearer tokens over plain HTTP.

To create a static Pages preview from this repository, create a Pages project connected to the GitHub repository, set the production branch to `main`, leave the build command empty, and set the build output directory to `server/static`. The checked-in `index.html` and its local assets will be served, but API features require a separately hosted, TLS-protected backend. The current app has no Cloudflare API/SQLite deployment configuration; use the local hub for private demos until authenticated production hosting, durable storage, and API URL configuration are implemented and verified. Do not describe the Pages preview as a fully hosted working coordinator system yet.

## Android app

Open `android/` in Android Studio with JDK 17 and an installed Android SDK, then build and run the `app` configuration. The APK bundles its map pack for offline use; the online map requests tiles when a connection is available (see Kavach Maps interface). `scripts/build_osm_pack.py` rebuilds the OSM pack and synchronizes its exact signed files into the APK assets.

The Android project has demo and production-variant debug unit tests and builds with Gradle 8.11.1, JDK 21, and Android SDK platform 34. CI builds the `demoDebug` variant; it uses synthetic responder and pack keys and is not for operational use. The V5 feature-branch debug APK is built at `android/app/build/outputs/apk/demo/debug/app-demo-debug.apk`; it is unsigned for distribution and must not be represented as a production release. The current branch APK was not installed on a phone in this pass because ADB reported no attached device. An earlier 0.2.0 prototype had a Pixel 8a run; that does not verify these changes. Production is fail-closed until hub-compatible device signing, trusted enrollment, a production pack signer, and a release keystore exist. The dossier's 2 GB target remains unvalidated. See [the test report](docs/test-report.md) for current test results and pending device checks.

## Rebuild the checked-in OSM pack

The checked-in signed pilot graph source is `data/raw/osm-medak-kamareddy-2026-11-19.json` (OSM/ODbL). The India overview snapshot is `data/raw/india-admin1-overview.json`; `scripts/build_india_overview.py` refreshes it from a hash-pinned Natural Earth source. The Telangana display database and indexed statewide route database are built from the [Geofabrik Southern Zone OSM extract](https://download.geofabrik.de/asia/india/southern-zone.html). The pack builder binds matching map and route database hashes into signed metadata. The compressed statewide routing archive is large (about 297 MB), so it is not committed to Git. For a route-enabled release build, place the matching route database at `data/raw/telangana-routes.sqlite` (or set `KAVACH_TELANGANA_ROUTES_DB`), set `KAVACH_RELEASE_DATA_DIR` to a persistent release-data folder, and run the pack builder. It writes `maps/telangana-routes.mapdata` into that folder; Gradle embeds that file only when building with the same `KAVACH_RELEASE_DATA_DIR`. Keep the signed `offline_map.json` metadata, source databases, and archive from the same build together. Without the route archive, the app continues to start and provides pilot routing. To rebuild the data, download that PBF into `data/raw/southern-zone-latest.osm.pbf`, install the data-build requirements, then run:

```powershell
python -m pip install -r server/requirements.txt osmium shapely
python scripts/build_telangana_map.py
python scripts/build_osm_pack.py
```

The PBF itself is a build input and is not checked in. The generated database records the PBF timestamp and SHA-256; the compressed map asset is checked against metadata covered by the signed pack. First launch verifies and extracts the 158 MB database to app-private storage. OpenStreetMap contributors are credited in-app under ODbL 1.0. Administrative boundaries use OSM where mapped; the Telangana state outline in the overview is Natural Earth reference geometry.

If the map database has already been generated, rebuild the signed pack and Android assets with:

```powershell
python scripts/build_osm_pack.py
```

This computes graph criticality and can take about 40 seconds. The command signs the pack with the repository's demo-only pack key and copies it byte-for-byte into Android assets. Rebuilds produce a new timestamped manifest and signature, so the generated pack changes need to be committed with the source changes.

## Verification

```powershell
python -m pytest -q
```

The Python suite checks the graph, signed event/hub flows, warning provenance and CAP parsing, polygon exposure and replay, mission lifecycle, responder receipts, signed task-linked inspections, supervisor approval and route recalculation, tamper and downgrade rejection, OSM pack provenance, and byte-identical Android pack assets. Dashboard browser tests are under `tests/e2e/`; Android unit tests are under `android/app/src/test/` and require the Android Gradle toolchain.

For the latest scoped checks and honest completion boundaries, see [`TEST_EXECUTION_REPORT.md`](TEST_EXECUTION_REPORT.md), [`FEATURE_IMPLEMENTATION_MATRIX.md`](FEATURE_IMPLEMENTATION_MATRIX.md), [`API_DOCUMENTATION.md`](API_DOCUMENTATION.md), and [`PROJECT_AUDIT.md`](PROJECT_AUDIT.md). The archived-warning-to-inspection workflow is a tested synthetic prototype; live agency polling, production deployment, physical device acceptance, and statewide operational coverage are not complete.

## Safety boundary

Reports remain claims until approved under the trust policy. Conflicts are shown rather than silently resolved. Unknown crossings are conservatively excluded unless an explicit warning policy is selected. Routes are candidates for qualified human review; a model or map tag cannot certify a road, bridge, culvert, or ford as safe.
