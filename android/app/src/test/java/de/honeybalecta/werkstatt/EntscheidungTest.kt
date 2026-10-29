package de.honeybalecta.werkstatt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EntscheidungTest {
    private val datei = Datei(id = 42, name = "HoneysWerkstatt-1.0.1.apk", groesse = 20, aktualisiert = 1_000L, apiUrl = "u", sha256 = null)
    private val frei = Freigabe(tag = "v1.0.1", titel = "v1.0.1", veroeffentlicht = 0, datei = datei)
    private val inst = Installiert(versionName = "1.0.1", versionCode = 2, zeit = 5_000, vonUns = true)

    @Test fun `Auftrag nach Selbst-Update wird nicht wiederholt`() {
        // So legt die App den Nachweis nach dem Neustart ab (abschliessenNachNeustart)
        val nachweis = Speicher.Nachweis(dateiId = 42, dateiStand = 1_000L, versionCode = 2, paketZeit = 5_000, fassung = null)
        assertTrue(Entscheidung.schonInstalliert(nachweis, inst, frei, null, null, erzwingen = false))
    }

    @Test fun `neu installieren auf Wunsch`() {
        val nachweis = Speicher.Nachweis(42, 1_000L, 2, 5_000, null)
        assertFalse(Entscheidung.schonInstalliert(nachweis, inst, frei, null, null, erzwingen = true))
    }

    @Test fun `neuere Datei wird installiert`() {
        val alt = Speicher.Nachweis(dateiId = 41, dateiStand = 900L, versionCode = 1, paketZeit = 4_000, fassung = null)
        assertFalse(Entscheidung.schonInstalliert(alt, inst.copy(versionCode = 1), frei, null, null, false))
        assertFalse(Entscheidung.schonInstalliert(null, inst, frei, null, null, false))
    }

    @Test fun `gleicher Build wird nicht noch einmal installiert`() {
        assertTrue(Entscheidung.gleicherBuild(2, inst, null, null, erzwingen = false))
        assertFalse(Entscheidung.gleicherBuild(3, inst, null, null, erzwingen = false))
        assertFalse(Entscheidung.gleicherBuild(2, inst, null, null, erzwingen = true))
        assertFalse(Entscheidung.gleicherBuild(2, null, null, null, erzwingen = false))
    }

    @Test fun `Fassungswechsel beim Vorlesepult bleibt möglich`() {
        // komplett und kompakt entstehen im selben Lauf und haben denselben versionCode
        assertFalse(Entscheidung.gleicherBuild(2, inst, "kompakt", "komplett", erzwingen = false))
        val nachweis = Speicher.Nachweis(42, 1_000L, 2, 5_000, "komplett")
        assertFalse(Entscheidung.schonInstalliert(nachweis, inst, frei, "kompakt", "komplett", false))
    }
}
