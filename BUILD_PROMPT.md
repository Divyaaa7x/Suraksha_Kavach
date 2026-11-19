# SURAKSHA KAVACH — Master Build Prompt (gstack + ponytail)

Source of truth: `SURAKSHA_KAVACH_Complete_Offline_2GB_Technical_Research_Dossier.pdf` (34 pages, v2.0, 19 Nov 2026).
Copy that PDF into `docs/dossier.pdf` before you start so every session can read it.

How to use this file:
1. Paste **Part A (Master Context)** once at the start of every new Claude Code session. It is the standing brief.
2. Then paste the **Phase prompts (Part B)** one at a time, in order. Don't start a phase until the previous phase's exit gate passes.
3. Each phase says which **gstack** skill drives it and where **ponytail** applies.

---

## PART A — MASTER CONTEXT (paste at the start of every session)

```
You are building SURAKSHA KAVACH for CipherCop 2026 (AI Disaster Response track).
The spec is docs/dossier.pdf. Read the relevant sections before you design or code anything.
If the dossier and this prompt disagree, the dossier wins. Don't invent requirements it lacks.

TOOLING RULES (always on):
- Use the gstack skills as the primary workflow: /office-hours, /spec, /plan-eng-review,
  /autoplan, /design-consultation, /review, /qa, /verify, /health, /document-generate, /ship.
- Run every coding task in /ponytail (full level) mode: the smallest change that fully solves
  the task. No speculative abstractions, no unused layers, no dependency without a reason.
  Mark deliberate shortcuts with a `shortcut:` comment so /ponytail-debt can list them.
- After each feature: /ponytail-review, then gstack /review, then /verify until green.
- Use /browse for web checks (gstack rule). /cso is unavailable on this Windows machine
  (no MSVC toolchain), so security review goes through /review + /ponytail-audit + the
  threat-model tests in Phase 8.

PRODUCT (dossier Exec Decision, §1-7, §26):
Offline-first decision support for flood-related emergency access in rural Telangana
(Medak/Kamareddy Aug 2025 pilot). It identifies road crossings that need verification,
estimates which settlements lose access under a scenario, compares feasible routes and keeps
trusted field observations through network outages.
Users: field inspector, responder, operations officer/supervisor, GIS/trust admin, auditor.
Two independently usable clients (ADR-02):
  1. Android field app (2 GB RAM phone is the hard minimum).
  2. Local command hub: website/dashboard + API on a laptop over LAN, with no internet.

NON-NEGOTIABLE SAFETY RULES (§12, §14, §24, §36, §52):
- Decision support only. No autonomous road certification, evacuation orders or dispatch.
  A human approves every access, rescue and closure decision.
- NEVER label a road "safe". No structural-safety claims.
- Reports are claims, not facts. An unverified report can't become VERIFIED through model
  inference, urgency or a newer timestamp alone.
- Conflicting reports produce DISPUTED plus a verification task, never auto-resolution.
- UNKNOWN/DISPUTED edges: conservative exclusion, or an explicit "unverified route" warning.
- Event sourcing: signed events are immutable and appended only. Current status is a
  derived view.
- Stale data is always shown with its age ("last known forecast", valid time).
- Every simulated, synthetic or demo record is labelled SYNTHETIC DEMO. No fake accuracy
  numbers, lives saved, live government integration or official endorsement.
- No mandatory LLM (ADR-01). The core works with zero ML models loaded.
- No bulk download from tile.openstreetmap.org. Build tiles from a Geofabrik extract and
  keep OSM/ODbL attribution.
- Cloud is optional. It must never block a core offline workflow (ADR-07).

STACK (§9, §10):
Android: Kotlin, Jetpack Compose, MVVM + unidirectional state, single activity,
  MapLibre Native (offline vector pack), Room/SQLite, Kotlin A*/Dijkstra over a prebuilt
  SQLite graph, WorkManager (deferrable sync) + foreground service (active peer session),
  Google Nearby Connections (P2P, behind a transport adapter interface), Android Keystore,
  Ed25519 signatures + AES-GCM, optional LiteRT for tiny models.
Hub/website: React + TypeScript + Vite + MapLibre GL JS (served locally),
  Python FastAPI + Pydantic, PostgreSQL/PostGIS, NetworkX for pilot routing,
  Docker Compose, local tiles. OSRM/Valhalla only later.
Pack tooling: Python scripts that build and sign the regional pack.

REPO LAYOUT (§20):
suraksha-kavach/
  android/    app, map, incidents, routing, sync, crypto, ai
  dashboard/  React/TS: incident-map, approvals, evidence-view
  server/     FastAPI, PostGIS models, sync, route/graph API
  edge/       docker-compose, local tiles, (later) OSRM/Valhalla
  data/       pack builder, geojson, OSM graph, metadata, synthetic fixtures
  ml/         (later) labelled data, model cards, eval scripts
  tests/      offline-e2e, graph-golden, sync-fuzz, security
  docs/       dossier.pdf, threat-model, source-register, demo-script, ADRs
```

