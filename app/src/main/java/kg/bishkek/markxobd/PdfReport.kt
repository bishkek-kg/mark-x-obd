package kg.bishkek.markxobd

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Отчёт для мастера: сводка, графики температуры и напряжения, метки, ошибки. */
object PdfReport {
    private const val W = 595
    private const val H = 842
    private const val M = 40f

    private val title = Paint().apply {
        isAntiAlias = true
        textSize = 18f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        color = Color.BLACK
    }
    private val h2 = Paint().apply {
        isAntiAlias = true
        textSize = 13f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        color = Color.BLACK
    }
    private val body = Paint().apply {
        isAntiAlias = true
        textSize = 10.5f
        color = Color.rgb(30, 30, 30)
    }
    private val small = Paint().apply {
        isAntiAlias = true
        textSize = 8.5f
        color = Color.rgb(100, 100, 100)
    }

    private class Writer(val doc: PdfDocument) {
        var pageNo = 0
        lateinit var page: PdfDocument.Page
        lateinit var c: Canvas
        var y = M

        fun newPage() {
            if (pageNo > 0) doc.finishPage(page)
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create())
            c = page.canvas
            y = M
        }

        fun ensure(h: Float) {
            if (y + h > H - M) newPage()
        }

        fun text(s: String, p: Paint, gap: Float = 4f) {
            for (para in s.split('\n')) {
                for (line in wrap(para, p, W - 2 * M)) {
                    ensure(p.textSize + gap)
                    y += p.textSize
                    c.drawText(line, M, y, p)
                    y += gap
                }
            }
        }

        fun space(h: Float) {
            y += h
        }

