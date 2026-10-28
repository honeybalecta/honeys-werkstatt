package de.honeybalecta.werkstatt

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Spielt APKs über eine PackageInstaller-Sitzung ein.
 * Ab Android 12 dürfen Updates ohne Rückfrage laufen, wenn die Werkstatt die App selbst installiert hat.
 */
object Installer {
    const val AKTION = "de.honeybalecta.werkstatt.INSTALL_ERGEBNIS"
    private val warten = ConcurrentHashMap<Int, CompletableDeferred<Unit>>()

    suspend fun installiere(context: Context, apk: File, e: Eintrag, unbeaufsichtigt: Boolean) {
        val pi = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(e.paket)
            setSize(apk.length())
            setInstallReason(PackageManager.INSTALL_REASON_USER)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            // Gilt nur bei der Erstinstallation: andere Quellen brauchen danach eine Rückfrage
            if (Build.VERSION.SDK_INT >= 34) setRequestUpdateOwnership(true)
            // Als App-Store-Quelle einstufen, damit Android 13+ keine „eingeschränkten Einstellungen“ verhängt
            if (Build.VERSION.SDK_INT >= 33) setPackageSource(PackageInstaller.PACKAGE_SOURCE_STORE)
        }
        val fertig = CompletableDeferred<Unit>()
        val sitzung = withContext(Dispatchers.IO) {
            val id = try { pi.createSession(params) } catch (x: Exception) {
                throw WerkstattFehler("Installation ließ sich nicht starten", x.message)
            }
            warten[id] = fertig
            try {
                pi.openSession(id).use { s ->
                    s.openWrite("base.apk", 0, apk.length()).use { aus ->
                        apk.inputStream().use { it.copyTo(aus, 256 * 1024) }
                        s.fsync(aus)
                    }
                    val intent = Intent(context, InstallErgebnis::class.java)
                        .setAction(AKTION)
                        .putExtra("id", e.id)
                        .putExtra("paket", e.paket)
                        .putExtra("sitzung", id)
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                        (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                    val ziel = PendingIntent.getBroadcast(context, id, intent, flags)
                    s.commit(ziel.intentSender)
                }
            } catch (x: Exception) {
                warten.remove(id)
                runCatching { pi.abandonSession(id) }
                throw WerkstattFehler("Die Datei ließ sich nicht an Android übergeben", x.message)
            }
            id
        }
        // Auf das Ergebnis warten, damit mehrere Updates sauber nacheinander laufen
        withTimeoutOrNull(if (unbeaufsichtigt) 3 * 60_000L else 10 * 60_000L) { fertig.await() }
        warten.remove(sitzung)
    }

    fun abgeschlossen(sitzung: Int) {
        warten.remove(sitzung)?.complete(Unit)
    }
}

class InstallErgebnis : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Installer.AKTION) return
        Werkstatt.init(context)
        val id = intent.getStringExtra("id") ?: return
        val paket = intent.getStringExtra("paket") ?: return
        val sitzung = intent.getIntExtra("sitzung", -1)
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val text = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val sperre = "Falls die automatische Sperre aktiv ist: Einstellungen › Sicherheit und Datenschutz › Automatische Sperre ausschalten."

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val bestaetigung: Intent? = if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                if (bestaetigung != null) Werkstatt.brauchtBestaetigung(id, bestaetigung)
                return  // die Sitzung läuft weiter; das Endergebnis kommt mit einer zweiten Nachricht
            }
            PackageInstaller.STATUS_SUCCESS ->
                Werkstatt.installErgebnis(id, paket, erfolg = true, abbruch = false, meldung = null, hinweis = null)
            PackageInstaller.STATUS_FAILURE_ABORTED ->
                Werkstatt.installErgebnis(id, paket, false, true, "Installation abgebrochen", sperre)
            PackageInstaller.STATUS_FAILURE_BLOCKED ->
                Werkstatt.installErgebnis(id, paket, false, false, "Vom System gesperrt", sperre)
            PackageInstaller.STATUS_FAILURE_CONFLICT ->
                Werkstatt.installErgebnis(id, paket, false, false, "Passt nicht zur installierten Version",
                    "Die installierte App ist mit einem anderen Schlüssel signiert. Einmal deinstallieren und neu installieren – danach laufen Updates normal.")
            PackageInstaller.STATUS_FAILURE_STORAGE ->
                Werkstatt.installErgebnis(id, paket, false, false, "Zu wenig Speicherplatz", text)
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                Werkstatt.installErgebnis(id, paket, false, false, "Mit diesem Gerät nicht verträglich", text)
            PackageInstaller.STATUS_FAILURE_INVALID ->
                Werkstatt.installErgebnis(id, paket, false, false, "Ungültige App-Datei", text)
            else ->
                Werkstatt.installErgebnis(id, paket, false, false, "Installation fehlgeschlagen", text)
        }
        Installer.abgeschlossen(sitzung)
    }
}
