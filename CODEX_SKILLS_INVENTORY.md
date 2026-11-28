# Codex Skills Inventory

Inventory for this work in the Codex desktop environment, 28 November 2026. Skill sources were the available-skill catalog and local `SKILL.md` files; no project-local `AGENTS.md` or `.agents/skills/` instructions were found.

| Skill | Location | Purpose | Used | Workstream |
|---|---|---|---|---|
| `ponytail` | `C:/Users/chatu/.codex/skills/ponytail/SKILL.md` | Smallest complete, tested code change | Yes; full mode principles | Implementation |
| `ponytail-audit` | `C:/Users/chatu/.codex/skills/ponytail-audit/SKILL.md` | Evidence-based repository audit | Yes; instructions read, findings assembled with specialist reviews | Audit |
| `ponytail-review` | `C:/Users/chatu/.codex/skills/ponytail-review/SKILL.md` | Review changed code and callers | Yes; instructions read, final review performed before reporting | QA |
| `computer-use:computer-use` | Bundled computer-use plugin skill | Native UI inspection | Not used; ADB screenshot capture and source inspection were sufficient for this pass | Not applicable |
| `web-perf` | User skill catalog | Website performance profiling | Not used; no web performance changes requested in the scoped fix | Not applicable |
| `cloudflare`, `frameworks-on-cloudflare`, `wrangler`, `cf-cli` | User skill catalog | Cloudflare deployment or Workers development | Not used; deployment was not part of the code fix and current dashboard requires a FastAPI backend | Not applicable |

No dedicated Android/Kotlin, Compose, GIS, PostGIS, API-security or geospatial skills were available in the catalog shown for this session. The project already supplies those technologies and tests; established repository practices and specialist audits were used instead. No third-party skill was installed.

## Specialist reviews

- Android/map UI preservation audit: `FRONTEND_PRESERVATION_SPEC.md`.
- GIS/graph review: `GIS_AUDIT.md`; reviewer also implemented and tested the Python direction fix.
- Backend/security review: `BACKEND_SECURITY_AUDIT.md`.
- Independent Android review of the Kotlin direction fix and precomputed-score behavior.

The environment exposed a maximum of three concurrent agents including the orchestrator, so the four requested workstreams ran as three specialist audits plus the primary implementation/review; a fourth Android specialist review ran after a slot opened.
