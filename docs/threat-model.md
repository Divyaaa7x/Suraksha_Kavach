# Threat model (summary of dossier sections 17 and 42)

Scope: the prototype as built. Each row says what is implemented and what is still open.

| Threat | Mitigation in this build | Status |
|---|---|---|
| Fake responder report | Ed25519 signature checked against an offline trust registry (`core/Trust.kt`, `server/kavach/signing.py`); signer IDs must match the signed origin, scoped keys are restricted to their asset, unsupported OFFICIAL/MODEL source claims are rejected | Implemented for the prototype. Keys are SYNTHETIC demo keys. |
| Forged or edited event | Canonical JSON is signed; any change fails verification (tested) | Implemented |
| Replay / reused event_id | Append-only store; identical duplicates acknowledged; different content rejected | Implemented |
| Unauthorised approval | Only the `supervisor` role may approve; enforced on both phone and hub | Implemented |
| Tampered offline pack | Pinned pack-signer key, per-file SHA-256 manifest, downgrade check | Implemented (tested) |
| Oversize payload | API request bodies are capped at 512 KiB before JSON parsing; each event is limited to 4 KiB and batches to 100 events | Implemented and covered by API/Android unit tests |
| Conflicting claims | Reports never overwrite an approval; conflicts show DISPUTED | Implemented (tested) |
| GPS spoofing | Fix age and accuracy shown; stale fix refused for a new report | Partial. Spoofing cannot be fully prevented. |
| Malicious peer (Nearby) | Pairing code must be confirmed by a person; incoming payloads are size/item capped, events validated before storing, and ACKs are limited to IDs sent to that peer | Unit-tested; physical two-device exchange remains unverified |
| Private key on device | Demo variant uses an intentionally public synthetic key; production event signing fails closed | **Open.** Provision an Android Keystore-compatible signing algorithm, device enrollment, key rotation/revocation, and hub trust distribution before operational use. |
| Production pack trust | Production release build pins an externally supplied Ed25519 public key; release build is blocked without that key and a release keystore | Partial. Organization must provision and protect the matching pack-signing private key. |
| Hub API abuse | Local-only demo, no authentication | **Open.** Add auth and TLS before any LAN outside a lab. |
| Stolen device | None | **Open.** Needs revocation list and device lock policy. |
| Cleartext LAN traffic | Only private addresses allowed by the network security config | Implemented |
| Pack / model poisoning | Model code is not in the build; packs are signed | Partial (no ML models shipped) |

Residual risk: a trusted, enrolled person can still submit a wrong report. Corroboration and human approval are the controls for that.
