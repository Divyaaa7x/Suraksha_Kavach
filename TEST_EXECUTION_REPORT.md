# Test Execution Report

Environment: Windows 11 host, JDK 21, Android SDK 34, Python 3.14.7; Pixel 8a was connected intermittently over ADB. Date: 28 November 2026.

## Results for this change

| Check | Result |
|---|---|
| Python unit/integration suite (`python -m pytest -q`) | **95 passed in 53.66 s**. |
| Python focused graph suite after directed fix | **15 passed** (specialist run). |
| Android demo unit tests (`testDemoDebugUnitTest`) | **40 passed**. |
| Android production-variant unit tests (`testProductionDebugUnitTest`) | **38 passed, 3 skipped** (41 discovered). |
| Android `assembleDemoDebug` | **Successful**. |
| Android `assembleProductionDebug` | **Successful**; this is an unsigned debug variant, not a production release. |
| Android `assembleProductionRelease` | **Blocked as designed**: missing release keystore and production signing/enrollment credentials; build failed closed before creating a release APK. |
| Dashboard browser E2E (`tests/e2e/dashboard_browser.py`) | **3 passed**; these cover route, token and statewide search flows, not the new operations UI. |
| Operations API end-to-end (`tests/test_ops_api.py`) | **3 tests added/run with the full Python suite**; includes archived warning → real pilot exposure → mission/ack → signed inspection → supervisor verification → route/status update. |
| Dashboard inline JavaScript syntax | **Passed** with `node --check` after the CAP import/exposure ranking UI edit. |
| Signed pilot pack | Regenerated with the current directed algorithm; signature verified and embedded criticality compared with recomputation. |
| Hub vs Android pack | Byte-identical files verified. |
| `git diff --check` | Passed (Git reports expected line-ending normalization warnings for the edited source files). |

## Pixel 8a smoke test (28 November 2026)

- Installed the rebuilt `demoDebug` APK and launched `org.kavach.MainActivity`; the Atlas India map rendered, and no fatal Android runtime exception was found in the captured log window.
- Opened Field Tasks and confirmed the synthetic responder warning is visible. With a local demo hub reached through ADB port reverse, the phone pulled an assigned mission for a real OSM pilot crossing.
- Recorded **DELIVERED**, **READ**, and **ACKNOWLEDGED** from the app. The hub persisted three separate responder receipt events and the mission reached `ACKNOWLEDGED`; a hub sync was not treated as human acknowledgement.
- Tapped **Start inspection**; the action was queued locally and remained explicitly marked as waiting for hub sync. Then stopped the local hub and force-stopped/relaunched the app. The assigned mission, saved receipts and pending action remained visible in Room. This verifies a limited offline restart/cache path on this device; the `IN_PROGRESS` transition was not sent to the hub during this smoke test.
- The Pixel exposed an acknowledgement-label wrapping issue; the controls were changed to a two-button row plus a full-width acknowledgement button. The rebuilt APK was reinstalled, and the corrected labels rendered without wrapping on the device.

This was a synthetic prototype smoke test, not a real emergency response. No GPS location was read or inspection submitted, no supervisor decision was made from the phone, and no second-device Nearby transfer, prolonged offline interval, 2 GB device, or production build was tested.

## Not run / not established

No 2 GB device, second-phone Nearby transfer, offline fresh install, warning-provider live access, production signing or deployment was tested. Dashboard browser E2E exercises existing route/token flows; the warning/mission workflow has API integration coverage but no browser automation yet. Historical test-report data is not a substitute for those checks.
