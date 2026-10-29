package com.example.nova.trained

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.File
import java.io.FileOutputStream

/**
 * Round B — the thin [android.graphics.pdf.PdfDocument] renderer for
 * [CollectorReportModel]. ALL content decisions live in the model
 * (see [CollectorReport]); this file only lays it out: A4 pages, an
 * accent title block, key/value meta + counts, a per-source table,
 * failures, warnings, artifacts, and a "Page X of Y" footer.
 *
 * Pagination is block-based: every block is measured with StaticLayout
 * before placement and moved whole to the next page when it would
 * overflow — no clipped content. (Every block in a report — a meta
 * line, a table row, a bullet — is far shorter than one page.)
 */
object PdfReportWriter {

    private const val PAGE_W = 595
    private const val PAGE_H = 842
    private const val MARGIN = 42f
    private const val CONTENT_W = PAGE_W - 2 * MARGIN
    private const val TOP = 48f
    private const val BOTTOM = PAGE_H - 56f

    // Matches the app theme's WarmAccent (0xFFD96B27) without pulling
    // Compose into a graphics-only renderer.
    private val ACCENT = Color.rgb(217, 107, 39)
    private val ACCENT_DARK = Color.rgb(154, 74, 27)
    private val INK = Color.rgb(26, 26, 26)
    private val BODY = Color.rgb(34, 34, 34)
    private val MUTED = Color.rgb(110, 110, 110)
    private val HAIRLINE = Color.rgb(214, 208, 201)

    private enum class Style { TITLE, SUBTITLE, HEADING, LABEL, VALUE, BODY, SMALL, MONO, TABLE_HEAD, TABLE_CELL }

