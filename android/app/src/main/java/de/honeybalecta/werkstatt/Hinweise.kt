package de.honeybalecta.werkstatt

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/** Benachrichtigungen: Updates, Fortschritt, Bestätigungen, Ergebnisse. */
object Hinweise {
    const val ID_FORTSCHRITT = 2
    private const val ID_UPDATES = 1
    private const val ID_SELBST = 3

    private const val K_UPDATES = "updates"
    private const val K_VORGANG = "vorgang"
    private const val K_BESTAETIGEN = "bestaetigen"
    private const val K_ERGEBNIS = "ergebnis"

    fun kanaele(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(K_UPDATES, "Updates verfügbar", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(K_VORGANG, "Downloads", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(K_BESTAETIGEN, "Bestätigung nötig", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(K_ERGEBNIS, "Erledigte Updates", NotificationManager.IMPORTANCE_LOW))
    }

    private fun zeige(context: Context, id: Int, n: Notification) {
        val nmc = NotificationManagerCompat.from(context)
        if (!nmc.areNotificationsEnabled()) return
        try { nmc.notify(id, n) } catch (_: SecurityException) { }
    }

    private fun weg(context: Context, id: Int) = NotificationManagerCompat.from(context).cancel(id)

    private fun oeffneApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun basis(context: Context, kanal: String) = NotificationCompat.Builder(context, kanal)
        .setSmallIcon(R.drawable.ic_stat_werkstatt)
        .setColor(0xFFE5402A.toInt())

    fun updates(context: Context, namen: List<String>) {
        if (namen.isEmpty()) { weg(context, ID_UPDATES); return }
        val titel = if (namen.size == 1) "Update für ${namen[0]}" else "${namen.size} Updates bereit"
        zeige(context, ID_UPDATES, basis(context, K_UPDATES)
            .setContentTitle(titel)
            .setContentText(namen.joinToString(", "))
            .setContentIntent(oeffneApp(context))
            .setAutoCancel(true)
            .build())
    }

    fun fortschritt(context: Context, text: String, prozent: Int): Notification =
        basis(context, K_VORGANG)
            .setContentTitle("Honeys Werkstatt")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, prozent.coerceAtLeast(0), prozent < 0)
            .setContentIntent(oeffneApp(context))
            .build()

    private fun nummer(basis: Int, id: String) = basis + (id.hashCode() and 0xFFFF)

    fun bestaetigen(context: Context, id: String, name: String, intent: Intent) {
        val pi = PendingIntent.getActivity(
            context, nummer(1000, id),
            Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        zeige(context, nummer(100_000, id), basis(context, K_BESTAETIGEN)
            .setContentTitle("$name bereit")
            .setContentText("Tippen, um die Installation zu bestätigen")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build())
    }

    fun bestaetigenWeg(context: Context, id: String) = weg(context, nummer(100_000, id))

    fun fertig(context: Context, name: String, version: String?) =
        zeige(context, nummer(200_000, name), basis(context, K_ERGEBNIS)
            .setContentTitle("$name aktualisiert")
            .setContentText(version?.let { "Jetzt Version $it" } ?: "Fertig")
            .setContentIntent(oeffneApp(context))
            .setAutoCancel(true)
            .build())

    fun fehler(context: Context, name: String, meldung: String) =
        zeige(context, nummer(300_000, name), basis(context, K_ERGEBNIS)
            .setContentTitle("$name: Update hat nicht geklappt")
            .setContentText(meldung)
            .setContentIntent(oeffneApp(context))
            .setAutoCancel(true)
            .build())

    /** Beim Selbst-Update endet die App; diese Meldung bleibt stehen und führt zurück. */
    fun selbstUpdate(context: Context) =
        zeige(context, ID_SELBST, basis(context, K_ERGEBNIS)
            .setContentTitle("Honeys Werkstatt wird aktualisiert")
            .setContentText("Tippen, um sie wieder zu öffnen")
            .setContentIntent(oeffneApp(context))
            .setAutoCancel(true)
            .build())

    fun selbstUpdateWeg(context: Context) = weg(context, ID_SELBST)
}
