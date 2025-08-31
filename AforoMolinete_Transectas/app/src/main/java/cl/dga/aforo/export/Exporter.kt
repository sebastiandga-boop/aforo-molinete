
package cl.dga.aforo.export

import android.content.ContentValues
import android.content.Context
import android.graphics.*
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import cl.dga.aforo.model.*
import kotlin.math.max

class Exporter(private val ctx: Context) {

    private fun createDownloadsFile(name: String, mime: String): java.io.OutputStream {
        val resolver = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
        }
        val uri: Uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("No se pudo crear archivo en Descargas")
        return resolver.openOutputStream(uri) ?: throw IllegalStateException("No se pudo abrir stream")
    }

    fun exportCsv(tr: Transecta, calib: Calibration) {
        val (Q, qi) = FlowCalculator.computeDischarge(tr.verticales, tr.edges)
        val xs = FlowCalculator.toAbsolutes(tr.verticales.map { it.dx })
        val areas = FlowCalculator.computeAreas(tr.verticales, tr.edges)

        val header = listOf(
            "Transecta", tr.nombre,
            "Fecha", tr.fechaIso,
            "Ubicacion", tr.ubicacion,
            "Timer (s)", tr.timerSeg.toString(),
            "Calibracion", "v=a+bRPS (a=${'$'}{calib.a}, b=${'$'}{calib.b}, PPR=${'$'}{calib.pulsesPerRev})"
        )
        val sb = StringBuilder()
        sb.appendLine(header.joinToString(","))
        sb.appendLine("i,x(m),dx(m),h(m),modo,v(m/s),area(m2),qi(m3/s)")
        tr.verticales.forEachIndexed { i, v ->
            val vmed = v.vMedida()
            val modo = if (v.mode == VertMode.ONE_POINT) "0.6h" else "0.2/0.8h"
            sb.appendLine("${'$'}{i+1},${'$'}{xs[i]},${'$'}{v.dx},${'$'}{v.h},${'$'}modo,${'$'}{vmed ?: ""},${'$'}{areas[i]},${'$'}{qi[i]}")
        }
        sb.appendLine(",,,, , , ,TOTAL(Q m3/s),${'$'}Q")

        val os = createDownloadsFile("${'$'}{tr.nombre}.csv", "text/csv")
        os.use { it.write(sb.toString().toByteArray()) }
    }

    fun makeSketchBitmap(tr: Transecta, width: Int = 1200, height: Int = 400): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.rgb(122,95,69)
        paint.strokeWidth = 6f

        val xs = FlowCalculator.toAbsolutes(tr.verticales.map { it.dx })
        val hs = tr.verticales.map { it.h }
        val maxX = xs.maxOrNull() ?: 1.0
        val maxH = max(1.0, hs.maxOrNull() ?: 1.0)

        c.drawLine(0f, height-20f, width.toFloat(), height-20f, paint)

        paint.color = Color.rgb(30,136,229)
        paint.strokeWidth = 4f
        xs.zip(hs).forEach { (x, h) ->
            val xPx = (x / maxX * (width * 0.95)).toFloat() + width*0.025f
            val yTop = (height - 20f - (h / maxH * (height*0.7f))).toFloat()
            c.drawLine(xPx, yTop, xPx, height-20f, paint)
        }
        return bmp
    }

    fun exportPdf(tr: Transecta, calib: Calibration) {
        val (Q, qi) = FlowCalculator.computeDischarge(tr.verticales, tr.edges)
        val xs = FlowCalculator.toAbsolutes(tr.verticales.map { it.dx })
        val areas = FlowCalculator.computeAreas(tr.verticales, tr.edges)
        val bmp = makeSketchBitmap(tr)

        val pdf = android.graphics.pdf.PdfDocument()
        val pageInfo = android.graphics.pdf.PdfDocument.PageInfo.Builder(1240, 1754, 1).create()
        val page = pdf.startPage(pageInfo)
        val c = page.canvas

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 42f; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 28f }
        var y = 80f
        c.drawText("Aforo con Molinete - ${'$'}{tr.nombre}", 60f, y, titlePaint); y += 40f
        c.drawText("Fecha: ${'$'}{tr.fechaIso}   Ubicación: ${'$'}{tr.ubicacion}", 60f, y, textPaint); y += 34f
        c.drawText("Calibración: v = a + b·RPS (a=${'$'}{calib.a}, b=${'$'}{calib.b}, PPR=${'$'}{calib.pulsesPerRev})", 60f, y, textPaint); y += 34f
        c.drawText("Bordes: izq=${'$'}{tr.edges.leftEdgeDist} m, der=${'$'}{tr.edges.rightEdgeDist} m   Timer=${'$'}{tr.timerSeg}s", 60f, y, textPaint); y += 40f

        val scaled = Bitmap.createScaledBitmap(bmp, 1120, 360, true)
        c.drawBitmap(scaled, 60f, y, null); y += 380f

        c.drawText("i   x(m)   dx(m)   h(m)   modo   v(m/s)   area   qi", 60f, y, textPaint); y += 28f
        tr.verticales.forEachIndexed { i, v ->
            val modo = if (v.mode == VertMode.ONE_POINT) "0.6h" else "0.2/0.8h"
            val line = "%2d  %.2f   %.2f   %.2f   %s   %s   %.2f   %.3f".format(
                i+1, xs[i], v.dx, v.h, modo, v.vMedida()?.let { "%.3f".format(it)} ?: "--", areas[i], qi[i]
            )
            c.drawText(line, 60f, y, textPaint); y += 26f
        }
        y += 20f
        c.drawText("Q total = %.3f m³/s".format(Q), 60f, y, titlePaint)

        pdf.finishPage(page)
        val os = createDownloadsFile("${'$'}{tr.nombre}.pdf", "application/pdf")
        os.use { pdf.writeTo(it) }
        pdf.close()
    }
}
