"""Signed demo client: stands in for an enrolled field phone or supervisor tablet.

Uses SYNTHETIC DEMO keys from kavach.pack. Examples:
  python scripts/demo_client.py observe OSM-W712618127 CLOSED
  python scripts/demo_client.py approve OSM-W712618127 VERIFIED_CLOSED "closed per supervisor check"
  python scripts/demo_client.py sync-file events.json
"""
import argparse
import json
import sys
import urllib.request
import uuid
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "server"))

from kavach.pack import demo_signing_key  # noqa: E402
from kavach.signing import sign_body  # noqa: E402


def get(base: str, path: str):
    with urllib.request.urlopen(base + path, timeout=10) as r:
        return json.loads(r.read())


def post(base: str, path: str, payload):
    req = urllib.request.Request(base + path, data=json.dumps(payload).encode(),
                                 headers={"Content-Type": "application/json"}, method="POST")
    with urllib.request.urlopen(req, timeout=10) as r:
        return json.loads(r.read())


def signed(body: dict, key_id: str) -> dict:
    return {**body, "signature": sign_body(body, demo_signing_key(key_id)), "key_id": key_id}


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--hub", default="http://127.0.0.1:8000")
    sub = ap.add_subparsers(dest="cmd", required=True)
    o = sub.add_parser("observe")
    o.add_argument("asset")
    o.add_argument("otype", choices=["WATER", "BLOCKAGE", "CLOSED", "OPEN", "UNKNOWN"])
    o.add_argument("--key", default="demo-responder-01")
    a = sub.add_parser("approve")
    a.add_argument("asset")
    a.add_argument("status", choices=["VERIFIED_OPEN", "VERIFIED_CLOSED", "RESTRICTED"])
    a.add_argument("rationale")
    s = sub.add_parser("sync-file")
    s.add_argument("path")
    args = ap.parse_args()
    pack_version = get(args.hub, "/v1/health")["pack_version"]  # always match the pack the hub is serving

    if args.cmd == "observe":
        body = {"event_id": str(uuid.uuid4()), "kind": "OBSERVATION", "asset_id": args.asset,
                "observation_type": args.otype, "observed_at": datetime.now(timezone.utc).isoformat(),
                "position": {"lat": 18.05, "lon": 78.26, "accuracy_m": 12}, "evidence_sha256": [],
                "source_class": "SIMULATED", "pack_version": pack_version, "origin_key_id": args.key,
                "note": "demo report"}
        print(post(args.hub, "/v1/events", signed(body, args.key)))
    elif args.cmd == "approve":
        key = "demo-supervisor-01"
        body = {"event_id": str(uuid.uuid4()), "kind": "APPROVAL", "asset_id": args.asset,
                "approved_status": args.status, "approved_at": datetime.now(timezone.utc).isoformat(),
                "rationale": args.rationale, "source_class": "SIMULATED", "pack_version": pack_version,
                "origin_key_id": key}
        print(post(args.hub, "/v1/approvals", signed(body, key)))
    elif args.cmd == "sync-file":
        events = json.loads(Path(args.path).read_text(encoding="utf-8"))
        print(post(args.hub, "/v1/sync/batch", events))


if __name__ == "__main__":
    main()
