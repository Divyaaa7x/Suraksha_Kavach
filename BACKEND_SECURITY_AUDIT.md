# Backend, Data, and Security Audit

**Scope:** FastAPI hub, SQLite event persistence, signed event validation, dashboard/API boundary, official data ingestion and operational workflow. Reviewed source and tests on 28 November 2026; this is a code review, not a deployed penetration test.

## What is present

- `server/kavach/api.py` serves the local dashboard and `/v1/*` endpoints for health, assets, status, routes, isolation, inspection ranking, events, approvals, and batch sync.
- Optional `KAVACH_HUB_TOKEN` middleware compares bearer token values in constant time and rejects configured tokens shorter than 32 UTF-8 bytes. `/v1/health`, `/`, and `/static/*` remain public. API request bodies are bounded to 512 KiB before JSON parsing.
- `server/kavach/service.py` stores events in SQLite with `event_id` primary key, validates event schema, timestamps, coordinates, pack version, trust role, signature and asset membership; duplicate event IDs with identical content are idempotent and conflicting reuse is rejected.
- `server/kavach/signing.py` uses canonical JSON plus Ed25519 verification, expiry/scope/role checks. Pack loading verifies signed/hash-pinned inputs.
- Dashboard JavaScript uses `sessionStorage` for the tab’s bearer token and sends it to API requests. Browser E2E includes token entry; hub tests cover unauthorized access, tampering, duplicates/replay IDs, wrong roles, malformed values, body limits, status resolution, routing after approval and idempotent batch sync.
- `server/kavach/statewide_routes.py` conditionally uses a signed route database. The checked-in/source release has no statewide route archive and standard mode falls back to pilot graph.

## Critical / high-priority gaps

1. **No operational mission, squad, warning, task, delivery, acknowledgement or inspection domain (Critical for V5 workflow).** Current persistent schema is a single `events` table (`service.py:21-30`). No user/role sessions or relational operational tables/migrations exist. The API has no mission/task/message/ack APIs and no persistent workflow state beyond crossing observation/approval events. Thus delivery, human acknowledgement, assignment, expiry, and complete rescue audit sequence cannot be executed end-to-end.
2. **No official warning adapters or warning lifecycle (Critical for stated objective).** `api.py` has no IMD, NDMA/SACHET, CWC or Telangana source adapters, provenance/revision/cancellation/expiry model, or warning cache. Scenario risk inputs are user-supplied comma-separated IDs and are synthetic overlays (`api.py:220-225`, `service.py:190-197`); they are not official flood warnings or geographic exposure intersections.
3. **Default API is unauthenticated unless explicitly configured (High).** `KAVACH_HUB_TOKEN` defaults empty (`api.py:136-162`). README documents this, but startup does not fail closed. `/` deliberately remains public even with auth and discloses dashboard shell; it does not itself expose event data without API auth. Bearer auth is shared-secret access only, with no individual identity, role-scoped API principal, session lifecycle or revocation.
4. **HTTP deployment exposes bearer tokens and location data (High).** The app has no TLS termination, secure cookie/session, or deployment guard. The UI warns against remote HTTP use, but a bearer sent over plain HTTP can be intercepted. `/v1/events` returns complete event payloads including responder coordinates to any holder of the shared token. No location-specific access policy or redaction exists.
5. **Demo supervisor approval is cryptographically synthetic (High).** `/v1/demo/approvals` signs with a publicly derivable demo key (`api.py:169-188`, `pack.py`), gated by `KAVACH_DEMO_MODE`, and returns an event that the regular hub accepts. This is explicitly labelled demo-only in the UI/docs but cannot be considered real authorization. Production release path is fail-closed elsewhere; no real identity enrollment is present.
6. **No rate limiting or per-principal quotas (High for exposed hub).** Request bytes are limited, but repeated valid/invalid requests, route searches and event submissions are not rate-limited. There is no login/abuse throttle because auth is a shared static token.
7. **SQLite is a prototype event log, not a migration-managed production store (High).** `CREATE TABLE IF NOT EXISTS` is the only schema evolution (`service.py:21-30, 46-52`); no migration framework, backup/restore procedure, retention/archival, WAL/busy timeout or operational health checks for disk/storage. The in-process mutex protects shared connection use within one process, not multi-process writers or externally edited DB. Append-only is an application convention; local DB owners can alter/delete rows, and there is no hash chain or independent immutable audit sink.

