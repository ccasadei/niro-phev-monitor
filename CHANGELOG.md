# Changelog

## [1.2.0](https://github.com/ccasadei/niro-phev-monitor/compare/v1.1.0...v1.2.0) (2026-11-09)


### Novità

* azzeramento del viaggio dalla pagina Viaggio con doppio tocco sull'intestazione ([771a59a](https://github.com/ccasadei/niro-phev-monitor/commit/771a59a3aa4342e57c663d9c5c2f9d0f249b89e3))
* log eventi persistente in files/events.log ([771a59a](https://github.com/ccasadei/niro-phev-monitor/commit/771a59a3aa4342e57c663d9c5c2f9d0f249b89e3))
* pagina Viaggio su Android Auto con km, kWh, benzina e litri risparmiati grazie all'EV ([771a59a](https://github.com/ccasadei/niro-phev-monitor/commit/771a59a3aa4342e57c663d9c5c2f9d0f249b89e3))
* totalizzatore nella pagina Viaggio e medie di consumo misurate ([#4](https://github.com/ccasadei/niro-phev-monitor/issues/4)) ([e9c77e5](https://github.com/ccasadei/niro-phev-monitor/commit/e9c77e59d9bcb62c67b414a80a096174d1e3e0ae))


### Correzioni

* il viaggio non si chiude più allo scollegamento di Android Auto e una sosta breve non lo spezza ([771a59a](https://github.com/ccasadei/niro-phev-monitor/commit/771a59a3aa4342e57c663d9c5c2f9d0f249b89e3))

## [1.1.0](https://github.com/ccasadei/niro-phev-monitor/compare/v1.0.0...v1.1.0) (2026-11-01)


### Novità

* interruttore per il rendering GPU su Android Auto (sperimentale) ([2c887f2](https://github.com/ccasadei/niro-phev-monitor/commit/2c887f2c821e382e825fbd41172d7f889b9588fb))
* registro viaggi con avvio automatico del polling all'avvio di Android Auto ([7b57638](https://github.com/ccasadei/niro-phev-monitor/commit/7b57638b85674864ed0f04ad4621c97a9dd085aa))


### Correzioni

* registro viaggi leggibile anche su schermi bassi ([c207248](https://github.com/ccasadei/niro-phev-monitor/commit/c207248cd1f1037c1fd481e7e64a52244ffdc6d7))

## 1.0.0 (2026-10-29)

Prima versione pubblica di Niro Monitor: telemetria della Kia Niro SG2 PHEV via dongle ELM327 Bluetooth, mostrata su Android Auto e sul telefono.

### Novità

* Dashboard Android Auto a tutto schermo con tre pagine: 6 gauge, 96 celle HV e cockpit con curva di potenza.
* Polling live di BMS (SoC, potenza, temperature, celle, 12V, SOH), motore 1.6 GDi (giri, velocità, temperatura, consumo) e odometro.
* Riconnessione automatica al dongle, con watchdog su adattatore e BMS e indicatore di dati non live.
* Scanner UDS per mappare i PID delle centraline, con esportazione JSON.
* Simulatore demo per provare l'interfaccia senza dongle.
