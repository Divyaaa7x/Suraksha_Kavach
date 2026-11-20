# Verification report

Updated 20 November 2026. This report contains observed results only; unrun hardware and platform checks stay pending.

## Statewide route runtime continuation — 20 November 2026

- Added optional signed-data packaging and installation for the statewide route SQLite database. The pack builder verifies that map and route databases came from the same SHA-256-pinned OSM extract; Android verifies both compressed archive and expanded database hashes, then checks route tables and indexes. Route-table validation now probes only the first required rows rather than scanning millions of rows at startup.
- Added an Android SQLite place search and A* route planner with indexed adjacency, one-way direction, vehicle masks, and a bounded search. The Routes screen preserves both pilot and Telangana modes; statewide results can be framed and highlighted on the offline map. Unknown mapped crossings are blocked under strict policy and clearly flagged under warning policy; OSM tags never imply passability.
- Added a hub SQLite route store with signed-database hash verification, place search, route compute API, and dashboard statewide mode. Pilot remains the default. Dashboard browser E2E covers place autocomplete, statewide route submission, unknown-crossing warning, and map geometry.
- Verification on the current working tree: Python suite **74 passed**; Android demo unit tests **31 passed**; production variant **29 passed, 3 demo-only skipped**; dashboard browser E2E **3 passed**; inline JavaScript syntax and `git diff --check` passed. The route metadata packaging helper was exercised with the generated databases: map DB **131,264,512 B**, route DB **590,577,664 B**, compressed route asset **297,135,922 B**. Map/route source hashes matched.
- Pixel 8a wireless ADB check: the current demo APK launched without a fatal app exception; the existing pilot route computed and highlighted on the offline Telangana map. The checked-in pack has no statewide route asset, so this device check did not exercise the new statewide runtime.
- The route runtime is optional and remains dormant in standard repository builds until a matching route DB is distributed and its hash is signed into the pack. No statewide crossing status is currently accepted through the trusted field event workflow, so all OSM crossing IDs remain `UNKNOWN`. The route output is a decision-support candidate, not turn-by-turn navigation or safety certification.
- Production gates still open: distribution of the 297 MB route asset; trusted statewide crossing inspections/approvals; production device identity enrollment and release signing; mandatory authenticated TLS hosting/deployment; two-device Nearby validation; and performance/acceptance testing on a 2 GB device. The Pixel 8a has 7.75 GB RAM and does not meet that hardware test requirement.

## Route-enabled release-data packaging and Pixel route check — 20 November 2026

