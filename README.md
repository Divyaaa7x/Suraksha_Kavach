# SURAKSHA KAVACH

Offline-first, human-approved decision support for flood-related road access in rural Telangana. The prototype combines an Android field app with a local command hub. It is not an emergency dispatch service or a road-safety certification system.

## Current implementation

- **Android field app:** Compose screens for readiness, a pan/zoom India states overview, detailed offline OSM road map for Medak/Kamareddy, reports, route comparison, isolation/inspection analysis, private-LAN hub sync, Nearby Connections, and event audit. The app verifies its signed pack before showing data. No ML model is required.
- **Coordinator dashboard:** FastAPI dashboard/API with a local SQLite append-only event store, field-report review, route comparison, an India reference overview, and the same local road-detail map. It runs without external map tiles or other web assets.
- **Decision engine:** A* route candidates, strict and warning policies, settlement reachability, crossing criticality, inspection ranking, conflict handling, and explicit no-route results.
- **Evidence handling:** Ed25519-signed observations and approvals, offline trust registry, immutable events, duplicate/replay rejection, and signed pack/hash validation.
- **Map pack:** A bounded OpenStreetMap extract for Medak/Kamareddy plus Natural Earth state/UT reference boundaries for the India overview. Only the Telangana extract has detailed routable roads; the national overview is not a road graph. Every crossing starts `UNKNOWN`; OSM tags do not establish passability.
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

The source extract is `data/raw/osm-medak-kamareddy-2026-11-19.json` (OSM/ODbL). The India overview snapshot is `data/raw/india-admin1-overview.json`; `scripts/build_india_overview.py` refreshes it from a hash-pinned Natural Earth source. Rebuild the OSM pack with:

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
