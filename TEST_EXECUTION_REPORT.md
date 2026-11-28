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

## Not run / not established

The feature-branch APK was not installed or visually exercised in this pass because ADB reported no attached device. No 2 GB device, second-phone Nearby transfer, LAN sync on hardware, offline fresh install, warning-provider live access, production signing or deployment was tested. Dashboard browser E2E exercises existing route/token flows; the warning/mission workflow has API integration coverage but no browser automation yet. Historical test-report data is not a substitute for those checks.
