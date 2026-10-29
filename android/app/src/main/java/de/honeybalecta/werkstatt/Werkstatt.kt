package de.honeybalecta.werkstatt

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

const val KATALOG_REPO = "honeybalecta/honeys-werkstatt"

data class Installiert(val versionName: String, val versionCode: Long, val zeit: Long, val vonUns: Boolean)

sealed interface Vorgang {
    data object Wartet : Vorgang
    data class Laedt(val gelesen: Long, val gesamt: Long) : Vorgang
    data object Einspielen : Vorgang
    data class Bestaetigen(val intent: Intent, val gestartet: Boolean = false) : Vorgang
    data class Fehler(val meldung: String, val hinweis: String? = null) : Vorgang
    data class Fertig(val text: String) : Vorgang
}

data class AppZustand(
    val eintrag: Eintrag,
    val installiert: Installiert? = null,
    val freigabe: Freigabe? = null,
    /** gewählte oder erkannte Fassung; null = noch nicht festgelegt */
    val fassung: String? = null,
    val installierteFassung: String? = null,
    val update: Boolean = false,
    val webBereit: Boolean = false,
    val fehler: WerkstattFehler? = null,
)

/** Zentrale: Katalog, Stände, Downloads und Installationen. Lebt so lange wie der App-Prozess. */
object Werkstatt {
    lateinit var app: Context; private set
    lateinit var speicher: Speicher; private set

