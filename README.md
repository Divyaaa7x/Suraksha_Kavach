# SURAKSHA KAVACH — prototype (CipherCop 2026)

Offline-first decision support for flood-related road-crossing verification and settlement isolation.
**Decision support only.** A human approves every access decision. Routes are not safety certificates.
**All data in this repository is SYNTHETIC DEMO data** unless marked OSM-derived.

Spec: `docs/` and `BUILD_PROMPT.md`. Source dossier: the 34-page technical dossier (v2.0).

## What works now
- `server/kavach/graph.py` — crossing status policy, constrained A* routing, isolation, criticality, inspection ranking.
- `server/kavach/signing.py`, `service.py` — Ed25519-signed immutable events, role checks, replay/duplicate handling, conflict → DISPUTED, approvals outrank reports.
- `server/kavach/pack.py` — signed offline pack: hash and signature checks, downgrade protection.
- `server/kavach/api.py` — local FastAPI hub (`/v1/*`) and offline dashboard at `/`.
- `server/static/index.html` — dashboard with no external resources (works with the internet off).
- `tests/` — 29 tests covering golden routes (T-03, T-04), replay/tamper (T-06, T-10), conflicts (T-11), size limits (T-14), sync merge.

## Not built yet
- Android field app (`android/`): needs the Android SDK, Kotlin, MapLibre, Nearby Connections. Not started.
- Phone-to-phone sync, Telugu UI, Keystore-backed keys, PostGIS, Docker. See `docs/`.

## Run
```powershell
python -m pip install -r server/requirements.txt
python scripts/build_demo_pack.py
python -m pytest -q
python -m uvicorn kavach.api:create_app --factory --app-dir server --port 8000
```
Then open http://127.0.0.1:8000/ .

Signed demo client (stands in for a field phone and a supervisor):
```powershell
python scripts/demo_client.py observe C1 OPEN
python scripts/demo_client.py approve C1 VERIFIED_OPEN "synthetic demo verification"
```

## Known shortcuts (prototype)
- Events are signed as canonical JSON, not CBOR (dossier prefers CBOR).
- Pack-signing and device keys are fixed SYNTHETIC demo seeds. Replace with provisioned keys before any real use.
- Routing graph is a 7-edge synthetic fixture, not a real Telangana network.
- No authentication on the HTTP API (local demo only).