    private fun paint(style: Style): TextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        when (style) {
            Style.TITLE -> { textSize = 19f; typeface = Typeface.DEFAULT_BOLD; color = INK }
            Style.SUBTITLE -> { textSize = 10.5f; color = MUTED }
            Style.HEADING -> { textSize = 12.5f; typeface = Typeface.DEFAULT_BOLD; color = ACCENT_DARK }
            Style.LABEL -> { textSize = 10f; typeface = Typeface.DEFAULT_BOLD; color = BODY }
            Style.VALUE -> { textSize = 10f; color = BODY }
            Style.BODY -> { textSize = 10.5f; color = BODY }
            Style.SMALL -> { textSize = 8.5f; color = MUTED }
            Style.MONO -> { textSize = 8.5f; typeface = Typeface.MONOSPACE; color = Color.rgb(68, 68, 68) }
            Style.TABLE_HEAD -> { textSize = 9.5f; typeface = Typeface.DEFAULT_BOLD; color = Color.WHITE }
            Style.TABLE_CELL -> { textSize = 10f; color = BODY }
        }
    }

    private sealed interface Op {
        data class Text(val x: Float, val top: Float, val width: Float, val text: String, val style: Style) : Op
        data class Rect(val x: Float, val top: Float, val width: Float, val height: Float, val color: Int) : Op
        data class HLine(val top: Float, val color: Int) : Op
    }

    private fun layoutHeight(text: String, style: Style, width: Float): Float {
        if (text.isEmpty()) return 0f
        val sl = StaticLayout.Builder.obtain(text, 0, text.length, paint(style), width.toInt())
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.12f)
            .setIncludePad(false)
            .build()
        return sl.height.toFloat()
    }

    /** Accumulates blocks page by page; a block never splits across pages. */
    private class Pager {
        val pages = mutableListOf<MutableList<Op>>()
        private var ops = mutableListOf<Op>()
        private var y = TOP

        init { pages += ops }

        private fun newPage() {
            ops = mutableListOf()
            pages += ops
            y = TOP
        }

        /** Ensures [height] fits on the current page, else starts a new one. */
        private fun ensure(height: Float) {
            if (y + height > BOTTOM && ops.isNotEmpty()) newPage()
        }

        fun gap(h: Float) {
            // A gap never starts a page by itself; it just moves y when
            // there is room, otherwise the next block paginates.
            if (y + h <= BOTTOM) y += h
        }

        fun rect(height: Float, color: Int, width: Float = CONTENT_W, x: Float = MARGIN) {
            ensure(height)
            ops += Op.Rect(x, y, width, height, color)
            y += height
        }

        fun hline(color: Int = HAIRLINE) {
            ensure(1f)
            ops += Op.HLine(y, color)
            y += 1f
        }

        fun text(text: String, style: Style, width: Float = CONTENT_W, x: Float = MARGIN, gapAfter: Float = 0f) {
            if (text.isEmpty()) return
            val h = layoutHeight(text, style, width)
            ensure(h)
            ops += Op.Text(x, y, width, text, style)
            y += h + gapAfter
        }

        /** Label (fixed column) + value (wraps in the remaining width), same block. */
        fun keyValue(label: String, value: String, labelWidth: Float = 168f) {
            val valueWidth = CONTENT_W - labelWidth
            val h = maxOf(
                layoutHeight(label, Style.LABEL, labelWidth),
                layoutHeight(value, Style.VALUE, valueWidth)
            )
            ensure(h)
            ops += Op.Text(MARGIN, y, labelWidth, label, Style.LABEL)
            ops += Op.Text(MARGIN + labelWidth, y, valueWidth, value, Style.VALUE)
            y += h + 2.5f
        }

        /** Three side-by-side cells (source table rows), one block. */
        fun row3(c1: String, w1: Float, c2: String, w2: Float, c3: String, style: Style) {
            val w3 = CONTENT_W - w1 - w2
            val h = maxOf(
                layoutHeight(c1, style, w1),
                layoutHeight(c2, style, w2),
                layoutHeight(c3, style, w3)
            )
            ensure(h)
            ops += Op.Text(MARGIN, y, w1, c1, style)
            ops += Op.Text(MARGIN + w1, y, w2, c2, style)
            ops += Op.Text(MARGIN + w1 + w2, y, w3, c3, style)
            y += h + 2f
        }

        fun heading(text: String) {
            gap(12f)
            text(text, Style.HEADING, gapAfter = 5f)
            hline(ACCENT)
            gap(5f)
        }
    }

    private fun buildPages(model: CollectorReportModel): List<List<Op>> {
        val p = Pager()
        // Title block
        p.rect(4f, ACCENT)
        p.gap(10f)
        p.text(model.title, Style.TITLE, gapAfter = 3f)
        p.text("${model.kindLabel} · ${model.reportId}", Style.SUBTITLE, gapAfter = 4f)
        p.hline()
        p.gap(6f)
        for ((k, v) in model.meta) p.keyValue(k, v)

        p.heading("Summary")
        for ((k, v) in model.counts) p.keyValue(k, v)

        p.heading("Sources")
        if (model.sourceRows.isEmpty()) {
            p.text("No sources were run.", Style.BODY)
        } else {
            // Table header: an accent band; drawPage paints the column
            // labels over any 15pt accent band in the op stream.
            p.rect(15f, ACCENT)
            headerLabels = listOf(
                Triple("Source", 0f, 120f),
                Triple("Status", 120f, 150f),
                Triple("Result", 270f, CONTENT_W - 270f)
            )
            for (row in model.sourceRows) {
                p.row3(row.label, 120f, row.status, 150f, row.detail, Style.TABLE_CELL)
                p.text(row.url, Style.MONO, gapAfter = if (row.note != null) 1f else 4f)
                row.note?.let { p.text(it, Style.SMALL, gapAfter = 4f) }
                p.hline()
                p.gap(3f)
            }
        }

        p.heading("Failed sources")
        if (model.failures.isEmpty()) {
            p.text("None.", Style.BODY)
        } else {
            for (failure in model.failures) p.text("•  $failure", Style.BODY, gapAfter = 2.5f)
        }

        p.heading("Warnings & limitations")
        for (warning in model.warnings) p.text("•  $warning", Style.BODY, gapAfter = 2.5f)

        p.heading("Artifacts")
        if (model.artifactNames.isEmpty()) {
            p.text("No artifact files were recorded for this run.", Style.BODY)
        } else {
            for (name in model.artifactNames) p.text(name, Style.MONO, gapAfter = 1.5f)
        }
        return p.pages
    }

    // Header labels recorded by buildPages for the CURRENT model; the
    // renderer is a singleton used from IO threads one run at a time —
    // guard by synchronizing write().
    private var headerLabels: List<Triple<String, Float, Float>> = emptyList()

    /** Renders [model] to [file] as a PDF. Returns true on success. */
    @Synchronized
    fun write(model: CollectorReportModel, file: File): Boolean {
        return try {
            val pages = buildPages(model)
            val doc = PdfDocument()
            pages.forEachIndexed { index, ops ->
                val pageInfo = PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, index + 1).create()
                val page = doc.startPage(pageInfo)
                drawPage(page.canvas, ops, model, index + 1, pages.size)
                doc.finishPage(page)
            }
            file.parentFile?.mkdirs()
            FileOutputStream(file).use { doc.writeTo(it) }
            doc.close()
            file.exists() && file.length() > 0
        } catch (e: Exception) {
            false
        }
    }

    private fun drawPage(canvas: Canvas, ops: List<Op>, model: CollectorReportModel, pageNum: Int, pageCount: Int) {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        var headerBandPending = false
        for (op in ops) {
            when (op) {
                is Op.Rect -> {
                    fill.color = op.color
                    canvas.drawRect(op.x, op.top, op.x + op.width, op.top + op.height, fill)
                    if (op.height == 15f && op.color == ACCENT) headerBandPending = true
                }
                is Op.HLine -> {
                    fill.color = op.color
                    canvas.drawRect(MARGIN, op.top, MARGIN + CONTENT_W, op.top + 1f, fill)
                }
                is Op.Text -> {
                    val sl = StaticLayout.Builder
                        .obtain(op.text, 0, op.text.length, paint(op.style), op.width.toInt())
                        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                        .setLineSpacing(0f, 1.12f)
                        .setIncludePad(false)
                        .build()
                    canvas.save()
                    canvas.translate(op.x, op.top)
                    sl.draw(canvas)
                    canvas.restore()
                }
            }
            // Draw the table-header labels over the accent band just drawn.
            if (headerBandPending && op is Op.Rect) {
                headerBandPending = false
                for ((label, dx, w) in headerLabels) {
                    val sl = StaticLayout.Builder
                        .obtain(label, 0, label.length, paint(Style.TABLE_HEAD), w.toInt())
                        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                        .setIncludePad(false)
                        .build()
                    canvas.save()
                    canvas.translate(op.x + dx + 2f, op.top + 2.5f)
                    sl.draw(canvas)
                    canvas.restore()
                }
            }
        }
        // Footer
        fill.color = HAIRLINE
        canvas.drawRect(MARGIN, PAGE_H - 40f, MARGIN + CONTENT_W, PAGE_H - 39f, fill)
        val left = StaticLayout.Builder
            .obtain(model.title, 0, model.title.length, paint(Style.SMALL), 340)
            .setIncludePad(false).build()
        canvas.save(); canvas.translate(MARGIN, PAGE_H - 34f); left.draw(canvas); canvas.restore()
        val rightText = "Page $pageNum of $pageCount"
        val right = StaticLayout.Builder
            .obtain(rightText, 0, rightText.length, paint(Style.SMALL), 120)
            .setAlignment(Layout.Alignment.ALIGN_OPPOSITE)
            .setIncludePad(false).build()
        canvas.save(); canvas.translate(MARGIN + CONTENT_W - 120f, PAGE_H - 34f); right.draw(canvas); canvas.restore()
    }
}
