"""Prototype persistence for warning exposure and the field inspection lifecycle.

This module deliberately keeps provenance and human decisions separate from graph status.
SQLite migrations are additive; task events are append-only and transition retries are idempotent.
"""
from __future__ import annotations

import json
import math
import re
import sqlite3
import threading
import uuid
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from pathlib import Path

STATES = ("CREATED", "QUEUED", "SENT", "FORWARDED", "DELIVERED", "READ", "ACKNOWLEDGED",
          "IN_PROGRESS", "SUBMITTED", "VERIFIED", "REJECTED", "EXPIRED")
TRANSITIONS = {
    "CREATED": {"QUEUED", "EXPIRED"}, "QUEUED": {"SENT", "EXPIRED"},
    "SENT": {"FORWARDED", "DELIVERED", "EXPIRED"}, "FORWARDED": {"DELIVERED", "EXPIRED"},
    "DELIVERED": {"READ", "ACKNOWLEDGED", "EXPIRED"}, "READ": {"ACKNOWLEDGED", "EXPIRED"},
    "ACKNOWLEDGED": {"IN_PROGRESS", "EXPIRED"}, "IN_PROGRESS": {"SUBMITTED", "EXPIRED"},
    "SUBMITTED": {"VERIFIED", "REJECTED"}, "VERIFIED": set(), "REJECTED": set(), "EXPIRED": set(),
}
SCHEMA = """
CREATE TABLE IF NOT EXISTS warnings(warning_id TEXT PRIMARY KEY, provider TEXT NOT NULL, source_url TEXT NOT NULL,
 source_class TEXT NOT NULL, original_alert_id TEXT, issued_at TEXT, valid_from TEXT,
 valid_until TEXT, geometry_json TEXT NOT NULL, update_of TEXT, cancel_of TEXT, original_json TEXT NOT NULL,
 imported_at TEXT NOT NULL, active INTEGER NOT NULL DEFAULT 1);
CREATE TABLE IF NOT EXISTS warning_revisions(revision_id TEXT PRIMARY KEY, warning_id TEXT NOT NULL,
 revision_type TEXT NOT NULL, original_alert_id TEXT, issued_at TEXT,
 payload_json TEXT NOT NULL, imported_at TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS squads(squad_id TEXT PRIMARY KEY, name TEXT NOT NULL, active INTEGER NOT NULL DEFAULT 1);
CREATE TABLE IF NOT EXISTS responders(responder_id TEXT PRIMARY KEY, squad_id TEXT NOT NULL, label TEXT NOT NULL,
 active INTEGER NOT NULL DEFAULT 1);
CREATE TABLE IF NOT EXISTS missions(mission_id TEXT PRIMARY KEY, crossing_id TEXT NOT NULL, squad_id TEXT NOT NULL,
 warning_id TEXT, priority INTEGER NOT NULL, state TEXT NOT NULL, created_at TEXT NOT NULL, updated_at TEXT NOT NULL,
 created_by TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS task_events(event_id TEXT PRIMARY KEY, mission_id TEXT NOT NULL, state TEXT NOT NULL,
 actor_id TEXT NOT NULL, actor_role TEXT NOT NULL, idempotency_key TEXT NOT NULL UNIQUE,
 created_at TEXT NOT NULL, payload_json TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS observations(observation_id TEXT PRIMARY KEY, mission_id TEXT NOT NULL,
 crossing_id TEXT NOT NULL, responder_id TEXT NOT NULL, observed_status TEXT NOT NULL, observed_at TEXT NOT NULL,
 lat REAL, lon REAL, notes TEXT NOT NULL, evidence_json TEXT NOT NULL, state TEXT NOT NULL,
 submitted_at TEXT NOT NULL, decision_by TEXT, decision_at TEXT, rationale TEXT);
CREATE TABLE IF NOT EXISTS delivery_receipts(receipt_id TEXT PRIMARY KEY, mission_id TEXT NOT NULL,
 responder_id TEXT NOT NULL, receipt_type TEXT NOT NULL, received_at TEXT NOT NULL, idempotency_key TEXT NOT NULL UNIQUE);
"""
MIGRATIONS = {1: SCHEMA, 2: "ALTER TABLE observations ADD COLUMN signed_event_id TEXT;"}
CURRENT_SCHEMA = max(MIGRATIONS)


def _now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def _time(value, required=False):
    if value is None and not required:
        return None
    if not isinstance(value, str):
        raise ValueError("timestamp must be ISO 8601 text")
    try:
        d = datetime.fromisoformat(value)
    except ValueError as exc:
        raise ValueError("timestamp must be ISO 8601") from exc
    if d.utcoffset() is None:
        raise ValueError("timestamp must include a timezone")
    return d.astimezone(timezone.utc).isoformat(timespec="seconds")


def _point_in_ring(x, y, ring):
    inside = False
    for i in range(len(ring) - 1):
        x1, y1 = ring[i][:2]; x2, y2 = ring[i + 1][:2]
        if (y1 > y) != (y2 > y) and x < (x2 - x1) * (y - y1) / (y2 - y1) + x1:
            inside = not inside
    return inside


