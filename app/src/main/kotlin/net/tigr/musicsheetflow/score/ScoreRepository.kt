package net.tigr.musicsheetflow.score

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.tigr.musicsheetflow.score.model.Score
import net.tigr.musicsheetflow.score.parser.MusicXmlParser
import java.io.File

/**
 * Repository for loading and caching musical scores.
 */
class ScoreRepository {

    companion object {
        private const val TAG = "ScoreRepository"
        private const val SCORES_DIR = "scores"
        private const val IMPORTED_DIR = "imported_scores"
    }

    private val parser = MusicXmlParser()
    private val scoreCache = java.util.concurrent.ConcurrentHashMap<String, Score>()

    /**
     * Get list of available score files in assets.
     */
    fun getAvailableScores(context: Context): List<ScoreInfo> {
        return try {
            val files = context.assets.list(SCORES_DIR) ?: emptyArray()
            files.filter { it.endsWith(".mxl") || it.endsWith(".musicxml") }
                .map { filename ->
                    ScoreInfo(
                        filename = filename,
                        displayName = filename
                            .removeSuffix(".mxl")
                            .removeSuffix(".musicxml")
                            .replace("_", " ")
                    )
                }
                .sortedBy { it.displayName }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to list scores", e)
            emptyList()
        }
    }

    /**
     * Get list of available scores with full metadata (loads each score).
     * Use for library display where metadata is needed.
     * Runs on IO dispatcher to avoid blocking UI.
     * Includes both bundled and imported scores.
     */
    suspend fun getAvailableScoresWithMetadata(context: Context): List<ScoreInfo> =
        withContext(Dispatchers.IO) {
            try {
                val bundledScores = getBundledScoresWithMetadata(context)
                val importedScores = getImportedScoresWithMetadata(context)
                (bundledScores + importedScores).sortedBy { it.displayName }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to list scores with metadata", e)
                throw e
            }
        }

    private fun getBundledScoresWithMetadata(context: Context): List<ScoreInfo> {
        val files = context.assets.list(SCORES_DIR) ?: emptyArray()
        return files.filter { it.endsWith(".mxl") || it.endsWith(".musicxml") }
            .mapNotNull { filename ->
                val score = loadScore(context, filename)
                if (score != null) {
                    ScoreInfo(
                        filename = filename,
                        displayName = score.title.ifEmpty {
                            filename.removeSuffix(".mxl")
                                .removeSuffix(".musicxml")
                                .replace("_", " ")
                        },
                        composer = score.composer,
                        measureCount = score.measureCount(),
                        noteCount = score.getAllNotes().size,
                        isImported = false
                    )
                } else null
            }
    }

    private fun getImportedScoresWithMetadata(context: Context): List<ScoreInfo> {
        val importDir = getImportedDir(context)
        if (!importDir.exists()) return emptyList()

        return importDir.listFiles()
            ?.filter { it.extension in listOf("mxl", "musicxml", "xml") }
            ?.mapNotNull { file ->
                val score = loadImportedScore(context, file.name)
                if (score != null) {
                    ScoreInfo(
                        filename = file.name,
                        displayName = score.title.ifEmpty {
                            file.nameWithoutExtension.replace("_", " ")
                        },
                        composer = score.composer,
                        measureCount = score.measureCount(),
                        noteCount = score.getAllNotes().size,
                        isImported = true
                    )
                } else null
            } ?: emptyList()
    }

    private fun getImportedDir(context: Context): File {
        return File(context.filesDir, IMPORTED_DIR).also {
            if (!it.exists()) it.mkdirs()
        }
    }

