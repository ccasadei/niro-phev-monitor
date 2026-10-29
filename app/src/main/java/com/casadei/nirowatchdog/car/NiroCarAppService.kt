package com.casadei.nirowatchdog.car

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.casadei.nirowatchdog.obd.TelemetryRepository

/**
 * Servizio Android Auto per Kia Niro SG2 Watchdog.
 */
class NiroCarAppService : CarAppService() {

    override fun createHostValidator(): HostValidator {
        // Permette il collegamento con l'unità infotainment della Niro SG2 e con il Desktop Head Unit (DHU)
        return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    }

    override fun onCreateSession(): Session = NiroCarSession()
}

class NiroCarSession : Session() {

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                // A vettura spenta / Android Auto disconnesso chiude l'OBD aperto dall'auto (dopo un breve margine)
                TelemetryRepository.onCarSessionEnded()
            }
        })
    }

    override fun onCreateScreen(intent: Intent): Screen {
        // Avvia automaticamente il collegamento OBD-II (es. Android-Vlink) non appena si apre su Android Auto
        TelemetryRepository.autoConnectFromCarSession(carContext)
        return CockpitSurfaceScreen(carContext)
    }
}
