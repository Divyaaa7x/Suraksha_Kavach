# Frontend Preservation Specification

Audit scope: Android Compose UI and the coordinator dashboard in `server/static/index.html`, checked against source at commit `1c8ae9e` and the Pixel 8a Atlas install (`org.kavach.atlas`, v0.1.0). This document records the existing interface as a preservation baseline; it does not authorize a redesign. A source-built 0.2.0 APK was not installed during this audit because its signing/package identity did not match the phone install.

## Android screen map

`AtlasHome.kt` is the map-first root. It holds the full-screen map, floating map controls, search, and an `AtlasSheet` with three detents. Its page state covers Browse, Place, Directions, Report, Field status, Analysis, Sync, Audit, and Regions. Other Compose surfaces live in `Screens.kt`, `DirectionsPanel.kt`, `IndiaOverviewMap.kt`, `TelanganaOfflineMap.kt`, and `IndiaOnline.kt`.

| Area | Current UI / behavior evidenced in source | Preserve when extending |
|---|---|---|
| Browse / map | MapLibre vector style when internet is validated or India tiles are saved; otherwise local Telangana canvas map. Floating compass, map/location controls, search capsule, weather card, crossing/status layers, selected-place pin and route overlay. | Full-bleed map, controls floating over it, current map palettes and camera behavior. |
| Search / place | Offline graph places and crossing matching, India-wide Photon search online, recent selections, place detail card, map focus/pin and crossing selection. | Search capsule, selected place sheet and its focus-on-map relationship. |
| Directions | `DirectionsPanel.kt`; pilot and optional statewide mode, vehicle and strict/warning policy inputs, result summary, route highlight and camera preview. | Existing map-route visual language, animated route reveal, sheet-driven workflow. |
| Report / field status | Compose field reporting and crossing status content in `Screens.kt`; report state and map-selected crossing are surfaced through the view model. | Keep report/status distinct from authoritative verification; preserve current fields and state labels. |
| Analysis | Existing graph-derived isolation and inspection analysis screen. | Keep explanations tied to the underlying graph and unknown crossing policy. |
| Sync / Nearby | LAN hub sync and Nearby device connection UI, pairing confirmation and peer acceptance controls in `Screens.kt`. | Preserve explicit peer confirmation and visible connection/transfer state. |
| Audit | Local event list with kind, asset, timestamp and signing key display. | Keep events legible and chronological/context visible. |
| Regions | India overview and offline India-region download status in `IndiaOnline.kt`; existing offline Telangana map remains separate. | Download progress/error/completion states, attribution and region selection semantics. |

## Existing visual language and motion

- Android typography: bundled Inter family and Compose Material typography mapping in `AtlasTheme.kt`.
- Atlas uses custom glass panels sampled from a blurred map snapshot (`Glass.kt`): desaturated/boosted backdrop, tint, top wash and fine rim. `pressable` uses a spring press scale (0.9) rather than default ripple-only feedback.
- Map screen uses rounded translucent controls, a bright blue primary Kavach button, white map labels with dark outlines, a dark navy/green map palette, and a compact weather/AQI glass card. Captured Pixel screenshot confirms those components and approximate positions at 1080×2400; this is a visual snapshot, not a complete component measurement export.
- `AtlasSheet.kt` has PEEK/HALF/FULL detents, nested scrolling, velocity-aware settle selection and spring settling (damping 0.86, stiffness 380). Keep its drag/scroll arbitration intact.
- `AtlasMap.kt` animates camera focus, route bounds, map tilt and route preview. Route rendering is progressively revealed over 30 frames; preview tracks route points with tilt/bearing.
- `TelanganaOfflineMap.kt` implements local canvas map gestures and animated zoom. Do not replace it with a network-only map.
- Theme-aware day/night map styles and system dark-mode selection are present. The verified Pixel snapshot was dark-style/system state; a complete light/dark visual regression comparison was not performed.

## Website dashboard baseline

