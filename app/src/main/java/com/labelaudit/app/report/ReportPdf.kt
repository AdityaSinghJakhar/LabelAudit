package com.labelaudit.app.report

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import com.labelaudit.app.pipeline.RuleStatus
import java.io.File

/**
 * Renders a [ScanReport] to PDF.
 *
 * Written with the platform PdfDocument rather than a PDF library: the report
 * is a handful of text blocks and small bitmaps, and adding a dependency for
 * that would cost more than it saves.
 *
 * The PDF is the evidence artefact an inspector files, so it carries the
 * value read and the citation for every check — the same content as the
 * screen, not a summary of it.
 */
object ReportPdf {

    private const val PAGE_WIDTH = 595   // A4 at 72 dpi
    private const val PAGE_HEIGHT = 842
    private const val MARGIN = 40f
    private const val CONTENT_WIDTH = PAGE_WIDTH - 2 * MARGIN

    private val title = Paint().apply {
        color = Color.BLACK
        textSize = 18f
        isFakeBoldText = true
        isAntiAlias = true
    }
    private val heading = Paint().apply {
        color = Color.BLACK
        textSize = 12f
        isFakeBoldText = true
        isAntiAlias = true
    }
    private val body = Paint().apply {
        color = Color.DKGRAY
        textSize = 9.5f
        isAntiAlias = true
    }
    private val small = Paint().apply {
        color = Color.GRAY
        textSize = 8f
        isAntiAlias = true
    }
    private val rule = Paint().apply {
        color = Color.LTGRAY
        strokeWidth = 0.7f
    }

    fun write(context: Context, report: ScanReport, destination: File): File {
        val document = PdfDocument()
        var pageNumber = 1
        var page = startPage(document, pageNumber)
        var canvas = page.canvas
        var y = MARGIN

        fun newPageIfNeeded(needed: Float) {
            if (y + needed <= PAGE_HEIGHT - MARGIN) return
            document.finishPage(page)
            pageNumber += 1
            page = startPage(document, pageNumber)
            canvas = page.canvas
            y = MARGIN
        }

        // ---- header
        canvas.drawText("Label Compliance Report", MARGIN, y + 14f, title)
        y += 30f
        canvas.drawText("Verdict: ${report.verdict}", MARGIN, y, heading)
        y += 14f
        y = drawWrapped(canvas, report.verdictExplanation, MARGIN, y, CONTENT_WIDTH, body)
        y += 6f
        canvas.drawText(
            "Scanned ${report.timestamp} · ruleset ${report.rulesetVersion} · " +
                "${report.framesUsed} frames" +
                if (report.framesGated > 0) " (${report.framesGated} gated)" else "",
            MARGIN, y, small
        )
        y += 12f
        y = drawWrapped(canvas, report.sourceCitation, MARGIN, y, CONTENT_WIDTH, small)
        y += 6f
        val blocking = report.blockingChecks
        if (blocking.isNotEmpty()) {
            y = drawWrapped(
                canvas,
                "Because of: " + blocking.joinToString(", ") { it.ruleId },
                MARGIN, y, CONTENT_WIDTH, body
            )
        }
        y += 8f
        canvas.drawLine(MARGIN, y, PAGE_WIDTH - MARGIN, y, rule)
        y += 16f

        // ---- one block per field, with its checks nested
        for (group in report.fields) {
            newPageIfNeeded(100f)

            canvas.drawText("${group.label}  —  ${group.status}", MARGIN, y, heading)
            y += 14f

            group.observedValue?.let {
                canvas.drawText("Read: $it", MARGIN, y, body)
                y += 11f
            }
            group.agreement?.let {
                canvas.drawText("Agreement: $it", MARGIN, y, body)
                y += 11f
            }


            // The citation is printed once per distinct clause; several
            // checks on one field commonly share one.
            var lastCitation: String? = null
            for (check in group.checks) {
                newPageIfNeeded(46f)
                canvas.drawText("  ${check.label} — ${check.status}", MARGIN, y, body)
                y += 11f
                canvas.drawText("  ${check.ruleId}", MARGIN + 10f, y, small)
                y += 10f
                if (check.message.isNotBlank()) {
                    y = drawWrapped(
                        canvas, check.message, MARGIN + 10f, y, CONTENT_WIDTH - 10f, body
                    )
                }
                if (check.citation != lastCitation) {
                    y = drawWrapped(
                        canvas, "Citation: ${check.citation}",
                        MARGIN + 10f, y, CONTENT_WIDTH - 10f, small
                    )
                    lastCitation = check.citation
                }
                y += 4f
            }

            y += 4f
            canvas.drawLine(MARGIN, y, PAGE_WIDTH - MARGIN, y, rule)
            y += 14f
        }

        // ---- fields with no consensus
        if (report.unresolved.isNotEmpty()) {
            newPageIfNeeded(60f)
            canvas.drawText("Fields without consensus", MARGIN, y, heading)
            y += 14f

            for (item in report.unresolved) {
                newPageIfNeeded(24f)
                val candidates = item.candidates.joinToString(", ") {
                    "${it.value} ×${it.votes}"
                }
                y = drawWrapped(
                    canvas,
                    "${item.field}: ${item.reason}" +
                        if (candidates.isNotBlank()) " — $candidates" else "",
                    MARGIN, y, CONTENT_WIDTH, body
                )
                y += 4f
            }
        }

        document.finishPage(page)

        destination.parentFile?.mkdirs()
        destination.outputStream().use { document.writeTo(it) }
        document.close()

        return destination
    }

    private fun startPage(document: PdfDocument, number: Int): PdfDocument.Page =
        document.startPage(
            PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, number).create()
        )

    /**
     * Draws [text] wrapped to [maxWidth], returning the new y. Wrapping is done
     * by measuring words because PdfDocument has no text layout of its own.
     */
    private fun drawWrapped(
        canvas: Canvas,
        text: String,
        x: Float,
        startY: Float,
        maxWidth: Float,
        paint: Paint
    ): Float {
        var y = startY
        val lineHeight = paint.textSize * 1.25f
        val line = StringBuilder()

        for (word in text.split(Regex("\\s+")).filter { it.isNotBlank() }) {
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (paint.measureText(candidate) <= maxWidth) {
                line.clear().append(candidate)
            } else {
                canvas.drawText(line.toString(), x, y, paint)
                y += lineHeight
                line.clear().append(word)
            }
        }
        if (line.isNotEmpty()) {
            canvas.drawText(line.toString(), x, y, paint)
            y += lineHeight
        }
        return y
    }

    /** Status colour used by both the PDF and the screen. */
    fun colorFor(status: RuleStatus): Int = when (status) {
        RuleStatus.PASS -> Color.rgb(21, 128, 61)
        RuleStatus.FAIL -> Color.rgb(185, 28, 28)
        RuleStatus.NEEDS_REVIEW -> Color.rgb(180, 83, 9)
        RuleStatus.NOT_ASSESSABLE -> Color.rgb(71, 85, 105)
        RuleStatus.NOT_APPLICABLE -> Color.rgb(100, 116, 139)
        RuleStatus.EXEMPT -> Color.rgb(100, 116, 139)
    }

    @Suppress("unused")
    private fun Bitmap.aspect(): Float = width.toFloat() / height.toFloat()
}