def _point_in_polygon(lon, lat, polygon):
    return _point_in_ring(lon, lat, polygon[0]) and not any(_point_in_ring(lon, lat, r) for r in polygon[1:])


def _orientation(a, b, c):
    return (b[0]-a[0])*(c[1]-a[1]) - (b[1]-a[1])*(c[0]-a[0])


def _segments_intersect(a, b, c, d):
    o1, o2, o3, o4 = _orientation(a,b,c), _orientation(a,b,d), _orientation(c,d,a), _orientation(c,d,b)
    if o1 * o2 < 0 and o3 * o4 < 0:
        return True
    def on(p,q,r):
        return min(p[0],q[0])-1e-12 <= r[0] <= max(p[0],q[0])+1e-12 and min(p[1],q[1])-1e-12 <= r[1] <= max(p[1],q[1])+1e-12
    return ((abs(o1) <= 1e-12 and on(a,b,c)) or (abs(o2) <= 1e-12 and on(a,b,d)) or
            (abs(o3) <= 1e-12 and on(c,d,a)) or (abs(o4) <= 1e-12 and on(c,d,b)))


def _valid_geometry(geometry):
    if not isinstance(geometry, dict):
        raise ValueError("warning geometry must be GeoJSON Polygon or MultiPolygon")
    typ, coords = geometry.get("type"), geometry.get("coordinates")
    polygons = [coords] if typ == "Polygon" else coords if typ == "MultiPolygon" else None
    if not isinstance(polygons, list) or not polygons:
        raise ValueError("warning geometry must be a non-empty Polygon or MultiPolygon")
    vertex_count=0
    for polygon in polygons:
        if not isinstance(polygon, list) or not polygon:
            raise ValueError("polygon requires an exterior ring")
        for ring in polygon:
            if not isinstance(ring, list) or len(ring) < 4 or ring[0] != ring[-1]:
                raise ValueError("polygon rings must be closed with at least four coordinates")
            vertex_count += len(ring)
            if vertex_count > 100_000: raise ValueError("warning geometry exceeds vertex limit")
            for p in ring:
                if not isinstance(p, (list, tuple)) or len(p) < 2 or any(isinstance(v, bool) or not isinstance(v,(int,float)) or not math.isfinite(v) for v in p[:2]):
                    raise ValueError("polygon coordinates must be finite numbers")
                if not -180 <= p[0] <= 180 or not -90 <= p[1] <= 90:
                    raise ValueError("polygon coordinates out of range")
            # Sweep by longitude so detailed district boundaries avoid quadratic validation.
            segs = [(min(a[0],b[0]), max(a[0],b[0]), min(a[1],b[1]), max(a[1],b[1]), i, a, b)
                    for i,(a,b) in enumerate(zip(ring,ring[1:]))]
            active=[]
            for seg in sorted(segs):
                xmin,xmax,ymin,ymax,i,a,b=seg
                active=[s for s in active if s[1] >= xmin]
                for other in active:
                    ox0,ox1,oy0,oy1,j,c,d=other
                    if abs(i-j)==1 or (i==0 and j==len(segs)-1): continue
                    if a in (c,d) or b in (c,d): continue
                    if ymax < oy0 or oy1 < ymin: continue
                    if _segments_intersect(a,b,c,d): raise ValueError("polygon ring self-intersects")
                active.append(seg)
            if abs(sum(ring[i][0]*ring[i+1][1]-ring[i+1][0]*ring[i][1] for i in range(len(ring)-1))) < 1e-12:
                raise ValueError("polygon ring has zero area")
    return polygons


def _intersects(geometry, points):
    polygons = _valid_geometry(geometry)
    for lon, lat in points:
        if any(_point_in_polygon(lon, lat, poly) for poly in polygons):
            return True
    for a,b in zip(points, points[1:]):
        for poly in polygons:
            for ring in poly:
                if any(_segments_intersect(a,b,c,d) for c,d in zip(ring, ring[1:])):
                    return True
    return False


def demo_warning_fixture():
    """Historical IMD rainfall text with a sourced, simplified district-boundary approximation."""
    path = Path(__file__).resolve().parents[2] / "data" / "fixtures" / "imd-2024-kamareddy-archived-warning.json"
    payload=json.loads(path.read_text(encoding="utf-8-sig"))
    payload.pop("issued_at", None)  # archival report gives validity, but no distinct issue timestamp
    return payload