        fun finish() {
            if (pageNo > 0) doc.finishPage(page)
        }
    }

    private fun wrap(s: String, p: Paint, width: Float): List<String> {
        if (s.isEmpty()) return listOf("")
        val out = ArrayList<String>()
        var line = StringBuilder()
        for (word in s.split(' ')) {
            val cand = if (line.isEmpty()) word else "$line $word"
            if (p.measureText(cand) <= width) {
                line = StringBuilder(cand)
            } else {
                if (line.isNotEmpty()) out.add(line.toString())
                line = StringBuilder(word)
            }
        }
        if (line.isNotEmpty()) out.add(line.toString())
        return out
    }

    fun build(dir: File, d: TripData, dtc: DtcResult?): File {
        val summary = TripReport.summarize(d)
        val doc = PdfDocument()
        val w = Writer(doc)
        w.newPage()
        w.text("Toyota Mark X 2005, 4GR-FSE — данные OBD", title, 6f)
        w.text(
            "Запись: ${d.file.name} · отчёт создан ${SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.US).format(Date())}",
            small,
            10f,
        )

        w.text("Сводка", h2, 6f)
        w.text(summary.text, body, 3f)
        w.space(10f)

        val cool = d.series("coolant")
        if (cool.size > 2) {
            w.text("Температура охлаждающей жидкости, °C", h2, 4f)
            w.ensure(190f)
            chart(w.c, RectF(M, w.y, W - M, w.y + 180f), cool, danger = Pids.OVERHEAT_C.toFloat(), marks = d.marks.map { it.minute.toFloat() })
            w.space(190f)
        }
        val volt = d.voltageSeries()
        if (volt.size > 2) {
            w.text("Напряжение бортовой сети, В (норма на работающем двигателе 13,5–14,5)", h2, 4f)
            w.ensure(190f)
            chart(w.c, RectF(M, w.y, W - M, w.y + 180f), volt, danger = null, low = CHARGE_LOW_V.toFloat(), marks = d.marks.map { it.minute.toFloat() })
            w.space(190f)
        }
        val speed = d.series("speed")
        if (speed.any { it.second > 1 }) {
            w.text("Скорость, км/ч", h2, 4f)
            w.ensure(140f)
            chart(w.c, RectF(M, w.y, W - M, w.y + 130f), speed, danger = null, marks = emptyList())
            w.space(140f)
        }

        if (dtc != null && (dtc.stored.isNotEmpty() || dtc.pending.isNotEmpty())) {
            w.space(6f)
            w.text("Коды ошибок (последнее чтение)", h2, 6f)
            (dtc.stored.map { it to "сохранённая" } + dtc.pending.map { it to "ожидающая" }).forEach { (code, kind) ->
                w.text("$code ($kind) — ${Dtc.describe(code)}", body, 3f)
            }
        }
        w.space(10f)
        w.text("Вертикальные пунктирные линии на графиках — метки, поставленные во время записи. Отчёт сформирован приложением Mark X OBD.", small, 3f)
        w.finish()

        dir.mkdirs()
        val f = File(dir, "report_" + d.file.nameWithoutExtension.removePrefix("trip_") + ".pdf")
        f.outputStream().use { doc.writeTo(it) }
        doc.close()
        return f
    }

    private fun chart(
        c: Canvas,
        r: RectF,
        pts: List<Pair<Float, Float>>,
        danger: Float?,
        low: Float? = null,
        marks: List<Float>,
    ) {
        val left = r.left + 34f
        val bottom = r.bottom - 16f
        val plot = RectF(left, r.top + 4f, r.right - 4f, bottom)
        var minV = pts.minOf { it.second }
        var maxV = pts.maxOf { it.second }
        if (danger != null && maxV > danger - 10) maxV = maxOf(maxV, danger + 1)
        if (low != null && minV < low + 1) minV = minOf(minV, low - 0.5f)
        if (maxV - minV < 1f) {
            maxV += 0.5f
            minV -= 0.5f
        }
        val pad = (maxV - minV) * 0.08f
        minV -= pad
        maxV += pad
        val maxX = maxOf(pts.last().first, 0.1f)
        fun x(m: Float) = plot.left + plot.width() * (m / maxX)
        fun y(v: Float) = plot.bottom - plot.height() * ((v - minV) / (maxV - minV))

        val grid = Paint().apply { color = Color.rgb(225, 225, 225); strokeWidth = 0.7f }
        val axisText = Paint().apply { isAntiAlias = true; textSize = 8f; color = Color.rgb(110, 110, 110) }
        for (i in 0..4) {
            val v = minV + (maxV - minV) * i / 4f
            val yy = y(v)
            c.drawLine(plot.left, yy, plot.right, yy, grid)
            val label = if (maxV - minV < 10) String.format(Locale.US, "%.1f", v) else String.format(Locale.US, "%.0f", v)
            c.drawText(label, r.left, yy + 3f, axisText)
        }
        val stepMin = when {
            maxX > 60 -> 10f
            maxX > 20 -> 5f
            maxX > 6 -> 2f
            else -> 1f
        }
        var m = 0f
        while (m <= maxX) {
            val xx = x(m)
            c.drawLine(xx, plot.top, xx, plot.bottom, grid)
            c.drawText("${m.toInt()} мин", xx - 8f, r.bottom - 2f, axisText)
            m += stepMin
        }
        danger?.let {
            if (it in minV..maxV) {
                c.drawLine(plot.left, y(it), plot.right, y(it), Paint().apply { color = Color.rgb(200, 40, 40); strokeWidth = 1.2f })
            }
        }
        low?.let {
            if (it in minV..maxV) {
                c.drawLine(plot.left, y(it), plot.right, y(it), Paint().apply { color = Color.rgb(220, 140, 0); strokeWidth = 1.2f })
            }
        }
        val markPaint = Paint().apply {
            color = Color.rgb(120, 80, 200)
            strokeWidth = 1f
            pathEffect = android.graphics.DashPathEffect(floatArrayOf(4f, 3f), 0f)
        }
        marks.forEach { mm -> c.drawLine(x(mm), plot.top, x(mm), plot.bottom, markPaint) }

        val path = Path()
        pts.forEachIndexed { i, (mx, v) ->
            if (i == 0) path.moveTo(x(mx), y(v)) else path.lineTo(x(mx), y(v))
        }
        c.drawPath(
            path,
            Paint().apply {
                isAntiAlias = true
                style = Paint.Style.STROKE
                strokeWidth = 1.4f
                color = Color.rgb(15, 91, 181)
            },
        )
        c.drawRect(plot, Paint().apply { style = Paint.Style.STROKE; color = Color.rgb(190, 190, 190); strokeWidth = 0.7f })
    }
}
