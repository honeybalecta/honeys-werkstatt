package de.honeybalecta.werkstatt

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Arbeitet die Warteschlange ab: laden, prüfen, installieren – eins nach dem anderen. */
class InstallArbeit(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    companion object {
        const val IDS = "ids"
        const val ERZWINGEN = "erzwingen"
    }

    private fun info(text: String, prozent: Int) = ForegroundInfo(
        Hinweise.ID_FORTSCHRITT,
        Hinweise.fortschritt(applicationContext, text, prozent),
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
    )

    override suspend fun getForegroundInfo(): ForegroundInfo = info("Bereite vor …", -1)

    override suspend fun doWork(): Result {
        Werkstatt.init(applicationContext)
        val ids = inputData.getStringArray(IDS) ?: return Result.success()
        val erzwingen = inputData.getBoolean(ERZWINGEN, false)
        // Im Vordergrund als Dienst laufen, damit große Downloads auch beim Wechsel in andere Apps weiterlaufen
        val alsDienst = Werkstatt.vordergrund && runCatching { setForeground(info("Bereite vor …", -1)) }.isSuccess
        var zuletzt = 0L
        for (id in ids) {
            Werkstatt.bearbeite(id, unbeaufsichtigt = !Werkstatt.vordergrund, erzwingen = erzwingen) { name, prozent ->
                val jetzt = System.currentTimeMillis()
                if (alsDienst && jetzt - zuletzt > 800) {
                    zuletzt = jetzt
                    val text = if (prozent >= 0) "$name wird geladen · $prozent %" else "$name wird installiert"
                    runCatching { setForegroundAsync(info(text, prozent)) }
                }
            }
        }
        return Result.success()
    }
}

/** Regelmäßige Prüfung im Hintergrund; installiert auf Wunsch still. */
class PruefArbeit(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        Werkstatt.init(applicationContext)
        val sp = Werkstatt.speicher
        if (!Werkstatt.pruefen()) return Result.success()
        val updates = Werkstatt.zustaende.value.filter { it.update }
        if (updates.isEmpty()) {
            sp.gemeldet = ""
            Hinweise.updates(applicationContext, emptyList())
            return Result.success()
        }
        var offen = updates
        val darf = applicationContext.packageManager.canRequestPackageInstalls()
        if (sp.automatischInstallieren && darf && !Werkstatt.vordergrund) {
            // Still geht nur bei Apps, die die Werkstatt selbst installiert hat (und ohne Fassungswechsel)
            val still = updates.filter { z ->
                z.eintrag.typ == Typ.WEB ||
                    (z.installiert?.vonUns == true && (z.fassung == null || z.fassung == z.installierteFassung))
            }.sortedBy { if (it.eintrag.istSelbst) 1 else 0 }
            for (z in still) Werkstatt.bearbeite(z.eintrag.id, unbeaufsichtigt = true)
            Werkstatt.lokal()
            offen = Werkstatt.zustaende.value.filter { it.update }
        }
        val kennung = offen.joinToString(",") { it.eintrag.id + "@" + (it.freigabe?.datei?.id ?: 0) }
        if (kennung != sp.gemeldet) {
            Hinweise.updates(applicationContext, offen.map { it.eintrag.name })
            sp.gemeldet = kennung
        }
        return Result.success()
    }
}

object Planung {
    fun plane(context: Context) {
        val wm = WorkManager.getInstance(context)
        val sp = Werkstatt.speicher
        if (!sp.imHintergrundPruefen) { wm.cancelUniqueWork("pruefen"); return }
        val bedingungen = Constraints.Builder()
            .setRequiredNetworkType(if (sp.nurWlan) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()
        val auftrag = PeriodicWorkRequestBuilder<PruefArbeit>(6, TimeUnit.HOURS)
            .setConstraints(bedingungen)
            .build()
        wm.enqueueUniquePeriodicWork("pruefen", ExistingPeriodicWorkPolicy.UPDATE, auftrag)
    }
}
