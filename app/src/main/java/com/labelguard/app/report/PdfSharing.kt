package com.labelguard.app.report

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * Hands an exported PDF to another app.
 *
 * A report that cannot leave the phone is not much use to an inspector who
 * has to file it, so the export flow ends in the system share sheet rather
 * than a file path the operator has to go hunting for.
 */
object PdfSharing {

    private fun uriFor(context: Context, pdf: File) = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        pdf
    )

    /** Share sheet (email, Drive, messaging). */
    fun shareIntent(
        context: Context,
        file: File,
        mimeType: String = "application/pdf",
        title: String = "Share compliance report"
    ): Intent {
        val uri = uriFor(context, file)
        return Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, file.nameWithoutExtension)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            title
        ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    }

    /**
     * Share several exported result files at once, for scoring a whole batch
     * against ground truth on a laptop.
     */
    fun shareManyIntent(context: Context, files: List<File>): Intent {
        val uris = ArrayList(files.map { uriFor(context, it) })
        return Intent.createChooser(
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "application/json"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            "Share scan results"
        ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    }

    /** Open in a PDF viewer, for checking the report before sending it. */
    fun openIntent(context: Context, pdf: File): Intent =
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uriFor(context, pdf), "application/pdf")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
}
