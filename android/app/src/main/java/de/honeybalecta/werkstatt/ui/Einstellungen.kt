package de.honeybalecta.werkstatt.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import de.honeybalecta.werkstatt.BuildConfig
import de.honeybalecta.werkstatt.GitHub
import de.honeybalecta.werkstatt.KATALOG_REPO
import de.honeybalecta.werkstatt.MainActivity
import de.honeybalecta.werkstatt.Planung
import de.honeybalecta.werkstatt.Werkstatt
import de.honeybalecta.werkstatt.WerkstattFehler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun Einstellungen(zurueck: () -> Unit) {
    val ctx = LocalContext.current
    val sp = Werkstatt.speicher
    val scope = rememberCoroutineScope()
    val sichtbar by MainActivity.sichtbar.collectAsState()

    var eingabe by remember { mutableStateOf("") }
    var hatToken by remember { mutableStateOf(sp.hatToken) }
    var tokenStatus by remember { mutableStateOf<String?>(null) }
    var prueft by remember { mutableStateOf(false) }
    var hintergrund by remember { mutableStateOf(sp.imHintergrundPruefen) }
    var auto by remember { mutableStateOf(sp.automatischInstallieren) }
    var wlan by remember { mutableStateOf(sp.nurWlan) }
    val darfInstallieren = remember(sichtbar) { ctx.packageManager.canRequestPackageInstalls() }

    fun pruefeToken(token: String) {
        prueft = true
        tokenStatus = "Prüfe …"
        scope.launch {
            val ergebnis = withContext(Dispatchers.IO) {
                runCatching { GitHub.pruefeToken(token) }
            }
            ergebnis.onSuccess { name ->
                sp.token = token
                hatToken = true
                eingabe = ""
                tokenStatus = "Verbunden als $name. Die Liste wird neu geladen."
                Werkstatt.scope.launch { Werkstatt.pruefen() }
            }.onFailure { x ->
                tokenStatus = (x.message ?: "Fehler") + ((x as? WerkstattFehler)?.hinweis?.let { " – $it" } ?: "")
            }
            prueft = false
        }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                IconButton(onClick = zurueck) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück") }
                Spacer(Modifier.width(4.dp))
                Text("Einstellungen", style = MaterialTheme.typography.headlineSmall)
            }

            Abschnitt("GitHub-Zugang") {
                Text(
                    if (hatToken) "Ein Lesetoken ist gespeichert. Damit sieht die Werkstatt auch Apps aus privaten Repos."
                    else "Für Apps aus privaten Repos (z. B. HoneyCut) braucht die Werkstatt einen Lesetoken. Er bleibt verschlüsselt auf diesem Gerät.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = eingabe,
                    onValueChange = { eingabe = it.trim() },
                    label = { Text(if (hatToken) "Neuen Token eintragen" else "Token (beginnt mit github_pat_)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                        focusedLabelColor = MaterialTheme.colorScheme.primary,
                        cursorColor = MaterialTheme.colorScheme.primary,
                    ),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { pruefeToken(eingabe) }, enabled = eingabe.length > 20 && !prueft, shape = RoundedCornerShape(12.dp)) {
                        Text("Speichern und prüfen")
                    }
                    Spacer(Modifier.width(8.dp))
                    if (hatToken) TextButton(onClick = {
                        sp.token = null; hatToken = false; tokenStatus = "Token entfernt."
                        Werkstatt.scope.launch { Werkstatt.pruefen() }
                    }) { Text("Entfernen") }
                }
                tokenStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                Text("So legst du ihn an", style = MaterialTheme.typography.titleSmall)
                Text(
                    "1. Auf GitHub „Fine-grained token“ anlegen (Knopf unten).\n" +
                        "2. Repository access: „All repositories“ – dann klappen auch künftige private Apps.\n" +
                        "3. Permissions › Repository › Contents: „Read-only“. Mehr braucht es nicht.\n" +
                        "4. Token kopieren und hier einfügen.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = {
                    runCatching {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW,
                            Uri.parse("https://github.com/settings/personal-access-tokens/new")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }) { Text("Token auf GitHub anlegen") }
            }

            Abschnitt("Updates") {
                Schalter("Im Hintergrund nach Updates suchen", "Etwa alle sechs Stunden, mit Benachrichtigung.", hintergrund) {
                    hintergrund = it; sp.imHintergrundPruefen = it; Planung.plane(ctx)
                }
                Schalter(
                    "Updates automatisch installieren",
                    "Ohne Rückfrage – für Apps, die über die Werkstatt installiert wurden. Neue Apps und Fassungswechsel fragen weiterhin.",
                    auto,
                ) { auto = it; sp.automatischInstallieren = it }
                Schalter("Nur im WLAN", "Gilt für die Prüfung im Hintergrund.", wlan) {
                    wlan = it; sp.nurWlan = it; Planung.plane(ctx)
                }
            }

            Abschnitt("Installation") {
                Text(
                    if (darfInstallieren) "Die Werkstatt darf Apps installieren." else "Die Werkstatt darf noch keine Apps installieren.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = {
                    ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")))
                }, shape = RoundedCornerShape(12.dp)) { Text(if (darfInstallieren) "Erlaubnis ansehen" else "Erlauben") }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Samsung: Solange die automatische Sperre an ist, lässt Android keine Installation außerhalb von Play Store und Galaxy Store zu. " +
                        "Ausschalten unter Einstellungen › Sicherheit und Datenschutz › Automatische Sperre. Die Erlaubnis oben gilt dann nur für die Werkstatt.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = {
                    runCatching { ctx.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }) { Text("Sicherheitseinstellungen öffnen") }
            }

            Abschnitt("Über") {
                Text("Honeys Werkstatt ${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})", style = MaterialTheme.typography.bodyMedium)
                Text("Katalog: $KATALOG_REPO › katalog.json", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Abschnitt(titel: String, inhalt: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(titel, style = MaterialTheme.typography.titleMedium)
            inhalt()
        }
    }
}

@Composable
private fun Schalter(titel: String, text: String, an: Boolean, aendern: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(titel, style = MaterialTheme.typography.bodyLarge)
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = an, onCheckedChange = aendern,
            colors = SwitchDefaults.colors(
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                uncheckedBorderColor = MaterialTheme.colorScheme.outline,
                uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        )
    }
}
