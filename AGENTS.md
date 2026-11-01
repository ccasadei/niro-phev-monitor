# AGENTS.md — Niro Monitor (Kia Niro SG2 PHEV 2024 & Android Auto)

Questo documento raccoglie tutte le conoscenze tecniche, le procedure di installazione per Android Auto, i dettagli del protocollo UDS/CAN della **Kia Niro SG2 PHEV (2024)** e la configurazione dell'ambiente di sviluppo emersi durante lo sviluppo e la calibrazione sul veicolo reale.

---

## 1. Ambiente di Sviluppo

Requisiti: JDK 17, Android SDK (compileSdk 34) e Gradle 8.9 (il repository non include il Gradle wrapper). Le impostazioni della macchina locale (percorsi della toolchain, dispositivi di test) vanno in `CLAUDE.local.md` e `local.properties`, entrambi esclusi da git.

* **Package**: `com.casadei.nirowatchdog`
* **Interfaccia OBD-II**: dongle ELM327 Bluetooth Classic (es. Vgate vLinker / "Android-Vlink"), profilo SPP UUID `00001101-0000-1000-8000-00805F9B34FB`, da associare al telefono prima dell'uso.

### Comando di Build
```bash
export JAVA_HOME=/percorso/jdk-17
export ANDROID_HOME=/percorso/android-sdk   # in alternativa sdk.dir in local.properties
gradle assembleDebug                        # Gradle 8.9, dalla radice del repository
```