- `KAVACH_RELEASE_DATA_DIR` now opts the pack builder and Android Gradle build into a separate route-data directory. The builder writes the route archive there and signs its archive/database hashes into `offline_map.json`; Gradle bundles the matching `maps/telangana-routes.mapdata` when the same directory is configured. A normal build can omit the archive and still start with pilot routing. The app can also read a separately installed archive from app-private `files/maps/` and verifies the same signed hashes before use.
- Local route-enabled `demoDebug` APK built at **381,005,552 bytes** and its ZIP contents confirmed the expected **297,135,922-byte** route archive. Python route/builder tests: **18 passed**. Android demo and production unit tests and debug assemble succeeded with the route-enabled package.
- Pixel 8a route-enabled APK installed. The app extracted the signed **590,577,664-byte** route DB and then returned offline place suggestions for Kamareddy and Nizamabad. Warning mode produced a **754-segment** candidate estimated at 3,099 s. The captured process PSS was **222,296 KiB** after route calculation; the strict-mode `SEARCH_LIMIT` state appeared by the 20-second capture and showed no route. PSS at that capture was **238,597 KiB**. Frame spot check: 195 frames, 7 janky (3.59%), p50 7 ms, p90 10 ms, p99 150 ms. No fatal exception or ANR appeared in the checked log window.
- This confirms the opt-in demo data path on the Pixel 8a; it does not make the source repository contain the route database or create a production-signed build. A standard checkout continues to use pilot-only routes. The 2 GB PSS target is exceeded during route calculation, and a long strict search can return `SEARCH_LIMIT`; both remain release blockers.
- GitHub Actions run [44733785873](https://github.com/Divyaaa7x/Suraksha_Kavach/actions/runs/44733785873) completed successfully for the packaging code (Python, dashboard browser, Android).
- GitHub Actions run [44734586347](https://github.com/Divyaaa7x/Suraksha_Kavach/actions/runs/44734586347) completed successfully for commit `683092c` (Python, dashboard browser E2E, Android unit tests/debug build and production-release fail-closed gate).

## Latest route compatibility and performance follow-up — 20 November 2026

- Commit `4e27560` accepts both legacy and compact adjacency index names when verifying route databases. This was required by the generated 590,577,664-byte route DB, whose actual indexes use `route_edges_compact_from`, `_to`, and `_crossing`. The offline pack builder also imports the `math` module required by its pilot-pack spatial grid.
- The Android A* search now has a 15-second wall-clock ceiling in addition to its expansion ceiling. Hitting either ceiling yields `SEARCH_LIMIT` with no path; it is not reported as `NO_VERIFIED_ROUTE`. This is an explicit bounded failure, not a solution for long strict-mode statewide searches.
- Local verification after these changes: Python suite **76 passed**; focused builder/route tests **17 passed**; Android demo and production unit-test tasks and `assembleDemoDebug` succeeded. GitHub Actions run [44729659614](https://github.com/Divyaaa7x/Suraksha_Kavach/actions/runs/44729659614) completed successfully for Python, dashboard browser, and Android jobs.
- Real route DB desktop check: Kamareddy to Nizamabad warning policy returned 754 segments in 2.32 seconds, with 10 crossings of unknown status and 37,807 expanded nodes. Earlier Pixel 8a testing produced the same warning candidate in under 15 seconds, but strict mode exceeded 45 seconds and reached about 370 MiB PSS before it was stopped. The new deadline was compiled and installed; the follow-up UI interaction could not be completed because the phone returned to its locked notification shade.
- A statewide-data APK (the route DB asset is about 297 MB compressed) was built and installed locally for route verification. The binary remains untracked and absent from GitHub; a normal checkout therefore still does not provide statewide place search/routing. The tracked production/demo package remains the pilot build.
- The route UI and route data remain a research prototype. Unknown statewide crossings cannot currently be field-approved through the trust workflow; strict searches can hit `SEARCH_LIMIT`; warning routes are not navigation guidance. Production signing/enrollment, authenticated TLS hosting, data distribution, two-device Nearby checks, and 2 GB-device performance validation remain incomplete.

## Latest continuation verification — 20 November 2026

- OSM routing rules: Python suite **61 passed**; Android `testDemoDebugUnitTest` **26 passed** and `testProductionDebugUnitTest` **24 passed, 3 demo-only tests skipped**; both demo and production debug APKs assembled. Dashboard browser E2E: **2 passed**.
- Routing-pack update `7e2ef38` adds OSM vehicle access and one-way handling. The rebuilt signed pilot pack verifies and is byte-identical between hub and Android. The statewide map still has display coverage only; the route graph remains the Medak/Kamareddy pilot.
- Pixel 8a (Android 17, 7.75 GB RAM) is reachable over wireless ADB. The demo APK installed and launched without a fatal exception in the checked logcat window. The device was at its secure lock screen, so visual interaction and route gestures were not checked. The production debug APK installed but failed closed during pack signature verification because this workstation has no production pack-signing public key configured; this is not a provisioned production release test.
- A full Southern Zone PBF map build completed in about ten minutes and reported 854,785 contextual roads, 532,186 roads with Telangana nodes, 9,847 waterways and 33,596 places/facilities. Its SQLite output differed slightly from the already signed map archive (four spatial-cell rows and file hash), so the signed asset was left unchanged. This pass did not generate a statewide routing graph.
- Remaining release gates: statewide offline routing and place search; production key/device enrollment and release signing; mandatory secure hub deployment and hosting; two-device Nearby field checks; Pixel interaction review and 2 GB device performance checks. Debug APK success is not production readiness.

## Statewide route data prototype — 20 November 2026

- The offline map builder now creates an additional indexed SQLite route database during the same PBF pass. Against the Southern Zone source, it produced **3,897,281 nodes, 4,165,108 segments, 10,158 tagged crossings, and 20,610 snapped places/facilities out of 21,157**. It excludes explicitly closed/private/destination-only and conditional-access roads, stores per-class access masks, and applies one-way direction.
- The final route DB schema uses numeric way/segment keys and indexed `u`/`v` adjacency; temporary snap-grid rows are removed. The generated 590,577,664-byte DB passed SQLite `integrity_check`. Gzip at level 6 measured 297,135,922 bytes. The adjacency query plan uses both indexes.
- A desktop SQLite A* baseline from Kamareddy to Nizamabad found a 754-segment path in 2.06 seconds after 37,807 expansions. It crossed 10 tagged structures; because status-policy filtering is not connected in this prototype, this demonstrates topology only and must not be used as a safe route.
- At the initial data-generation stage, builder tests covered access rules, direction handling, stable nearest-place snapping, and adjacency index use; the Python suite then had **63 passed**.
- At that initial stage the route DB was not packaged or consumed. The runtime and signed-metadata packaging were added in the continuation section above. The 297 MB asset is still absent from this repository, and Git LFS upload access is not configured, so the checked-in app/hub do not yet receive statewide routing data.

## Current branch follow-up — 20 November 2026

- Added optional `KAVACH_HUB_TOKEN` authentication for all `/v1/*` data routes except health, with bearer tokens sent by both the web dashboard and Android LAN sync. Dashboard token is held in the current browser tab; Android token stays in ViewModel memory and is masked in the Sync screen. A configured-token API regression test verifies missing/wrong tokens are rejected and a correct token is accepted.
- Verification: Python suite **58 passed**, including rejection of tokens shorter than 32 UTF-8 bytes; Android `testDemoDebugUnitTest testProductionDebugUnitTest assembleDemoDebug` succeeded; dashboard JavaScript `node --check` and `git diff --check` passed. A live local hub check returned HTTP 401 without the token and HTTP 200 with it; health reported auth enabled.
- Latest demo APK installed on the connected Pixel 8a. The device had returned to its secure lock screen during this check, so a fresh visual review and Android-to-hub sync with a token were not verified. Earlier Pixel map and UI observations are listed below and are not evidence for this auth change.
- This remains partial security: if no token is configured the API is open; plain HTTP exposes bearer tokens to network interception. TLS, production identity/enrollment, device revocation, Nearby two-device validation and production release signing remain open.
- Added Chromium dashboard E2E checks to CI for initial page hydration, synthetic pack data, pilot crossing search/detail (including UNKNOWN status), strict no-route and warning-route behavior, token entry after an initial 401, JavaScript errors, and 390 px mobile overflow. The first browser run found and fixed horizontal overflow in the mobile grid and a noisy missing-features response for synthetic packs. Local browser checks: **2 passed**; all inline scripts pass `node --check`.

## Automated checks

| Check | Result | Evidence |
|---|---|---|
| Python unit/integration suite | PASS | `python -m pytest -q`: 44 tests passed on Windows/Python 3.14.7, including India overview, demo-approval guard, and parallel dashboard SQLite read cases. |
| Android build | PASS | Gradle 8.11.1, JDK 21, Android SDK platform 34: `testDebugUnitTest assembleDebug` succeeded. |
| Android Kotlin unit tests | PASS | 22 tests across graph, JSON/crypto, pack verification, LAN URL validation, and Nearby payload batching; zero failures. |
| Install and launch on Pixel 8a | PASS | Debug APK installed through ADB; `org.kavach/.MainActivity` launched on Android 17. |
| India overview and local-detail switch | PASS | Pixel renders 36 Natural Earth state/UT reference boundaries, highlights Telangana, and opens the bounded Medak/Kamareddy OSM detail map. |
| Route candidate and route-focused map | PASS | Pixel produced `ROUTE_CANDIDATE` (95 nodes/94 segments); “Show route on map” focuses the map around the teal outlined candidate path. |
| Permission timing and Nearby start | PARTIAL PASS | Location and Nearby permissions are requested only from their actions. Nearby starts on Pixel; a second physical device was unavailable for pairing and transfer verification. |
| Coordinator dashboard | PASS | Local FastAPI dashboard loaded in browser with India overview, Telangana selected, local road map, and coordinator review panel. |
| Synthetic coordinator API guards | PASS | Default-disabled demo approval and nonblank rationale are covered by API tests. |
| Synthetic graph golden cases | PASS | `tests/test_graph.py`, including closed-edge exclusion and no-route behavior. |
| Hub event, signature, conflict, replay and payload checks | PASS | `tests/test_hub.py`. |
| OSM pack verification and provenance rules | PASS | `tests/test_real_pack.py`; crossings start unknown. |
| Android APK data pack matches signed hub pack | PASS | `test_apk_pack_is_byte_identical_and_verified`. |
| Pack rebuild | PASS | `scripts/build_demo_pack.py` and `scripts/build_osm_pack.py`; OSM pack build also synchronizes Android assets. |

## Follow-up validation — 20 November 2026

- Production release safety gate: added separate `demo` and `production` build flavors. Demo debug builds continue to use only synthetic keys. Production event signing refuses to create reports until a hub-compatible signing key and trusted enrollment are implemented. `assembleProductionRelease` is blocked even when release credentials are present; absent provisioning produces an explicit missing-credentials failure. This prevents a release APK from being mistaken for an operational build.
- Latest local verification: Python suite 56 passed, covering oversized content-length and chunked-body rejection, malformed signed events, asset-scoped trust, source-class restrictions, non-finite/invalid coordinates, and signer identity consistency; Android `testDemoDebugUnitTest testProductionDebugUnitTest assembleDemoDebug` succeeded (demo: 25 passed; production: 23 passed, 3 demo-fixture tests skipped). Production-variant tests confirm report signing refuses an unprovisioned identity. Nearby transfer unit tests cover byte/item limits and reject ACKs for unsent event IDs. The production release task failed before packaging and left no production APK. Dashboard JavaScript syntax check and `git diff --check` passed. The demo APK was installed on the connected Pixel 8a and `MainActivity` resumed; the checked recent logcat contained no fatal exception, app ANR, or SQLite exception.
- GitHub Actions run for commit `912225a` completed successfully (Python, Android, dashboard). The production gate and its CI assertion are newer local changes; their hosted CI result remains pending until pushed and a new run completes.

- Startup/render update: the Android app now publishes the verified core pack before decompressing and verifying the 131 MB map database in the background. Pixel 8a reached the ready screen while map verification continued; the detailed offline map appeared after verification. On a short map pan/zoom sample, `dumpsys gfxinfo` recorded 183 frames with 6 janky frames (3.28%; p50 6 ms, p90 9 ms, p99 200 ms). This is a device spot check, not a 2 GB performance guarantee.
- Dashboard map now reuses cached `Path2D` geometry between redraws and avoids resetting the canvas backing store when its size is unchanged. A 20-draw browser check at a 687 × 358 CSS-pixel map measured median 0.1 ms and maximum 0.3 ms per `draw()` call; this is a static headless-browser sample, not a full interaction benchmark.
- The updated Android build passed `testDebugUnitTest assembleDebug`; Python suite passed (44 tests); dashboard inline JavaScript passed `node --check`. Pixel 8a displayed the detailed Telangana map and search; dashboard search opened the selected crossing card. No app crash or ANR appeared in the checked logcat window.
- Fixed Android road-name generation so a named road contributes one midpoint label, regardless of how many geometry points it contains.
- Added dashboard map zoom and fit controls, keyboard pan/zoom/reset, and a keyboard-accessible pilot-crossing selector with status details.
- Python suite: 44 passed. Android `testDebugUnitTest assembleDebug`: build successful with the project toolchain (Gradle 8.11.1, JDK 21, SDK 34). Dashboard inline JavaScript passed `node --check`.
- Pixel 8a (Android 17): installed and launched the updated APK over wireless ADB; verified India overview, statewide Telangana map rendering, zoom/pan, route candidate calculation (95 nodes, 94 segments), and route display on the map. Opened the report form and inspected validation fields without submitting a fabricated report. Filtered logcat showed no app crash or SQLite errors.
- Dashboard browser: verified route calculation, crossing selection, zoom button and keyboard pan. At a 390 px mobile viewport, document width remained 390 px with a 340 px map; desktop viewport was 1440 px. Captured screenshots at both sizes.
- Map UI pass: Pixel 8a showed the statewide Telangana map with north-up cue, dynamic scale bar, and route-focused label filtering. Reduced map height and a visible “Choose a crossing” empty state for the accessible pilot-crossing picker.
- Dashboard UI pass: 390 px mobile viewport has no horizontal overflow (390 px document width), map appears in the first viewport, the India overview starts collapsed, and the offline map reports a 50 km scale with 96 pilot crossings available. The India overview expands and redraws; the “prefers-reduced-motion” media setting was recognized. Desktop canvas measured 898 px wide at a requested 1440 px emulated viewport; headless Chromium reported a 1425 px CSS layout viewport because of its scrollbar.
- After the map UI edits, `python -m pytest -q -p no:cacheprovider`: 44 passed; inline dashboard JavaScript passed `node --check`; Android `testDebugUnitTest assembleDebug` succeeded. The final picker-placeholder/map-height tweak compiled in the next Android build before the device capture.
- No Cloudflare deployment was performed. The page still depends on the FastAPI `/v1/*` API; the hub is unauthenticated and intended for a private demo LAN, so it must not be exposed publicly as-is.

## Pending checks

| Check | Status | Reason |
|---|---|---|
| Airplane-mode cold start, GPS freshness, process-kill durability | PENDING DEVICE | Offline cold start and process-kill durability were not exercised. An indoors GPS request returned the expected “No GNSS fix yet” message; an outdoor fix was not tested. |
| 2 GB RAM PSS, cold-start and route latency budgets | PENDING DEVICE | Pixel 8a is not a 2 GB device. No 2 GB performance claim is made. |
| Nearby phone-to-phone pairing and transfer | PENDING DEVICES | Start/permission flow was exercised on Pixel; peer discovery, pairing confirmation, and transfer require a second physical Android device. |
| LAN phone-to-hub sync | PENDING DEVICE | Python API behavior is covered, but Android networking is not device-validated. |
| Background behavior, battery and radio range | PENDING DEVICES | Requires controlled tests on target phones. |

## Pixel 8a observation

The connected Pixel 8a (Android 17, device codename `akita`) installed and launched the debug app. A recent route-map reading after rendering was 191,937 KiB PSS (about 187 MiB); a recent settled home reading was 139,799 KiB. Earlier active-map captures were higher. These are spot readings from a Pixel 8a, not a 2 GB phone or a controlled memory benchmark. They do not establish the dossier's 200 MB common-action PSS target or prove 2 GB suitability.

## Important interpretation

The checked-in OSM extract is not a field survey. Route and isolation tests establish behavior over the supplied graph, not real-world passability, response times, forecast skill, lives saved, or operational effectiveness. The pack's signing keys are synthetic demonstration keys. Do not use the prototype for live rescue operations.