---

## PART B — PHASE PROMPTS (paste one at a time)

### Phase 0 — Repo bootstrap and framing  ·  gstack `/office-hours` → `/spec`

```
/office-hours
Context: SURAKSHA KAVACH (see docs/dossier.pdf). Challenge the scope against the dossier's
P0 list (§6 F01-F08, §29 FR-01..FR-10) and against the deadlines: screening 20 and 25 Nov
2026, finals 30 Nov-1 Dec 2026. Write the design doc the other gstack skills read. Name the
narrowest reliable slice for the finals (§48, §54 Stage 1-3).

Then: git init the repo, create the §20 layout with READMEs only, add .gitignore,
LICENSE, docs/source-register.md (dossier §55 R01-R42), and docs/adr/ with ADR-01..ADR-10
copied from §26 as one file each. Commit.
```
Then:
```
/spec
Turn the design doc into an executable spec. For each requirement FR-01..FR-20 and
F01..F16, record priority (P0/P1/P2/P3), acceptance evidence (dossier wording), owning
module, and test ID (T-01..T-16 from §47). Mark the P2/P3 items "not promised for finals".
Save to docs/spec.md.
```
**Exit gate:** `docs/spec.md` exists and every P0 item has a test ID.

---

### Phase 1 — Architecture lock  ·  gstack `/plan-eng-review` (or `/autoplan`)

```
/plan-eng-review
Review docs/spec.md and lock the architecture. Cover:
1. Data model (dossier §11): Asset, RoadEdge, Crossing, Settlement, Facility, Incident,
   Observation, Evidence, Verification, RoutePlan, Device, SyncEnvelope, ModelVersion,
   HazardSnapshot. One shared schema definition generates the Room entities, Pydantic
   models and TS types (or document why not).
2. Event envelope (§12, §39): event_id (ULID), incident_id, asset_id, observer/device id,
   observation_type, observed_at/device_time/received_at, position+accuracy_m,
   evidence SHA-256 list, source_class FIELD/OFFICIAL/MODEL/SIMULATED, pack_version,
   origin_key_id, Ed25519 signature over CANONICAL CBOR (not whitespace-normalised JSON),
   parent_event_ids. Target <= 1 KB without photos.
3. Crossing state machine (§36): UNKNOWN, REPORTED_OPEN, REPORTED_CLOSED, VERIFIED_OPEN,
   VERIFIED_CLOSED, RESTRICTED, DISPUTED, STALE, PREDICTED_RISK, with the route rule for each.
   resolved_status() is a pure function of (events, approvals, policy, time).
4. Routing (§14, §35): cost = travel_time + operational_penalty; hard exclusions first;
   UNKNOWN policy; output = route + excluded edges with reasons + graph_version + evidence
   age + uncertain edges, or NO_VERIFIED_ROUTE.
5. Isolation and criticality: connected components, reachability to facilities, bridges
   and articulation points, crossing dependency score, inspection-priority ranking (FR-08).
6. Offline pack (§34): manifest.cbor, signature.ed25519, map/, graph/roads.sqlite,
   registry/places.sqlite, policy/safety.json, trust/roles.cbor, LICENSES.txt. Validate
   before activation, activate atomically, downgrade protection.
7. KAVACH Relay protocol (§38): HELLO/AUTH/INVENTORY/REQUEST/EVENT/RECEIPT/APPROVAL/
   MEDIA_CHUNK/ACK/RELAY, the delivery states LOCAL_COMMITTED..EXPIRED, TTL/hop count,
   seen-cache, quotas, priority queue P0-P3 (§41).
8. Hub API (§21, §39): /v1/assets, /v1/routes/compute, /v1/events, /v1/approvals,
   /v1/sync/inventory, /v1/sync/batch, /v1/isolated-settlements, /v1/health/offline.
9. RBAC matrix (§4, §37).
10. 2 GB memory policy (§31): capability modes, trim callbacks, text-only fallback.
Produce diagrams, edge cases and a test plan. Write the decisions to docs/architecture.md.
Keep the graph algorithm in ONE place per platform. Use golden fixtures shared by the Kotlin
and Python tests so both engines must agree.
```
**Exit gate:** `docs/architecture.md` is approved and has no open questions on P0.

---

### Phase 2 — Data pack + graph core (MVP A)  ·  `/ponytail` + `/verify`

