package de.honeybalecta.werkstatt

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.time.Instant

/** Fehler mit einer Erklärung, die so in der App angezeigt werden kann. */
class WerkstattFehler(meldung: String, val hinweis: String? = null) : IOException(meldung)

/** Vom Menschen abgebrochen. */
class Abgebrochen : IOException("abgebrochen")

data class Datei(
    val id: Long,
    val name: String,
    val groesse: Long,
    val aktualisiert: Long,     // Epoch-Millisekunden
    val apiUrl: String,
    val sha256: String?,
)

data class Freigabe(
    val tag: String,
    val titel: String,
    val veroeffentlicht: Long,
    val datei: Datei,
) {
    /** Versionsnummer aus dem Tag, z. B. "android-v2.7.0" → "2.7.0". */
    val version: String get() = Versionen.zahlTeil(tag) ?: tag
}

data class Inhalt(val sha: String, val groesse: Long)

object GitHub {
    private const val API = "https://api.github.com"

    private fun verbinde(url: String, token: String?, accept: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = 20_000
            readTimeout = 45_000
            setRequestProperty("Accept", accept)
            setRequestProperty("User-Agent", "HoneysWerkstatt/${BuildConfig.VERSION_NAME}")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            if (token != null && URL(url).host == "api.github.com") setRequestProperty("Authorization", "Bearer $token")
        }

    private fun fehler(c: HttpURLConnection, repo: String?): WerkstattFehler {
        val code = c.responseCode
        val rest = c.getHeaderField("x-ratelimit-remaining")
        return when {
            code == 401 -> WerkstattFehler("GitHub lehnt den Token ab", "Der Token ist abgelaufen oder falsch. Lege in den Einstellungen einen neuen an.")
            (code == 403 || code == 429) && rest == "0" -> WerkstattFehler("Abfragegrenze von GitHub erreicht", "Ohne Token sind 60 Abfragen pro Stunde erlaubt. Mit Token sind es deutlich mehr.")
            code == 404 -> WerkstattFehler(
                "Repo ${repo ?: ""} nicht erreichbar".replace("  ", " "),
                "Bei privaten Repos braucht die Werkstatt einen Lesetoken mit Zugriff auf dieses Repo (Einstellungen)."
            )
            else -> WerkstattFehler("GitHub antwortet mit Fehler $code")
        }
    }

