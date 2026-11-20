# Verification report

Updated 20 November 2026. This report contains observed results only; unrun hardware and platform checks stay pending.

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
