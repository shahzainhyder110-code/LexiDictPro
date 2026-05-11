package com.lexidict.pro.parser.zip

import com.lexidict.pro.core.domain.model.DictionaryFormat
import com.lexidict.pro.parser.DictionaryParser
import com.lexidict.pro.parser.ParseProgress
import com.lexidict.pro.parser.bgl.BglParser
import com.lexidict.pro.parser.dictd.DictdParser
import com.lexidict.pro.parser.dsl.DslParser
import com.lexidict.pro.parser.stardict.StarDictParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Parser for ZIP-compressed dictionaries.
 *
 * Strategy:
 * 1. Peek inside the ZIP to identify the dictionary format.
 * 2. Extract to a temporary directory.
 * 3. Delegate parsing to the appropriate sub-parser.
 *
 * Supports ZIP archives containing:
 * - StarDict (.ifo + .idx + .dict/.dict.dz)
 * - BGL (.bgl)
 * - DSL (.dsl / .dsl.dz)
 * - dictd (.index + .dict / .dict.dz)
 */
class ZipParser(
    private val bglParser: BglParser     = BglParser(),
    private val dslParser: DslParser     = DslParser(),
    private val starDictParser: StarDictParser = StarDictParser(),
    private val dictdParser: DictdParser = DictdParser()
) : DictionaryParser {

    companion object {
        /** Recognised extensions inside a ZIP archive */
        private val DICT_EXTENSIONS = setOf("bgl", "dsl", "ifo", "index")
        private const val MAX_EXTRACT_SIZE = 2L * 1024 * 1024 * 1024 // 2 GB safety limit
    }

    override fun canParse(file: File): Boolean {
        if (file.extension.lowercase() != "zip") return false
        // Quick peek to see if there's a recognised dictionary file inside
        return try {
            ZipInputStream(file.inputStream().buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val ext = File(entry.name).extension.lowercase()
                    if (ext in DICT_EXTENSIONS) return@use true
                    entry = zip.nextEntry
                }
                false
            }
        } catch (_: Exception) { false }
    }

    override suspend fun extractMetadata(file: File): Map<String, String> {
        val tmpDir = extractToTemp(file) ?: return emptyMap()
        return try {
            val delegate = findDelegate(tmpDir) ?: return emptyMap()
            val entryFile = findEntryFile(tmpDir, delegate) ?: return emptyMap()
            delegate.extractMetadata(entryFile) + mapOf("sourceZip" to file.name)
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    override fun parseWords(file: File): Flow<ParseProgress> = flow {
        emit(ParseProgress(0, 0, "Extracting ZIP…", false))

        val tmpDir = extractToTemp(file)
        if (tmpDir == null) {
            emit(ParseProgress(0, 0, "Failed to extract ZIP", true))
            return@flow
        }

        try {
            val delegate = findDelegate(tmpDir)
            if (delegate == null) {
                emit(ParseProgress(0, 0, "No supported dictionary found inside ZIP", true))
                return@flow
            }

            val entryFile = findEntryFile(tmpDir, delegate)
            if (entryFile == null) {
                emit(ParseProgress(0, 0, "Cannot locate entry file for parser", true))
                return@flow
            }

            // Stream parse results from the delegate
            delegate.parseWords(entryFile).collect { progress ->
                emit(progress)
            }
        } finally {
            tmpDir.deleteRecursively()
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun estimateWordCount(file: File): Int {
        val tmpDir = extractToTemp(file) ?: return 0
        return try {
            val delegate = findDelegate(tmpDir) ?: return 0
            val entryFile = findEntryFile(tmpDir, delegate) ?: return 0
            delegate.estimateWordCount(entryFile)
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Extract the ZIP file to a unique temp directory.
     * Returns the directory or null on failure.
     */
    private fun extractToTemp(zip: File): File? {
        val tmpDir = File(zip.parent, "lexidict_extract_${zip.nameWithoutExtension}_${System.currentTimeMillis()}")
        tmpDir.mkdirs()

        return try {
            var totalExtracted = 0L
            ZipInputStream(zip.inputStream().buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    // Security: prevent path traversal
                    val target = File(tmpDir, entry.name.replace("..", ""))
                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile?.mkdirs()
                        val written = target.outputStream().buffered().use { out ->
                            zis.copyTo(out)
                        }
                        totalExtracted += written
                        if (totalExtracted > MAX_EXTRACT_SIZE) {
                            zis.closeEntry()
                            return null // Abort: too large
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            tmpDir
        } catch (_: Exception) {
            tmpDir.deleteRecursively()
            null
        }
    }

    /**
     * Walk the extracted directory and return the first matching sub-parser.
     */
    private fun findDelegate(dir: File): DictionaryParser? {
        val allFiles = dir.walkTopDown().filter { it.isFile }.toList()
        for (file in allFiles) {
            when {
                bglParser.canParse(file)      -> return bglParser
                starDictParser.canParse(file) -> return starDictParser
                dslParser.canParse(file)      -> return dslParser
                dictdParser.canParse(file)    -> return dictdParser
            }
        }
        return null
    }

    /**
     * Given a delegate parser, find the main entry file inside the extracted dir.
     */
    private fun findEntryFile(dir: File, delegate: DictionaryParser): File? {
        return dir.walkTopDown()
            .filter { it.isFile && delegate.canParse(it) }
            .firstOrNull()
    }
}
