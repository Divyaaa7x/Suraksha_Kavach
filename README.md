# SURAKSHA KAVACH

[![CI](https://github.com/Divyaaa7x/Suraksha_Kavach/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/Divyaaa7x/Suraksha_Kavach/actions/workflows/ci.yml)

Offline-first, human-approved decision support for flood-related road access in rural Telangana. The prototype combines an Android field app with a local command hub. It is not an emergency dispatch service or a road-safety certification system.

## Current implementation

- **Android field app:** Compose screens for readiness, a pan/zoom India states overview, a statewide offline Telangana road map with waterways, settlements, facilities and mapped administrative boundaries, reports, route comparison, isolation/inspection analysis, private-LAN hub sync, Nearby Connections, and event audit. The app verifies its signed pack and map before showing data. No ML model is required.
- **Coordinator dashboard:** FastAPI dashboard/API with a local SQLite append-only event store, field-report review, route comparison, an India reference overview, and the same local road-detail map. It runs without external map tiles or other web assets.
- **Decision engine:** Pilot A* route candidates, strict and warning policies, settlement reachability, crossing criticality, inspection ranking, conflict handling, and explicit no-route results. An optional indexed Telangana route database enables place search and statewide route candidates in Android and the web hub when the signed data is installed.
- **Evidence handling:** Ed25519-signed observations and approvals, offline trust registry, immutable events, duplicate/replay rejection, and signed pack/hash validation.
- **Map pack:** The India overview uses Natural Earth reference boundaries. Telangana detail is drawn from a spatially indexed OpenStreetMap Southern Zone snapshot, including 532,186 mapped road ways with Telangana nodes, nearby border context, waterways, places/facilities and available administrative boundary ways. Coverage reflects the source mapping and is not a field survey or a guarantee that every road is present. The checked-in app and hub still ship the Medak/Kamareddy pilot route graph because the 297 MB compressed statewide route database is not distributed in this repository. If a matching route database is generated and included in signed metadata, the app and hub can search snapped places and compute statewide candidates; unknown tagged crossings are blocked in strict mode or clearly flagged in warning mode. OSM tags do not establish passability.
- **Synthetic fixture:** A small invented graph used for deterministic golden tests and clearly labelled demo flows.

## Important limits

This is a research prototype. The phone uses a clearly labelled synthetic demo signing key, not Android Keystore provisioning. Configure `KAVACH_HUB_TOKEN` to require bearer authentication for all `/v1/*` data routes except health; the dashboard shell stays accessible so a coordinator can enter the token. Enter the same token in the dashboard tab and Android Sync screen. Without it, the API remains unauthenticated and is only suitable for a controlled demo network. A bearer token over HTTP does not provide transport confidentiality; never expose the hub publicly without TLS and production authentication. The optional dashboard approval route is disabled unless `KAVACH_DEMO_MODE=1`, and remains synthetic when enabled. Nearby exchange has not been validated across two physical devices, and app performance has not been measured on a 2 GB phone. The app has no certified field data, live agency integration, structural inspection, guaranteed radio coverage, or autonomous dispatch. See [the threat model](docs/threat-model.md) and [the test report](docs/test-report.md).

## Run the local hub

Requires Python 3.11+.

```powershell
python -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install -r server/requirements.txt
python scripts/build_demo_pack.py
python -m pytest -q
python -m uvicorn kavach.api:create_app --factory --app-dir server --host 0.0.0.0 --port 8000
```

Open `http://127.0.0.1:8000/`. The default hub uses the OSM pack; the demo-pack build is for tests and explicit synthetic scenarios. For the demo pack, set `KAVACH_PACK_DIR=data/packs/kavach-demo-synthetic` before starting the hub. To protect a private-LAN demo, configure a random token before starting the server (PowerShell: `$env:KAVACH_HUB_TOKEN = [guid]::NewGuid().ToString('N')`). Tokens shorter than 32 UTF-8 bytes are rejected. Enter it in the dashboard's Hub connection security panel and Android Sync screen. Health remains public for diagnostics. Without a token, all data routes are unauthenticated. The sample command binds to all interfaces; do not expose it to the internet, and use TLS for remote access.

## Android app

Open `android/` in Android Studio with JDK 17 and an installed Android SDK, then build and run the `app` configuration. The APK bundles its map pack and performs no map tile requests. `scripts/build_osm_pack.py` rebuilds the OSM pack and synchronizes its exact signed files into the APK assets.

The Android project currently has 31 demo and 32 production-variant debug unit tests (three demo-only cases are skipped in the production variant) and builds with Gradle 8.11.1, JDK 21, and Android SDK platform 34. CI builds the `demoDebug` variant; it uses synthetic responder and pack keys and is not for operational use. Production is fail-closed: `assembleProductionRelease` refuses to produce a release until hub-compatible device signing and trusted enrollment are implemented and validated. The release also requires a production pack signer, a non-demo responder identity already enrolled in the signed trust pack, and a release keystore. Keep private signing material out of the repository. Production event signing intentionally refuses to run until that identity path exists. The demo APK was installed and exercised on a connected Pixel 8a running Android 17. The Pixel is not a 2 GB device; the dossier's 200 MB target and 2 GB suitability are not established. See [the test report](docs/test-report.md) for measurements and pending field checks.

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

The Python suite checks the graph, signed event/hub flows, tamper and downgrade rejection, OSM pack provenance, and byte-identical Android pack assets. Android unit tests are under `android/app/src/test/` and require the Android Gradle toolchain.

## Safety boundary

Reports remain claims until approved under the trust policy. Conflicts are shown rather than silently resolved. Unknown crossings are conservatively excluded unless an explicit warning policy is selected. Routes are candidates for qualified human review; a model or map tag cannot certify a road, bridge, culvert, or ford as safe.
