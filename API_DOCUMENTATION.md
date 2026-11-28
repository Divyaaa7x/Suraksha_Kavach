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

There are no warning import, mission, squad, task, delivery, human acknowledgement or task-linked verification endpoints yet. Do not use this API as an emergency-dispatch backend.
