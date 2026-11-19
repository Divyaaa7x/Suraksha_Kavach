"""Signed offline data packs (dossier sections 34 and 43).

A pack is a directory: manifest.json (file hashes, pack_version, created_at), signature.json
(Ed25519 over the canonical manifest), graph.json, policy.json, trust.json, LICENSES.txt.
Import verifies the signature against a PINNED pack-signer key (not one shipped inside the pack)
and every file hash before anything is activated. Downgrades are rejected by default (T-06).

All keys in this file are SYNTHETIC DEMO keys derived from fixed public strings. They protect
nothing and must be replaced by provisioned keys before any real use.
"""
from __future__ import annotations

import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path

from nacl.exceptions import BadSignatureError
from nacl.signing import SigningKey, VerifyKey

from . import graph as G
from .signing import TrustEntry, TrustRegistry, canonical_bytes

DEMO_PACK_SIGNER_SEED = hashlib.sha256(b"SYNTHETIC DEMO ONLY - pack signer - not for production").digest()
PINNED_PACK_SIGNER_PUBKEY = SigningKey(DEMO_PACK_SIGNER_SEED).verify_key.encode().hex()

DEMO_KEYS = {
    "demo-responder-01": ("field_responder", b"SYNTHETIC DEMO ONLY - responder 01"),
    "demo-supervisor-01": ("supervisor", b"SYNTHETIC DEMO ONLY - supervisor 01"),
}
PACK_FILES = ["graph.json", "policy.json", "trust.json", "LICENSES.txt"]


class PackError(Exception):
    pass


def demo_signing_key(key_id: str) -> SigningKey:
    return SigningKey(hashlib.sha256(DEMO_KEYS[key_id][1]).digest())


def demo_trust_entries() -> list[TrustEntry]:
    out = []
    for key_id, (role, _) in DEMO_KEYS.items():
        pub = demo_signing_key(key_id).verify_key.encode().hex()
        out.append(TrustEntry(key_id=key_id, public_key_hex=pub, role=role, expires_at="2030-01-01T00:00:00+00:00"))
    return out


def build_pack(out_dir: str | Path, graph_dict: dict, policy: dict, pack_version: str,
               licenses_text: str, extra_files: dict[str, bytes] | None = None,
               source_class: str | None = None) -> Path:
    out = Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)
    trust = {"entries": [e.__dict__ for e in demo_trust_entries()]}
    files = {
        "graph.json": json.dumps(graph_dict, separators=(",", ":"), sort_keys=True).encode(),
        "policy.json": json.dumps(policy, indent=2, sort_keys=True).encode(),
        "trust.json": json.dumps(trust, indent=2, sort_keys=True).encode(),
        "LICENSES.txt": licenses_text.encode(),
        **(extra_files or {}),
    }
    for name, data in files.items():
        (out / name).write_bytes(data)
    manifest = {
        "pack_version": pack_version,
        "created_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "schema": 1,
        "source_class": source_class or graph_dict.get("source_class", "SIMULATED"),
        "files": {name: hashlib.sha256(data).hexdigest() for name, data in files.items()},
    }
    mbytes = canonical_bytes(manifest)
    (out / "manifest.json").write_bytes(mbytes)
    sig = SigningKey(DEMO_PACK_SIGNER_SEED).sign(mbytes).signature.hex()
    (out / "signature.json").write_text(json.dumps({"alg": "ed25519", "signature": sig}, indent=2))
    return out


def load_pack(pack_dir: str | Path, min_created_at: str | None = None) -> dict:
    d = Path(pack_dir)
    try:
        manifest_bytes = (d / "manifest.json").read_bytes()
        manifest = json.loads(manifest_bytes)
        sig = json.loads((d / "signature.json").read_text())["signature"]
    except (OSError, ValueError, KeyError) as exc:
        raise PackError(f"pack unreadable: {exc}") from exc

    try:
        VerifyKey(bytes.fromhex(PINNED_PACK_SIGNER_PUBKEY)).verify(manifest_bytes, bytes.fromhex(sig))
    except (BadSignatureError, ValueError) as exc:
        raise PackError("manifest signature invalid") from exc

    for name, expected in manifest["files"].items():
        p = d / name
        if not p.exists():
            raise PackError(f"missing file {name}")
        if hashlib.sha256(p.read_bytes()).hexdigest() != expected:
            raise PackError(f"hash mismatch for {name}")

    if min_created_at and manifest["created_at"] < min_created_at:
        raise PackError("downgrade rejected: pack older than installed pack")

    graph_bytes = (d / "graph.json").read_bytes()
    graph = G.graph_from_dict(json.loads(graph_bytes))
    graph.pinned_version = "g-" + hashlib.sha256(graph_bytes).hexdigest()[:12]
    crit_file = d / "criticality.json"
    if crit_file.exists():  # precomputed at build time; it is hash-checked above like every other file
        graph.precomputed_criticality = json.loads(crit_file.read_text())
    trust_raw = json.loads((d / "trust.json").read_text())
    trust = TrustRegistry([TrustEntry(**e) for e in trust_raw["entries"]])
    policy = json.loads((d / "policy.json").read_text())
    return {"manifest": manifest, "graph": graph, "trust": trust, "policy": policy}
