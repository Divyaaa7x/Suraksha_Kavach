# Test Execution Report

Environment: Windows 11 host, JDK 21, Android SDK 34, Python 3.14.7; Pixel 8a was connected intermittently over ADB. Date: 28 November 2026.

## Results for this change

| Check | Result |
|---|---|
| Python unit/integration suite (`python -m pytest -q`) | **78 passed**. |
| Python focused graph suite after directed fix | **15 passed** (specialist run). |
| Android demo unit tests | **37 passed**. |
| Android production-variant unit tests | **35 passed, 3 demo-only skipped** (38 discovered). |
| Android `assembleDemoDebug` | **Successful**. |
| Android `assembleProductionDebug` | **Successful**; this is an unsigned debug variant, not a production release. |
| Android `assembleProductionRelease` | **Blocked as designed**: missing release keystore and production signing/enrollment credentials; build failed closed before creating a release APK. |
| Dashboard browser E2E (`tests/e2e/dashboard_browser.py`) | **3 passed**. |
| Dashboard inline JavaScript syntax | **2 scripts parsed successfully** with `node --check`. |
| Signed pilot pack | Regenerated with the current directed algorithm; signature verified and embedded criticality compared with recomputation. |
| Hub vs Android pack | Byte-identical files verified. |
| `git diff --check` | Passed (Git reports expected line-ending normalization warnings for the edited source files). |

## Not run / not established

No current 0.2.0 APK was installed or visually exercised in this pass. No 2 GB device, second-phone Nearby transfer, LAN sync on hardware, offline fresh install, warning-provider live access, production signing or deployment was tested. Dashboard browser E2E exercises the existing prototype route path, not the absent V5 workflow. Historical test-report data is not a substitute for those checks.
