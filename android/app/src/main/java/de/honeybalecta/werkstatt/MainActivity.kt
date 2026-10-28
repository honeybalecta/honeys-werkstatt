package de.honeybalecta.werkstatt

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import de.honeybalecta.werkstatt.ui.Einstellungen
import de.honeybalecta.werkstatt.ui.Liste
import de.honeybalecta.werkstatt.ui.WerkstattTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    companion object {
        /** Zählt hoch, wenn die App wieder sichtbar wird (Berechtigungen und Stände neu lesen). */
        val sichtbar = MutableStateFlow(0)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Werkstatt.init(this)
        Hinweise.selbstUpdateWeg(this)
        setContent {
            WerkstattTheme {
                var seite by rememberSaveable { mutableStateOf("liste") }
                val erlaubnis = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
                LaunchedEffect(Unit) {
                    if (Build.VERSION.SDK_INT >= 33 &&
                        ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    ) erlaubnis.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                BackHandler(enabled = seite != "liste") { seite = "liste" }
                when (seite) {
                    "einstellungen" -> Einstellungen(zurueck = { seite = "liste" })
                    else -> Liste(zuEinstellungen = { seite = "einstellungen" })
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        Werkstatt.vordergrund = true
    }

    override fun onStop() {
        Werkstatt.vordergrund = false
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        Werkstatt.lokal()
        sichtbar.value++
        // Beim Öffnen nachsehen, wenn die letzte Prüfung über eine Viertelstunde her ist
        if (System.currentTimeMillis() - Werkstatt.speicher.zuletztGeprueft > 15 * 60_000 && !Werkstatt.prueftGerade.value) {
            Werkstatt.scope.launch { Werkstatt.pruefen() }
        }
    }
}
