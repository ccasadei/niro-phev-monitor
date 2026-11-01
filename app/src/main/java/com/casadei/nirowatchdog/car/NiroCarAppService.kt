package com.casadei.nirowatchdog.car

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator
import com.casadei.nirowatchdog.obd.TelemetryRepository

/**
 * Servizio Android Auto per Kia Niro SG2 Watchdog.
 *
 * Android Auto avvia il servizio appena si collega all'auto, anche se la schermata di Niro Monitor non viene
 * aperta, e lo distrugge quando si scollega: il polling OBD (e quindi il registro viaggi) segue questo ciclo
 * di vita, così copre l'intero viaggio.
 */
class NiroCarAppService : CarAppService() {

    override fun createHostValidator(): HostValidator {
        // Permette il collegamento con l'unità infotainment della Niro SG2 e con il Desktop Head Unit (DHU)
        return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    }

    override fun onCreate() {
        super.onCreate()
        TelemetryRepository.init(this)
        TelemetryRepository.autoConnectFromCarSession(this)
    }

    override fun onDestroy() {
        // Android Auto scollegato: il polling aperto dall'auto si ferma dopo un breve margine e il viaggio si chiude
        TelemetryRepository.onCarSessionEnded()
        super.onDestroy()
    }

    override fun onCreateSession(): Session = NiroCarSession()
}

class NiroCarSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen {
        // Riapre il collegamento OBD (es. Android-Vlink) se non è attivo o era andato in pausa
        TelemetryRepository.init(carContext)
        TelemetryRepository.autoConnectFromCarSession(carContext)
        return CockpitSurfaceScreen(carContext)
    }
}