## Medium-priority gaps

- API shapes are inconsistent: events/approvals accept untyped `dict` and report domain rejection as HTTP 200; statewide unavailable errors use 404 while regular route unknown nodes return domain `error` in a success response. This is workable for the demo but weakens client error handling and OpenAPI contracts (`api.py:230-256`, `service.py:58-108`).
- `sync_batch` allows 100 events, but no transaction wraps the batch; partial acceptance is possible if an unexpected storage failure occurs. No durable outbound queue/message delivery state exists on the server.
- `/v1/events` has no pagination/filter-by-cursor and loads all rows; inventory also reads all IDs. This will scale poorly with event growth (`service.py:182-187, 236-239`).
- No CORS configuration is defined. Current same-origin dashboard works; a separately hosted Cloudflare Pages UI will need a deliberate secure API origin/CORS design (and TLS), not wildcard CORS.
- No user/squad authorization for crossing changes; trust registry role is per signing key, but API authentication is not mapped to that identity. Signed approvals do enforce supervisor role via trust registry; the demo endpoint bypasses human key possession using synthetic key material.
- No PostGIS/geospatial warning-to-road/crossing intersection API. Offline map viewport query exists, but official warning polygon ingest, intersection result provenance, uncertainty and temporal checks do not.
- No database schema for delivery/read/ack state; an accepted HTTP request only proves server receipt, not recipient delivery or acknowledgement.

## Test evidence and coverage boundaries

- `tests/test_hub.py` has meaningful API tests for token gating, short-token rejection, bounded body handling, event signatures, duplicate and conflicting IDs, role enforcement, status conflicts, approvals, route changes, isolated settlements, ranking and sync merge.
- `tests/e2e/dashboard_browser.py` covers dashboard loading/token entry and statewide route path. These are prototype integration checks, not production authentication or official-feed checks.
- There are no tests for official warning adapters, mission creation/assignment, persistent acknowledgements, delivery/retry queues, migration/upgrade from prior DB, concurrent multi-process writers, rate limits, privacy/role-scoped location access, TLS deployment, or end-to-end V5 workflow; the corresponding features are absent.

## Recommended implementation order

1. Add a minimal persistent operational schema and migrations for warnings, squads/responders, missions/tasks, messages/delivery/acknowledgements, crossing reports and reviewer decisions. Keep the existing signed event stream as the immutable synchronization input.
2. Add test/archived warning adapter interfaces first (fixtures clearly labelled), then official source adapters only against verified accessible/licensed interfaces. Preserve source payload/hash, retrieved/published/validity/revision/cancellation data.
3. Implement explicit assignment -> queued/sent -> delivered -> acknowledged -> inspected -> reviewer-verified state transitions with role checks and audit events. A transport receipt must never synthesize human acknowledgement.
4. Add geography-aware warning exposure and settlement isolation/route consequence service using the existing graph, with explainable outputs and tests. Mark unverified sources/assumptions.
5. Before remote deployment: require secure transport, choose identity-bearing auth and role authorization, isolate/redact location access, add throttling, migration/backup plan, and verify Cloudflare static frontend API integration. Do not expose the local hub directly.
6. Add migration, lifecycle, authorization, warning expiry/revision, queue retry and complete workflow integration tests before claiming V5 readiness.

## Overall status

Backend/API foundation: **implemented and useful for a local prototype**. Signed observations, approvals, duplicate handling and route/status endpoints have automated coverage. V5 operational backend: **mostly missing**. Official data ingestion, mission/squad/task/message/ack lifecycle, identity-based authorization, production transport/storage and the full workflow are not implemented. No live government feed or production deployment was tested.
