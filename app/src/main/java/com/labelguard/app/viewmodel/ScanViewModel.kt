package com.labelguard.app.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.labelguard.app.history.HistoryCsv
import com.labelguard.app.history.HistoryStore
import com.labelguard.app.history.ScanRecord
import com.labelguard.app.ocr.OcrEngine
import com.labelguard.app.pipeline.Consensus
import com.labelguard.app.pipeline.EvidenceCrops
import com.labelguard.app.pipeline.FieldExtractor
import com.labelguard.app.pipeline.RulesEngine
import com.labelguard.app.pipeline.Ruleset
import com.labelguard.app.registry.Enrolment
import com.labelguard.app.registry.SkuRecord
import com.labelguard.app.registry.SkuStore
import com.labelguard.app.report.ReportPdf
import com.labelguard.app.report.ResultsExport
import com.labelguard.app.report.ScanReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed interface ScanState {
    data object Idle : ScanState
    data class Reading(val frame: Int, val total: Int) : ScanState
    data class Reported(val report: ScanReport) : ScanState

    /**
     * Too few frames produced a usable reading.
     *
     * NOT_ASSESSABLE, not a violation: the label may be perfectly compliant,
     * we simply could not read it well enough to say.
     */
    data class NotAssessable(
        val reason: String,
        val detail: Map<String, Any>
    ) : ScanState

    data class Failed(val message: String) : ScanState
}

/**
 * Frames captured of one face of a product.
 *
 * Declarations are split across a pack, so a compliant product may need its
 * front and back read together before every rule has something to look at.
 */
data class SideCapture(val label: String, val frames: List<File>)

/** A PDF that has been written and can now be opened or shared. */
data class ExportedPdf(val file: File, val message: String)

/**
 * Runs the whole pipeline on the device: OCR, extraction, consensus, rules,
 * report. Nothing here touches the network.
 *
 * There is no millimetre scale. Measuring text height needs a known physical
 * reference in frame, which a nitrogen-flushed chips pack cannot practically
 * carry, so the r. 9 height rule is deferred in the ruleset rather than
 * reported against a guessed scale.
 */
class ScanViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow<ScanState>(ScanState.Idle)
    val state: StateFlow<ScanState> = _state.asStateFlow()

    private val _exportStatus = MutableStateFlow<String?>(null)
    val exportStatus: StateFlow<String?> = _exportStatus.asStateFlow()

    private val _exported = MutableStateFlow<ExportedPdf?>(null)
    val exported: StateFlow<ExportedPdf?> = _exported.asStateFlow()

    private val _bulk = MutableStateFlow<BulkRun?>(null)
    val bulk: StateFlow<BulkRun?> = _bulk.asStateFlow()

    private val _exportedResults = MutableStateFlow<List<File>>(emptyList())
    val exportedResults: StateFlow<List<File>> = _exportedResults.asStateFlow()

    /** Parsed once; the ruleset does not change while the app runs. */
    private val ruleset: Ruleset by lazy {
        getApplication<Application>().assets.open("ruleset.yaml").use(Ruleset::load)
    }

    val skuStore = SkuStore(application)

    private val historyStore = HistoryStore(application)

    private val _history = MutableStateFlow(historyStore.load())
    val history: StateFlow<List<ScanRecord>> = _history.asStateFlow()

    private val _historyQuery = MutableStateFlow("")
    val historyQuery: StateFlow<String> = _historyQuery.asStateFlow()

    /** Scans matching the current search, newest first. */
    val visibleHistory: StateFlow<List<ScanRecord>> = _history

    fun searchHistory(query: String) {
        _historyQuery.value = query
    }

    fun filteredHistory(): List<ScanRecord> =
        _history.value.filter { it.matches(_historyQuery.value) }

    fun historySummary(): HistoryStore.Summary = historyStore.summarise(_history.value)

    /**
     * Declarations that disagree across scans of the same product. Needs no
     * reference data and nobody's word: the packs contradict each other.
     */
    fun historyConflicts(): List<HistoryStore.Conflict> =
        historyStore.conflicts(_history.value)

    /**
     * The history as a spreadsheet, for the enforcement return that has to be
     * filed in something other than a PDF.
     */
    fun exportHistoryCsv() {
        viewModelScope.launch {
            _exportStatus.value = "Writing history…"
            try {
                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                val file = File(
                    File(getApplication<Application>().getExternalFilesDir(null), "reports"),
                    "labelguard-history-" + stamp + ".csv"
                )
                val written = withContext(Dispatchers.IO) {
                    HistoryCsv.write(filteredHistory(), file)
                }
                _exportedResults.value = listOf(written)
                _exportStatus.value = "Saved " + written.name
            } catch (e: Exception) {
                _exportStatus.value = e.message ?: "Could not write the history"
            }
        }
    }

    fun deleteScan(id: String) {
        _history.value = historyStore.remove(id)
    }

    fun clearHistory() {
        _history.value = historyStore.clear()
    }

    /** Every completed scan is recorded; the history is the audit trail. */
    private fun record(report: ScanReport) {
        _history.value = historyStore.add(ScanRecord.from(report))
    }

    private val _registry = MutableStateFlow(skuStore.load())
    val registry: StateFlow<List<SkuRecord>> = _registry.asStateFlow()

    /** Fields from the last completed scan, so it can be enrolled. */
    private var lastScanFields: Map<String, Consensus.AgreedField> = emptyMap()

    /**
     * Register the pack just scanned as the reference for a SKU.
     *
     * The operator is asserting this pack is compliant; the app cannot check
     * that, and records the claim's origin so a later reader can weigh it.
     */
    fun enrolLastScan(skuId: String, note: String = "") {
        if (skuId.isBlank() || lastScanFields.isEmpty()) return
        skuStore.put(Enrolment.fromScan(skuId, lastScanFields, note))
        _registry.value = skuStore.load()
        _exportStatus.value = "Registered '$skuId' from this scan"
    }

    fun removeSku(skuId: String) {
        skuStore.remove(skuId)
        _registry.value = skuStore.load()
    }

    /**
     * Analyse one product from one or more captured faces.
     *
     * Each side is read on its own and the agreed values are merged, because
     * sides are complementary rather than corroborating: a field printed only
     * on the back is not a disagreement with the front.
     */
    fun scanSides(sides: List<SideCapture>) {
        viewModelScope.launch {
            _exportStatus.value = null
            _exported.value = null
            _bulk.value = null

            val frames = sides.flatMap { it.frames }
            if (frames.isEmpty()) {
                _state.value = ScanState.Failed("Capture failed — no frames")
                return@launch
            }

            try {
                val report = analyseSides(sides)
                record(report)
                _state.value = ScanState.Reported(report)
            } catch (e: Exception) {
                _state.value = ScanState.Failed(e.message ?: "Could not read the label")
            } finally {
                frames.forEach { it.delete() }
            }
        }
    }

    private suspend fun analyseSides(sides: List<SideCapture>): ScanReport {
        val started = System.nanoTime()
        var processed = 0
        val totalFrames = sides.sumOf { it.frames.size }

        val perSide = mutableListOf<Consensus.Result>()
        val cropSources = mutableListOf<Pair<File, Consensus.Result>>()
        val rawLines = mutableListOf<String>()

        for (side in sides) {
            val perFrame = side.frames.map { file ->
                processed += 1
                _state.value = ScanState.Reading(processed, totalFrames)
                val ocr = OcrEngine.recognize(getApplication(), file)
                // One entry per side, from its fullest frame, labelled so a
                // reader can tell which face a line came from.
                if (ocr.lines.isNotEmpty() && rawLines.none { it == "— ${side.label} —" }) {
                    rawLines += "— ${side.label} —"
                    rawLines += ocr.lines.map { it.text }
                }
                FieldExtractor.extract(ocr.lines)
            }
            val result = Consensus.build(perFrame)
            perSide += result
            cropSources += side.frames.first() to result
        }

        val merged = Consensus.merge(perSide)

        // Crop each field from the side that actually read it.
        val crops = withContext(Dispatchers.Default) {
            buildMap {
                for ((frame, result) in cropSources) {
                    val boxes = merged.fields
                        .filterKeys { it in result.fields.keys && it !in keys }
                        .mapValues { it.value.box }
                    if (boxes.isNotEmpty()) putAll(EvidenceCrops.extract(frame, boxes))
                }
            }
        }

        val matched = matchSku(merged)
        val evaluation = RulesEngine.evaluate(
            ruleset = matched?.let { Enrolment.applyTo(ruleset, it) } ?: ruleset,
            fields = merged.fields,
            context = RulesEngine.Context(addressRoles = addressRoles(merged))
        )

        lastScanFields = merged.fields

        return ScanReport.from(
            evaluation = evaluation,
            crops = crops,
            consensus = merged,
            framesUsed = totalFrames,
            framesGated = 0,
            elapsedMs = (System.nanoTime() - started) / 1_000_000,
            rawLines = rawLines,
            matchedSkuId = matched?.skuId,
            referenceNote = matched?.source?.trustNote
        )
    }

    /**
     * Runs the pipeline over already-captured frames of one product.
     *
     * Shared by the camera path and by bulk upload so the two cannot drift
     * into evaluating labels differently.
     */
    private suspend fun analyse(
        frames: List<File>,
        onProgress: (Int) -> Unit = {}
    ): ScanReport {
        val started = System.nanoTime()
        val perFrame = mutableListOf<Map<String, Consensus.Observation>>()
        val usableFrames = mutableListOf<File>()
        var rawLines: List<String> = emptyList()

        frames.forEachIndexed { index, file ->
            onProgress(index + 1)
            val ocr = OcrEngine.recognize(getApplication(), file)
            perFrame += FieldExtractor.extract(ocr.lines)
            usableFrames += file
            // Keep the fullest reading: the frame that resolved most text is
            // the most useful one to show when a declaration was missed.
            if (ocr.lines.size > rawLines.size) rawLines = ocr.lines.map { it.text }
        }

        val consensus = Consensus.build(perFrame)
        val crops = withContext(Dispatchers.Default) {
            EvidenceCrops.extract(
                usableFrames.first(),
                consensus.fields.mapValues { it.value.box }
            )
        }

        val matched = matchSku(consensus)
        val evaluation = RulesEngine.evaluate(
            ruleset = matched?.let { Enrolment.applyTo(ruleset, it) } ?: ruleset,
            fields = consensus.fields,
            context = RulesEngine.Context(addressRoles = addressRoles(consensus))
        )

        lastScanFields = consensus.fields

        return ScanReport.from(
            evaluation = evaluation,
            crops = crops,
            consensus = consensus,
            framesUsed = perFrame.size,
            framesGated = 0,
            elapsedMs = (System.nanoTime() - started) / 1_000_000,
            rawLines = rawLines,
            matchedSkuId = matched?.skuId,
            referenceNote = matched?.source?.trustNote
        )
    }

    // ---------------------------------------------------------------- bulk

    /**
     * Analyse a set of images, one product per image.
     *
     * A failure on one image must not abandon the rest of the batch, so each
     * is caught individually and reported in place.
     */
    fun scanBulk(uris: List<Uri>) {
        if (uris.isEmpty()) return

        viewModelScope.launch {
            _exportStatus.value = null
            _exported.value = null
            _bulk.value = BulkRun(emptyList(), processed = 0, total = uris.size)

            val items = mutableListOf<BulkItem>()

            for ((index, uri) in uris.withIndex()) {
                val name = uri.lastPathSegment ?: "image ${index + 1}"
                val item = try {
                    val file = withContext(Dispatchers.IO) { copyToCache(uri, index) }
                    try {
                        // Recorded here rather than when the item is opened:
                        // a bulk run of forty packs is forty inspections
                        // whether or not anyone taps into each one.
                        val report = analyse(listOf(file))
                        record(report)
                        BulkItem(name, report, error = null)
                    } finally {
                        file.delete()
                    }
                } catch (e: Exception) {
                    BulkItem(name, report = null, error = e.message ?: "Could not read image")
                }

                items += item
                _bulk.value = BulkRun(items.toList(), processed = index + 1, total = uris.size)
            }
        }
    }

    private fun copyToCache(uri: Uri, index: Int): File {
        val destination = File(getApplication<Application>().cacheDir, "bulk_$index.jpg")
        getApplication<Application>().contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "could not open image" }
            destination.outputStream().use { input.copyTo(it) }
        }
        return destination
    }

    fun openBulkItem(item: BulkItem) {
        item.report?.let { _state.value = ScanState.Reported(it) }
    }

    fun clearBulk() {
        _bulk.value = null
    }

    /**
     * Which registered SKU, if any, this pack is.
     *
     * Price is deliberately not part of the match: a wrong price is the
     * violation being looked for, and letting it prevent recognition would
     * mean the packs most worth catching are the ones never compared.
     */
    private fun matchSku(consensus: Consensus.Result): SkuRecord? =
        skuStore.bestMatch(Enrolment.matchableFields(consensus.fields))

    /**
     * Role-tagged addresses reconstructed from the agreed manufacturer field.
     * A field that reached consensus was anchored during extraction, so its
     * role is already established.
     */
    private fun addressRoles(
        consensus: Consensus.Result
    ): Map<FieldExtractor.AddressRole, List<String>> {
        val agreed = consensus.fields["manufacturer_address"] ?: return emptyMap()
        return mapOf(FieldExtractor.AddressRole.MANUFACTURER to listOf(agreed.value))
    }

    // ------------------------------------------------------------- exports

    fun exportPdf() {
        val report = (_state.value as? ScanState.Reported)?.report ?: return

        viewModelScope.launch {
            _exportStatus.value = "Writing PDF…"
            try {
                val file = withContext(Dispatchers.IO) {
                    ReportPdf.write(getApplication(), report, reportFile("labelguard-report"))
                }
                _exported.value = ExportedPdf(
                    file,
                    "Saved ${file.name} (${file.length() / 1024} KB)"
                )
                _exportStatus.value = "Saved ${file.name} (${file.length() / 1024} KB)"
            } catch (e: Exception) {
                _exportStatus.value = "PDF export failed: ${e.message}"
            }
        }
    }

    /**
     * Write scan results as JSON for offline accuracy scoring.
     *
     * The app can report what it read but not whether it read correctly. That
     * needs a human-typed ground truth and a statistic, which is what the
     * LabelGuard eval harness computes from these files.
     */
    fun exportResults() {
        viewModelScope.launch {
            _exportStatus.value = "Writing results…"
            try {
                val directory = File(
                    getApplication<Application>().getExternalFilesDir(null),
                    "reports"
                )
                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

                val written = withContext(Dispatchers.IO) {
                    val run = _bulk.value
                    if (run != null) {
                        ResultsExport.writeAll(
                            directory,
                            run.items.mapNotNull { item ->
                                item.report?.let { item.name.substringBeforeLast('.') to it }
                            }
                        )
                    } else {
                        val report = (_state.value as? ScanState.Reported)?.report
                            ?: return@withContext emptyList()
                        listOf(ResultsExport.write(directory, "scan-$stamp", report))
                    }
                }

                _exportStatus.value = if (written.isEmpty()) {
                    "Nothing to export"
                } else {
                    _exportedResults.value = written
                    "Exported ${written.size} result file(s) to ${directory.name}/"
                }
            } catch (e: Exception) {
                _exportStatus.value = "Results export failed: ${e.message}"
            }
        }
    }

    private fun reportFile(prefix: String): File {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        return File(
            getApplication<Application>().getExternalFilesDir(null),
            "reports/$prefix-$stamp.pdf"
        )
    }

    fun dismiss() {
        _state.value = ScanState.Idle
        _exportStatus.value = null
        _exported.value = null
        _exportedResults.value = emptyList()
    }
}
