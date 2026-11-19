# Verification report

Updated 19 November 2026. This report contains observed results only; unrun hardware and platform checks stay pending.

## Automated checks

| Check | Result | Evidence |
|---|---|---|
| Python unit/integration suite | PASS | `python -m pytest -q`: 35 tests passed on Windows/Python 3.14.7. |
| Android build | PASS | Gradle 8.11.1, JDK 21, Android SDK platform 34: `testDebugUnitTest assembleDebug` succeeded. |
| Android Kotlin unit tests | PASS | 19 tests across graph, JSON/crypto, and offline pack verification; zero failures. |
| Install and launch on Pixel 8a | PASS | Debug APK installed through ADB; `org.kavach/.MainActivity` remained resumed on Android 17. |
| Synthetic graph golden cases | PASS | `tests/test_graph.py`, including closed-edge exclusion and no-route behavior. |
| Hub event, signature, conflict, replay and payload checks | PASS | `tests/test_hub.py`. |
| OSM pack verification and provenance rules | PASS | `tests/test_real_pack.py`; crossings start unknown. |
| Android APK data pack matches signed hub pack | PASS | `test_apk_pack_is_byte_identical_and_verified`. |
| Pack rebuild | PASS | `scripts/build_demo_pack.py` and `scripts/build_osm_pack.py`; OSM pack build also synchronizes Android assets. |

## Pending checks

| Check | Status | Reason |
|---|---|---|
| Airplane-mode cold start, GPS freshness, process-kill durability | PENDING DEVICE | App was launched with the device connected, but these workflow scenarios were not exercised. |
| 2 GB RAM PSS, cold-start and route latency budgets | PENDING DEVICE | Pixel 8a is not a 2 GB device. No 2 GB performance claim is made. |
| Nearby phone-to-phone pairing and transfer | PENDING DEVICES | Requires two physical Android devices with supported Google Play services and permissions. |
| LAN phone-to-hub sync | PENDING DEVICE | Python API behavior is covered, but Android networking is not device-validated. |
| Background behavior, battery and radio range | PENDING DEVICES | Requires controlled tests on target phones. |

## Pixel 8a observation

The connected Pixel 8a (Android 17, device codename `akita`) installed and launched the debug app. Five `dumpsys meminfo org.kavach` samples after settling were 227,101, 222,213, 222,161, 222,161 and 222,161 KiB PSS (median 222,161 KiB, about 217 MiB). This exceeds the dossier's preliminary 200 MB common-action PSS target on this phone. A separate earlier reading reached 306,667 KiB while the UI was active. This is a real-device measurement, but it does not substitute for testing the 2 GB minimum device; investigate and reduce memory before claiming the stated budget.

## Important interpretation

The checked-in OSM extract is not a field survey. Route and isolation tests establish behavior over the supplied graph, not real-world passability, response times, forecast skill, lives saved, or operational effectiveness. The pack's signing keys are synthetic demonstration keys. Do not use the prototype for live rescue operations.
