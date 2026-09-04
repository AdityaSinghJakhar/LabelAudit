package com.labelaudit.app

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.labelaudit.app.report.PdfSharing
import androidx.compose.material3.TextButton
import com.labelaudit.app.ui.screens.BulkScreen
import com.labelaudit.app.ui.screens.CameraScreen
import com.labelaudit.app.ui.screens.ReportScreen
import com.labelaudit.app.ui.theme.LabelAuditTheme
import com.labelaudit.app.viewmodel.ExportedPdf
import com.labelaudit.app.viewmodel.ScanState
import com.labelaudit.app.viewmodel.ScanViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LabelAuditTheme {
                LabelAuditApp()
            }
        }
    }
}

/** Android's photo picker caps the selection; keep a batch reviewable too. */
private const val MAX_BULK_IMAGES = 30

@Composable
private fun LabelAuditApp(viewModel: ScanViewModel = viewModel()) {
    val scanState by viewModel.state.collectAsStateWithLifecycle()
    val exportStatus by viewModel.exportStatus.collectAsStateWithLifecycle()
    val exported by viewModel.exported.collectAsStateWithLifecycle()
    val bulk by viewModel.bulk.collectAsStateWithLifecycle()
    val exportedResults by viewModel.exportedResults.collectAsStateWithLifecycle()

    val context = LocalContext.current

    // The photo picker needs no storage permission on any supported version.
    val pickImages = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_BULK_IMAGES)
    ) { uris -> viewModel.scanBulk(uris) }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        val run = bulk
        if (run != null && scanState !is ScanState.Reported) {
            BulkScreen(
                run = run,
                onOpen = viewModel::openBulkItem,
                onDone = viewModel::clearBulk,
                modifier = Modifier.padding(innerPadding),
                onExportResults = viewModel::exportResults,
                onShareResults = exportedResults
                    .takeIf { it.isNotEmpty() }
                    ?.let { files -> { shareResults(context, files) } },
                exportStatus = exportStatus
            )
            return@Scaffold
        }

        when (val state = scanState) {
            // The report replaces the viewfinder once a scan completes; it is
            // the deliverable, not an overlay on the photo.
            is ScanState.Reported -> ReportScreen(
                report = state.report,
                onExportPdf = viewModel::exportPdf,
                onRescan = viewModel::dismiss,
                exportStatus = exportStatus,
                onSharePdf = exported?.let { pdf -> { sharePdf(context, pdf) } },
                onOpenPdf = exported?.let { pdf -> { openPdf(context, pdf) } },
                onExportResults = viewModel::exportResults,
                onShareResults = exportedResults
                    .takeIf { it.isNotEmpty() }
                    ?.let { files -> { shareResults(context, files) } },
                onEnrol = { skuId -> viewModel.enrolLastScan(skuId) },
                modifier = Modifier.padding(innerPadding)
            )

            else -> Box(modifier = Modifier.fillMaxSize()) {
                CameraScreen(
                    onSidesCaptured = viewModel::scanSides,
                    modifier = Modifier.padding(innerPadding)
                )

                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(innerPadding)
                        .padding(16.dp)
                        .fillMaxWidth()
                ) {
                    statusMessage(state)?.let { StatusPanel(it) }

                    TextButton(
                        onClick = {
                            pickImages.launch(
                                PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly
                                )
                            )
                        },
                        modifier = Modifier.align(Alignment.TopEnd)
                    ) {
                        Text("Upload images")
                    }
                }
            }
        }
    }
}

private fun sharePdf(context: Context, pdf: ExportedPdf) {
    runCatching { context.startActivity(PdfSharing.shareIntent(context, pdf.file)) }
        .onFailure { Toast.makeText(context, "No app to share PDFs", Toast.LENGTH_SHORT).show() }
}

private fun shareResults(context: Context, files: List<java.io.File>) {
    runCatching { context.startActivity(PdfSharing.shareManyIntent(context, files)) }
        .onFailure { Toast.makeText(context, "No app to share files", Toast.LENGTH_SHORT).show() }
}

private fun openPdf(context: Context, pdf: ExportedPdf) {
    runCatching { context.startActivity(PdfSharing.openIntent(context, pdf.file)) }
        .onFailure { Toast.makeText(context, "No PDF viewer installed", Toast.LENGTH_SHORT).show() }
}

private fun statusMessage(state: ScanState): String? = when (state) {
    ScanState.Idle -> null
    is ScanState.Reading -> "Reading frame ${state.frame} of ${state.total}…"
    is ScanState.NotAssessable -> formatNotAssessable(state)
    is ScanState.Failed -> state.message
    is ScanState.Reported -> null
}

/**
 * NOT_ASSESSABLE is not a compliance failure. Word it so an inspector cannot
 * mistake "we could not read it properly" for "this label is wrong".
 */
private fun formatNotAssessable(state: ScanState.NotAssessable): String {
    val explanation = when (state.reason) {
        "too_few_usable_frames" ->
            "Not enough readable frames. Hold steadier, fill more of the frame " +
                "with the label, and scan again."
        else -> state.reason
    }

    return buildString {
        append("NOT ASSESSABLE\n")
        append(explanation)
        if (state.detail.isNotEmpty()) {
            append("\n\n(")
            append(state.detail.entries.joinToString(", ") { "${it.key}=${it.value}" })
            append(")")
        }
    }
}

@Composable
private fun StatusPanel(message: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 3.dp
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .heightIn(max = 260.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 10.dp)
        )
    }
}