The website is a single static HTML/CSS/JavaScript dashboard (`server/static/index.html`) paired with the FastAPI hub. It has no separate React/TypeScript frontend module. The existing visual model is system UI typography, responsive two-column workspace collapsing at 800px, light/dark CSS variables, white/dark cards, thin borders, 10px card corners, status colors, compact tables and accessible focus outlines.

Current major regions: hub connection/status and optional tab-scoped bearer-token entry; collapsible India boundary reference map/state selector; Telangana offline canvas map with zoom/fit controls, attribution, crossing picker, map search and selection card; route comparison with pilot/statewide selection, origin/destination, vehicle class and policy; isolated settlements and crossing inspection-priority tables; coordinator field-report review and rationale/decision actions. Canvas map has keyboard panning/zoom/fit guidance and accessibility labeling. Dashboard controls have small press/hover transitions and a reduced-motion override.

Preserve the dashboard's existing map/canvas and status semantics when adding mission and squad information. The HTML itself explicitly marks scenario overlays and optional approval as demo-only and says the synthetic approval key is not authentication. Do not visually turn those demo paths into authoritative operational controls.

## Baseline evidence and limits

- Source inspection: Android UI package files named above; dashboard HTML/CSS/inline JS.
- Pixel visual baseline captured in this audit: `../pixel-comparison/atlas-current-review.png` relative to the canonical checkout (India map view with weather card, compass, location/map control capsule and search sheet peek). This is a single frame from the already-installed v0.1.0 Atlas app.
- Device did not provide a stable screenshot for every subsequent interaction during this pass. No Regions sheet or search flow was fully verified visually. Do not treat a source-defined screen as a device-tested screen.
- No Compose UI test suite or recorded screenshot-baseline harness was found in the current Android project. Unit tests cover some UI helpers (sheet detents, map/blur helpers), but they do not prove rendered layout or complete interaction flows.
- The Pixel screenshot is a baseline for preservation only. It cannot prove color/dimension parity on other density, font-scale, theme, or accessibility settings.

## Preservation checklist for changes

1. Keep `AtlasHome` as the app's map-first entry and route new actions through the existing sheet/page model unless a clear tested need requires otherwise.
2. Reuse `AtlasSheet`, existing glass surfaces, map controls, type styles and status palette; do not introduce a second component system.
3. Do not change map style URIs, map colors, camera defaults, marker sizing, sheet detents, route reveal/preview timing or press spring as incidental refactoring.
4. Keep offline canvas functionality and clear map attribution; offline UI must remain usable without tile services.
5. Maintain explicit unknown/unverified status treatment. Do not make demo data or a transmitted message look like verified field evidence.
6. For Android UI edits, capture the same Pixel screen at the same resolution and compare geometry, typography, colors, map camera and overlay positions. Add interaction captures for each altered sheet/control; report any device-unavailable checks separately.
7. For dashboard edits, compare desktop and <=800px layouts, keyboard focus, reduced-motion mode, light/dark styles, map controls, and empty/loading/error states.

## Observed risks (not design-change authorization)

- The root's visual style is polished, but the installed starting camera presents a broad India extent; it does not immediately foreground Telangana response context.
- Online basemap appearance depends on external style/tiles/terrain availability. Network fallback exists in source, but offline region download and offline cold-start were not verified in this session.
- Search and mapping depend on external Photon/OpenFreeMap/Open-Meteo services while online; the dashboard map is local/offline canvas. New mission UI should show provenance/connectivity without implying these sources are always available.
- Map overlay and sheet cover relationship is dynamic via bottom padding; changes to sheet heights can obscure route extents or focus targets.
- Android's app code has a legacy `Screens.kt` set as well as the newer Atlas map-first shell. Audit actual navigation references before changing or deleting either surface; unused-looking UI may still be reachable through the sheet.
- Current website is a dashboard shell and hub client, not a standalone complete hosted service. Keep that capability boundary clear in labels and visual hierarchy.