### Commit e Release
I commit su `main` seguono i [Conventional Commits](https://www.conventionalcommits.org/it/) (`feat:`, `fix:`, `perf:`, ...): si lavora con PR unite in squash, il cui titolo diventa il messaggio del commit. Il workflow `release-please` apre una "release PR" con versione semantica e `CHANGELOG.md` aggiornati; unendola si creano tag `vX.Y.Z` e GitHub Release. La versione è anche in `versionName` di `app/build.gradle.kts` (riga marcata `x-release-please-version`).

---

## 2. CRITICO: Installazione per Android Auto (`CAR.VALIDATOR` su Android 11+ / AA 17.9+)

### Il Problema
Su Android 11+ (API 30+) con Android Auto 17.9+, il validatore interno di Android Auto (`CAR.VALIDATOR` / `CarProjectionValidator`) rifiuta le app che usano `androidx.car.app.CarAppService` (categoria `TEMPLATE`, come `androidx.car.app.category.IOT` o `NAVIGATION`) se vengono installate normalmente tramite `adb install` o `adb install -i com.android.vending`, **anche quando "Origini sconosciute" (`allow_unknown_sources`) è abilitato nelle Opzioni Sviluppatore di Android Auto**.

Perché succede:
1. Il flag `allow_unknown_sources` di Android Auto copre solo alcune categorie legacy (es. `MEDIA` / `MESSAGING`), ma **non** la categoria `TEMPLATE` (`CarAppService`).
2. Per le app `TEMPLATE`, `CAR.VALIDATOR` interroga `PackageManager.getInstallSourceInfo(pkg)`:
   * Legge prima **`getInitiatingPackageName()`** (`initiatingPackageName`).
   * **Solo se** `initiatingPackageName` è uguale a `"com.google.android.packageinstaller"`, allora legge **`getInstallingPackageName()`** (`installerPackageName`).
   * Infine verifica che il valore risultante sia `"com.android.vending"` (Google Play Store).
3. Quando si usa `adb install -i com.android.vending`, Android imposta `initiatingPackageName = "com.android.shell"`. Di conseguenza `CAR.VALIDATOR` ignora `installerPackageName` e blocca l'app con:
   `W CAR.VALIDATOR: Package DENIED; failed all other checks [com.casadei.nirowatchdog]`.

### La Soluzione (Installazione tramite `InstallStart` + MediaStore URI)
Per far sì che Android registri contemporaneamente:
* `initiatingPackageName = com.google.android.packageinstaller`
* `installerPackageName = com.android.vending`

è necessario far creare la sessione `PackageInstaller` direttamente a `com.google.android.packageinstaller`, avviando la sua activity `InstallStart` da `adb shell` (`UID 2000`, che possiede il permesso di sistema `INSTALL_PACKAGES`) con i flag `ORIGINATING_UID=2000`, `NOT_UNKNOWN_SOURCE=true` e `INSTALLER_PACKAGE_NAME=com.android.vending`:

```bash
# Dalla radice del repository
ADB=adb   # oppure $ANDROID_HOME/platform-tools/adb
APK=app/build/outputs/apk/debug/app-debug.apk

# 1. Copia l'APK in Download con nome univoco (per ottenere un nuovo _id MediaStore) e indicizzalo da UID 2000
FNAME="niro-monitor-$(date +%s).apk"
$ADB shell am force-stop com.google.android.packageinstaller
$ADB push "$APK" "/sdcard/Download/$FNAME"
$ADB shell content call --uri content://media --method scan_file --arg "/storage/emulated/0/Download/$FNAME"
ID=$($ADB shell content query --uri content://media/external/downloads --projection _id:_display_name \
  | grep "$FNAME" | head -n 1 | sed -E 's/.*_id=([0-9]+),.*/\1/' | tr -d '\r')

# 2. Avvia InstallStart passando i parametri di origine attendibile e installer Play Store
$ADB shell am start -a android.intent.action.VIEW \
  -d "content://media/external/downloads/$ID" \
  -t "application/vnd.android.package-archive" \
  --grant-read-uri-permission \
  --ei android.intent.extra.ORIGINATING_UID 2000 \
  --ez android.intent.extra.NOT_UNKNOWN_SOURCE true \
  --es android.intent.extra.INSTALLER_PACKAGE_NAME com.android.vending \
  -n com.google.android.packageinstaller/com.android.packageinstaller.InstallStart
```

3. **Conferma sul telefono**: a schermo sbloccato appare il popup di sistema *"Vuoi installare/aggiornare questa app?"*. Toccando **Installa/Aggiorna** (oppure inviando da tastiera `KEYCODE_TAB` + `KEYCODE_DPAD_RIGHT` + `KEYCODE_ENTER`), l'app viene installata da `com.google.android.packageinstaller` con `installerPackageName=com.android.vending`.

4. **Verifica dell'installazione**:
```bash
$ADB shell dumpsys package com.casadei.nirowatchdog | grep -E "installerPackageName|initiatingPackageName"
```
Deve restituire:
```text
installerPackageName=com.android.vending
initiatingPackageName=com.google.android.packageinstaller
```

5. **Concessione permessi e refresh di Android Auto**:
```bash
$ADB shell pm grant com.casadei.nirowatchdog android.permission.BLUETOOTH_CONNECT
$ADB shell pm grant com.casadei.nirowatchdog android.permission.BLUETOOTH_SCAN
$ADB shell pm grant com.casadei.nirowatchdog android.permission.ACCESS_FINE_LOCATION
$ADB shell am force-stop com.google.android.projection.gearhead
```

---

## 3. Telemetria UDS / OBD-II — Kia Niro SG2 PHEV (2024)

Gli offset sono stati ricavati e verificati su catture reali del bus CAN della **Kia Niro SG2 PHEV 2024** (risposte ELM327 grezze, non incluse nel repository) a diversi livelli di carica: BMS 29.0%, 67.5% e 85.5% con PTC attivo. Sono implementati in [`Sg2PidParser.kt`](app/src/main/java/com/casadei/nirowatchdog/obd/Sg2PidParser.kt); i test in `app/src/test/` usano alcune di quelle risposte come fixture, con odometro e contatori cumulativi sostituiti da valori sintetici (vedi §5). Gli indici `d[n]` contano i byte **dopo** l'eco `62 01 0x`.

### Scoperte Chiave sul Protocollo UDS della Niro SG2 PHEV 2024
1. **Raffreddamento Batteria HV (PHEV 11.1 kWh) e PTC Heater**:
   * La batteria ad alta tensione della Niro SG2 PHEV 2024 utilizza **raffreddamento forzato ad aria** con ventola dedicata nella centralina **BMS (`7E4` → `7EC`)**.
   * Nel comando `22 01 01` su `7E4`:
     * `d[27..28]`: candidati per **Fan Mode / Speed step** (`0x00` in tutte le catture, da confermare con ventola attiva). `d[29]` **non** è la ventola: è la tensione 12V (vedi sotto).
     * Il consumo del riscaldatore PTC abitacolo è già incluso direttamente nell'assorbimento del pacco batteria HV (`hvPowerKw`, es. `+2.0 kW` a fermo nella cattura all'85.5%), mentre la centralina clima `7B3` non espone un PID separato dei kW del PTC.