    private fun text(pfad: String, token: String?, repo: String?, accept: String = "application/vnd.github+json"): String {
        val c = try { verbinde("$API$pfad", token, accept) } catch (e: IOException) { throw offline(e) }
        try {
            val code = try { c.responseCode } catch (e: IOException) { throw offline(e) }
            if (code != 200) throw fehler(c, repo)
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    private fun offline(e: IOException) =
        if (e is WerkstattFehler) e else WerkstattFehler("Keine Verbindung zu GitHub", e.message)

    /** Kleine Datei aus einem Repo als Text (Standardzweig). */
    fun rohText(repo: String, pfad: String, token: String?): String =
        text("/repos/$repo/contents/${pfad.trimStart('/')}", token, repo, "application/vnd.github.raw+json")

    /** Prüfsumme einer Datei im Repo (ändert sich mit jedem Commit, der die Datei ändert). */
    fun inhalt(repo: String, pfad: String, token: String?): Inhalt {
        val o = JSONObject(text("/repos/$repo/contents/${pfad.trimStart('/')}", token, repo))
        return Inhalt(o.getString("sha"), o.optLong("size"))
    }

    /** Neueste veröffentlichte Freigabe, die eine passende Datei enthält. */
    fun neuesteFreigabe(repo: String, muster: Regex, token: String?): Freigabe? {
        for (seite in 1..3) {
            val arr = JSONArray(text("/repos/$repo/releases?per_page=50&page=$seite", token, repo))
            if (arr.length() == 0) return null
            for (i in 0 until arr.length()) {
                val r = arr.getJSONObject(i)
                if (r.optBoolean("draft") || r.optBoolean("prerelease")) continue
                val assets = r.optJSONArray("assets") ?: continue
                var beste: Datei? = null
                for (j in 0 until assets.length()) {
                    val a = assets.getJSONObject(j)
                    val name = a.getString("name")
                    if (!name.endsWith(".apk", ignoreCase = true) || !muster.containsMatchIn(name)) continue
                    if (a.optString("state", "uploaded") != "uploaded") continue
                    val d = Datei(
                        id = a.getLong("id"),
                        name = name,
                        groesse = a.optLong("size"),
                        aktualisiert = zeit(a.optString("updated_at")),
                        apiUrl = a.getString("url"),
                        sha256 = a.optString("digest").takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:"),
                    )
                    if (beste == null || d.aktualisiert > beste.aktualisiert) beste = d
                }
                if (beste != null) return Freigabe(
                    tag = r.getString("tag_name"),
                    titel = r.optString("name").ifBlank { r.getString("tag_name") },
                    veroeffentlicht = zeit(r.optString("published_at")),
                    datei = beste,
                )
            }
            if (arr.length() < 50) return null
        }
        return null
    }

    /** Prüft den Token und liefert den GitHub-Namen, zu dem er gehört. */
    fun pruefeToken(token: String): String {
        val o = JSONObject(text("/user", token, null))
        return o.optString("login")
    }

    /**
     * Lädt eine Release-Datei. GitHub leitet auf einen Speicher-Server weiter; dorthin darf der Token nicht mit.
     * [fortschritt] bekommt gelesene und gesamte Bytes, [abbrechen] wird zwischendurch abgefragt.
     */
    fun lade(datei: Datei, token: String?, ziel: File, fortschritt: (Long, Long) -> Unit, abbrechen: () -> Boolean) {
        var url = datei.apiUrl
        var c: HttpURLConnection? = null
        try {
            for (runde in 0 until 6) {
                c = try { verbinde(url, token, "application/octet-stream") } catch (e: IOException) { throw offline(e) }
                val code = try { c.responseCode } catch (e: IOException) { throw offline(e) }
                if (code in 300..399) {
                    url = c.getHeaderField("Location") ?: throw WerkstattFehler("Weiterleitung ohne Ziel")
                    c.disconnect(); c = null
                    continue
                }
                if (code != 200) throw fehler(c, null)
                break
            }
            val v = c ?: throw WerkstattFehler("Zu viele Weiterleitungen")
            val gesamt = v.contentLengthLong.takeIf { it > 0 } ?: datei.groesse
            val md = MessageDigest.getInstance("SHA-256")
            ziel.parentFile?.mkdirs()
            var gelesen = 0L
            var zuletzt = 0L
            v.inputStream.use { ein ->
                ziel.outputStream().use { aus ->
                    val puffer = ByteArray(256 * 1024)
                    while (true) {
                        if (abbrechen()) throw Abgebrochen()
                        val n = try { ein.read(puffer) } catch (e: IOException) { throw WerkstattFehler("Download unterbrochen", e.message) }
                        if (n < 0) break
                        aus.write(puffer, 0, n)
                        md.update(puffer, 0, n)
                        gelesen += n
                        if (gelesen - zuletzt > 512 * 1024 || gelesen == gesamt) { zuletzt = gelesen; fortschritt(gelesen, gesamt) }
                    }
                }
            }
            if (datei.groesse > 0 && gelesen != datei.groesse)
                throw WerkstattFehler("Download unvollständig ($gelesen von ${datei.groesse} Bytes)")
            val summe = md.digest().joinToString("") { "%02x".format(it) }
            if (datei.sha256 != null && !summe.equals(datei.sha256, ignoreCase = true))
                throw WerkstattFehler("Prüfsumme stimmt nicht", "Die Datei ist beim Laden beschädigt worden. Bitte noch einmal versuchen.")
        } catch (e: Throwable) {
            ziel.delete(); throw e
        } finally {
            c?.disconnect()
        }
    }

    /** Lädt eine Datei aus dem Repo (Standardzweig) roh in [ziel]. */
    fun ladeInhalt(repo: String, pfad: String, token: String?, ziel: File) {
        val c = try { verbinde("$API/repos/$repo/contents/${pfad.trimStart('/')}", token, "application/vnd.github.raw+json") }
                catch (e: IOException) { throw offline(e) }
        try {
            val code = try { c.responseCode } catch (e: IOException) { throw offline(e) }
            if (code != 200) throw fehler(c, repo)
            ziel.parentFile?.mkdirs()
            val tmp = File(ziel.path + ".neu")
            c.inputStream.use { ein -> tmp.outputStream().use { ein.copyTo(it) } }
            if (!tmp.renameTo(ziel)) { tmp.copyTo(ziel, overwrite = true); tmp.delete() }
        } finally { c.disconnect() }
    }

    private fun zeit(s: String?): Long = runCatching { Instant.parse(s).toEpochMilli() }.getOrDefault(0L)
}

object Versionen {
    private val zahl = Regex("""\d+(\.\d+)*""")

    fun zahlTeil(s: String?): String? = s?.let { zahl.find(it)?.value }

    /** Vergleicht zwei Versionen, wenn sie gleich aufgebaut sind; sonst null (nicht vergleichbar). */
    fun vergleiche(a: String?, b: String?): Int? {
        val x = zahlTeil(a)?.split('.')?.map { it.toLongOrNull() ?: 0 } ?: return null
        val y = zahlTeil(b)?.split('.')?.map { it.toLongOrNull() ?: 0 } ?: return null
        if (x.size != y.size) return null
        for (i in x.indices) if (x[i] != y[i]) return x[i].compareTo(y[i])
        return 0
    }
}
