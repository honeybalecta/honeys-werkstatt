package de.honeybalecta.werkstatt

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewAssetLoader
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Zeigt ein Web-Werkzeug im Vollbild. Vor dem Öffnen holt es kurz die neueste Fassung aus dem Repo. */
class WebActivity : ComponentActivity() {

    companion object {
        fun intent(context: Context, id: String): Intent =
            Intent(context, WebActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .setData(Uri.parse("werkstatt://web/$id"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_NEW_TASK)

        fun oeffne(context: Context, id: String) = context.startActivity(intent(context, id))

        private const val ANTHRAZIT = 0xFF131312.toInt()
        private const val CREME = 0xFFEDE7DA.toInt()
        private const val GRAU = 0xFFA9A397.toInt()
    }

    private lateinit var web: WebView
    private lateinit var huelle: LinearLayout
    private lateinit var text: TextView
    private lateinit var kreis: ProgressBar
    private lateinit var nochmal: Button
    private var rueckruf: ValueCallback<Array<Uri>>? = null

    private val auswahl = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val cb = rueckruf ?: return@registerForActivityResult
        rueckruf = null
        val uris = ArrayList<Uri>()
        r.data?.clipData?.let { c -> for (i in 0 until c.itemCount) uris += c.getItemAt(i).uri }
        if (uris.isEmpty()) r.data?.data?.let { uris += it }
        cb.onReceiveValue(if (r.resultCode == Activity.RESULT_OK && uris.isNotEmpty()) uris.toTypedArray() else null)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        Werkstatt.init(this)
        val id = intent?.data?.lastPathSegment
        val e = id?.let { Werkstatt.eintrag(it) }?.takeIf { it.typ == Typ.WEB }
        if (e == null) {
            Toast.makeText(this, "Dieses Werkzeug gibt es nicht mehr im Katalog.", Toast.LENGTH_LONG).show()
            finish(); return
        }
        @Suppress("DEPRECATION")
        setTaskDescription(ActivityManager.TaskDescription(e.name, Symbole.webSymbol(this, e.name, 192), ANTHRAZIT))

        val wurzel = FrameLayout(this).apply { setBackgroundColor(ANTHRAZIT) }
        web = WebView(this).apply { setBackgroundColor(ANTHRAZIT); visibility = View.INVISIBLE }
        wurzel.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        huelle = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(64, 64, 64, 64) }
        kreis = ProgressBar(this)
        text = TextView(this).apply { setTextColor(CREME); textSize = 16f; gravity = Gravity.CENTER; setPadding(0, 32, 0, 0) }
        nochmal = Button(this).apply { text = "Erneut versuchen"; visibility = View.GONE; setOnClickListener { starte(e) } }
        huelle.addView(kreis); huelle.addView(text); huelle.addView(nochmal)
        wurzel.addView(huelle, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        ViewCompat.setOnApplyWindowInsetsListener(wurzel) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            WindowInsetsCompat.CONSUMED
        }
        setContentView(wurzel)

        val lader = WebViewAssetLoader.Builder()
            .setDomain(WebWerkzeug.HOST)
            .addPathHandler("/web/", WebWerkzeug.Dateien(this))
            .build()

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            textZoom = 100
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            setSupportZoom(false)
        }
        web.addJavascriptInterface(Bruecke(this), "WerkstattBruecke")
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                lader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.url.host == WebWerkzeug.HOST) return false
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
                return true
            }

            override fun onPageFinished(view: WebView, url: String?) {
                web.visibility = View.VISIBLE
                huelle.visibility = View.GONE
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(view: WebView, cb: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                rueckruf?.onReceiveValue(null)
                rueckruf = cb
                val i = runCatching { params.createIntent() }.getOrNull() ?: Intent(Intent.ACTION_GET_CONTENT).setType("*/*")
                i.addCategory(Intent.CATEGORY_OPENABLE)
                if (params.mode == FileChooserParams.MODE_OPEN_MULTIPLE) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                return try { auswahl.launch(i); true } catch (x: Exception) { rueckruf = null; false }
            }
        }
        web.setDownloadListener { url, _, _, _, _ ->
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        }
        onBackPressedDispatcher.addCallback(this) { if (web.canGoBack()) web.goBack() else finish() }

        if (savedInstanceState != null && WebWerkzeug.bereit(e)) {
            web.restoreState(savedInstanceState)
            if (web.url == null) web.loadUrl(WebWerkzeug.adresse(e))
        } else {
            starte(e)
        }
    }

    private fun starte(e: Eintrag) {
        nochmal.visibility = View.GONE
        kreis.visibility = View.VISIBLE
        huelle.visibility = View.VISIBLE
        val bereit = WebWerkzeug.bereit(e)
        text.text = if (bereit) "${e.name} wird geöffnet …" else "${e.name} wird geladen …"
        text.setTextColor(CREME)
        lifecycleScope.launch {
            val token = Werkstatt.speicher.token
            val holen = Werkstatt.scope.async { runCatching { WebWerkzeug.aktualisiere(e, token) }.exceptionOrNull() }
            // Liegt schon eine Fassung vor, nur kurz auf eine neuere warten; sonst bis zum Ende
            val fehler = if (bereit) { withTimeoutOrNull(4_000) { holen.await() }; null } else holen.await()
            if (fehler != null && !WebWerkzeug.bereit(e)) {
                kreis.visibility = View.GONE
                text.text = "${e.name} ließ sich nicht laden.\n${fehler.message ?: ""}" +
                    ((fehler as? WerkstattFehler)?.hinweis?.let { "\n\n$it" } ?: "")
                text.setTextColor(GRAU)
                nochmal.visibility = View.VISIBLE
                return@launch
            }
            Werkstatt.lokal()
            web.loadUrl(WebWerkzeug.adresse(e))
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::web.isInitialized) web.saveState(outState)
    }

    override fun onPause() { if (::web.isInitialized) web.onPause(); super.onPause() }
    override fun onResume() { super.onResume(); if (::web.isInitialized) web.onResume() }
    override fun onDestroy() {
        if (::web.isInitialized) { (web.parent as? ViewGroup)?.removeView(web); web.destroy() }
        super.onDestroy()
    }
}