```
/ponytail
Build data/ pack tooling and the graph core, following docs/architecture.md.
- data/build_pack.py: from a Geofabrik Southern India extract, clip a small Medak pilot
  bbox and emit the graph sqlite (nodes, directed edges, crossings) plus places sqlite
  (settlements, facilities, aliases). Add the manifest, sign it with an Ed25519 pack key,
  and write LICENSES.txt with OSM attribution.
- data/fixtures/: a SYNTHETIC golden graph that matches dossier Figure 3 (C1 verified
  closed, C2 unknown, C3 to evaluate), plus expected outputs.
- server/graph/: resolved_status, eligible-edge filter, A*, isolation (components,
  bridges, articulation points), crossing criticality, inspection ranking.
- Golden tests: T-03 (closed C1 excluded with a reason), T-04 (NO VERIFIED ROUTE),
  T-16 (100% golden agreement), plus a 100-route latency benchmark (p50/p95).
Every synthetic record has source_class=SIMULATED.
```
Then `/ponytail-review` → `/review` → `/verify`.
**Exit gate:** golden tests are green and a tampered pack is rejected (T-06).

---

### Phase 3 — Android field app core (MVP B)  ·  `/ponytail` + `/verify`

```
/ponytail
Build android/ per docs/architecture.md. Kotlin + Compose, minSdk chosen for 2 GB
Android Go-class phones.
- Pack import: verify the signature and every file hash, activate atomically, keep the previous
  pack, show the pack age (FR-01, T-06).
- Offline MapLibre map with crossings by status, settlements, hospitals, graph version.
  No network call after install (FR-02, T-01).
- Offline search of villages/facilities with aliases (FR-03).
- New-observation form: type, time, GPS + accuracy + fix age (never show a stale fix as live,
  T-02), template text, photo (downscaled, hashed, encrypted at rest), status
  OPEN/RESTRICTED/CLOSED/UNKNOWN saved as a CLAIM (FR-04, FR-05). Autosave the draft on every
  edit. A committed event survives process kill (T-05).
- Port the graph core to Kotlin. Route comparison screen: shortest vs constrained, excluded
  crossings + reasons (FR-06). Isolation analysis screen (FR-07). Inspection candidates
  with transparent score and reason (FR-08). Conflict detection gives DISPUTED + task (FR-09,
  T-11). The Kotlin engine must pass the SAME golden fixtures as Phase 2.
- Readiness home: local-only badge, pack version, radios, battery, last sync, role.
- Memory policy (§31): isLowRamDevice/memoryClass mode selection, onTrimMemory clears
  tile cache + stops optional AI, text-only incident mode at critical memory (T-13).
- Badges (§45): HISTORICAL, SYNTHETIC DEMO, PREDICTED, REPORTED, OFFICER VERIFIED, STALE,
  NO VERIFIED ROUTE. Don't use colour alone. English + Telugu labels, large touch targets.
```
Before UI work: `/design-consultation` (field-use design system: outdoor contrast, large controls, low-light mode).
After: `/ponytail-review` → `/review` → `/verify` → `/design-review`.
**Exit gate:** airplane-mode cold start works, the route is computed, and the observation survives a kill.

---

### Phase 4 — Crypto, identity, trust pack (MVP C)  ·  `/ponytail` + `/review`

```
/ponytail
Implement dossier §43 provisioning on Android and the hub:
- Device key in Android Keystore where supported. Ed25519 event signing (record whether the
  key is hardware-backed per device and never assume it).
- Enrolment: the station signs device_id, public_key, role, expiry, scope into trust/roles.cbor.
- Canonical CBOR serialisation shared by Kotlin and Python. Add cross-language test vectors.
- Reject an invalid signature (FR-10), a replay (seen-cache + TTL, T-10), an oversize payload
  with bounded memory (T-14) and an expired role. Show "revocation status unknown" when the
  trust pack is overdue.
- Approvals are separate authority-signed events that reference the observation and never
  mutate it (RBAC per §37).
- No hardcoded shared secrets across the fleet.
```
Then `/ponytail-review` → `/review` (security focus: §17, §42 threat table) → `/verify`.
**Exit gate:** tampered, replayed and unsigned events are all rejected in tests.

---

### Phase 5 — Command hub website + API (MVP E)  ·  `/ponytail` + `/qa`

```
/ponytail
Build server/ (FastAPI + PostGIS) and dashboard/ (React/TS/Vite/MapLibre GL JS), served
entirely from the local machine via edge/docker-compose.yml. Nothing may call the public
internet: bundle the fonts, glyphs, sprites and tiles.
- Endpoints from §21/§39 with Pydantic validation, size limits, idempotent writes.
- Dashboard screens: operational map (crossing status, settlements, facilities),
  incident list, crossing detail (sources, verified age, reports, route impact),
  evidence audit (immutable history, approvals, conflicts, origin/receipt/observed times
  separated), approvals queue (approve/reject with reason + expiry), route comparison,
  isolation analysis with scenario controls, team assignment (human approves), health /
  offline status page.
- Role login for simulated users (clearly labelled demo identities).
- Scenario selector: manual rainfall scenario → PREDICTED_RISK tier per crossing
  (§16 P0 prediction). Road links go to "inspection needed", never auto-closed.
```
Then `/design-review` on the dashboard, then `/qa` against `http://localhost` with the network unplugged, then `/review` → `/verify`.
**Exit gate:** the hub works with the internet disconnected and the approval → recompute → audit trail appears.

