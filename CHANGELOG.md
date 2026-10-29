# Changelog

## 1.0.0 (2026-10-29)

Prima versione pubblica di Niro Monitor: telemetria della Kia Niro SG2 PHEV via dongle ELM327 Bluetooth, mostrata su Android Auto e sul telefono.

### Novità

* Dashboard Android Auto a tutto schermo con tre pagine: 6 gauge, 96 celle HV e cockpit con curva di potenza.
* Polling live di BMS (SoC, potenza, temperature, celle, 12V, SOH), motore 1.6 GDi (giri, velocità, temperatura, consumo) e odometro.
* Riconnessione automatica al dongle, con watchdog su adattatore e BMS e indicatore di dati non live.
* Scanner UDS per mappare i PID delle centraline, con esportazione JSON.
* Simulatore demo per provare l'interfaccia senza dongle.
