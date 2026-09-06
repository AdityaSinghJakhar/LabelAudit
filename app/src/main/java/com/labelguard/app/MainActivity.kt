package com.labelguard.app

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.labelguard.app.report.PdfSharing
import com.labelguard.app.ui.screens.BulkScreen
import com.labelguard.app.ui.screens.CameraScreen
import com.labelguard.app.auth.Role
import com.labelguard.app.ui.screens.HistoryScreen
import com.labelguard.app.ui.screens.CalibrationScreen
import com.labelguard.app.ui.screens.RoleScreen
import com.labelguard.app.ui.screens.ReportScreen
import com.labelguard.app.ui.theme.LabelGuardTheme
import com.labelguard.app.viewmodel.ExportedPdf
import com.labelguard.app.viewmodel.ScanState
import com.labelguard.app.viewmodel.ScanViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LabelGuardTheme {
                LabelGuardApp()
            }
        }
    }
}

/** Android's photo picker caps the selection; keep a batch reviewable too. */
private const val MAX_BULK_IMAGES = 30

@Composable
private fun LabelGuardApp(viewModel: ScanViewModel = viewModel()) {
    val scanState by viewModel.state.collectAsStateWithLifecycle()
    val exportStatus by viewModel.exportStatus.collectAsStateWithLifecycle()
    val exported by viewModel.exported.collectAsStateWithLifecycle()
    val bulk by viewModel.bulk.collectAsStateWithLifecycle()
    val exportedResults by viewModel.exportedResults.collectAsStateWithLifecycle()

    val history by viewModel.history.collectAsStateWithLifecycle()
    val historyQuery by viewModel.historyQuery.collectAsStateWithLifecycle()
    var showHistory by remember { mutableStateOf(false) }
    var showRoles by remember { mutableStateOf(false) }
    var showCalibration by remember { mutableStateOf(false) }
    val calibration by viewModel.calibration.collectAsStateWithLifecycle()
    val role by viewModel.role.collectAsStateWithLifecycle()
    val roleMessage by viewModel.roleMessage.collectAsStateWithLifecycle()
    val keepCorpus by viewModel.keepCorpus.collectAsStateWithLifecycle()
    val corpusSummary by viewModel.corpusSummary.collectAsStateWithLifecycle()
    val syncStatus by viewModel.syncStatus.collectAsStateWithLifecycle()

    val context = LocalContext.current

    // The photo picker needs no storage permission on any supported version.
    val pickImages = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_BULK_IMAGES)
    ) { uris -> viewModel.scanBulk(uris) }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        // Back walks this hierarchy instead of leaving the app. Each handler
        // composes only while its screen is showing, and the early returns
        // below keep exactly one of them active at a time. Nothing is
        // registered for the viewfinder itself, so back from there still
        // exits, which is what the system expects of a root screen.
        if (showCalibration) {
            BackHandler { showCalibration = false }

            CalibrationScreen(
                optics = viewModel.optics,
                existing = calibration,
                onSave = viewModel::saveCalibration,
                onClear = viewModel::clearCalibration,
                onBack = { showCalibration = false },
                modifier = Modifier.padding(innerPadding)
            )
            return@Scaffold
        }

        if (showRoles) {
            BackHandler { showRoles = false }

            RoleScreen(
                role = role,
                hasPasscode = viewModel.hasInspectorPasscode,
                message = roleMessage,
                onClaimInspector = viewModel::claimInspector,
                onRelease = viewModel::releaseInspector,
                onCalibrate = { showRoles = false; showCalibration = true }
                    .takeIf { role.can(Role.Capability.MANAGE_REGISTRY) },
                calibrationSummary = calibration?.describe(),
                keepCorpus = keepCorpus,
                onKeepCorpus = viewModel::setKeepCorpus
                    .takeIf { role.can(Role.Capability.MANAGE_REGISTRY) },
                corpusSummary = corpusSummary,
                onClearCorpus = viewModel::clearCorpus
                    .takeIf { role.can(Role.Capability.MANAGE_REGISTRY) },
                onShareCorpus = {
                    val files = viewModel.corpusFiles()
                    if (files.isNotEmpty()) shareResults(context, files)
                }.takeIf { role.can(Role.Capability.MANAGE_REGISTRY) },
                onDismissMessage = viewModel::clearRoleMessage,
                onBack = { showRoles = false },
                modifier = Modifier.padding(innerPadding)
            )
            return@Scaffold
        }

        if (showHistory) {
            BackHandler { showHistory = false }

            HistoryScreen(
                records = viewModel.filteredHistory(),
                summary = viewModel.historySummary(),
                conflicts = viewModel.historyConflicts(),
                query = historyQuery,
                onQueryChange = viewModel::searchHistory,
                onDelete = viewModel::deleteScan,
                onExportCsv = viewModel::exportHistoryCsv
                    .takeIf { role.can(Role.Capability.EXPORT_HISTORY) },
                onShareCsv = exportedResults
                    .takeIf { it.isNotEmpty() }
                    ?.let { files -> { shareResults(context, files) } },
                exportStatus = exportStatus,
                syncStatus = syncStatus,
                onSyncNow = viewModel::syncAllPending,
                onBack = { showHistory = false },
                modifier = Modifier.padding(innerPadding)
            )
            return@Scaffold
        }

        val run = bulk
        if (run != null && scanState !is ScanState.Reported) {
            BackHandler { viewModel.clearBulk() }

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
            is ScanState.Reported -> {
                // The same thing the Rescan button does, so back from a report
                // returns to whatever preceded it: the viewfinder after a
                // single scan, or the bulk list when the report was opened
                // from one, since clearing the state leaves the run in place.
                BackHandler { viewModel.dismiss() }

                ReportScreen(
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
                    // Absent for a shopper: registering a reference asserts
                    // what a correct pack of this product says, which someone
                    // who bought it off a shelf has no way to know.
                    onEnrol = { skuId: String -> viewModel.enrolLastScan(skuId) }
                        .takeIf { role.can(Role.Capability.ENROL_REFERENCE) },
                    modifier = Modifier.padding(innerPadding)
                )
            }

            // One screen, one layout. The role, history and upload
            // controls now live in the camera screen's own top bar; as
            // separate overlays here they had no way to avoid each other and
            // collided in the top-right corner.
            else -> CameraScreen(
                onSidesCaptured = viewModel::scanSides,
                optics = viewModel.optics,
                roleLabel = role.label,
                historyCount = history.size,
                onOpenRole = { showRoles = true },
                onOpenHistory = { showHistory = true },
                onUpload = {
                    pickImages.launch(
                        PickVisualMediaRequest(
                            ActivityResultContracts.PickVisualMedia.ImageOnly
                        )
                    )
                },
                statusMessage = statusMessage(state),
                modifier = Modifier.padding(innerPadding)
            )
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
