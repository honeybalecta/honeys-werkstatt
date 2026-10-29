package de.honeybalecta.werkstatt

/**
 * Reine Entscheidungen ohne Android-Abhängigkeit (deshalb auch auf dem Build-Rechner testbar).
 */
object Entscheidung {
    /**
     * Ist genau diese Release-Datei schon installiert? Dann gibt es nichts zu tun – etwa wenn Android
     * einen Auftrag nach einem Neustart der App noch einmal anstößt.
     */
    fun schonInstalliert(
        nachweis: Speicher.Nachweis?,
        installiert: Installiert?,
        frei: Freigabe,
        fassung: String?,
        installierteFassung: String?,
        erzwingen: Boolean,
    ): Boolean {
        if (erzwingen || installiert == null || nachweis == null) return false
        if (fassung != null && installierteFassung != null && fassung != installierteFassung) return false
        return nachweis.dateiId == frei.datei.id &&
            nachweis.dateiStand == frei.datei.aktualisiert &&
            nachweis.versionCode == installiert.versionCode
    }

    /**
     * Nach dem Laden: Hat die Datei denselben Build (versionCode) wie die installierte App und bleibt die Fassung gleich,
     * wird nicht noch einmal installiert – außer jemand wählt ausdrücklich „neu installieren“.
     */
    fun gleicherBuild(
        neuerCode: Long,
        installiert: Installiert?,
        fassung: String?,
        installierteFassung: String?,
        erzwingen: Boolean,
    ): Boolean {
        if (erzwingen || installiert == null) return false
        if (fassung != null && installierteFassung != null && fassung != installierteFassung) return false
        return neuerCode == installiert.versionCode
    }
}