2. **Centralina BMS (`7E4` → `7EC`) — SoC BMS e Soglia Riserva Ibrida (15.5%)**:
   * **`22 01 01` (62 byte payload)**:
     * `d[4]`: **SOC BMS Reale** (`d[4] / 2.0` %) — es. `0x3A` (58) = `29.0%`, `0xAB` (171) = `85.5%`. **È il valore di riferimento principale dell'app.**
     * **Soglia Reale Accensione Motore Termico (15.5% BMS)**: verificato su strada sulla Niro SG2 PHEV 2024: quando il SoC BMS scende a **15.5%**, l'auto accende il motore termico e passa in ibrido forzato (HEV). L'autonomia EV stimata (`estimatedEvRangeKm`) viene quindi calcolata solo sulla percentuale utile sopra il 15.5%: `usableEvSocPct = max(0, socBms - 15.5)`.
     * `d[10..11]`: **Corrente HV** (`signed16(d[10], d[11]) / 10.0` A; **positivo = scarica/assorbimento**, negativo = ricarica/rigenerazione) — es. `0x0034` = `+5.2 A` con PTC acceso a veicolo fermo.
     * `d[12..13]`: **Tensione totale pacco HV** (`u16(d[12], d[13]) / 10.0` V) — es. `0x0D72` = `344.2 V` (al 29%), `0x0F23` = `387.5 V` (all'85.5%).
     * `d[14]`: **Temp. Max Batteria HV** (`signed8(d[14])` °C).
     * `d[15]`: **Temp. Min Batteria HV** (`signed8(d[15])` °C).
     * `d[16..19]`: **Temp. Moduli 1..4** (`signed8` °C).
     * `d[21]`: vale `0x00` in tutte le catture, **non** è una temperatura. La temperatura del circuito batteria si legge da `22 01 06` `d[4]`.
     * `d[23]`: **Tensione Cella Max** (`d[23] / 50.0` V) — es. `0xB3` = `3.58 V` (al 29%), `0xCA` = `4.04 V` (all'85.5%).
     * `d[24]`: **No. Cella Max** (`d[24]`).
     * `d[25]`: **Tensione Cella Min** (`d[25] / 50.0` V) — es. `0xB3` = `3.58 V` (al 29%), `0xC9` = `4.02 V` (all'85.5%).
     * `d[26]`: **No. Cella Min** (`d[26]`).
     * `d[29]`: **Batteria Ausiliaria 12V** (`d[29] / 10.0` V) — es. `0x86` (`13.4 V`), `0x8F` (`14.3 V`). `d[30]` vale `0x00` in tutte le catture.
     * `d[38..41]` / `d[42..45]`: **Energia cumulativa caricata / scaricata** (`u32 / 10` kWh).
   * **`22 01 05` (46 byte payload)**:
     * Confrontando le catture al 29% e all'85.5%, il byte `d[27]` resta fisso a `0x25` (`37 / 2 = 18.5%`), che rappresenta la **soglia fissa di riserva HEV**, mentre `d[31]` varia da `0x43` (`33.5%`) a `0xB5` (`90.5%`). Per evitare qualsiasi ambiguità, l'interfaccia usa esclusivamente il **SoC BMS (`22 01 01` `d[4]`)**.
     * `d[25..26]`: **SOH (State of Health)** (`u16(d[25], d[26]) / 10.0` %) — es. `0x037A` (890) = `89.0%`, costante tra le catture. `d[17]` **non** è il SOH (varia tra `0xC8` e `0xF1`): fino a ottobre 2026 il codice leggeva `d[17] / 2` e mostrava di fatto sempre 100%.
   * **`22 01 02`, `22 01 03`, `22 01 04` (Tensioni singole 96 celle)**:
     * Ciascun PID restituisce 32 celle consecutive (`d[4..35]`, valore `byte / 50.0` V), per un totale di **96 celle**.
3. **Centralina ECM / Motore Termico 1.6 GDi (`7E0` → `7E8`) e Cambio 6-DCT (`7E1` → `7E9`)**:
   * Sulla Niro SG2 PHEV la centralina `7E0` risponde ai PID standard OBD-II Mode 01 (`01xx`):
     * `01 04`: **Carico motore (Engine Load)** (`A * 100 / 255` %).
     * `01 05`: **Temperatura liquido raffreddamento motore (ECT)** (`A - 40` °C).
     * `01 0C`: **Giri motore (RPM)** (`((A * 256) + B) / 4` rpm).
     * `01 0D`: **Velocità veicolo** (`A` km/h).
     * `01 10`: **MAF Air Flow Rate** (`((A * 256) + B) / 100` g/s) → conversione stechiometrica benzina GDi in `L/h` e `L/100km`.
     * `01 5E`: **Engine Fuel Rate** (`((A * 256) + B) / 20` L/h).
     * `01 A4`: **Transmission Actual Gear** (oppure lettura UDS su `7E1` `22 01 01` / calcolo rapporto RPM/velocità 6-DCT per determinare `P / N` o `1ª..6ª` marcia).
4. **Centralina ODO / Cluster (`7C6` → `7CE`) e ADAS (`7D1`, `7C4`)**:
   * `7C6` → `22 B0 02`: **Odometro totale (km)** in `d[6..8]` (24-bit unsigned integer, es. `0x0186A0` = `100.000 km`).
   * `7C4` → `22 01 01` (MFC Front Camera ADAS): risponde `62 01 01 0E` sul bus CAN diagnostico; lo sniffer UDS include sonde estese (`220100..220105`, `22B001..22B003`, `22C001`) per mappare limiti di velocità (ISLA), corsia (LKA/LFA) e distanza veicolo anteriore.

---

## 4. Architettura UI e Personalizzazioni (`Niro Monitor`)

* **Full-Screen su Android Auto Coolwalk (`category.NAVIGATION` + `SurfaceCallback` Responsivo)**:
  * [`AndroidManifest.xml`](app/src/main/AndroidManifest.xml) dichiara `NiroCarAppService` nella categoria `androidx.car.app.category.NAVIGATION` (con permesso `androidx.car.app.NAVIGATION_TEMPLATES`).
  * La schermata principale [`CockpitSurfaceScreen.kt`](app/src/main/java/com/casadei/nirowatchdog/car/CockpitSurfaceScreen.kt) usa `NavigationTemplate` + `SurfaceCallback` occupando il **100% dell'altezza dello schermo** (nessun header in alto).
* **Zero Header Superiore & Cambio Pagina con Tap / Swipe**:
  * L'header superiore è stato completamente eliminato per massimizzare la dimensione dei gauge e dei grafici fin dal bordo superiore dello schermo.
  * Tutte le informazioni di stato (`NIRO MONITOR`, modalità `EV PURO` / `IBRIDO HEV`, stato connessione OBD, indicatore pagina `1/3..3/3` e firma `Made with ❤️ by Cristiano Casadei`) sono raccolte nella **barra footer compatta in basso** (`drawBottomFooterBar`).
  * Per cambiare pagina in circolo (`6 Gauge` → `96 Celle` → `Cockpit` → `6 Gauge`) basta **toccare lo schermo (Tap)** o **strisciare a destra/sinistra (Swipe)** grazie a `SurfaceCallback.onClick` / `onFling` / `onScroll` abilitati tramite `setPanModeListener`.
* **3 Modalità di Visualizzazione Full-Screen**:
  1. **`6 Gauge`** (predefinita): 6 indicatori circolari con icone vettoriali (`BATTERIA HV (BMS)`, `POTENZA HV LIVE`, `TEMP. BATTERIA HV`, `MOTORE 1.6 GDi`, `CONSUMO & MARCIA` con `L/100km` / `kWh/100` e marcia `1ª..6ª`, `SERVIZI 12V & LDC`).
  2. **`96 Celle`**: 4 card riepilogative (celle min/max, ΔV e SOH, moduli, energia cumulativa) + istogramma delle 96 celle in 12 gruppi da 8, barra di ripartizione viaggio EV/termico (doppio tocco per azzerare) e barra SoC con riserva 15.5%.
  3. **`Cockpit`**: gauge grande della potenza HV, curva della potenza degli ultimi ~60 s (75 campioni da ~800 ms, `POWER_HISTORY_SIZE`) e barra inferiore con le temperature dei 4 moduli.
* **Indicatore dati non live**: il footer mostra `⚠ DATI NON LIVE` finché non arriva una risposta BMS valida e `⚠ DATI FERMI DA Ns` se l'ultima è più vecchia di 5 s (pallino EV/HEV grigio). Senza questo indicatore i valori di default dello stato (es. BMS 67.5%) sembrerebbero dati reali.
* **Firma Personalizzata**:
  * Sia sul canvas Android Auto (in basso a destra nella barra celle) sia sull'app telefono compare la dicitura:
    `Made with ❤️ by Cristiano Casadei`.
* **Icona Adattiva Full-Bleed**:
  * Per evitare bordi bianchi attorno all'icona sia sul launcher del telefono sia su Android Auto, lo sfondo adattivo in [`colors.xml`](app/src/main/res/values/colors.xml) usa `#0E1520` (identico al bordo esterno dell'icona) e il foreground è il PNG `@mipmap/ic_launcher_foreground` (una versione per densità in `mipmap-*dpi/`), referenziato da [`mipmap-anydpi-v26/ic_launcher.xml`](app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml).
* **Auto-Connessione OBD**:
  * Android Auto avvia [`NiroCarAppService`](app/src/main/java/com/casadei/nirowatchdog/car/NiroCarAppService.kt) appena si collega all'auto, **anche se la schermata di Niro Monitor non viene aperta**, e lo distrugge quando si scollega (verificato nei log: il servizio parte un minuto dopo il collegamento di Android Auto e non con le attivazioni notturne di `gearhead`). Il polling segue questo ciclo di vita: parte in `onCreate()` del servizio e si ferma 20 s dopo `onDestroy()`; se nel frattempo il servizio si ricrea, prosegue senza riconnettere. Aprire la schermata riavvia il polling se era andato in pausa.
  * Se il polling live non è già attivo (connesso, in connessione o in attesa di riconnessione), cerca tra i dispositivi Bluetooth associati un nome tipo `Android-Vlink` / `V-LINK` / `OBD` / `ELM` / `Vgate`. In auto **non** ripiega sul primo dispositivo associato, che potrebbe essere l'autoradio o un auricolare. Un live già avviato dal telefono viene "adottato" dall'auto senza riconnettere.
* **Registro viaggi** ([`TripRecorder`](app/src/main/java/com/casadei/nirowatchdog/obd/TripRecorder.kt), sezione "Registro viaggi" dell'app telefono):
  * un viaggio va dal primo ciclo di polling con risposta BMS valida fino allo stop del polling (Android Auto scollegato, demo, scansione) o a una pausa senza dati di oltre 5 minuti;
  * **distanza** dall'odometro del cruscotto (`7C6 22B002`) per i viaggi da 10 km in su, altrimenti dalla velocità integrata; km in EV puro dalla velocità con termico spento;
  * **batteria HV** dai contatori cumulativi del BMS (`22 01 01` `d[38..45]`, 0,1 kWh): erogati, rientrati (frenata e ricarica dal termico) e netti, più SoC iniziale e finale;
  * **benzina** stimata integrando nel tempo il consumo istantaneo calcolato dal MAF (L/h): è una stima, non una misura;
  * salvataggio in `files/trips.json` ogni 30 s e a fine viaggio: un viaggio interrotto dalla chiusura del processo viene ripreso (pausa breve) o archiviato al riavvio. Esportazione CSV con `;` e virgola decimale.

---

## 5. Robustezza del Polling OBD e Test

* **Un'attività alla volta** ([`TelemetryRepository`](app/src/main/java/com/casadei/nirowatchdog/obd/TelemetryRepository.kt)): demo, live e scansione si sostituiscono a vicenda tramite `launchExclusive`, che chiude subito il socket del job precedente (sblocca `connect()`/`read()` bloccanti) e fa partire il nuovo solo dopo la sua terminazione. Il dongle SPP accetta una sola connessione: `dongleMutex` in `withElmLink` garantisce comunque un solo socket aperto.
* **Riconnessione automatica**: su errore Bluetooth, dongle muto (3 comandi senza prompt `>`), dongle riavviato da solo (banner `ELM327` fuori da `ATZ`) o BMS che non risponde per 8 cicli, chiude il socket e riprova con attesa crescente 1 s → 30 s. Il live avviato dal telefono va in pausa dopo 6 tentativi consecutivi senza dati, quello dell'auto dopo 12 (~6-8 minuti: con l'auto accesa il BMS risponde, quindi indica un dongle scollegato o assente).
* **Canale ELM327** ([`ElmLink`](app/src/main/java/com/casadei/nirowatchdog/obd/ElmLink.kt)):
  * scarta i byte residui prima di ogni comando;
  * invia `ATSH` solo quando cambia centralina;
  * aggiunge il suffisso `1` ai PID Mode 01 (risposta immediata, senza attendere il timeout):
    * con `?` il suffisso si disattiva;
    * con NO DATA si riprova senza suffisso solo finché il suffisso non ha mai funzionato (massimo 3 volte), per non raddoppiare i round-trip quando è la centralina a tacere;
  * accetta solo risposte provenienti dall'header atteso (richiesta + 8) e positive per il PID richiesto.
* **Riassemblaggio ISO-TP** (`Sg2PidParser.extractIsoTpPayload`): solo righe esadecimali, controllo della sequenza dei Consecutive Frame e troncamento alla lunghezza del First Frame (niente padding `AA`). Una risposta con un frame perso o duplicato viene scartata; prima i byte successivi slittavano e producevano valori sbagliati (es. il dump `7B3 220100` all'85.5% salta il frame `24`). Gli NRC `7F xx 78` (response pending) vengono saltati.
* **Ciclo di polling** (periodo target 800 ms): ogni ciclo legge `220101` (BMS) e `010C`/`010D` (motore), più al massimo **un** PID lento scelto a rotazione:
  * `220106` ogni ~4 s;
  * celle `220102..04` ogni ~3 s con la pagina 96 Celle visibile, altrimenti ogni ~32 s;
  * odometro `7C6 22B002` ogni ~20 s;
  * `220105` ogni ~48 s.
  Tutte le letture del ciclo sono pubblicate con un'unica `StateFlow.update {}`: un solo ridisegno per ciclo e nessuna modifica concorrente persa (es. l'azzeramento del viaggio).
* **Rendering Android Auto**: Paint/colori/Path allocati una volta, auto-fit del testo con una sola `measureText`, richieste di ridisegno raggruppate (max ~15 fps), ticker a 1 s attivo solo per countdown e indicatore di dati fermi; se un frame non si riesce a disegnare (surface non valida) si riprova dopo 250 ms.
* **Rendering GPU (`lockHardwareCanvas`) provato e scartato** (ottobre 2026, DHU 1190×460, telefono Android 16 con GPU Mali, demo pagina 1):
  * **Costo:**
    * main thread per frame: CPU ~22,5 ms contro GPU ~9,5 ms;
    * CPU totale del processo: GPU **+30%** (2,9 s/min contro 2,2 s/min), per `RenderThread` e driver Mali;
    * a ~1 frame/s il main thread non è un collo di bottiglia.
  * **Rischio:** quando Android Auto riconsegna a un processo appena riavviato la surface già usata via CPU, `eglCreateWindowSurface` fallisce ("already connected to another API") e HWUI va in **abort nativo** ("drawRenderNode called on a context with no surface!"). Non è intercettabile con try/catch e Android Auto riavvia l'app in loop.
* **Test JVM** (nessun dispositivo necessario):
```bash
gradle testDebugUnitTest
```
  `Sg2PidParserTest` decodifica risposte ELM327 reali incluse nel test (frame persi/duplicati, padding, NRC 0x78, messaggi ELM, SOH, odometro sintetico). `ElmLinkTest` simula un ELM327 (ATSH, suffisso `1` e relativi fallback, risposte tardive, centralina sbagliata, dongle muto o riavviato). `TripRecorderTest` e `TripLogTextTest` coprono il registro viaggi (integrazione di distanza e benzina, contatori BMS, odometro, pause, ripresa dopo riavvio, file JSON, CSV e testo mostrato).
