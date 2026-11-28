# Current Architecture

## Android field application

One Kotlin/Jetpack Compose module uses `AtlasHome` as the map-first entry. `KavachViewModel` joins the verified pack, crossing/event state and route state to UI surfaces. `AtlasMap` renders an online MapLibre vector style when validated internet is available; `TelanganaOfflineMap` draws from an extracted, signed SQLite display map when offline. The bundled pilot `RoadGraph` supports status-aware routing and analysis; an optional separately signed SQLite database enables statewide route queries when supplied. Local signed events can be synchronized by LAN hub or Nearby Connections.

## Hub and coordinator dashboard

FastAPI serves both the HTML dashboard at `/` and `/v1/*` APIs. `Hub` owns the signed pack, route/status logic and SQLite append-only event log. The browser dashboard reads these endpoints and also draws the offline Telangana and India reference maps using canvas. An optional shared bearer token gates non-health API/dashboard access; it is not individual user authentication.

## Data path today

OSM-derived graph and status events → signed pack/event validation → SQLite event store → resolved crossing status → route/isolation/inspection APIs → Android/dashboard rendering. Signed pack manifests hash their files and are mirrored byte-for-byte into Android assets. The optional statewide route database must match the map's pinned source hash.

## Not present in the architecture

There are no official warning adapters or warning records; no warning polygon intersection or exposure engine; no mission, squad, assignment, delivery, human acknowledgement or task-linked inspection records; no production user/session/role service; no database migration chain; and no hosted durable production API. These are not hidden behind UI controls or currently delivered modules.
