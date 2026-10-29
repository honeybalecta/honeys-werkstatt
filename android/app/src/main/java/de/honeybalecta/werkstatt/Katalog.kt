package de.honeybalecta.werkstatt

import org.json.JSONArray
import org.json.JSONObject

/**
 * Der Katalog (katalog.json im Repo honeybalecta/honeys-werkstatt) legt fest, welche Apps die Werkstatt zeigt.
 * Neue Apps kommen nur hinein, wenn Raphael zugestimmt hat.
 */
enum class Typ { APK, WEB }

data class Fassung(val id: String, val name: String, val muster: Regex)

data class Eintrag(
    val id: String,
    val name: String,
    val typ: Typ,
    val repo: String,                // "besitzer/repo"
    val beschreibung: String,
    // APK
    val paket: String? = null,
    val muster: Regex? = null,       // Dateiname der APK im Release
    val fassungen: List<Fassung> = emptyList(),
    // Web-Werkzeug
    val dateien: List<String> = emptyList(),
    val start: String = "index.html",
) {
    val istSelbst: Boolean get() = paket == BuildConfig.APPLICATION_ID
    val githubUrl: String get() = "https://github.com/$repo" + if (typ == Typ.APK) "/releases" else ""

    /** Muster für die gewählte Fassung, sonst das allgemeine Muster. */
    fun musterFuer(fassung: String?): Regex? =
        fassungen.firstOrNull { it.id == fassung }?.muster ?: muster ?: fassungen.firstOrNull()?.muster
}

data class Katalog(val eintraege: List<Eintrag>) {
    companion object {
        fun lies(text: String): Katalog {
            val o = JSONObject(text)
            val apps = o.optJSONArray("apps") ?: JSONArray()
            val liste = ArrayList<Eintrag>()
            for (i in 0 until apps.length()) {
                val a = apps.optJSONObject(i) ?: continue
                // Reine Windows-Apps (ohne Paketnamen) gehören nicht auf das Handy
                if (a.optString("typ") != "web" && a.optString("paket").isBlank()) continue
                runCatching { liste += eintrag(a) }
            }
            return Katalog(liste)
        }

        private fun eintrag(a: JSONObject): Eintrag {
            val typ = if (a.optString("typ") == "web") Typ.WEB else Typ.APK
            val fassungen = ArrayList<Fassung>()
            a.optJSONArray("fassungen")?.let { arr ->
                for (j in 0 until arr.length()) {
                    val f = arr.getJSONObject(j)
                    fassungen += Fassung(f.getString("id"), f.optString("name", f.getString("id")), Regex(f.getString("datei")))
                }
            }
            val dateien = ArrayList<String>()
            a.optJSONArray("dateien")?.let { arr -> for (j in 0 until arr.length()) dateien += arr.getString(j) }
            val start = a.optString("start", "index.html").ifBlank { "index.html" }
            if (typ == Typ.WEB && dateien.isEmpty()) dateien += start
            return Eintrag(
                id = a.getString("id"),
                name = a.getString("name"),
                typ = typ,
                repo = a.getString("repo"),
                beschreibung = a.optString("beschreibung"),
                paket = a.optString("paket").ifBlank { null },
                muster = a.optString("datei").ifBlank { null }?.let { Regex(it) },
                fassungen = fassungen,
                dateien = dateien,
                start = start,
            )
        }
    }
}