/** Nimmt Dateien aus der Seite entgegen und legt sie in „Bilder“ oder „Download“ ab. */
class Bruecke(private val activity: Activity) {
    private data class Offen(val datei: File, val name: String, val mime: String)
    private val offen = ConcurrentHashMap<String, Offen>()
    @Volatile private var toast: Toast? = null

    @JavascriptInterface
    fun beginnen(name: String, mime: String): String {
        val h = UUID.randomUUID().toString()
        val f = File(activity.cacheDir, "bruecke/$h").apply { parentFile?.mkdirs(); delete() }
        val sauber = name.replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), "_").trim().ifBlank { "datei" }.take(120)
        offen[h] = Offen(f, sauber, mime)
        return h
    }

    @JavascriptInterface
    fun teil(h: String, b64: String) {
        val o = offen[h] ?: return
        FileOutputStream(o.datei, true).use { it.write(Base64.decode(b64, Base64.NO_WRAP)) }
    }

    @JavascriptInterface
    fun abschliessen(h: String): String {
        val o = offen.remove(h) ?: return "FEHLER:Unbekannter Vorgang"
        return try {
            val mime = o.mime.ifBlank { WebWerkzeug.mimeVon(o.name) }
            val bild = mime.startsWith("image/")
            val sammlung = if (bild) MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                           else MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val ort = (if (bild) Environment.DIRECTORY_PICTURES else Environment.DIRECTORY_DOWNLOADS) + "/Honeys Werkstatt"
            val werte = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, o.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, ort)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val r = activity.contentResolver
            val uri = r.insert(sammlung, werte) ?: return "FEHLER:Speicherort nicht verfügbar"
            r.openOutputStream(uri)?.use { aus -> o.datei.inputStream().use { it.copyTo(aus) } }
                ?: return "FEHLER:Datei ließ sich nicht schreiben"
            werte.clear(); werte.put(MediaStore.MediaColumns.IS_PENDING, 0)
            r.update(uri, werte, null, null)
            val anzeige = (if (bild) "Bilder" else "Download") + " › Honeys Werkstatt › " + o.name
            melden("Gespeichert: $anzeige")
            anzeige
        } catch (x: Exception) {
            "FEHLER:" + (x.message ?: "Speichern fehlgeschlagen")
        } finally {
            o.datei.delete()
        }
    }

    @JavascriptInterface
    fun melden(text: String) {
        activity.runOnUiThread {
            toast?.cancel()
            toast = Toast.makeText(activity, text, Toast.LENGTH_SHORT).also { it.show() }
        }
    }
}
