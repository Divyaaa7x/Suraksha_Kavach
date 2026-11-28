# Current Data Model

This document records the model that exists in the repository today. It does not imply that proposed V5 entities already exist.

## Geographic and graph data

- **Node:** `node_id`, kind (`junction`, `settlement`, `facility`), latitude/longitude, optional name and OSM population estimate.
- **Edge:** `edge_id`, directed endpoints `u`/`v`, length, estimated road-class speed, optional `crossing_id`, one-way flag and vehicle access set.
- **Crossing:** stable graph crossing ID, mapped type and restricted-vehicle set. OSM tags alone do not convey verified passability.
- **Route database:** optional indexed route nodes, segments, crossing way IDs, snapped places and source metadata, validated against signed source hashes.
- **Offline map database:** spatially indexed OSM roads, places, water features and mapped boundaries, separate from the pilot routing graph.

## Trust and field events

Signed Ed25519 observation and approval events carry event ID, event kind, crossing/asset ID, event time, source class, pack version and signer identity. The trust registry associates public keys with roles, expiry and optional asset scope. The SQLite hub stores immutable event payloads keyed by event ID; duplicate identical submissions are idempotent, conflicting event-ID reuse is rejected. Crossing status is derived from reports and approvals; unresolved reports remain unverified or disputed.

## What needs a future schema/migration

The current database has no relational records for official warnings/revisions, users/squads, missions, inspection assignments/reports, message delivery/read/acknowledgements or reviewer audit decisions. These require explicit migrations and foreign-key/index policy before implementing the full V5 workflow. No migration system currently exists.
