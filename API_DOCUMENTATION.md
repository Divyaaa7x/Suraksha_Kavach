# Current Hub API

FastAPI serves these routes from `server/kavach/api.py`. When `KAVACH_HUB_TOKEN` is configured, send `Authorization: Bearer <token>` to protected routes. Health and the dashboard shell are public. Auth is optional shared-token protection, not production user authentication. Most errors use existing prototype response shapes; consult generated `/docs` for request models.

| Method | Path | Current behavior |
|---|---|---|
| GET | `/v1/health` | Hub/pack/auth/demo readiness summary. |
| GET | `/v1/assets` | Current routing graph nodes, edges and crossing inventory. |
| GET | `/v1/features` | Bundled map features when available. |
| GET | `/v1/india-overview` | India state/UT reference boundary collection. |
| GET | `/v1/offline-map` | Telangana local-map viewport data; bounded geographic extent and zoom. |
| GET | `/v1/status` | Resolved crossing status, optionally with comma-separated demo scenario IDs. |
| GET/POST | `/v1/events` | List events or submit one signed observation/approval event. |
| POST | `/v1/approvals` | Submit a signed approval event. |
| POST | `/v1/routes/compute` | Pilot route with vehicle/policy and optional scenario IDs. |
| GET | `/v1/routes/places` | Search optional signed statewide route database; 404 when absent. |
| POST | `/v1/statewide/routes/compute` | Compute candidate in optional statewide route database; 404 when absent. |
| GET | `/v1/isolated-settlements` | Directed settlement-to-facility accessibility under selected status policy. |
| GET | `/v1/inspection-ranking` | Structural crossing criticality baseline. |
| GET | `/v1/sync/inventory` | Known event IDs and pack version. |
| POST | `/v1/sync/batch` | Submit a batch of signed events. |
| POST | `/v1/demo/approvals` | Synthetic supervisor approval only when `KAVACH_DEMO_MODE=1`; never production authorization. |
| GET | `/v1/ops/warnings` | List imported warning records with provenance and active/cancelled state. |
| GET | `/v1/ops/warnings/fixture` | Read the bundled archived IMD replay fixture; reading does not import it. |
| GET | `/v1/ops/warnings/{warning_id}/history` | List append-only revisions for a warning. |
| POST | `/v1/ops/warnings/import` | Import a provenance-bearing GeoJSON warning record; exact retries are idempotent and conflicting overwrite is rejected. |
| POST | `/v1/ops/warnings/import-cap` | Parse and import one caller-supplied CAP 1.2 XML alert, including references and source metadata; not a live feed. |
| GET | `/v1/ops/exposure` | Intersect a warning with pilot crossing geometry at a requested replay time; returns geographic exposure, not observed flooding. |
| GET | `/v1/ops/squads` | List prototype squads. |
| GET | `/v1/ops/missions` | List missions, optionally filtered to an authorized responder's squad. |
| POST | `/v1/ops/missions` | Create an inspection mission with an optional idempotency key. |
| POST | `/v1/ops/missions/{mission_id}/transition` | Apply an allowed, role-checked state transition. |
| POST | `/v1/ops/missions/{mission_id}/receipts` | Persist a responder delivery/read/human acknowledgement receipt separately from hub sync. |
| POST | `/v1/ops/missions/{mission_id}/observations` | Submit a structured task-linked signed inspection observation. |
| GET | `/v1/ops/observations` | List inspection observations for supervisor review. |
| POST | `/v1/ops/observations/{observation_id}/verify` | Verify/reject a submitted observation and record approval through the existing signed event path. |

Operational writes are disabled unless `KAVACH_DEMO_MODE=1`. The demo uses synthetic identities. Geographic exposure is a screening result; only a verified observation changes crossing status in the pilot graph. This remains a local research prototype, not an emergency-dispatch backend or production authorization system.
