# EV-Telemetry plugin

OsmAnd plugin for DIY electric vehicles: live BLE telemetry from **JBD** and **ANT BMS**, **FarDriver** / **VESC** controllers, and **CSC** wheel/cadence sensors; remaining-range estimation, voice alerts, trip CSV/GPX, charge history.

## Documentation

| Document | Contents |
|---|---|
| [`doc/EV-Telemetry.md`](doc/EV-Telemetry.md) | All telemetry fields, sources, rates, formulas |
| [`doc/CalculationInfo.md`](doc/CalculationInfo.md) | Distance, energy, remaining range methodology |
| [`doc/EnergyConsumptionCalculations.md`](doc/EnergyConsumptionCalculations.md) | 22 Aug 2026: trip Wh vs next charge, double-count, range, controller current |
| [`doc/BMS_DATA_ABOUT.md`](doc/BMS_DATA_ABOUT.md) | JBD / ANT BLE protocol and session |
| [`doc/README-ANALOG.md`](doc/README-ANALOG.md) | Comparison with R-Speedo, Locus 4.35, upstream PR notes |

## Code layout

Kotlin sources in this directory; UI resources under `OsmAnd/res/` (`ev_bms_*`, `values/strings.xml`).
