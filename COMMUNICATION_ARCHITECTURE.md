# Current Communication Architecture

## Implemented transports

- **Private LAN / Wi-Fi:** Android can submit its signed event queue to the local FastAPI hub over reachable LAN addresses. The hub validates events and merges duplicate IDs idempotently. Optional hub bearer auth can be configured.
- **Nearby Connections:** Android uses Google Nearby Connections for nearby peer discovery and signed-event exchange with pairing-code confirmation. Payload sizes/items are bounded; acknowledgements concern event transfer IDs.
- **Offline queue:** Android keeps local field events until hub/peer synchronization. The event identifier and signature allow duplicate delivery to be safely recognized by the hub.

## Delivery boundary

An HTTP acceptance or event-sync ACK means the hub/peer accepted an event ID. It does not mean a person received, read or acknowledged an operational instruction. No durable mission message outbox, recipient delivery receipts, read receipts, human ACK state, expiry/retry policy or transport-selection manager exists yet.

## Security and deployment boundary

The current demo uses synthetic keys. Production Android identity enrollment and revocation are not implemented. Hub token auth is optional and shared. Cleartext private LAN use is limited by Android network configuration, but bearer tokens over HTTP are not confidential. Do not expose the prototype hub publicly. Two-device pairing and transfer must be validated on physical phones before claiming offline rescue communications.
