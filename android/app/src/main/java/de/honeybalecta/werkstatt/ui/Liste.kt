package de.honeybalecta.werkstatt.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.graphics.drawable.toBitmap
import de.honeybalecta.werkstatt.AppZustand
import de.honeybalecta.werkstatt.MainActivity
import de.honeybalecta.werkstatt.Symbole
import de.honeybalecta.werkstatt.Typ
import de.honeybalecta.werkstatt.Vorgang
import de.honeybalecta.werkstatt.WebActivity
import de.honeybalecta.werkstatt.WebWerkzeug
import de.honeybalecta.werkstatt.Werkstatt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Liste(zuEinstellungen: () -> Unit) {
    val ctx = LocalContext.current
    val zustaende by Werkstatt.zustaende.collectAsState()
    val vorgaenge by Werkstatt.vorgaenge.collectAsState()
    val prueft by Werkstatt.prueftGerade.collectAsState()
    val sichtbar by MainActivity.sichtbar.collectAsState()
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    var fassungFuer by remember { mutableStateOf<AppZustand?>(null) }
    var fassungDannInstallieren by remember { mutableStateOf(false) }

    val darfInstallieren = remember(sichtbar) { ctx.packageManager.canRequestPackageInstalls() }
    val hatToken = remember(sichtbar, zustaende) { Werkstatt.speicher.hatToken }

    LaunchedEffect(Unit) { Werkstatt.meldungen.collect { snack.showSnackbar(it) } }

    // Android bittet um Bestätigung: Dialog direkt öffnen
    LaunchedEffect(vorgaenge) {
        vorgaenge.forEach { (id, v) ->
            if (v is Vorgang.Bestaetigen && !v.gestartet) {
                Werkstatt.setzeVorgang(id, v.copy(gestartet = true))
                runCatching { ctx.startActivity(Intent(v.intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        }
    }

    fun ausfuehren(z: AppZustand) {
        val e = z.eintrag
        if (e.typ == Typ.APK && e.fassungen.size > 1 && z.fassung == null) {
            fassungFuer = z; fassungDannInstallieren = true; return
        }
        Werkstatt.starte(listOf(e.id))
    }

    val updates = zustaende.filter { it.update && !laeuft(vorgaenge[it.eintrag.id]) }

    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { pad ->
        PullToRefreshBox(
            isRefreshing = prueft,
            onRefresh = { scope.launch { Werkstatt.pruefen() } },
            modifier = Modifier.padding(pad).fillMaxSize(),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Kopf(
                        untertitel = (when {
                            prueft -> "Prüfe GitHub …"
                            updates.size == 1 -> "1 Update bereit"
                            updates.size > 1 -> "${updates.size} Updates bereit"
                            zustaende.isEmpty() -> "Katalog wird geladen …"
                            else -> "Alles aktuell"
                        }) + zuletzt(Werkstatt.speicher.zuletztGeprueft),
                        aktualisieren = { scope.launch { Werkstatt.pruefen() } },
                        einstellungen = zuEinstellungen,
                    )
                }
                if (!darfInstallieren) item {
                    HinweisKarte(
                        titel = "Installieren erlauben",
                        text = "Damit die Werkstatt Apps installieren und aktualisieren kann, braucht sie einmal deine Erlaubnis.",
                        knopf = "Erlauben",
                    ) {
                        ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")))
                    }
                }
                if (!hatToken && zustaende.any { it.fehler != null && it.freigabe == null && it.eintrag.typ == Typ.APK }) item {
                    HinweisKarte(
                        titel = "Lesetoken fehlt",
                        text = "Apps aus privaten Repos sieht die Werkstatt erst mit einem GitHub-Lesetoken.",
                        knopf = "Einrichten",
                        aktion = zuEinstellungen,
                    )
                }
                if (updates.size > 1) item {
                    Button(
                        onClick = { Werkstatt.starte(updates.map { it.eintrag.id }) },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(14.dp),
                    ) { Text("Alle aktualisieren (${updates.size})") }
                }
                items(zustaende, key = { it.eintrag.id }) { z ->
                    AppKarte(
                        z = z,
                        vorgang = vorgaenge[z.eintrag.id],
                        ausfuehren = { ausfuehren(z) },
                        fassungWechseln = { fassungFuer = z; fassungDannInstallieren = false },
                    )
                }
                item {
                    Text(
                        "Zum Aktualisieren nach unten ziehen. Die Liste kommt aus dem Repo honeybalecta/honeys-werkstatt.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp),
                    )
                }
            }
        }
    }

    fassungFuer?.let { z ->
        FassungsDialog(z, schliessen = { fassungFuer = null }) { f ->
            fassungFuer = null
            Werkstatt.speicher.setzeFassung(z.eintrag.id, f)
            Werkstatt.lokal()
            if (fassungDannInstallieren || z.installiert != null && z.installierteFassung != f) Werkstatt.starte(listOf(z.eintrag.id))
        }
    }
}

private fun laeuft(v: Vorgang?) = v is Vorgang.Wartet || v is Vorgang.Laedt || v is Vorgang.Einspielen || v is Vorgang.Bestaetigen

private fun zuletzt(t: Long): String {
    if (t <= 0) return ""
    val jetzt = System.currentTimeMillis()
    val heute = SimpleDateFormat("yyyyMMdd", Locale.GERMANY).let { it.format(Date(jetzt)) == it.format(Date(t)) }
    val f = if (heute) SimpleDateFormat("HH:mm", Locale.GERMANY) else SimpleDateFormat("d.M. HH:mm", Locale.GERMANY)
    return " · geprüft ${f.format(Date(t))}"
}

private fun mb(bytes: Long): String = if (bytes <= 0) "" else
    if (bytes >= 10 * 1024 * 1024) "${bytes / (1024 * 1024)} MB" else String.format(Locale.GERMANY, "%.1f MB", bytes / 1048576.0)

@Composable
private fun Kopf(untertitel: String, aktualisieren: () -> Unit, einstellungen: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp, bottom = 8.dp)) {
        Waben(Modifier.size(34.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Honeys Werkstatt", style = MaterialTheme.typography.headlineSmall)
            Text(untertitel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = aktualisieren) { Icon(Icons.Filled.Refresh, contentDescription = "Nach Updates suchen") }
        IconButton(onClick = einstellungen) { Icon(Icons.Filled.Settings, contentDescription = "Einstellungen") }
    }
}

/** Markenzeichen: drei Waben, die obere rot. */
@Composable
fun Waben(modifier: Modifier) {
    val unten = MaterialTheme.colorScheme.onBackground
    val oben = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val s = size.minDimension / 108f
        val r = 14f * s
        val d = (Math.sqrt(3.0).toFloat() * 14f + 3f) * s
        val dy = d * Math.sqrt(3.0).toFloat() / 2f
        val c = size.minDimension / 2f
        val y0 = c - 2f * dy / 3f
        drawPath(wabenPfad(c, y0, r), oben)
        drawPath(wabenPfad(c - d / 2f, y0 + dy, r), unten)
        drawPath(wabenPfad(c + d / 2f, y0 + dy, r), unten)
    }
}

fun wabenPfad(cx: Float, cy: Float, r: Float): Path = Path().apply {
    for (i in 0 until 6) {
        val w = Math.toRadians((-90 + 60 * i).toDouble())
        val x = cx + r * cos(w).toFloat()
        val y = cy + r * sin(w).toFloat()
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

@Composable
private fun HinweisKarte(titel: String, text: String, knopf: String, aktion: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(titel, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            Button(onClick = aktion, shape = RoundedCornerShape(12.dp)) { Text(knopf) }
        }
    }
}

@Composable
private fun AppSymbol(z: AppZustand, groesse: Dp) {
    val ctx = LocalContext.current
    val paket = z.eintrag.paket
    val bild = remember(paket, z.installiert?.versionCode) {
        if (z.eintrag.typ == Typ.APK && paket != null && z.installiert != null)
            runCatching { ctx.packageManager.getApplicationIcon(paket).toBitmap(144, 144).asImageBitmap() }.getOrNull()
        else null
    }
    if (bild != null) {
        Image(bild, contentDescription = null, modifier = Modifier.size(groesse).clip(RoundedCornerShape(12.dp)))
        return
    }
    // Platzhalter: volle Wabe für Apps, Umriss für Web-Werkzeuge
    val farbe = MaterialTheme.colorScheme.onBackground
    val grund = MaterialTheme.colorScheme.background
    val web = z.eintrag.typ == Typ.WEB
    Box(Modifier.size(groesse), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(groesse)) {
            val r = size.minDimension * 0.47f
            val p = wabenPfad(size.width / 2, size.height / 2, r)
            if (web) drawPath(p, farbe, style = Stroke(width = size.minDimension * 0.06f)) else drawPath(p, farbe)
        }
        Text(
            Symbole.initiale(z.eintrag.name),
            color = if (web) farbe else grund,
            fontWeight = FontWeight.Bold,
            fontSize = (groesse.value * 0.36f).sp,
        )
    }
}

@Composable
private fun AppKarte(z: AppZustand, vorgang: Vorgang?, ausfuehren: () -> Unit, fassungWechseln: () -> Unit) {
    val ctx = LocalContext.current
    val e = z.eintrag
    var menue by remember { mutableStateOf(false) }

    // Fertig-Meldungen verschwinden nach kurzer Zeit
    LaunchedEffect(vorgang) {
        if (vorgang is Vorgang.Fertig) { delay(4_000); if (Werkstatt.vorgaenge.value[e.id] == vorgang) Werkstatt.setzeVorgang(e.id, null) }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, if (z.update && vorgang == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(start = 16.dp, top = 14.dp, end = 8.dp, bottom = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppSymbol(z, 48.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(e.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (e.beschreibung.isNotBlank()) Text(
                        e.beschreibung, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
                Box {
                    IconButton(onClick = { menue = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Mehr") }
                    DropdownMenu(expanded = menue, onDismissRequest = { menue = false }) {
                        Menue(ctx, z, schliessen = { menue = false }, fassungWechseln = fassungWechseln)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Box(Modifier.padding(end = 8.dp)) {
                when (vorgang) {
                    null, is Vorgang.Fertig -> Grundzeile(z, vorgang as? Vorgang.Fertig, ausfuehren)
                    is Vorgang.Wartet -> Laufzeile("In der Warteschlange", null) { Werkstatt.abbrechen(e.id) }
                    is Vorgang.Laedt -> {
                        val anteil = if (vorgang.gesamt > 0) vorgang.gelesen.toFloat() / vorgang.gesamt else null
                        val text = if (anteil != null) "Lädt ${(anteil * 100).toInt()} % · ${mb(vorgang.gelesen).ifBlank { "0 MB" }} von ${mb(vorgang.gesamt)}"
                                   else "Lädt …"
                        Laufzeile(text, anteil) { Werkstatt.abbrechen(e.id) }
                    }
                    is Vorgang.Einspielen -> Laufzeile("Wird installiert …", null, null)
                    is Vorgang.Bestaetigen -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Bitte die Installation bestätigen", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Button(onClick = {
                            runCatching { ctx.startActivity(Intent(vorgang.intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        }, shape = RoundedCornerShape(12.dp)) { Text("Bestätigen") }
                    }
                    is Vorgang.Fehler -> Column {
                        Row(verticalAlignment = Alignment.Top) {
                            Column(Modifier.weight(1f)) {
                                Text(vorgang.meldung, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                                vorgang.hinweis?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                            IconButton(onClick = { Werkstatt.setzeVorgang(e.id, null) }) { Icon(Icons.Filled.Close, contentDescription = "Schließen") }
                        }
                        Spacer(Modifier.height(6.dp))
                        OutlinedButton(onClick = ausfuehren, shape = RoundedCornerShape(12.dp)) { Text("Noch einmal versuchen") }
                    }
                }
            }
        }
    }
}

@Composable
private fun Laufzeile(text: String, anteil: Float?, abbrechen: (() -> Unit)?) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (abbrechen != null) TextButton(onClick = abbrechen) { Text("Abbrechen") }
        }
        Spacer(Modifier.height(4.dp))
        val farbe = MaterialTheme.colorScheme.primary
        val spur = MaterialTheme.colorScheme.surfaceContainerHigh
        if (anteil != null) LinearProgressIndicator(progress = { anteil }, modifier = Modifier.fillMaxWidth(), color = farbe, trackColor = spur)
        else LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = farbe, trackColor = spur)
    }
}

@Composable
private fun Grundzeile(z: AppZustand, fertig: Vorgang.Fertig?, ausfuehren: () -> Unit) {
    val ctx = LocalContext.current
    val e = z.eintrag
    val (text, knopf, betont) = beschriftung(z)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            val aktuell = fertig != null || (!z.update && (z.installiert != null || z.webBereit) && z.fehler == null)
            if (aktuell) {
                Canvas(Modifier.size(8.dp)) { drawCircle(Farbe.gruen) }
                Spacer(Modifier.width(8.dp))
            }
            Column {
                Text(fertig?.text ?: text, style = MaterialTheme.typography.bodyMedium,
                    color = if (z.fehler != null && z.freigabe == null && e.typ == Typ.APK) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                z.fehler?.hinweis?.takeIf { z.freigabe == null && !z.webBereit }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (knopf != null) {
            Spacer(Modifier.width(8.dp))
            val klick: () -> Unit = when (knopf) {
                "Öffnen" -> ({ Werkstatt.oeffnen(ctx, z) })
                else -> ausfuehren
            }
            if (betont) Button(onClick = klick, shape = RoundedCornerShape(12.dp)) { Text(knopf) }
            else OutlinedButton(onClick = klick, shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) { Text(knopf) }
        }
    }
}

/** Statuszeile, Knopfbeschriftung und ob der Knopf betont (rot) ist. */
private fun beschriftung(z: AppZustand): Triple<String, String?, Boolean> {
    val e = z.eintrag
    if (e.typ == Typ.WEB) return when {
        !z.webBereit && z.fehler != null -> Triple(z.fehler.message ?: "Nicht erreichbar", "Laden", true)
        !z.webBereit -> Triple("Web-Werkzeug · noch nicht geladen", "Laden", true)
        z.update -> Triple("Web-Werkzeug · neue Fassung im Repo", "Aktualisieren", true)
        else -> Triple("Web-Werkzeug · aktuell", "Öffnen", false)
    }
    val inst = z.installiert
    val frei = z.freigabe
    val fassungName = e.fassungen.firstOrNull { it.id == (z.fassung ?: z.installierteFassung) }?.name
    val zusatz = listOfNotNull(fassungName, frei?.datei?.groesse?.let { mb(it) }?.ifBlank { null }).joinToString(" · ")
    return when {
        frei == null && z.fehler != null -> Triple(z.fehler.message ?: "Nicht erreichbar", if (inst != null && !e.istSelbst) "Öffnen" else null, false)
        inst == null -> Triple(
            "Nicht installiert" + (frei?.let { " · ${it.version}" } ?: "") + (if (zusatz.isNotBlank()) " · $zusatz" else ""),
            "Installieren", true)
        z.update && z.fassung != null && z.installierteFassung != null && z.fassung != z.installierteFassung ->
            Triple("Wechsel zu: ${fassungName ?: z.fassung}", "Wechseln", true)
        z.update -> Triple(
            "${inst.versionName} → ${frei?.version ?: "neu"}" + (frei?.datei?.groesse?.let { " · ${mb(it)}" } ?: ""),
            "Aktualisieren", true)
        e.istSelbst -> Triple("Aktuell · ${inst.versionName} · diese App", null, false)
        else -> Triple("Aktuell · ${inst.versionName}" + (fassungName?.let { " · $it" } ?: ""), "Öffnen", false)
    }
}

@Composable
private fun Menue(ctx: Context, z: AppZustand, schliessen: () -> Unit, fassungWechseln: () -> Unit) {
    val e = z.eintrag
    fun punkt(text: String, aktion: () -> Unit): @Composable () -> Unit = {
        DropdownMenuItem(text = { Text(text) }, onClick = { schliessen(); aktion() })
    }
    val punkte = ArrayList<@Composable () -> Unit>()
    if (e.typ == Typ.APK) {
        if (z.installiert != null && !e.istSelbst) punkte += punkt("Öffnen") { Werkstatt.oeffnen(ctx, z) }
        if (e.fassungen.size > 1) punkte += punkt("Fassung wählen") { fassungWechseln() }
        if (z.installiert != null && z.freigabe != null) punkte += punkt("Neueste Fassung neu installieren") { Werkstatt.starte(listOf(e.id)) }
        if (z.installiert != null && !e.istSelbst) punkte += punkt("Deinstallieren") { Werkstatt.deinstallieren(ctx, e.paket!!) }
    } else {
        if (z.webBereit) punkte += punkt("Öffnen") { WebActivity.oeffne(ctx, e.id) }
        if (z.webBereit) punkte += punkt("Auf den Startbildschirm") { aufStartbildschirm(ctx, z) }
        punkte += punkt("Neu laden") { Werkstatt.starte(listOf(e.id)) }
        if (z.webBereit) punkte += punkt("Entfernen") { WebWerkzeug.loeschen(e); Werkstatt.lokal() }
    }
    punkte += punkt("Auf GitHub ansehen") {
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(e.githubUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
    punkte.forEach { it() }
}

private fun aufStartbildschirm(ctx: Context, z: AppZustand) {
    val e = z.eintrag
    if (!ShortcutManagerCompat.isRequestPinShortcutSupported(ctx)) {
        Werkstatt.meldungen.tryEmit("Dein Startbildschirm nimmt keine Verknüpfungen an.")
        return
    }
    val info = ShortcutInfoCompat.Builder(ctx, "web-${e.id}")
        .setShortLabel(e.name)
        .setLongLabel(e.name)
        .setIcon(IconCompat.createWithAdaptiveBitmap(Symbole.webSymbol(ctx, e.name)))
        .setIntent(WebActivity.intent(ctx, e.id))
        .build()
    ShortcutManagerCompat.requestPinShortcut(ctx, info, null)
}

@Composable
private fun FassungsDialog(z: AppZustand, schliessen: () -> Unit, gewaehlt: (String) -> Unit) {
    val e = z.eintrag
    var wahl by remember { mutableStateOf(z.fassung ?: z.installierteFassung ?: e.fassungen.firstOrNull()?.id) }
    AlertDialog(
        onDismissRequest = schliessen,
        title = { Text("${e.name}: Fassung") },
        text = {
            Column {
                e.fassungen.forEach { f ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        RadioButton(selected = wahl == f.id, onClick = { wahl = f.id })
                        Text(f.name, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                if (z.installiert != null) Text(
                    "Ein Wechsel installiert die andere Fassung über die vorhandene. Deine Daten bleiben.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = { wahl?.let(gewaehlt) }, enabled = wahl != null) { Text("Übernehmen") } },
        dismissButton = { TextButton(onClick = schliessen) { Text("Abbrechen") } },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
}