---

### Phase 6 — Offline sync: phone↔phone and phone↔hub (MVP D)  ·  `/ponytail` + `/verify`

```
/ponytail
Implement KAVACH Relay (dossier §13, §38, §41) behind one Transport interface:
- NearbyConnectionsTransport (primary; requires Play services), LanHttpTransport (hub
  over hotspot/router), QrTransport (single compact event or key bundle). Wi-Fi Direct is
  only a capability-detected stub unless it's tested.
- Handshake: protocol version, trust-pack version, incident digest, pairing fingerprint +
  out-of-band code. A wrong code means no exchange (T-09).
- Exact inventory diff by event IDs (Bloom filter only as an optimisation, with periodic exact
  reconciliation). Priority queue P0..P3: small events before photos (FR-14).
- Persist the queue across restarts (FR-12). Resumable media chunks. Per-event ACK only
  after a validated durable write. Bounded exponential backoff. Hop count/TTL/seen-cache,
  per-peer quotas and rate limits.
- Carry-forward relay A→B→C (T-08), or an explicit pending state.
- Hub sync: /v1/sync/inventory + /v1/sync/batch, resumable after a LAN drop with no
  duplicate approvals (T-15).
- Battery: duty-cycled discovery, explicit "Sync now" button, foreground service only while
  a session is active.
Add a deterministic relay simulator in tests/ (interrupted contacts, FIFO vs priority).
```
Then `/ponytail-review` → `/review` → `/verify`.
**Exit gate:** two phones exchange a signed event with mobile data off (T-07), the hub dashboard updates, and there are no duplicates.

---

### Phase 7 — Optional AI layer (P1/P2, only if time allows)  ·  `/ponytail lite`

```
/ponytail
Add only the deterministic "AI" from §15/§32, which needs no ML weights:
- Incident text parser: keywords + finite-state templates (EN + transliterated Telugu),
  gazetteer place extraction validated against the pack, never inventing places.
- Duplicate grouping: spatial/time window + trigram similarity, linking reports and keeping
  originals (F10).
- Report summary from structured facts only (template), never generated free text.
Any learned model (fastText / MobileNetV3 INT8) is P2: load on demand, release immediately,
fall back to the manual form, and ship a model card. No accuracy claims without a labelled
local test set (§33).
```
**Exit gate:** the app is fully functional with all models removed.

---

### Phase 8 — Hardening, tests, metrics  ·  `/qa`, `/health`, `/ponytail-audit`

```
/ponytail-audit
Audit the whole repo against the dossier: safety rules in Part A, threat table §42,
privacy §44 (no continuous tracking, encrypted media, role-restricted exports).
```
```
/health
```
```
/qa
Run the full §47 matrix T-01..T-16 and §18 metrics on the code. Write results to
docs/test-report.md: route latency p50/p95, PSS during map+incident+sync (adb dumpsys
meminfo, target <= 200 MB), cold start (target < 8 s), signed event size, peer delivery %,
duplicates, battery. Report PASS/FAIL with real numbers only. Mark the tests that need a
physical 2 GB phone as "pending device" and never fabricate them.
```
Then `/ponytail-debt` to list remaining shortcuts in `docs/debt.md`.
**Exit gate:** every item in the §52 go/no-go checklist is ticked or documented as a limitation.

---

### Phase 9 — Demo, docs, release  ·  `/document-generate`, `/make-pdf`, `/ship`

```
/document-generate
Write docs/demo-script.md (the 10-step §19 script, fully offline), docs/threat-model.md,
docs/device-matrix.md, docs/model-card.md (if any model), README with reproducible build,
pinned versions, and third-party licenses (§49).
```
```
/make-pdf docs/demo-script.md
```
```
/ship
```
Also record a backup demo video. Rehearse the §19 script with the internet off on the laptop and both phones.

---

## Daily loop (inside any phase)

```
/context-restore          # start of day
/ponytail <task>          # build the smallest correct thing
/ponytail-review          # is every line needed, is risky logic tested
/review                   # gstack pre-land review
/verify                   # tests green
/context-save             # end of day
```

## Reality check (from dossier §48)

Full secure multi-hop on 2 GB phones is "a significant multi-month engineering project". For the 30 Nov finals, aim for Phases 0-6 (P0 + core P1) working reliably offline, with everything simulated clearly labelled. "Production-ready" for a real deployment additionally needs authorised asset data, agency approval, field validation and tests on real 2 GB devices (§37, §53). Don't claim those in the pitch.
