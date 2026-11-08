# Niro PHEV Monitor

Telemetria **non ufficiale** per la **Kia Niro SG2 PHEV (2024)** su **Android Auto**, letta dalla presa OBD-II con un dongle ELM327 Bluetooth.

> **In English** — Unofficial telemetry app for the Kia Niro SG2 PHEV (2024). It reads the car's diagnostic data (HV battery, cells, engine, odometer) through a Bluetooth ELM327 OBD-II dongle and shows it full-screen on Android Auto, with an automatic trip log of distance, battery energy and estimated fuel. Documentation and UI are in Italian. If you find it useful, you can support it via [PayPal](https://paypal.me/ccasadei74).

> [!IMPORTANT]
> Progetto personale e **non ufficiale**, non affiliato né approvato da Kia. "Kia" e "Niro" sono marchi dei rispettivi proprietari. L'app legge soltanto dati diagnostici e non scrive nulla sulle centraline, ma la usi a tuo rischio. Non interagire con l'app mentre guidi.

## Funzionalità

* **Dashboard Android Auto a tutto schermo**, quattro pagine che si cambiano con un tocco o uno swipe:
  * **6 Gauge**: SoC della batteria HV con autonomia EV stimata, potenza HV, temperatura della batteria, motore 1.6 GDi, consumo e marcia, batteria 12V;
  * **96 Celle**: tensioni delle 96 celle (min/max, sbilanciamento, istogramma), stato di salute (SOH), temperature dei moduli, energia cumulativa;
  * **Cockpit**: potenza istantanea con curva dell'ultimo minuto e temperature dei 4 moduli;
  * **Viaggio**: km percorsi (quanti in elettrico), kWh netti della batteria, benzina stimata e litri risparmiati grazie all'EV per il viaggio in corso o l'ultimo concluso.
* **Avvio automatico**: il collegamento al dongle parte quando Android Auto si collega all'auto, anche senza aprire l'app sul display, e si ferma quando si scollega.
* **Registro viaggi**: per ogni viaggio km (anche in EV puro), kWh erogati, recuperati e netti dalla batteria, benzina stimata, litri risparmiati rispetto alla guida solo in ibrido (riferimento 5,0 L/100 km), SoC iniziale e finale. Una sosta di pochi minuti non spezza il viaggio. Mostra il totale del giorno ed esporta in CSV.
* **Indicatore di dati non live**: il footer avvisa quando i valori non arrivano dall'auto o sono fermi da più di 5 secondi.
* **Scanner UDS** per esplorare le centraline ed esportare le risposte grezze in JSON.
* **Simulatore demo** per provare l'interfaccia senza auto né dongle.
* **Rendering GPU sperimentale** (spento di default), attivabile dall'app sul telefono.

## Requisiti

* **Auto**: Kia Niro SG2 PHEV 2024 (batteria 11,1 kWh, 96 celle). Altri modelli Hyundai/Kia possono rispondere in parte, ma gli offset dei dati sono calibrati su questa.
* **Dongle OBD-II**: ELM327 **Bluetooth Classic** (profilo SPP), ad esempio Vgate vLinker. Va associato al telefono; l'app lo riconosce dal nome (`Vlink`, `OBD`, `ELM`, `Vgate`, `Konnwei`, ...).
* **Telefono**: Android 10 o successivo, con Android Auto.

## Precisione dei dati

* **Energia della batteria**: dai contatori cumulativi del BMS, con risoluzione 0,1 kWh. È il dato più affidabile del registro viaggi.
* **Distanza**: dall'odometro del cruscotto per i viaggi da 10 km in su (risoluzione 1 km), altrimenti integrando la velocità.
* **Benzina**: **stima** calcolata dal flusso d'aria del motore (MAF) assumendo una miscela stechiometrica. Per i consumi ufficiali fa fede il computer di bordo.

## Compilazione

Servono JDK 17, Android SDK (compileSdk 34) e Gradle 8.9: il repository non include il Gradle wrapper.

```bash
export JAVA_HOME=/percorso/jdk-17
export ANDROID_HOME=/percorso/android-sdk   # in alternativa sdk.dir in local.properties
gradle assembleDebug                        # APK in app/build/outputs/apk/debug/
gradle testDebugUnitTest                    # test JVM, nessun dispositivo necessario
```

## Installazione

L'app sul telefono si installa come un normale APK. Android Auto però mostra solo le app di navigazione e "template" installate dal Play Store, anche con le opzioni sviluppatore attive: la procedura usata in sviluppo è descritta in [`AGENTS.md`](AGENTS.md) (§2).

Al primo avvio concedi i permessi Bluetooth e scegli il dongle. In auto non serve altro: il registro viaggi si compila da solo.

## Documentazione tecnica

[`AGENTS.md`](AGENTS.md) raccoglie le conoscenze del progetto:
* gli identificativi UDS/OBD e gli offset dei dati della Niro SG2 PHEV, verificati su catture reali;
* l'architettura del polling (riconnessione automatica, watchdog, validazione delle risposte ISO-TP);
* il registro viaggi e il rendering su Android Auto.

## Contribuire

* Le modifiche arrivano su `main` tramite pull request unite in **squash**.
* Il titolo della PR segue i [Conventional Commits](https://www.conventionalcommits.org/it/) (`feat:`, `fix:`, `perf:`, `docs:`, ...).
* [release-please](https://github.com/googleapis/release-please) calcola da lì versione, `CHANGELOG.md`, tag e GitHub Release.

## Sostieni il progetto

Niro PHEV Monitor è gratuito e sviluppato nel tempo libero. Se ti è utile, puoi offrirmi qualcosa con PayPal:

* [☕ Un caffè — 3 €](https://paypal.me/ccasadei74/3EUR)
* [🍕 Una pizza — 10 €](https://paypal.me/ccasadei74/10EUR)
* [💚 Importo libero](https://paypal.me/ccasadei74)

Gli importi sono solo suggerimenti: puoi sempre cambiarli prima di confermare. Grazie!

## Autore

Made with ❤️ by Cristiano Casadei.