    val zustaende = MutableStateFlow<List<AppZustand>>(emptyList())
    val vorgaenge = MutableStateFlow<Map<String, Vorgang>>(emptyMap())
    val prueftGerade = MutableStateFlow(false)
    val meldungen = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** true, solange die Hauptansicht sichtbar ist (dann öffnet sie Bestätigungen selbst). */
    @Volatile var vordergrund = false

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var katalog = Katalog(emptyList())
    private val freigaben = ConcurrentHashMap<String, Freigabe>()
    private val fehler = ConcurrentHashMap<String, WerkstattFehler>()
    private val webUpdates = ConcurrentHashMap<String, Boolean>()
    private val abgebrochen = ConcurrentHashMap.newKeySet<String>()
    private val pruefSperre = Mutex()

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        speicher = Speicher(app)
        katalog = speicher.katalogText?.let { runCatching { Katalog.lies(it) }.getOrNull() } ?: eingebauterKatalog()
        abschliessenNachNeustart()
        lokal()
    }

    private fun eingebauterKatalog(): Katalog =
        runCatching { Katalog.lies(app.assets.open("katalog.json").bufferedReader().use { it.readText() }) }
            .getOrDefault(Katalog(emptyList()))

    fun eintrag(id: String): Eintrag? = katalog.eintraege.firstOrNull { it.id == id }
    fun zustand(id: String): AppZustand? = zustaende.value.firstOrNull { it.eintrag.id == id }

    // ------------------------------------------------------------------ Stände

    fun installiert(paket: String?): Installiert? {
        if (paket == null) return null
        val pm = app.packageManager
        val info = try { pm.getPackageInfo(paket, 0) } catch (e: PackageManager.NameNotFoundException) { return null }
        val von = runCatching {
            if (Build.VERSION.SDK_INT >= 30) pm.getInstallSourceInfo(paket).installingPackageName
            else @Suppress("DEPRECATION") pm.getInstallerPackageName(paket)
        }.getOrNull()
        return Installiert(info.versionName ?: "?", info.longVersionCode, info.lastUpdateTime, von == app.packageName)
    }

    private fun installierteFassung(e: Eintrag, inst: Installiert?): String? {
        if (inst == null || e.fassungen.isEmpty() || e.paket == null) return null
        val n = speicher.nachweis(e.paket)
        if (n?.fassung != null && n.versionCode == inst.versionCode) return n.fassung
        return e.fassungen.firstOrNull { inst.versionName.contains(it.id, ignoreCase = true) }?.id
    }

    private fun gewaehlteFassung(e: Eintrag, inst: Installiert?): String? {
        if (e.fassungen.isEmpty()) return null
        speicher.fassung(e.id)?.let { f -> if (e.fassungen.any { it.id == f }) return f }
        return installierteFassung(e, inst)
    }

    private fun schluessel(e: Eintrag, fassung: String?) = e.id + "|" + (fassung ?: e.fassungen.firstOrNull()?.id ?: "")

    private fun brauchtUpdate(e: Eintrag, inst: Installiert?, frei: Freigabe?, fassung: String?, instFassung: String?): Boolean {
        if (inst == null || frei == null || e.paket == null) return false
        if (fassung != null && instFassung != null && fassung != instFassung) return true
        val n = speicher.nachweis(e.paket)
        if (n != null && n.versionCode == inst.versionCode && abs(n.paketZeit - inst.zeit) < 5_000)
            return n.dateiId != frei.datei.id || n.dateiStand != frei.datei.aktualisiert
        Versionen.vergleiche(frei.version, inst.versionName)?.let { if (it != 0) return it > 0 }
        return frei.datei.aktualisiert > inst.zeit + 60_000
    }

    /** Neu berechnen ohne Netz (nach Installationen, beim Zurückkehren in die App). */
    fun lokal() {
        val liste = katalog.eintraege.map { e ->
            when (e.typ) {
                Typ.APK -> {
                    val inst = installiert(e.paket)
                    val fassung = gewaehlteFassung(e, inst)
                    val instF = installierteFassung(e, inst)
                    val frei = freigaben[schluessel(e, fassung)]
                    AppZustand(e, inst, frei, fassung, instF, brauchtUpdate(e, inst, frei, fassung, instF), fehler = fehler[e.id])
                }
                Typ.WEB -> {
                    val bereit = WebWerkzeug.bereit(e)
                    AppZustand(e, webBereit = bereit, update = bereit && webUpdates[e.id] == true, fehler = fehler[e.id])
                }
            }
        }.sortedBy { if (it.eintrag.istSelbst) 1 else 0 }
        zustaende.value = liste
    }

    /** Fragt GitHub nach Katalog und neuesten Freigaben. Liefert false, wenn gar nichts erreichbar war. */
    suspend fun pruefen(): Boolean = withContext(Dispatchers.IO) { pruefSperre.withLock {
        prueftGerade.value = true
        try {
            val token = speicher.token
            var erreichbar = false
            runCatching { GitHub.rohText(KATALOG_REPO, "katalog.json", token) }.onSuccess { text ->
                runCatching { Katalog.lies(text) }.onSuccess { k ->
                    if (k.eintraege.isNotEmpty()) { katalog = k; speicher.katalogText = text }
                }
                erreichbar = true
            }
            coroutineScope {
                katalog.eintraege.map { e ->
                    async(Dispatchers.IO) {
                        try {
                            when (e.typ) {
                                Typ.APK -> {
                                    val fassung = gewaehlteFassung(e, installiert(e.paket))
                                    val muster = e.musterFuer(fassung) ?: throw WerkstattFehler("Im Katalog fehlt das Dateimuster")
                                    val f = GitHub.neuesteFreigabe(e.repo, muster, token)
                                        ?: throw WerkstattFehler("Noch keine APK im Release", "Im Repo ${e.repo} gibt es kein Release mit passender APK.")
                                    freigaben[schluessel(e, fassung)] = f
                                }
                                Typ.WEB -> { webUpdates[e.id] = WebWerkzeug.gibtUpdate(e, token) }
                            }
                            fehler.remove(e.id)
                            erreichbar = true
                        } catch (x: WerkstattFehler) {
                            fehler[e.id] = x
                        } catch (x: Exception) {
                            fehler[e.id] = WerkstattFehler("Abfrage fehlgeschlagen", x.message)
                        }
                        Unit
                    }
                }.awaitAll()
            }
            if (erreichbar) speicher.zuletztGeprueft = System.currentTimeMillis()
            lokal()
            erreichbar
        } finally {
            prueftGerade.value = false
        }
    } }

    // ------------------------------------------------------------------ Aufträge

    fun setzeVorgang(id: String, v: Vorgang?) = vorgaenge.update { if (v == null) it - id else it + (id to v) }

    fun waehleFassung(id: String, fassung: String) {
        speicher.setzeFassung(id, fassung)
        lokal()
        scope.launch { pruefen() }
    }

    /**
     * Legt Installationen/Updates in die Warteschlange (nacheinander, auch wenn die App geschlossen wird).
     * [erzwingen]: auch dann installieren, wenn genau dieser Build schon drauf ist („neu installieren“).
     */
    fun starte(ids: List<String>, erzwingen: Boolean = false) {
        if (ids.isEmpty()) return
        val reihe = ids.sortedBy { if (eintrag(it)?.istSelbst == true) 1 else 0 }  // sich selbst zuletzt
        reihe.forEach { abgebrochen.remove(it); setzeVorgang(it, Vorgang.Wartet) }
        val auftrag = OneTimeWorkRequestBuilder<InstallArbeit>()
            .setInputData(workDataOf(InstallArbeit.IDS to reihe.toTypedArray(), InstallArbeit.ERZWINGEN to erzwingen))
            .build()
        WorkManager.getInstance(app).enqueueUniqueWork("installieren", ExistingWorkPolicy.APPEND_OR_REPLACE, auftrag)
    }

    fun abbrechen(id: String) {
        abgebrochen += id
        val v = vorgaenge.value[id]
        if (v is Vorgang.Wartet || v is Vorgang.Bestaetigen || v is Vorgang.Fehler || v is Vorgang.Fertig) setzeVorgang(id, null)
    }

    fun istAbgebrochen(id: String) = id in abgebrochen

    /** Holt eine App oder ein Web-Werkzeug und spielt es ein. Läuft im Hintergrund-Auftrag. */
    suspend fun bearbeite(id: String, unbeaufsichtigt: Boolean, erzwingen: Boolean = false, fortschritt: (String, Int) -> Unit = { _, _ -> }) {
        val e = eintrag(id) ?: return
        if (istAbgebrochen(id)) { setzeVorgang(id, null); return }
        try {
            when (e.typ) {
                Typ.WEB -> {
                    setzeVorgang(id, Vorgang.Laedt(0, 0))
                    WebWerkzeug.aktualisiere(e, speicher.token)
                    webUpdates[id] = false
                    fehler.remove(id)
                    setzeVorgang(id, Vorgang.Fertig("Aktuell"))
                    lokal()
                }
                Typ.APK -> installiereApk(e, unbeaufsichtigt, erzwingen, fortschritt)
            }
        } catch (x: Abgebrochen) {
            setzeVorgang(id, null)
        } catch (x: WerkstattFehler) {
            setzeVorgang(id, Vorgang.Fehler(x.message ?: "Fehler", x.hinweis))
            if (unbeaufsichtigt) Hinweise.fehler(app, e.name, x.message ?: "Fehler")
        } catch (x: Exception) {
            setzeVorgang(id, Vorgang.Fehler("Unerwarteter Fehler", x.toString()))
        }
    }

    private suspend fun installiereApk(e: Eintrag, unbeaufsichtigt: Boolean, erzwingen: Boolean, fortschritt: (String, Int) -> Unit) {
        val paket = e.paket ?: throw WerkstattFehler("Im Katalog fehlt der Paketname")
        if (!app.packageManager.canRequestPackageInstalls())
            throw WerkstattFehler("Installieren ist noch nicht erlaubt", "Erlaube Honeys Werkstatt in den Einstellungen, Apps zu installieren.")
        val inst = installiert(paket)
        val fassung = gewaehlteFassung(e, inst) ?: e.fassungen.firstOrNull()?.id
        val token = speicher.token
        val frei = freigaben[schluessel(e, fassung)]
            ?: GitHub.neuesteFreigabe(e.repo, e.musterFuer(fassung) ?: throw WerkstattFehler("Im Katalog fehlt das Dateimuster"), token)
                ?.also { freigaben[schluessel(e, fassung)] = it }
            ?: throw WerkstattFehler("Keine APK im Release gefunden")

        // Genau diese Datei ist schon installiert (z. B. weil Android den Auftrag nach einem Neustart wiederholt)
        if (Entscheidung.schonInstalliert(speicher.nachweis(paket), inst, frei, fassung, installierteFassung(e, inst), erzwingen)) {
            speicher.vergissAusstehend(paket)
            setzeVorgang(e.id, Vorgang.Fertig("Aktuell"))
            lokal()
            return
        }

        val datei = File(app.cacheDir, "apk/${e.id}.apk")
        setzeVorgang(e.id, Vorgang.Laedt(0, frei.datei.groesse))
        withContext(Dispatchers.IO) {
            GitHub.lade(frei.datei, token, datei,
                fortschritt = { g, t ->
                    setzeVorgang(e.id, Vorgang.Laedt(g, t))
                    fortschritt(e.name, if (t > 0) (g * 100 / t).toInt() else -1)
                },
                abbrechen = { istAbgebrochen(e.id) || !isActive })
        }

        // Gehört die Datei wirklich zu dieser App? Ist sie nicht älter als das, was schon drauf ist?
        val info = app.packageManager.getPackageArchiveInfo(datei.path, 0)
            ?: run { datei.delete(); throw WerkstattFehler("Die Datei ist keine gültige App") }
        if (info.packageName != paket) {
            datei.delete()
            throw WerkstattFehler("Falsche Datei im Release", "Sie gehört zu ${info.packageName}, erwartet war $paket.")
        }
        val neu = info.longVersionCode
        val jetzt = installiert(paket)
        if (jetzt != null && neu < jetzt.versionCode) {
            datei.delete()
            throw WerkstattFehler("Die Datei ist älter als die installierte Version",
                "Android erlaubt kein Zurückstufen. Wer die ältere Fassung will, deinstalliert die App vorher.")
        }
        // Derselbe Build ist schon drauf: nur den Nachweis ablegen, nicht noch einmal installieren
        if (Entscheidung.gleicherBuild(neu, jetzt, fassung, installierteFassung(e, jetzt), erzwingen)) {
            datei.delete()
            jetzt?.let { speicher.setzeNachweis(paket, Speicher.Nachweis(frei.datei.id, frei.datei.aktualisiert, it.versionCode, it.zeit, fassung)) }
            speicher.vergissAusstehend(paket)
            setzeVorgang(e.id, Vorgang.Fertig("Aktuell"))
            lokal()
            return
        }

        speicher.merkeAusstehend(paket, Speicher.Nachweis(frei.datei.id, frei.datei.aktualisiert, neu, System.currentTimeMillis(), fassung))
        if (istAbgebrochen(e.id)) { datei.delete(); throw Abgebrochen() }
        setzeVorgang(e.id, Vorgang.Einspielen)
        fortschritt(e.name, -1)
        if (e.istSelbst) {
            // Beim Selbst-Update beendet Android die App. Liefe die Installation noch im Hintergrund-Auftrag,
            // gälte dieser als abgebrochen und Android startete ihn nach dem Neustart wieder – eine Endlosschleife.
            // Deshalb erst den Auftrag sauber beenden, dann installieren.
            Hinweise.selbstUpdate(app)
            scope.launch {
                delay(2_500)
                try {
                    Installer.installiere(app, datei, e, unbeaufsichtigt)
                } catch (x: WerkstattFehler) {
                    Hinweise.selbstUpdateWeg(app)
                    speicher.vergissAusstehend(paket)
                    setzeVorgang(e.id, Vorgang.Fehler(x.message ?: "Update fehlgeschlagen", x.hinweis))
                }
            }
            return
        }
        Installer.installiere(app, datei, e, unbeaufsichtigt)
    }

    /** Ergebnis aus [InstallErgebnis]. */
    fun installErgebnis(id: String, paket: String, erfolg: Boolean, abbruch: Boolean, meldung: String?, hinweis: String?) {
        val e = eintrag(id)
        File(app.cacheDir, "apk/$id.apk").delete()
        if (erfolg) {
            val inst = installiert(paket)
            speicher.ausstehend(paket)?.let { a ->
                if (inst != null) speicher.setzeNachweis(paket, a.copy(paketZeit = inst.zeit, versionCode = inst.versionCode))
            }
            speicher.vergissAusstehend(paket)
            setzeVorgang(id, Vorgang.Fertig("Installiert"))
            if (!vordergrund && e != null) Hinweise.fertig(app, e.name, inst?.versionName)
        } else if (abbruch) {
            speicher.vergissAusstehend(paket)
            setzeVorgang(id, Vorgang.Fehler(meldung ?: "Abgebrochen", hinweis))
        } else {
            speicher.vergissAusstehend(paket)
            setzeVorgang(id, Vorgang.Fehler(meldung ?: "Installation fehlgeschlagen", hinweis))
            if (!vordergrund && e != null) Hinweise.fehler(app, e.name, meldung ?: "Installation fehlgeschlagen")
        }
        Hinweise.bestaetigenWeg(app, id)
        lokal()
    }

    /** Android will eine Bestätigung. Im Vordergrund öffnet die Ansicht sie, sonst kommt eine Benachrichtigung. */
    fun brauchtBestaetigung(id: String, intent: Intent) {
        setzeVorgang(id, Vorgang.Bestaetigen(intent))
        if (!vordergrund) eintrag(id)?.let { Hinweise.bestaetigen(app, id, it.name, intent) }
    }

    /** Nach einem Selbst-Update startet der Prozess neu: angefangene Nachweise hier abschließen. */
    private fun abschliessenNachNeustart() {
        for (e in katalog.eintraege) {
            val paket = e.paket ?: continue
            val a = speicher.ausstehend(paket) ?: continue
            val inst = installiert(paket)
            if (inst != null && inst.versionCode == a.versionCode && inst.zeit >= a.paketZeit - 1_000) {
                speicher.setzeNachweis(paket, a.copy(paketZeit = inst.zeit))
            }
            if (System.currentTimeMillis() - a.paketZeit > 30 * 60_000 || inst?.versionCode == a.versionCode) speicher.vergissAusstehend(paket)
        }
    }

    fun deinstallieren(context: Context, paket: String) {
        context.startActivity(Intent(Intent.ACTION_DELETE, android.net.Uri.parse("package:$paket")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun oeffnen(context: Context, z: AppZustand) {
        val e = z.eintrag
        if (e.typ == Typ.WEB) { WebActivity.oeffne(context, e.id); return }
        val i = e.paket?.let { context.packageManager.getLaunchIntentForPackage(it) }
        if (i != null) context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        else meldungen.tryEmit("${e.name} hat kein Startsymbol.")
    }
}
