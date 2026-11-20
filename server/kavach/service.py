"""Hub service: append-only SQLite event store, validation, sync and status views.

Events are never updated or deleted (dossier ADR-04). Re-sent events are acknowledged as duplicates
and conflicting content under an existing event_id is rejected (T-10, T-11, replay defence).
"""
from __future__ import annotations

import json
import math
import sqlite3
import threading
from datetime import datetime, timezone
from pathlib import Path

from . import graph as G
from .pack import load_pack, PackError
from .statewide_routes import StatewideRoutes
from .signing import (APPROVABLE_STATES, MAX_EVENT_BYTES, OBSERVATION_TYPES, ROLE_FIELD,
                      ROLE_SUPERVISOR, TrustRegistry, now_iso)

SCHEMA = """
CREATE TABLE IF NOT EXISTS events (
    event_id    TEXT PRIMARY KEY,
    kind        TEXT NOT NULL,
    asset_id    TEXT NOT NULL,
    body_json   TEXT NOT NULL,
    signature   TEXT NOT NULL,
    key_id      TEXT NOT NULL,
    received_at TEXT NOT NULL
);
"""

OBS_FIELDS = {"event_id", "kind", "asset_id", "observation_type", "observed_at", "position",
              "evidence_sha256", "source_class", "pack_version", "origin_key_id", "note"}
APP_FIELDS = {"event_id", "kind", "asset_id", "approved_status", "approved_at", "rationale",
              "source_class", "pack_version", "origin_key_id"}
SOURCE_CLASSES = {"FIELD", "SIMULATED"}


