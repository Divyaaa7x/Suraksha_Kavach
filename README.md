# SURAKSHA KAVACH

[![CI](https://github.com/Divyaaa7x/Suraksha_Kavach/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/Divyaaa7x/Suraksha_Kavach/actions/workflows/ci.yml)

Offline-first, human-approved decision support for flood-related road access in rural Telangana. The prototype combines an Android field app with a local command hub. It is not an emergency dispatch service or a road-safety certification system.

## Current implementation

- **Android field app:** Compose screens for readiness, a pan/zoom India states overview, a statewide offline Telangana road map with waterways, settlements, facilities and mapped administrative boundaries, reports, route comparison, isolation/inspection analysis, private-LAN hub sync, Nearby Connections, and event audit. The app verifies its signed pack and map before showing data. No ML model is required.
- **Coordinator dashboard:** FastAPI dashboard/API with a local SQLite append-only event store, field-report review, route comparison, an India reference overview, and the same local road-detail map. It runs without external map tiles or other web assets.
- **Decision engine:** A* route candidates, strict and warning policies, settlement reachability, crossing criticality, inspection ranking, conflict handling, and explicit no-route results.
- **Evidence handling:** Ed25519-signed observations and approvals, offline trust registry, immutable events, duplicate/replay rejection, and signed pack/hash validation.
- **Map pack:** The India overview uses Natural Earth reference boundaries. Telangana detail is drawn from a spatially indexed OpenStreetMap Southern Zone snapshot, including 532,186 mapped road ways with Telangana nodes, nearby border context, waterways, places/facilities and available administrative boundary ways. Coverage reflects the source mapping and is not a field survey or a guarantee that every road is present. The routable graph remains the Medak/Kamareddy pilot; statewide map display does not imply statewide routing. Every crossing in that route graph starts `UNKNOWN`; OSM tags do not establish passability.
- **Synthetic fixture:** A small invented graph used for deterministic golden tests and clearly labelled demo flows.

## Important limits

This is a research prototype. The phone uses a clearly labelled synthetic demo signing key, not Android Keystore provisioning. The local hub API is unauthenticated and accepts signatures from synthetic demo keys; keep it on a controlled private demo network and do not publish or expose it to the internet. The optional dashboard approval route is disabled unless `KAVACH_DEMO_MODE=1`, and remains synthetic when enabled. Nearby exchange has not been validated across two physical devices, and app performance has not been measured on a 2 GB phone. The app has no certified field data, live agency integration, structural inspection, guaranteed radio coverage, or autonomous dispatch. See [the threat model](docs/threat-model.md) and [the test report](docs/test-report.md).

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

Open `http://127.0.0.1:8000/`. The default hub uses the OSM pack; the demo-pack build is for tests and explicit synthetic scenarios. For the demo pack, set `KAVACH_PACK_DIR=data/packs/kavach-demo-synthetic` before starting the hub. The API binds to all interfaces in the command above so a phone can reach it over a private LAN; use only in a controlled demo network because authentication is not implemented.

## Android app

Open `android/` in Android Studio with JDK 17 and an installed Android SDK, then build and run the `app` configuration. The APK bundles its map pack and performs no map tile requests. `scripts/build_osm_pack.py` rebuilds the OSM pack and synchronizes its exact signed files into the APK assets.

The Android project and 22 Kotlin unit tests build successfully with Gradle 8.11.1, JDK 21, and Android SDK platform 34. The debug APK was installed and exercised on a connected Pixel 8a running Android 17. The Pixel is not a 2 GB device; the dossier's 200 MB target and 2 GB suitability are not established. See [the test report](docs/test-report.md) for measurements and pending field checks.

## Rebuild the checked-in OSM pack

The route graph source is `data/raw/osm-medak-kamareddy-2026-11-19.json` (OSM/ODbL). The India overview snapshot is `data/raw/india-admin1-overview.json`; `scripts/build_india_overview.py` refreshes it from a hash-pinned Natural Earth source. The Telangana display database is built from the [Geofabrik Southern Zone OSM extract](https://download.geofabrik.de/asia/india/southern-zone.html) and is packaged compressed in the Android assets. To rebuild it, download that PBF into `data/raw/southern-zone-latest.osm.pbf`, install the data-build requirements, then run:

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
