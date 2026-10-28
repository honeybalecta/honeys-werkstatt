package de.honeybalecta.werkstatt

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import kotlin.math.cos
import kotlin.math.sin

/** Wabensymbole für Web-Werkzeuge (Startbildschirm, Übersicht der zuletzt genutzten Apps). */
object Symbole {
    private const val ANTHRAZIT = 0xFF131312.toInt()
    private const val CREME = 0xFFEDE7DA.toInt()

    fun schrift(context: Context, fett: Boolean = true): Typeface =
        runCatching { Typeface.createFromAsset(context.assets, if (fett) "fonts/Inter-Bold.ttf" else "fonts/Inter-Regular.ttf") }
            .getOrElse { if (fett) Typeface.DEFAULT_BOLD else Typeface.DEFAULT }

    fun wabe(cx: Float, cy: Float, r: Float): Path = Path().apply {
        for (i in 0 until 6) {
            val w = Math.toRadians((-90 + 60 * i).toDouble())
            val x = cx + r * cos(w).toFloat()
            val y = cy + r * sin(w).toFloat()
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    }

    fun initiale(name: String): String = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"

    /**
     * Quadratisches Symbol: Anthrazit, Wabe als Umriss in Creme, Anfangsbuchstabe.
     * Für adaptive Symbole liegt alles im inneren Bereich (66 von 108).
     */
    fun webSymbol(context: Context, name: String, groesse: Int = 432): Bitmap {
        val bmp = Bitmap.createBitmap(groesse, groesse, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(ANTHRAZIT)
        val m = groesse / 2f
        val r = groesse * 0.25f
        val linie = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = CREME; style = Paint.Style.STROKE; strokeWidth = groesse * 0.035f; strokeJoin = Paint.Join.ROUND
        }
        c.drawPath(wabe(m, m, r), linie)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = CREME; textAlign = Paint.Align.CENTER; textSize = groesse * 0.24f; typeface = schrift(context)
        }
        val y = m - (text.descent() + text.ascent()) / 2
        c.drawText(initiale(name), m, y, text)
        return bmp
    }
}