class Hub:
    def __init__(self, db_path: str | Path, pack_dir: str | Path,
                 route_db_path: str | Path | None = None, route_db_sha256: str | None = None):
        self.pack_dir = Path(pack_dir)
        self.pack = load_pack(pack_dir)  # raises PackError on tampering (T-06)
        self.graph: G.RoadGraph = self.pack["graph"]
        self.trust: TrustRegistry = self.pack["trust"]
        self.pack_version: str = self.pack["manifest"]["pack_version"]
        self.statewide_routes = (StatewideRoutes(route_db_path, route_db_sha256)
                                 if route_db_path and route_db_sha256 else None)
        self._lock = threading.Lock()
        self.db = sqlite3.connect(str(db_path), check_same_thread=False)
        self.db.row_factory = sqlite3.Row
        self.db.executescript(SCHEMA)
        self.db.commit()

    # ------------------------------------------------------------ validation
    def submit(self, raw: dict) -> dict:
        """Validate and append one signed event. Returns {'status': accepted|duplicate|rejected, ...}."""
        if not isinstance(raw, dict):
            return self._reject("payload must be an object")
        size = len(json.dumps(raw, separators=(",", ":")).encode())
        if size > MAX_EVENT_BYTES:
            return self._reject(f"payload {size} bytes exceeds {MAX_EVENT_BYTES}")
        try:
            body = {k: raw[k] for k in raw if k not in ("signature", "key_id", "received_at")}
            signature, key_id = raw["signature"], raw["key_id"]
        except KeyError as exc:
            return self._reject(f"missing field {exc}")
        if not isinstance(signature, str) or not isinstance(key_id, str):
            return self._reject("signature and key_id must be text")

        kind = body.get("kind")
        if kind == "OBSERVATION":
            err = self._check_observation(body)
            role = None  # field or supervisor both may observe
        elif kind == "APPROVAL":
            err = self._check_approval(body)
            role = ROLE_SUPERVISOR
        else:
            err = f"unknown kind {kind!r}"
        if err:
            return self._reject(err)

        if body["origin_key_id"] != key_id:
            return self._reject("origin_key_id does not match signing key_id")

        if body["asset_id"] not in self.graph.crossings:
            return self._reject(f"asset {body['asset_id']} not in pack {self.pack_version}")

        ok, msg = self.trust.verify(body, signature, key_id, role, asset_id=body["asset_id"])
        if not ok:
            return self._reject(f"signature check failed: {msg}")

        with self._lock:
            row = self.db.execute("SELECT body_json, signature FROM events WHERE event_id=?",
                                  (body["event_id"],)).fetchone()
            if row is not None:
                same = json.loads(row["body_json"]) == body and row["signature"] == signature
                if same:
                    return {"status": "duplicate", "event_id": body["event_id"]}
                return self._reject("event_id reused with different content")
            received = now_iso()
            self.db.execute(
                "INSERT INTO events(event_id,kind,asset_id,body_json,signature,key_id,received_at) VALUES(?,?,?,?,?,?,?)",
                (body["event_id"], kind, body["asset_id"], json.dumps(body, sort_keys=True), signature, key_id, received))
            self.db.commit()
        return {"status": "accepted", "event_id": body["event_id"], "received_at": received}

    def _reject(self, reason: str) -> dict:
        return {"status": "rejected", "reason": reason}

    def _check_observation(self, b: dict) -> str | None:
        if set(b) - OBS_FIELDS:
            return f"unexpected fields {sorted(set(b) - OBS_FIELDS)}"
        missing = OBS_FIELDS - set(b)
        if missing:
            return f"missing fields {sorted(missing)}"
        if not self._valid_common_fields(b):
            return "event_id, asset_id, origin_key_id and pack_version must be text"
        if not isinstance(b.get("observation_type"), str) or b["observation_type"] not in OBSERVATION_TYPES:
            return "observation_type invalid"
        if not isinstance(b.get("source_class"), str) or b["source_class"] not in SOURCE_CLASSES:
            return "source_class invalid"
        pos = b.get("position")
        if not isinstance(pos, dict):
            return "position required"
        lat, lon, accuracy = pos.get("lat"), pos.get("lon"), pos.get("accuracy_m")
        try:
            valid_numbers = all(not isinstance(n, bool) and isinstance(n, (int, float)) and math.isfinite(float(n))
                                for n in (lat, lon, accuracy))
        except (OverflowError, ValueError):
            valid_numbers = False
        if not valid_numbers:
            return "position coordinates and accuracy must be finite numbers"
        if not -90 <= lat <= 90 or not -180 <= lon <= 180 or accuracy < 0:
            return "position coordinates or accuracy are outside valid ranges"
        try:
            observed_at = datetime.fromisoformat(b["observed_at"])
        except (TypeError, ValueError):
            return "observed_at must be ISO 8601"
        if observed_at.utcoffset() is None:
            return "observed_at must include a timezone"
        hashes = b.get("evidence_sha256")
        if not isinstance(hashes, list) or any(not isinstance(h, str) or len(h) != 64 or
                                                any(c not in "0123456789abcdefABCDEF" for c in h) for h in hashes):
            return "evidence_sha256 must contain SHA-256 hex digests"
        if not isinstance(b.get("note"), str):
            return "note must be text"
        if b.get("pack_version") != self.pack_version:
            return f"pack_version {b.get('pack_version')} does not match {self.pack_version}"
        return None

    def _check_approval(self, b: dict) -> str | None:
        if set(b) - APP_FIELDS:
            return f"unexpected fields {sorted(set(b) - APP_FIELDS)}"
        missing = APP_FIELDS - set(b)
        if missing:
            return f"missing fields {sorted(missing)}"
        if not self._valid_common_fields(b):
            return "event_id, asset_id, origin_key_id and pack_version must be text"
        if not isinstance(b.get("approved_status"), str) or b["approved_status"] not in APPROVABLE_STATES:
            return "approved_status must be VERIFIED_OPEN, VERIFIED_CLOSED or RESTRICTED"
        if not isinstance(b.get("rationale"), str) or not b["rationale"].strip():
            return "rationale required for approval"
        if not isinstance(b.get("source_class"), str) or b["source_class"] not in SOURCE_CLASSES:
            return "source_class invalid"
        if b.get("pack_version") != self.pack_version:
            return f"pack_version {b.get('pack_version')} does not match {self.pack_version}"
        try:
            approved_at = datetime.fromisoformat(b["approved_at"])
        except (KeyError, TypeError, ValueError):
            return "approved_at must be ISO 8601"
        if approved_at.utcoffset() is None:
            return "approved_at must include a timezone"
        return None

    def _valid_common_fields(self, body: dict) -> bool:
        return all(isinstance(body.get(field), str) and body[field]
                   for field in ("event_id", "asset_id", "origin_key_id", "pack_version"))

    # ------------------------------------------------------------ views
    def events(self, kind: str | None = None) -> list[dict]:
        q = "SELECT * FROM events" + (" WHERE kind=?" if kind else "") + " ORDER BY received_at, event_id"
        with self._lock:
            rows = self.db.execute(q, (kind,) if kind else ()).fetchall()
        out = []
        for r in rows:
            d = json.loads(r["body_json"])
            d.update({"signature": r["signature"], "key_id": r["key_id"], "received_at": r["received_at"]})
            out.append(d)
        return out

    def statuses(self, scenario_at_risk: list[str] | None = None) -> dict[str, dict]:
        obs = [e for e in self.events("OBSERVATION")]
        apps = [e for e in self.events("APPROVAL")]
        now = datetime.now(timezone.utc)
        view = G.resolve_status(obs, apps, now)
        out = {}
        for c in self.graph.crossings:
            v = view.get(c, {"status": G.UNKNOWN, "conflict_with_reports": False, "basis": None, "approved_by": None})
            if scenario_at_risk and c in scenario_at_risk and v["status"] == G.UNKNOWN:
                v = {**v, "status": G.PREDICTED_RISK, "basis": "scenario overlay (SYNTHETIC)"}
            out[c] = v
        return out

    def _status_map(self, scenario_at_risk=None) -> dict[str, str]:
        return {c: v["status"] for c, v in self.statuses(scenario_at_risk).items()}

    def route(self, origin: str, dest: str, vehicle: str = "light", policy: str = G.STRICT,
              scenario_at_risk=None) -> dict:
        for n in (origin, dest):
            if n not in self.graph.nodes:
                return {"error": f"unknown node {n}"}
        res = G.plan_route(self.graph, self._status_map(scenario_at_risk), origin, dest, vehicle, policy)
        res["baseline_shortest_path"] = G.shortest_path(self.graph, origin, dest)
        return res

    def search_statewide_places(self, query: str, limit: int = 20) -> list[dict]:
        if self.statewide_routes is None:
            return []
        return self.statewide_routes.search_places(query, limit)

    def route_statewide(self, origin: str, dest: str, vehicle: str = "light", policy: str = G.STRICT) -> dict:
        if self.statewide_routes is None:
            return {"error": "signed statewide route database is not installed"}
        # The current trust registry only covers pilot crossings, so statewide crossings stay UNKNOWN.
        return self.statewide_routes.compute(origin, dest, vehicle, policy)

    def isolated(self, vehicle: str = "light", policy: str = G.STRICT, scenario_at_risk=None) -> list[dict]:
        return G.isolated_settlements(self.graph, self._status_map(scenario_at_risk), vehicle, policy)

    def inspection(self, scenario_at_risk=None) -> list[dict]:
        return G.inspection_ranking(self.graph, self._status_map(scenario_at_risk))

    def inventory(self) -> list[str]:
        return [r["event_id"] for r in self.db.execute("SELECT event_id FROM events ORDER BY event_id")]

    def sync_batch(self, events: list[dict]) -> list[dict]:
        if len(events) > 100:
            return [self._reject("batch larger than 100 events")]
        return [self.submit(e) for e in events]

    def health(self) -> dict:
        counts = dict(self.db.execute("SELECT kind, COUNT(*) FROM events GROUP BY kind").fetchall())
        return {
            "status": "ok",
            "mode": "local-hub-offline",
            "pack_version": self.pack_version,
            "pack_created_at": self.pack["manifest"]["created_at"],
            "graph_version": self.graph.version(),
            "events": {"OBSERVATION": counts.get("OBSERVATION", 0), "APPROVAL": counts.get("APPROVAL", 0)},
            "source_class": self.graph.source_class,
            "source_class_note": "Map and road data: OpenStreetMap, (c) OpenStreetMap contributors, ODbL. "
                                 "Crossing status: no field verification yet unless a report or approval exists.",
            "server_time": now_iso(),
        }
