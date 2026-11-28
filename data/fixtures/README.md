# Archived warning fixtures

`imd-2024-kamareddy-archived-warning.json` is a historical, district-level heavy-to-very-heavy rainfall warning transcribed from the India Meteorological Department (IMD) Monsoon Report 2024, section 9.3(a), Day 1 / figure 9.8. The report identifies Kamareddy and gives the validity window as 31 August 2024 08:30 IST to 1 September 2024 08:30 IST. The source does not publish a machine alert ID, so the fixture records `NOT_PUBLISHED_IN_SOURCE` rather than fabricating one.

The district boundary comes from the Survey of India GeoSOI/IUDX `district_boundaries` item `KAMAREDDY` (district LGD 685). It is simplified at 0.0002 degrees to keep the fixture compact. The polygon represents the district-level warning coverage only; it is not a rainfall footprint or flood-inundation polygon. Exposure means the road geometry lies within the named warning district. It does not mean a crossing is flooded, damaged, or closed.

Sources:

- IMD, *Monsoon Report 2024*, [official PDF](https://mausam.imd.gov.in/imd_latest/monsoonreport2024.pdf).
- Survey of India GeoSOI/IUDX, [KAMAREDDY district boundary item](https://geo-soi.iudx.io/collections/district_boundaries/items/KAMAREDDY?f=json), served in CRS84 GeoJSON. The service metadata identifies Survey of India as the source. Preserve this attribution and verify current reuse terms before redistributing outside this prototype.

This is an archived replay fixture. It is never presented as a current/live warning.
