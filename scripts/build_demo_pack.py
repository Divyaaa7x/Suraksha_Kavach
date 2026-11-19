"""Build the SYNTHETIC demo pack: python scripts/build_demo_pack.py"""
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "server"))

from kavach.fixtures import DEMO_POLICY, SYNTHETIC_LICENSE, golden_graph  # noqa: E402
from kavach.pack import build_pack  # noqa: E402

if __name__ == "__main__":
    out = build_pack(ROOT / "data" / "packs" / "kavach-demo-synthetic", golden_graph(), DEMO_POLICY,
                     pack_version="DEMO-SYN-001", licenses_text=SYNTHETIC_LICENSE)
    print(f"built {out}")
