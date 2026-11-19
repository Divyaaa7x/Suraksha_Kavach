# Demo script (dossier section 19), SYNTHETIC DATA ONLY

Run every step with mobile data and Wi-Fi internet OFF. Say "synthetic" when you show each screen.

Setup (once, with internet, before the demo):
1. Build the synthetic pack: `python scripts/build_demo_pack.py`
2. Install the APK on two Android phones (minSdk 26). Grant location and nearby permissions.
3. Start the hub on the laptop: `python -m uvicorn kavach.api:create_app --factory --app-dir server --port 8000`
4. Put phones and laptop on the same hotspot. Note the laptop's address (e.g. 192.168.137.1).

| # | Step | What to show | Expected |
|---|------|--------------|----------|
| 1 | Airplane mode, cold start | Home screen | Pack version, pack age, SYNTHETIC banner, crossing statuses. Works with no network. |
| 2 | Flood scenario | Routes tab: scenario overlay `C2,C3` | C2 and C3 show PREDICTED_RISK. Warning only; nothing is auto-closed. |
| 3 | Isolation | Analysis tab | Village C is cut off if C4 is unavailable. Population is labelled as an estimate. |
| 4 | Compare routes | Routes: A → H, strict vs baseline | Baseline takes C1 (unknown). The constrained route excludes C1 with a reason. |
| 5 | Field report on phone 2 | Get GPS fix, save C1 OPEN | Saved as a signed claim, not a verified fact. Audit shows "pending sync". |
| 6 | Phone-to-phone | Sync tab: Start on both phones, confirm the 4-digit codes match, Connect | Events move; each phone shows "stored N". The sender marks events synced only after the ACK. |
| 7 | Approve on hub | `python scripts/demo_client.py approve C1 VERIFIED_OPEN "synthetic check"` | Route recomputes on the hub. Audit trail shows the approval. |
| 8 | Contradiction | `python scripts/demo_client.py observe C1 CLOSED` | C1 shows "conflicting reports". It stays VERIFIED_OPEN; the conflict is listed, not auto-resolved. |
| 9 | Restore hub | Hotspot on, tap "Sync with hub now" | Phone pulls the approval and pushes its own reports. No duplicates. |
| 10 | Metrics | `docs/test-report.md` | Show only measured numbers. Mark anything not measured on a real phone as pending. |

Talking points: every decision needs a human approval; the phone never labels a road safe; a report is a claim until approved.