    /**
     * Check if a score file exists in assets.
     */
    fun scoreExists(context: Context, filename: String): Boolean {
        return try {
            val files = context.assets.list(SCORES_DIR) ?: emptyArray()
            filename in files
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Load a score from assets by filename.
     */
    fun loadScore(context: Context, filename: String): Score? {
        // Check cache first
        scoreCache[filename]?.let { return it }

        // Parse and cache
        val score = parser.parseFromAssets(context, filename)
        if (score != null) {
            scoreCache[filename] = score
            Log.i(TAG, "Loaded score: ${score.title} by ${score.composer} " +
                    "(${score.measureCount()} measures, ${score.getAllNotes().size} notes)")
        }
        return score
    }

    /**
     * Load an imported score from the imported scores directory.
     */
    fun loadImportedScore(context: Context, filename: String): Score? {
        val cacheKey = "imported:$filename"
        scoreCache[cacheKey]?.let { return it }

        val importDir = getImportedDir(context)
        val file = File(importDir, filename)

        // Validate path is within import directory (prevent path traversal)
        if (!file.canonicalPath.startsWith(importDir.canonicalPath)) {
            Log.e(TAG, "Invalid filename - path traversal attempt: $filename")
            return null
        }

        if (!file.exists()) {
            Log.e(TAG, "Imported score file not found: $filename")
            return null
        }

        val score = parser.parseFromFile(file)
        if (score != null) {
            scoreCache[cacheKey] = score
            Log.i(TAG, "Loaded imported score: ${score.title} by ${score.composer} " +
                    "(${score.measureCount()} measures, ${score.getAllNotes().size} notes)")
        }
        return score
    }

    /**
     * Import a MusicXML score from a content URI.
     * Returns the filename on success, null on failure.
     */
    suspend fun importScore(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        try {
            val resolver = context.contentResolver

            // Check file size first (10MB limit for MusicXML files)
            val maxFileSizeBytes = 10 * 1024 * 1024L
            val fileSize = resolver.query(uri, null, null, null, null)?.use { cursor ->
                val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (cursor.moveToFirst() && sizeIndex >= 0) {
                    cursor.getLong(sizeIndex)
                } else null
            }

            if (fileSize != null && fileSize > maxFileSizeBytes) {
                Log.e(TAG, "File too large: $fileSize bytes (max: $maxFileSizeBytes)")
                return@withContext null
            }

            val originalFilename = getFilenameFromUri(context, uri)
            val targetFilename = generateUniqueFilename(context, originalFilename)
            val targetFile = File(getImportedDir(context), targetFilename)

            resolver.openInputStream(uri)?.use { input ->
                targetFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: run {
                Log.e(TAG, "Failed to open input stream for URI: $uri")
                return@withContext null
            }

            // Validate the file is parseable
            val score = parser.parseFromFile(targetFile)
            if (score == null) {
                Log.e(TAG, "Failed to parse imported file: $targetFilename")
                targetFile.delete()
                return@withContext null
            }

            // Cache the parsed score
            scoreCache["imported:$targetFilename"] = score
            Log.i(TAG, "Imported score: ${score.title} as $targetFilename")
            targetFilename
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import score from URI: $uri", e)
            null
        }
    }

    private fun getFilenameFromUri(context: Context, uri: Uri): String {
        var filename = "imported_score.mxl"
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIndex >= 0) {
                filename = cursor.getString(nameIndex)
            }
        }
        return filename
    }

    private fun generateUniqueFilename(context: Context, original: String): String {
        val importDir = getImportedDir(context)
        var filename = original
        var counter = 1
        val baseName = original.substringBeforeLast(".")
        val extension = original.substringAfterLast(".", "mxl")

        while (File(importDir, filename).exists()) {
            filename = "${baseName}_$counter.$extension"
            counter++
        }
        return filename
    }

    /**
     * Delete an imported score.
     */
    fun deleteImportedScore(context: Context, filename: String): Boolean {
        val importDir = getImportedDir(context)
        val file = File(importDir, filename)

        // Validate path is within import directory (prevent path traversal)
        if (!file.canonicalPath.startsWith(importDir.canonicalPath)) {
            Log.e(TAG, "Invalid filename - path traversal attempt: $filename")
            return false
        }

        if (file.exists() && file.delete()) {
            scoreCache.remove("imported:$filename")
            Log.i(TAG, "Deleted imported score: $filename")
            return true
        }
        return false
    }

    /**
     * Clear the score cache.
     */
    fun clearCache() {
        scoreCache.clear()
    }
}

/**
 * Basic info about an available score file.
 */
data class ScoreInfo(
    val filename: String,
    val displayName: String,
    val composer: String = "",
    val measureCount: Int = 0,
    val noteCount: Int = 0,
    val isImported: Boolean = false
)