class OperationsStore:
    def __init__(self, db_path: str | Path, graph, demo_mode: bool = False):
        self.graph = graph
        self.demo_mode = demo_mode
        self.lock = threading.RLock()
        self.db = sqlite3.connect(str(db_path), check_same_thread=False)
        self.db.row_factory = sqlite3.Row
        self.db.execute("PRAGMA foreign_keys=ON")
        version=self.db.execute("PRAGMA user_version").fetchone()[0]
        if version > CURRENT_SCHEMA: raise RuntimeError(f"unsupported operations schema version {version}")
        while version < CURRENT_SCHEMA:
            self.db.executescript(MIGRATIONS[version + 1])
            self.db.execute(f"PRAGMA user_version={version + 1}")
            self.db.commit()
            version += 1
        self.db.commit()

    def seed_demo(self):
        if not self.demo_mode: raise PermissionError("synthetic demo mode is disabled")
        self.add_squad("SYN-SQUAD-01", "SYNTHETIC Demo Response Squad")
        self.add_responder("demo-responder-01", "SYN-SQUAD-01", "SYNTHETIC Responder 01")

    def import_demo_fixture(self):
        if not self.demo_mode: raise PermissionError("synthetic demo mode is disabled")
        return self.import_warning(demo_warning_fixture())

    def import_cap_xml(self, xml_payload: str | bytes, source_url: str, source_class: str = "SIMULATED") -> dict:
        """Import one bounded CAP 1.2 alert. No provider-specific network access occurs here."""
        if not isinstance(xml_payload, (str, bytes)):
            raise ValueError("CAP payload must be XML text or bytes")
        if not isinstance(source_url, str) or not source_url.strip():
            raise ValueError("source_url is required to retain CAP provenance")
        if source_class not in {"ARCHIVED_OFFICIAL", "SIMULATED"}:
            raise ValueError("source_class must be ARCHIVED_OFFICIAL or SIMULATED")
        raw = xml_payload.encode("utf-8") if isinstance(xml_payload, str) else xml_payload
        if not raw or len(raw) > 1_000_000:
            raise ValueError("CAP payload must be between 1 byte and 1 MB")
        if re.search(rb"<!\s*(?:DOCTYPE|ENTITY)\b", raw, re.IGNORECASE):
            raise ValueError("DTD and entity declarations are not accepted")
        try:
            original_xml = raw.decode("utf-8")
            parse_bytes = raw
            if isinstance(xml_payload, str):
                parse_bytes = re.sub(rb"(<\?xml[^?]*\bencoding\s*=\s*)(['\"])[^'\"]+\2",
                                     rb'\1"UTF-8"', raw, count=1, flags=re.IGNORECASE)
            root = ET.fromstring(parse_bytes)
        except (UnicodeDecodeError, ET.ParseError) as exc:
            raise ValueError(f"invalid CAP XML: {exc}") from exc

        nodes = list(root.iter())
        if len(nodes) > 20_000:
            raise ValueError("CAP document has too many XML elements")
        stack = [(root, 1)]
        while stack:
            element, depth = stack.pop()
            if depth > 64: raise ValueError("CAP document nesting exceeds limit")
            stack.extend((child, depth + 1) for child in element)
        local = lambda tag: tag.rsplit("}", 1)[-1].lower()
        if root.tag != "{urn:oasis:names:tc:emergency:cap:1.2}alert":
            raise ValueError("CAP document root must use the CAP 1.2 namespace")
        def child_text(parent, name):
            return next((e.text.strip() for e in parent if local(e.tag) == name and e.text and e.text.strip()), None)

        sender, identifier, sent = (child_text(root, n) for n in ("sender", "identifier", "sent"))
        msg_type = child_text(root, "msgtype")
        if not sender or not identifier or not sent: raise ValueError("CAP sender, identifier and sent are required")
        sent_time = _time(sent, True)
        if msg_type not in {"Alert", "Update", "Cancel"}: raise ValueError("CAP msgType must be Alert, Update or Cancel")

        refs = []
        ref_text = child_text(root, "references")
        if ref_text:
            for token in ref_text.split():
                parts = token.split(",")
                if len(parts) != 3 or any(not part for part in parts): raise ValueError("CAP references must be sender,identifier,sent triples")
                _time(parts[2], True)
                refs.append({"sender": parts[0], "identifier": parts[1], "sent": parts[2],
                             "warning_id": f"CAP:{parts[0]}:{parts[1]}"})
        if msg_type in {"Update", "Cancel"} and not refs: raise ValueError("CAP Update/Cancel requires references to an imported alert")
        for ref in refs:
            if not self.db.execute("SELECT 1 FROM warnings WHERE warning_id=?", (ref["warning_id"],)).fetchone():
                raise ValueError(f"CAP reference not found in archive: {ref['warning_id']}")

        polygons = []
        info_records = []
        for info in (e for e in root if local(e.tag) == "info"):
            fields = {name: child_text(info, name) for name in ("language", "category", "event", "urgency", "severity", "certainty", "effective", "onset", "expires", "headline", "description", "instruction")}
            info_records.append(fields)
            for area in (e for e in info if local(e.tag) == "area"):
                area_desc = child_text(area, "areadesc")
                polygon_texts = [e.text.strip() for e in area if local(e.tag) == "polygon" and e.text and e.text.strip()]
                for polygon_text in polygon_texts:
                    ring = []
                    for point in polygon_text.split():
                        pair = point.split(",")
                        if len(pair) != 2: raise ValueError("CAP polygon points must be lat,lon pairs")
                        try: lat, lon = float(pair[0]), float(pair[1])
                        except ValueError as exc: raise ValueError("CAP polygon coordinates must be numbers") from exc
                        if not math.isfinite(lat) or not math.isfinite(lon): raise ValueError("CAP polygon coordinates must be finite")
                        ring.append([lon, lat])
                    if len(ring) < 3: raise ValueError("CAP polygon needs at least three points")
                    if ring[0] != ring[-1]: ring.append(ring[0])
                    polygons.append({"ring": ring, "area_desc": area_desc})
        if not polygons and refs:
            with self.lock:
                prior = self.db.execute("SELECT geometry_json FROM warnings WHERE warning_id=?", (refs[0]["warning_id"],)).fetchone()
            if prior:
                stored = json.loads(prior["geometry_json"])
                polygons = [{"ring": ring, "area_desc": "inherited from referenced alert"}
                            for poly in (stored["coordinates"] if stored["type"] == "MultiPolygon" else [stored["coordinates"]])
                            for ring in poly[:1]]
        if not polygons: raise ValueError("CAP alert has no supported polygon geometry")
        geometry = {"type": "MultiPolygon", "coordinates": [[p["ring"]] for p in polygons]}
        _valid_geometry(geometry)

        primary_ref = refs[0]["warning_id"] if refs else None
        valid_from = next((f[k] for f in info_records for k in ("effective", "onset") if f.get(k)), None)
        valid_until = next((f["expires"] for f in info_records if f.get("expires")), None)
        title = next((f["headline"] or f["event"] for f in info_records if f.get("headline") or f.get("event")), identifier)
        payload = {
            "warning_id": f"CAP:{sender}:{identifier}", "provider": sender, "source_url": source_url,
            "source_class": source_class, "original_alert_id": identifier, "issued_at": sent,
            "valid_from": valid_from, "valid_until": valid_until, "geometry": geometry,
            "update_of": primary_ref if msg_type == "Update" else None,
            "cancel_of": primary_ref if msg_type == "Cancel" else None,
            "original": {"format": "CAP 1.2", "sender": sender, "identifier": identifier, "sent": sent,
                         "msg_type": msg_type, "references": refs, "info": info_records,
                         "areas": [p["area_desc"] for p in polygons], "title": title,
                         "original_xml": original_xml,
                         "geometry_provenance": "CAP area polygon converted from lat,lon to GeoJSON lon,lat."}
        }
        imported = self.import_warning(payload)
        if len(refs) > 1 and msg_type in {"Update", "Cancel"}:
            with self.lock, self.db:
                self.db.executemany("UPDATE warnings SET active=0 WHERE warning_id=?",
                                    [(r["warning_id"],) for r in refs[1:]])
        return imported

    def import_warning(self, p: dict) -> dict:
        required = ("provider", "source_url", "source_class", "geometry", "original")
        if not isinstance(p, dict) or any(not p.get(k) for k in required):
            raise ValueError("warning requires provider, attributable source_url, source_class, geometry and original; unavailable alert IDs and issue times may be null")
        if not isinstance(p["provider"],str) or not isinstance(p["source_url"],str) or not isinstance(p["original"],dict):
            raise ValueError("provider/source_url must be text and original must be an object")
        if p["source_class"] not in {"ARCHIVED_OFFICIAL", "SIMULATED"}:
            raise ValueError("source_class must be ARCHIVED_OFFICIAL or SIMULATED")
        geometry = p["geometry"]
        _valid_geometry(geometry)
        issued = _time(p.get("issued_at")); vf = _time(p.get("valid_from")); vu = _time(p.get("valid_until"))
        if vf and vu and vf > vu:
            raise ValueError("valid_until precedes valid_from")
        wid = str(p.get("warning_id") or p.get("original_alert_id") or uuid.uuid4())
        imported = _now(); rev_type = "CANCEL" if p.get("cancel_of") else "UPDATE" if p.get("update_of") else "ORIGINAL"
        geometry_json=json.dumps(geometry,separators=(",",":"))
        original_json=json.dumps(p["original"],ensure_ascii=False,sort_keys=True)
        with self.lock, self.db:
            prior=self.db.execute("SELECT * FROM warnings WHERE warning_id=?",(wid,)).fetchone()
            if prior:
                same=(prior["provider"],prior["source_url"],prior["source_class"],prior["original_alert_id"],prior["issued_at"],prior["valid_from"],prior["valid_until"],prior["geometry_json"],prior["update_of"],prior["cancel_of"],prior["original_json"])
                current=(p["provider"],p["source_url"],p["source_class"],p.get("original_alert_id"),issued,vf,vu,geometry_json,p.get("update_of"),p.get("cancel_of"),original_json)
                if same != current: raise ValueError("warning_id already exists with different content; import a referenced revision under a new warning_id")
                return self.list_warnings(wid)[0]
            if p.get("update_of")==wid or p.get("cancel_of")==wid: raise ValueError("warning cannot reference itself")
            reference = p.get("cancel_of") or p.get("update_of")
            if reference and not self.db.execute("SELECT 1 FROM warnings WHERE warning_id=?",(reference,)).fetchone():
                raise ValueError("update/cancellation references an unknown warning")
            if p.get("cancel_of"):
                self.db.execute("UPDATE warnings SET active=0 WHERE warning_id=?",(p["cancel_of"],))
            elif p.get("update_of"):
                self.db.execute("UPDATE warnings SET active=0 WHERE warning_id=?",(p["update_of"],))
            self.db.execute("INSERT INTO warnings(warning_id,provider,source_url,source_class,original_alert_id,issued_at,valid_from,valid_until,geometry_json,update_of,cancel_of,original_json,imported_at,active) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                (wid,p["provider"],p["source_url"],p["source_class"],p.get("original_alert_id"),issued,vf,vu,geometry_json,p.get("update_of"),p.get("cancel_of"),original_json,imported,0 if p.get("cancel_of") else 1))
            self.db.execute("INSERT INTO warning_revisions VALUES(?,?,?,?,?,?,?)", (str(uuid.uuid4()),wid,rev_type,p.get("original_alert_id"),issued,json.dumps(p,ensure_ascii=False,sort_keys=True),imported))
        return self.list_warnings(wid)[0]

    def list_warnings(self, warning_id=None):
        q="SELECT * FROM warnings"+(" WHERE warning_id=?" if warning_id else "")+" ORDER BY issued_at DESC,warning_id"
        with self.lock: rows=self.db.execute(q,(warning_id,) if warning_id else ()).fetchall()
        return [{**dict(r),"geometry":json.loads(r["geometry_json"]),"original":json.loads(r["original_json"]),"active":bool(r["active"])} for r in rows]

    def warning_history(self, warning_id):
        if not self.db.execute("SELECT 1 FROM warnings WHERE warning_id=?",(warning_id,)).fetchone(): raise KeyError("unknown warning")
        return [dict(r) for r in self.db.execute("SELECT * FROM warning_revisions WHERE warning_id=? ORDER BY imported_at,revision_id",(warning_id,))]

    def exposure(self, warning_id: str, at: str | None = None):
        warning = next(iter(self.list_warnings(warning_id)),None)
        if not warning: raise KeyError("unknown warning")
        instant = _time(at or _now(), True)
        valid = warning["active"] and (not warning["valid_from"] or instant >= warning["valid_from"]) and (not warning["valid_until"] or instant < warning["valid_until"])
        rows={}
        for e in self.graph.edges.values():
            if not e.crossing_id: continue
            a,b=self.graph.nodes[e.u],self.graph.nodes[e.v]
            exposed=valid and _intersects(warning["geometry"],[(a.lon,a.lat),(b.lon,b.lat)])
            if exposed:
                row=rows.setdefault(e.crossing_id,{"warning_id":warning_id,"crossing_id":e.crossing_id,"edge_ids":[],"state":"GEOGRAPHICALLY_EXPOSED","physical_status":"UNCONFIRMED","source_class":warning["source_class"],"explanation":"Warning geometry intersects mapped road segment; this is not field confirmation."})
                row["edge_ids"].append(e.edge_id)
        criticality=self.graph.precomputed_criticality or {}
        ordered=sorted(rows, key=lambda c:(criticality.get(c) is None, -(criticality.get(c) or 0), c))
        result=[]
        for rank,crossing_id in enumerate(ordered,1):
            score=criticality.get(crossing_id)
            result.append({**rows[crossing_id],"rank":rank,"settlements_cut_off_if_closed":score,
                           "rank_reason":(f"{score} settlements structurally depend on this crossing if it is closed; score is from the signed pack." if score is not None else "No signed structural criticality score is available; ordered by crossing ID.")})
        return result

    def add_squad(self, squad_id, name):
        with self.db: self.db.execute("INSERT INTO squads(squad_id,name) VALUES(?,?) ON CONFLICT(squad_id) DO UPDATE SET name=excluded.name,active=1",(squad_id,name))

    def add_responder(self, responder_id, squad_id, label):
        with self.db:
            if not self.db.execute("SELECT 1 FROM squads WHERE squad_id=? AND active=1",(squad_id,)).fetchone(): raise ValueError("unknown or inactive squad")
            self.db.execute("INSERT INTO responders VALUES(?,?,?,1) ON CONFLICT(responder_id) DO UPDATE SET squad_id=excluded.squad_id,label=excluded.label,active=1",(responder_id,squad_id,label))

    def list_squads(self):
        return [dict(r) for r in self.db.execute("SELECT s.squad_id,s.name,s.active,COUNT(r.responder_id) responders FROM squads s LEFT JOIN responders r ON r.squad_id=s.squad_id AND r.active=1 WHERE s.active=1 GROUP BY s.squad_id ORDER BY s.name")]

    def create_mission(self,crossing_id,squad_id,priority,warning_id=None,actor_role="commander",actor_id="demo-commander-01",idempotency_key=None):
        if actor_role not in {"commander","supervisor"}: raise PermissionError("commander role required")
        self._authorize(actor_id,actor_role,actor_role)
        if crossing_id not in self.graph.crossings: raise ValueError("crossing is not in active graph")
        if isinstance(priority,bool) or not isinstance(priority,int) or not 1<=priority<=5: raise ValueError("priority must be 1 through 5")
        if not self.db.execute("SELECT 1 FROM squads WHERE squad_id=? AND active=1",(squad_id,)).fetchone(): raise ValueError("unknown or inactive squad")
        if warning_id and not self.db.execute("SELECT 1 FROM warnings WHERE warning_id=?",(warning_id,)).fetchone(): raise ValueError("unknown warning")
        mid=str(uuid.uuid4()); now=_now(); key=idempotency_key or "create:"+mid
        create_payload={"crossing_id":crossing_id,"squad_id":squad_id,"priority":priority,"warning_id":warning_id}
        with self.lock,self.db:
            prior=self.db.execute("SELECT mission_id,state,actor_id,actor_role,payload_json FROM task_events WHERE idempotency_key=?",(key,)).fetchone()
            if prior:
                if (prior["state"],prior["actor_id"],prior["actor_role"],prior["payload_json"]) != ("CREATED",actor_id,actor_role,json.dumps(create_payload,sort_keys=True)):
                    raise ValueError("mission idempotency key reused with different content")
                return self.get_mission(prior["mission_id"])
            self.db.execute("INSERT INTO missions VALUES(?,?,?,?,?,?,?,?,?)",(mid,crossing_id,squad_id,warning_id,priority,"CREATED",now,now,actor_id))
            self._event(mid,"CREATED",actor_id,actor_role,key,create_payload)
        return self.get_mission(mid)

    def _event(self,mid,state,actor_id,role,key,payload):
        existing=self.db.execute("SELECT * FROM task_events WHERE idempotency_key=?",(key,)).fetchone()
        if existing:
            if existing["mission_id"]!=mid or existing["state"]!=state or json.loads(existing["payload_json"])!=payload: raise ValueError("idempotency key reused with different content")
            return False
        self.db.execute("INSERT INTO task_events VALUES(?,?,?,?,?,?,?,?)",(str(uuid.uuid4()),mid,state,actor_id,role,key,_now(),json.dumps(payload,sort_keys=True)))
        return True

    def transition(self,mission_id,to_state,actor_role="commander",idempotency_key=None,actor_id="demo-commander-01"):
        if to_state not in STATES: raise ValueError("unknown mission state")
        roles={"CREATED":{"commander","supervisor"},"QUEUED":{"commander","supervisor"},"SENT":{"commander","supervisor"},"FORWARDED":{"system","commander"},"DELIVERED":{"system","field_responder"},"READ":{"field_responder"},"ACKNOWLEDGED":{"field_responder"},"IN_PROGRESS":{"field_responder"},"SUBMITTED":{"field_responder"},"VERIFIED":{"supervisor"},"REJECTED":{"supervisor"},"EXPIRED":{"system","commander","supervisor"}}
        if not self.demo_mode: raise PermissionError("operations workflow requires explicit prototype demo mode")
        if actor_role not in roles[to_state]: raise PermissionError(f"{actor_role} cannot transition to {to_state}")
        if actor_role == "system":
            if actor_id != "demo-system": raise PermissionError("system actor is server-only")
        elif actor_role == "field_responder":
            m = self.db.execute("SELECT squad_id FROM missions WHERE mission_id=?",(mission_id,)).fetchone()
            if not m or not self.db.execute("SELECT 1 FROM responders WHERE responder_id=? AND squad_id=? AND active=1",(actor_id,m[0])).fetchone(): raise PermissionError("responder is not an active member of the assigned squad")
        else:
            self._authorize(actor_id,actor_role,"supervisor" if actor_role=="supervisor" else "commander")
        with self.lock,self.db:
            m=self.db.execute("SELECT * FROM missions WHERE mission_id=?",(mission_id,)).fetchone()
            if not m: raise KeyError("unknown mission")
            key=idempotency_key or f"{mission_id}:{to_state}:{actor_id}"
            prior=self.db.execute("SELECT mission_id,state,actor_id,actor_role,payload_json FROM task_events WHERE idempotency_key=?",(key,)).fetchone()
            if prior:
                expected=(mission_id,to_state,actor_id,actor_role,"{}")
                actual=(prior["mission_id"],prior["state"],prior["actor_id"],prior["actor_role"],prior["payload_json"])
                if actual!=expected: raise ValueError("idempotency key reused with different content")
                return self.get_mission(mission_id)
            if m["state"]==to_state:
                self._event(mission_id,to_state,actor_id,actor_role,key,{})
                return self.get_mission(mission_id)
            if to_state not in TRANSITIONS[m["state"]]: raise ValueError(f"invalid transition {m['state']} -> {to_state}")
            self._event(mission_id,to_state,actor_id,actor_role,key,{})
            self.db.execute("UPDATE missions SET state=?,updated_at=? WHERE mission_id=?",(to_state,_now(),mission_id))
        return self.get_mission(mission_id)

    def submit_inspection(self,mission_id,actor_id,payload,actor_role="field_responder",signed_event_id=None):
        if not self.demo_mode: raise PermissionError("operations workflow requires explicit prototype demo mode")
        if actor_role!="field_responder": raise PermissionError("field responder role required")
        if not isinstance(payload,dict): raise ValueError("inspection must be an object")
        with self.lock,self.db:
            m=self.db.execute("SELECT * FROM missions WHERE mission_id=?",(mission_id,)).fetchone()
            if not m: raise KeyError("unknown mission")
            resp=self.db.execute("SELECT * FROM responders WHERE responder_id=? AND squad_id=? AND active=1",(actor_id,m["squad_id"])).fetchone()
            if not resp: raise PermissionError("responder is not an active member of the assigned squad")
            required={"observed_status","observed_at","notes"}
            if not required<=payload.keys() or payload.keys()-required-{"coordinates","evidence","observation_id","signed_event_id"}: raise ValueError("inspection requires observed_status, observed_at, notes and optional coordinates/evidence/observation_id/signed_event_id")
            if payload["observed_status"] not in {"OPEN","CLOSED","WATER","BLOCKAGE","RESTRICTED","UNKNOWN"}: raise ValueError("invalid observed status")
            if not isinstance(payload["notes"],str) or len(payload["notes"])>2000: raise ValueError("notes must be text up to 2000 characters")
            evidence_items=payload.get("evidence",[])
            if not isinstance(evidence_items,list) or len(evidence_items)>10 or any(not isinstance(item,dict) for item in evidence_items): raise ValueError("evidence must be a list of at most 10 metadata objects")
            if payload.get("observation_id") is not None and (not isinstance(payload["observation_id"],str) or not payload["observation_id"] or len(payload["observation_id"])>128): raise ValueError("observation_id must be non-empty text up to 128 characters")
            observed=_time(payload["observed_at"],True); coords=payload.get("coordinates")
            lat=lon=None
            if coords is not None:
                lat,lon=coords.get("lat"),coords.get("lon")
                if any(isinstance(v,bool) or not isinstance(v,(int,float)) or not math.isfinite(v) for v in (lat,lon)) or not -90<=lat<=90 or not -180<=lon<=180: raise ValueError("coordinates invalid")
            signed_event_id=signed_event_id or payload.get("signed_event_id")
            if signed_event_id is not None and (not isinstance(signed_event_id,str) or not signed_event_id): raise ValueError("signed_event_id must be non-empty text")
            oid=payload.get("observation_id") or str(uuid.uuid4()); evidence=json.dumps(evidence_items,sort_keys=True)
            existing=self.db.execute("SELECT * FROM observations WHERE observation_id=?",(oid,)).fetchone()
            if existing:
                same=(existing["mission_id"],existing["crossing_id"],existing["responder_id"],existing["observed_status"],existing["observed_at"],existing["lat"],existing["lon"],existing["notes"],existing["evidence_json"],existing["signed_event_id"])
                expected=(mission_id,m["crossing_id"],actor_id,payload["observed_status"],observed,lat,lon,payload["notes"],evidence,signed_event_id)
                if same!=expected: raise ValueError("observation_id reused with different content")
                return dict(existing)
            if m["state"]!="IN_PROGRESS": raise ValueError("mission must be IN_PROGRESS before submitting inspection")
            now=_now()
            self.db.execute("INSERT INTO observations(observation_id,mission_id,crossing_id,responder_id,observed_status,observed_at,lat,lon,notes,evidence_json,state,submitted_at,signed_event_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",(oid,mission_id,m["crossing_id"],actor_id,payload["observed_status"],observed,lat,lon,payload["notes"],evidence,"SUBMITTED",now,signed_event_id))
            self._event(mission_id,"SUBMITTED",actor_id,actor_role,"inspection:"+oid,{"observation_id":oid})
            self.db.execute("UPDATE missions SET state='SUBMITTED',updated_at=? WHERE mission_id=?",(now,mission_id))
        return self.get_observation(oid)

    def verify_inspection(self,observation_id,decision,actor_role="supervisor",rationale="",actor_id="demo-supervisor-01"):
        if not self.demo_mode: raise PermissionError("operations workflow requires explicit prototype demo mode")
        self._authorize(actor_id,actor_role,"supervisor")
        if decision not in {"VERIFIED","REJECTED"} or not isinstance(rationale,str) or not rationale.strip(): raise ValueError("decision must be VERIFIED or REJECTED with rationale")
        with self.lock,self.db:
            o=self.db.execute("SELECT * FROM observations WHERE observation_id=?",(observation_id,)).fetchone()
            if not o: raise KeyError("unknown observation")
            if o["state"]!="SUBMITTED":
                if o["state"]==decision: return self.get_observation(observation_id)
                raise ValueError("observation already decided")
            now=_now(); self.db.execute("UPDATE observations SET state=?,decision_by=?,decision_at=?,rationale=? WHERE observation_id=?",(decision,actor_id,now,rationale,observation_id))
            self._event(o["mission_id"],decision,actor_id,actor_role,"decision:"+observation_id,{"observation_id":observation_id,"rationale":rationale})
            self.db.execute("UPDATE missions SET state=?,updated_at=? WHERE mission_id=?",(decision,now,o["mission_id"]))
        return self.get_observation(observation_id)

    def record_delivery(self,mission_id,responder_id,receipt_type,idempotency_key,actor_role="field_responder",actor_id=None,received_at=None):
        if not self.demo_mode: raise PermissionError("operations workflow requires explicit prototype demo mode")
        if receipt_type not in {"FORWARDED","DELIVERED","READ","ACKNOWLEDGED"}: raise ValueError("invalid receipt type")
        if receipt_type=="FORWARDED":
            if actor_role!="system" or actor_id!="demo-system": raise PermissionError("only the transport service can record forwarding")
        elif actor_role!="field_responder" or actor_id!=responder_id:
            raise PermissionError("receipt must be recorded by the receiving responder")
        received=_time(received_at,True) if received_at is not None else None
        with self.lock,self.db:
            m=self.db.execute("SELECT * FROM missions WHERE mission_id=?",(mission_id,)).fetchone()
            if not m: raise KeyError("unknown mission")
            if receipt_type in {"DELIVERED","READ","ACKNOWLEDGED"} and not self.db.execute("SELECT 1 FROM responders WHERE responder_id=? AND squad_id=? AND active=1",(responder_id,m["squad_id"])).fetchone(): raise PermissionError("responder is not assigned to mission squad")
            old=self.db.execute("SELECT mission_id,responder_id,receipt_type,received_at FROM delivery_receipts WHERE idempotency_key=?",(idempotency_key,)).fetchone()
            if old:
                received=received or old["received_at"]
                if (old["mission_id"],old["responder_id"],old["receipt_type"],old["received_at"])!=(mission_id,responder_id,receipt_type,received): raise ValueError("receipt idempotency key reused with different content")
            else:
                received=received or _now()
                self.db.execute("INSERT INTO delivery_receipts VALUES(?,?,?,?,?,?)",(str(uuid.uuid4()),mission_id,responder_id,receipt_type,received,idempotency_key))
        return {"mission_id":mission_id,"responder_id":responder_id,"receipt_type":receipt_type,"received_at":received,"is_human_acknowledgement":receipt_type=="ACKNOWLEDGED"}

    def _authorize(self, actor_id, actor_role, required):
        # Competition prototype identity map only; replace with enrolled signed identities before deployment.
        if not self.demo_mode: raise PermissionError("operations workflow requires explicit prototype demo mode")
        allowed = {"demo-commander-01": {"commander"}, "demo-supervisor-01": {"supervisor", "commander"}}
        if actor_role != required or actor_role not in allowed.get(actor_id, set()):
            raise PermissionError(f"authorized {required} demo identity required")

    def get_mission(self,mid):
        row=self.db.execute("SELECT * FROM missions WHERE mission_id=?",(mid,)).fetchone()
        if not row: raise KeyError("unknown mission")
        return {**dict(row),"events":[dict(r) for r in self.db.execute("SELECT event_id,state,actor_id,actor_role,created_at,payload_json FROM task_events WHERE mission_id=? ORDER BY rowid",(mid,))],"receipts":[dict(r) for r in self.db.execute("SELECT * FROM delivery_receipts WHERE mission_id=? ORDER BY rowid",(mid,))]}

    def list_missions(self): return [self.get_mission(r[0]) for r in self.db.execute("SELECT mission_id FROM missions ORDER BY created_at DESC")]

    def list_observations(self): return [dict(r) for r in self.db.execute("SELECT o.*,m.squad_id,m.priority,m.state AS mission_state FROM observations o JOIN missions m USING(mission_id) ORDER BY o.submitted_at DESC")]

    def get_observation(self,oid):
        row=self.db.execute("SELECT * FROM observations WHERE observation_id=?",(oid,)).fetchone()
        if not row: raise KeyError("unknown observation")
        return dict(row)

    def current_crossing_status(self,base_statuses=None):
        out=dict(base_statuses or {})
        for r in self.db.execute("SELECT crossing_id,observed_status FROM observations WHERE state='VERIFIED' ORDER BY decision_at"):
            out[r["crossing_id"]]={"OPEN":"VERIFIED_OPEN","CLOSED":"VERIFIED_CLOSED","WATER":"VERIFIED_CLOSED","BLOCKAGE":"VERIFIED_CLOSED","RESTRICTED":"RESTRICTED","UNKNOWN":"UNKNOWN"}[r["observed_status"]]
        return out
