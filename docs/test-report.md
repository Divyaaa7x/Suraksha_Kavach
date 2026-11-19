# Verification report

Updated 19 November 2026. This report contains observed results only; unrun hardware and platform checks stay pending.

## Automated checks

| Check | Result | Evidence |
|---|---|---|
| Python unit/integration suite | PASS | `python -m pytest -q`: 35 tests passed on Windows/Python 3.14.7. |
| Synthetic graph golden cases | PASS | `tests/test_graph.py`, including closed-edge exclusion and no-route behavior. |
| Hub event, signature, conflict, replay and payload checks | PASS | `tests/test_hub.py`. |
| OSM pack verification and provenance rules | PASS | `tests/test_real_pack.py`; crossings start unknown. |
| Android APK data pack matches signed hub pack | PASS | `test_apk_pack_is_byte_identical_and_verified`. |
| Pack rebuild | PASS | `scripts/build_demo_pack.py` and `scripts/build_osm_pack.py`; OSM pack build also synchronizes Android assets. |

## Pending checks

| Check | Status | Reason |
|---|---|---|
| Android Gradle compilation and Kotlin unit tests | PENDING | Android SDK/Studio, Gradle, and `adb` are not installed in the current environment. |
| Airplane-mode cold start, GPS freshness, process-kill durability | PENDING DEVICE | Requires a physical Android device. |
| 2 GB RAM PSS, cold-start and route latency budgets | PENDING DEVICE | No measured device results; no performance claim is made. |
| Nearby phone-to-phone pairing and transfer | PENDING DEVICES | Requires two physical Android devices with supported Google Play services and permissions. |
| LAN phone-to-hub sync | PENDING DEVICE | Python API behavior is covered, but Android networking is not device-validated. |
| Background behavior, battery and radio range | PENDING DEVICES | Requires controlled tests on target phones. |

## Important interpretation

The checked-in OSM extract is not a field survey. Route and isolation tests establish behavior over the supplied graph, not real-world passability, response times, forecast skill, lives saved, or operational effectiveness. The pack's signing keys are synthetic demonstration keys. Do not use the prototype for live rescue operations.
