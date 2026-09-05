package com.labelguard.app.eval

import android.content.Context
import com.labelguard.app.report.ResultsExport
import com.labelguard.app.report.ScanReport
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Date

/**
 * Scans kept as a corpus, so accuracy can be measured rather than asserted.
 *
 * The app normally deletes every frame the moment a scan finishes, which is
 * right for a shopper's phone and fatal for evaluation. Two things become
 * impossible without the images:
 *
 *   * **Ground truth.** Somebody has to look at the pack and write down what
 *     it really declares. There is nothing to look at once the photo is gone.
 *   * **Regression.** An improved extractor can only be shown to be an
 *     improvement by running it again over the same images. Deleting them
 *     means every change is an opinion.
 *
 * So this keeps the frames and the prediction side by side, one directory per
 * scan, in a layout the Python harness reads directly:
 *
 *     corpus/
 *       20260905-111114-a3f2/
 *         scan.json        the app's own prediction, ResultsExport shape
 *         frame-01.jpg     the images it was made from
 *         frame-02.jpg
 *
 * Off by default and inspector-only. It is an evaluation instrument, it grows
 * without bound, and a shopper has no reason to carry it.
 */
class CorpusStore(private val context: Context) {

    private val root: File
        get() = File(context.getExternalFilesDir(null), DIRECTORY)

    /**
     * Keep this scan.
     *
     * Frames are **copied**, not moved: the caller deletes its originals in a
     * `finally` block, and a corpus that quietly took ownership of them would
     * turn a failed save into lost evidence.
     */
    fun keep(report: ScanReport, frames: List<File>): Entry? = runCatching {
        val id = newId()
        val directory = File(root, id).apply { mkdirs() }

        frames.forEachIndexed { index, frame ->
            if (!frame.exists()) return@forEachIndexed
            frame.copyTo(File(directory, "frame-%02d.jpg".format(index + 1)), overwrite = true)
        }

        ResultsExport.write(directory, id, report)
            .renameTo(File(directory, SCAN_FILE))

        Entry(id, directory)
    }.getOrNull()

    data class Entry(val id: String, val directory: File)

    fun entries(): List<Entry> =
        root.listFiles()
            ?.filter { it.isDirectory }
            ?.sortedByDescending { it.name }
            ?.map { Entry(it.name, it) }
            .orEmpty()

    /** Total bytes on disk, so the size of an unbounded store stays visible. */
    fun sizeBytes(): Long =
        root.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    fun frameCount(): Int =
        root.walkBottomUp().count { it.isFile && it.extension.equals("jpg", true) }

    fun clear(): Boolean = root.deleteRecursively()

    /** Every file in the corpus, for sharing it off the device in one go. */
    fun allFiles(): List<File> =
        root.walkBottomUp().filter { it.isFile }.sortedBy { it.path }.toList()

    val path: String get() = root.absolutePath

    /**
     * A human-readable summary for the settings screen.
     *
     * Size is stated because this store is deliberately unbounded: an
     * evaluation set that silently discarded its oldest members would stop
     * being the thing the numbers were computed over.
     */
    fun summary(): String {
        val entries = entries().size
        if (entries == 0) return "No scans kept yet."
        val mb = sizeBytes() / (1024.0 * 1024.0)
        return "%d scan(s), %d frame(s), %.1f MB".format(entries, frameCount(), mb)
    }

    private fun newId(): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        // A suffix, because a bulk run completes several scans inside one
        // second and they must not land in the same directory.
        val suffix = (0..0xffff).random().toString(16).padStart(4, '0')
        return "$stamp-$suffix"
    }

    companion object {
        const val DIRECTORY = "corpus"
        const val SCAN_FILE = "scan.json"
    }
}
