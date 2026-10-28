package de.honeybalecta.werkstatt

import android.content.Context
import android.webkit.MimeTypeMap
import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Web-Werkzeuge: HTML-Dateien aus einem Repo, die die Werkstatt selbst vorhält und anzeigt.
 * Sie liegen unter files/web/<id>/ und werden unter https://appassets.androidplatform.net/web/<id>/ ausgeliefert.
 */
object WebWerkzeug {
    const val HOST = "appassets.androidplatform.net"
    private const val BRUECKE = "__werkstatt/bruecke.js"

    fun wurzel(context: Context = Werkstatt.app) = File(context.filesDir, "web")
    fun ordner(id: String) = File(wurzel(), id)
    fun bereit(e: Eintrag) = File(ordner(e.id), e.start).isFile
    fun adresse(e: Eintrag) = "https://$HOST/web/${e.id}/${e.start}"

    /** true, wenn im Repo eine andere Fassung liegt als hier. */
    fun gibtUpdate(e: Eintrag, token: String?): Boolean = e.dateien.any { d ->
        val sha = GitHub.inhalt(e.repo, d, token).sha
        sha != Werkstatt.speicher.webSha(e.id, d) || !File(ordner(e.id), d).isFile
    }

    /** Holt alle geänderten Dateien. */
    fun aktualisiere(e: Eintrag, token: String?) {
        for (d in e.dateien) {
            val ziel = File(ordner(e.id), d)
            if (!ziel.canonicalPath.startsWith(ordner(e.id).canonicalPath + File.separator))
                throw WerkstattFehler("Ungültiger Dateipfad im Katalog: $d")
            val inhalt = GitHub.inhalt(e.repo, d, token)
            if (inhalt.sha == Werkstatt.speicher.webSha(e.id, d) && ziel.isFile) continue
            GitHub.ladeInhalt(e.repo, d, token, ziel)
            Werkstatt.speicher.setzeWebSha(e.id, d, inhalt.sha)
        }
    }

    fun loeschen(e: Eintrag) {
        ordner(e.id).deleteRecursively()
        e.dateien.forEach { Werkstatt.speicher.setzeWebSha(e.id, it, null) }
    }

    /** Liefert die Dateien aus; in HTML-Seiten kommt vorne die Brücke (Speichern, Downloads) hinein. */
    class Dateien(private val context: Context) : WebViewAssetLoader.PathHandler {
        override fun handle(path: String): WebResourceResponse? {
            if (path == BRUECKE) {
                return antwort("text/javascript", context.assets.open("bruecke.js").use { it.readBytes() })
            }
            val wurzel = wurzel(context).canonicalFile
            val f = File(wurzel, path).canonicalFile
            if (!f.path.startsWith(wurzel.path + File.separator) || !f.isFile) {
                return WebResourceResponse("text/plain", "utf-8", 404, "Not Found", mapOf("Cache-Control" to "no-store"),
                    ByteArrayInputStream("Nicht gefunden".toByteArray()))
            }
            val mime = mimeVon(f.name)
            if (mime == "text/html") {
                val html = f.readText(Charsets.UTF_8)
                return antwort(mime, einbauen(html).toByteArray(Charsets.UTF_8))
            }
            return WebResourceResponse(mime, if (mime.startsWith("text/") || mime.endsWith("javascript") || mime.endsWith("json")) "utf-8" else null,
                200, "OK", mapOf("Cache-Control" to "no-cache"), f.inputStream())
        }

        private fun antwort(mime: String, bytes: ByteArray) =
            WebResourceResponse(mime, "utf-8", 200, "OK", mapOf("Cache-Control" to "no-cache"), ByteArrayInputStream(bytes))

        private fun einbauen(html: String): String {
            val tag = """<script src="/web/$BRUECKE"></script>"""
            Regex("<head[^>]*>", RegexOption.IGNORE_CASE).find(html)?.let { return html.substring(0, it.range.last + 1) + tag + html.substring(it.range.last + 1) }
            Regex("<html[^>]*>", RegexOption.IGNORE_CASE).find(html)?.let { return html.substring(0, it.range.last + 1) + tag + html.substring(it.range.last + 1) }
            return tag + html
        }
    }

    fun mimeVon(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "html", "htm" -> "text/html"
            "js", "mjs" -> "text/javascript"
            "css" -> "text/css"
            "json" -> "application/json"
            "svg" -> "image/svg+xml"
            "wasm" -> "application/wasm"
            "woff2" -> "font/woff2"
            "woff" -> "font/woff"
            "ttf" -> "font/ttf"
            "onnx", "bin" -> "application/octet-stream"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        }
    }
}
