"""Verified viewport queries for the compressed OSM Telangana display map."""
from __future__ import annotations

import gzip
import hashlib
import json
import math
import sqlite3
import struct
import tempfile
import threading
from pathlib import Path

GRID = 0.1


def _cell(value: float) -> int:
    return math.floor(value / GRID)


class OfflineMap:
    def __init__(self, archive: Path, metadata_file: Path, cache_dir: Path | None = None):
        self.archive = archive
        self.metadata = json.loads(metadata_file.read_text(encoding="utf-8"))
        self.cache_dir = cache_dir or (Path(tempfile.gettempdir()) / "suraksha-kavach-map")
        self._lock = threading.Lock()
        self._database: Path | None = None

    @staticmethod
    def _hash(path: Path) -> str:
        digest = hashlib.sha256()
        with path.open("rb") as source:
            for block in iter(lambda: source.read(1024 * 1024), b""):
                digest.update(block)
        return digest.hexdigest()

    def _db_path(self) -> Path:
        if self._database and self._database.is_file():
            return self._database
        with self._lock:
            if self._database and self._database.is_file():
                return self._database
            expected_archive = self.metadata["archive_sha256"]
            expected_db = self.metadata["database_sha256"]
            if self._hash(self.archive) != expected_archive:
                raise ValueError("offline map archive failed integrity verification")
            self.cache_dir.mkdir(parents=True, exist_ok=True)
            target = self.cache_dir / f"{expected_db}.sqlite"
            if not target.is_file() or target.stat().st_size != self.metadata["database_bytes"] or self._hash(target) != expected_db:
                temp = target.with_suffix(".tmp")
                digest = hashlib.sha256()
                written = 0
                with gzip.open(self.archive, "rb") as source, temp.open("wb") as output:
                    for block in iter(lambda: source.read(1024 * 1024), b""):
                        output.write(block)
                        digest.update(block)
                        written += len(block)
                if written != self.metadata["database_bytes"] or digest.hexdigest() != expected_db:
                    temp.unlink(missing_ok=True)
                    raise ValueError("offline map database failed integrity verification")
                temp.replace(target)
            self._database = target
            return target

    @staticmethod
    def _points(blob: bytes) -> list[list[float]]:
        return [[lon, lat] for lat, lon in struct.iter_unpack("<ff", blob)]

    def query(self, west: float, south: float, east: float, north: float, zoom: int) -> dict:
        db = sqlite3.connect(f"{self._db_path().as_uri()}?mode=ro", uri=True)
        try:
            x0, x1, y0, y1 = _cell(west), _cell(east), _cell(south), _cell(north)
            roads = [{
                "kind": row[1], "name": row[2], "in_state": bool(row[3]), "coords": self._points(row[4]),
            } for row in db.execute(
                "SELECT r.id,r.highway,r.name,r.in_state,r.geometry FROM roads r WHERE r.min_zoom<=? "
                "AND r.id IN (SELECT item_id FROM spatial_cells WHERE layer='road' AND min_zoom BETWEEN 6 AND ? "
                "AND cell_x BETWEEN ? AND ? AND cell_y BETWEEN ? AND ?) "
                "ORDER BY r.min_zoom LIMIT 15000", (zoom, zoom, x0, x1, y0, y1))]
            water = [{"kind": row[0], "name": row[1], "coords": self._points(row[2])}
                     for row in db.execute(
                "SELECT w.kind,w.name,w.geometry FROM waterways w WHERE w.id IN (SELECT item_id FROM spatial_cells "
                "WHERE layer='water' AND min_zoom=0 AND cell_x BETWEEN ? AND ? AND cell_y BETWEEN ? AND ?) "
                "LIMIT 8000", (x0, x1, y0, y1))]
            places = [{"kind": row[0], "name": row[1], "lat": row[2], "lon": row[3]}
                      for row in db.execute(
                "SELECT kind,name,lat,lon FROM places WHERE min_zoom<=? AND lat BETWEEN ? AND ? "
                "AND lon BETWEEN ? AND ? LIMIT 1200", (zoom, south, north, west, east))]
            boundaries = [{"level": row[0], "name": row[1], "coords": self._points(row[2])}
                          for row in db.execute(
                "SELECT b.admin_level,b.name,b.geometry FROM boundaries b WHERE b.min_zoom<=? "
                "AND b.id IN (SELECT item_id FROM spatial_cells WHERE layer='boundary' AND min_zoom BETWEEN 6 AND ? "
                "AND cell_x BETWEEN ? AND ? AND cell_y BETWEEN ? AND ?)", (zoom, zoom, x0, x1, y0, y1))]
            return {"source": "OpenStreetMap contributors · ODbL", "roads": roads,
                    "waterways": water, "places": places, "boundaries": boundaries}
        finally:
            db.close()
