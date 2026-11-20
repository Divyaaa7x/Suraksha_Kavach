"""Signed, immutable event envelopes and the offline trust registry (dossier sections 12, 38, 43).

Canonical form: UTF-8 JSON with sorted keys and no insignificant whitespace, signed with Ed25519.
SHORTCUT: the dossier prefers canonical CBOR; JSON is used for the prototype so the Android app and
the hub can share one readable test vector. Replace with CBOR before any field deployment.
"""
from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass
from datetime import datetime, timezone

from nacl.exceptions import BadSignatureError
from nacl.signing import SigningKey, VerifyKey

OBSERVATION_TYPES = {"WATER", "BLOCKAGE", "CLOSED", "OPEN", "UNKNOWN"}
APPROVABLE_STATES = {"VERIFIED_OPEN", "VERIFIED_CLOSED", "RESTRICTED"}
MAX_EVENT_BYTES = 4096  # dossier 39: bounded parser (T-14)
ROLE_FIELD = "field_responder"
ROLE_SUPERVISOR = "supervisor"


def canonical_bytes(body: dict) -> bytes:
    return json.dumps(body, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8")


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


@dataclass
class TrustEntry:
    key_id: str
    public_key_hex: str
    role: str
    expires_at: str  # ISO 8601, UTC
    scope: str = "*"


class TrustRegistry:
    def __init__(self, entries: list[TrustEntry]):
        self._entries = {e.key_id: e for e in entries}

    def get(self, key_id: str) -> TrustEntry | None:
        return self._entries.get(key_id)

    def verify(self, body: dict, signature_hex: str, key_id: str, required_role: str | None,
               at: datetime | None = None, asset_id: str | None = None) -> tuple[bool, str]:
        if not isinstance(body, dict) or not isinstance(signature_hex, str) or not isinstance(key_id, str):
            return False, "invalid signature envelope"
        entry = self.get(key_id)
        if entry is None:
            return False, "unknown key id"
        at = at or datetime.now(timezone.utc)
        if at.utcoffset() is None:
            return False, "verification time must include a timezone"
        try:
            expires_at = datetime.fromisoformat(entry.expires_at)
        except (TypeError, ValueError):
            return False, "key expiry invalid"
        if expires_at.utcoffset() is None:
            return False, "key expiry must include a timezone"
        if at > expires_at:
            return False, "key expired"
        if entry.scope != "*" and asset_id != entry.scope:
            return False, "key not scoped for this asset"
        if required_role and entry.role != required_role:
            return False, f"role {entry.role} not permitted (need {required_role})"
        try:
            VerifyKey(bytes.fromhex(entry.public_key_hex)).verify(canonical_bytes(body), bytes.fromhex(signature_hex))
        except (BadSignatureError, TypeError, ValueError):
            return False, "bad signature"
        return True, "ok"

    def to_dict(self) -> list[dict]:
        return [e.__dict__ for e in sorted(self._entries.values(), key=lambda x: x.key_id)]


def sign_body(body: dict, signing_key: SigningKey) -> str:
    return signing_key.sign(canonical_bytes(body)).signature.hex()


def digest_of(obj_bytes: bytes) -> str:
    return hashlib.sha256(obj_bytes).hexdigest()
