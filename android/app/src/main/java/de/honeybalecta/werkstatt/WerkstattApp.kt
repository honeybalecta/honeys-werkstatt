package de.honeybalecta.werkstatt

import android.app.Application

class WerkstattApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Werkstatt.init(this)
        Hinweise.kanaele(this)
        runCatching { Planung.plane(this) }
    }
}
