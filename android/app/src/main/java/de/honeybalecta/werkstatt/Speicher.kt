package de.honeybalecta.werkstatt

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Was die Werkstatt sich merkt: Einstellungen, Token (verschlüsselt), installierte Stände. */
class Speicher(context: Context) {
    private val p: SharedPreferences = context.getSharedPreferences("werkstatt", Context.MODE_PRIVATE)

    // ---------- Einstellungen ----------
    var imHintergrundPruefen: Boolean
        get() = p.getBoolean("hintergrund", true)
        set(v) = p.edit().putBoolean("hintergrund", v).apply()
    var automatischInstallieren: Boolean
        get() = p.getBoolean("auto", false)
        set(v) = p.edit().putBoolean("auto", v).apply()
    var nurWlan: Boolean
        get() = p.getBoolean("wlan", true)
        set(v) = p.edit().putBoolean("wlan", v).apply()
    var zuletztGeprueft: Long
        get() = p.getLong("geprueft", 0)
        set(v) = p.edit().putLong("geprueft", v).apply()
    var katalogText: String?
        get() = p.getString("katalog", null)
        set(v) = p.edit().putString("katalog", v).apply()
    /** Updates, über die schon benachrichtigt wurde (damit nicht stündlich dieselbe Meldung kommt). */
    var gemeldet: String
        get() = p.getString("gemeldet", "") ?: ""
        set(v) = p.edit().putString("gemeldet", v).apply()

    // ---------- Fassungen ----------
    fun fassung(id: String): String? = p.getString("fassung.$id", null)
    fun setzeFassung(id: String, fassung: String?) = p.edit().putString("fassung.$id", fassung).apply()

    // ---------- Installationsnachweise ----------
    /** Welche Release-Datei die Werkstatt zuletzt für ein Paket installiert hat. */
    data class Nachweis(val dateiId: Long, val dateiStand: Long, val versionCode: Long, val paketZeit: Long, val fassung: String?)

    fun nachweis(paket: String): Nachweis? = p.getString("nachweis.$paket", null)?.let {
        runCatching {
            val o = JSONObject(it)
            Nachweis(o.getLong("datei"), o.getLong("stand"), o.getLong("code"), o.getLong("zeit"), o.optString("fassung").ifBlank { null })
        }.getOrNull()
    }

    fun setzeNachweis(paket: String, n: Nachweis?) {
        if (n == null) { p.edit().remove("nachweis.$paket").apply(); return }
        val o = JSONObject().put("datei", n.dateiId).put("stand", n.dateiStand).put("code", n.versionCode)
            .put("zeit", n.paketZeit).put("fassung", n.fassung ?: "")
        p.edit().putString("nachweis.$paket", o.toString()).apply()
    }

    /** Vorgemerkt beim Start einer Installation (paketZeit = Startzeit), bestätigt nach Erfolg. */
    fun merkeAusstehend(paket: String, n: Nachweis) =
        p.edit().putString("ausstehend.$paket", JSONObject().put("datei", n.dateiId).put("stand", n.dateiStand)
            .put("code", n.versionCode).put("zeit", n.paketZeit).put("fassung", n.fassung ?: "").toString()).apply()

    fun ausstehend(paket: String): Nachweis? = p.getString("ausstehend.$paket", null)?.let {
        runCatching {
            val o = JSONObject(it)
            Nachweis(o.getLong("datei"), o.getLong("stand"), o.getLong("code"), o.optLong("zeit"), o.optString("fassung").ifBlank { null })
        }.getOrNull()
    }

    fun vergissAusstehend(paket: String) = p.edit().remove("ausstehend.$paket").apply()

    // ---------- Web-Werkzeuge ----------
    fun webSha(id: String, datei: String): String? = p.getString("web.$id.$datei", null)
    fun setzeWebSha(id: String, datei: String, sha: String?) = p.edit().putString("web.$id.$datei", sha).apply()
    var webGeprueft: Long
        get() = p.getLong("webgeprueft", 0)
        set(v) = p.edit().putLong("webgeprueft", v).apply()

    // ---------- Token ----------
    /** GitHub-Lesetoken, verschlüsselt mit einem Schlüssel aus dem Android-Schlüsselspeicher. */
    var token: String?
        get() {
            val roh = p.getString("token", null) ?: return null
            return runCatching {
                val bytes = Base64.decode(roh, Base64.NO_WRAP)
                val iv = bytes.copyOfRange(0, 12)
                val c = Cipher.getInstance("AES/GCM/NoPadding")
                c.init(Cipher.DECRYPT_MODE, schluessel(), GCMParameterSpec(128, iv))
                String(c.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
            }.getOrNull()
        }
        set(v) {
            if (v.isNullOrBlank()) { p.edit().remove("token").apply(); return }
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, schluessel())
            val daten = c.iv + c.doFinal(v.trim().toByteArray(Charsets.UTF_8))
            p.edit().putString("token", Base64.encodeToString(daten, Base64.NO_WRAP)).apply()
        }

    val hatToken: Boolean get() = p.contains("token")

    private fun schluessel(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return g.generateKey()
    }

    companion object { private const val ALIAS = "werkstatt-token" }
}
