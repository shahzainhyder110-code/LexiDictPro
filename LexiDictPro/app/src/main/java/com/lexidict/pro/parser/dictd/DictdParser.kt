package com.lexidict.pro.parser.dictd

import com.lexidict.pro.core.domain.model.DictionaryFormat
import com.lexidict.pro.parser.DictionaryParser
import com.lexidict.pro.parser.ParseProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okio.BufferedSource
import okio.ByteString.Companion.decodeBase64
import okio.GzipSource
import okio.buffer
import okio.source
import java.io.File
import java.nio.charset.Charset

/**
 * Parser for the dictd (DICT protocol) dictionary format.
 *
 * A dictd dictionary consists of two files:
 *  - .index  : tab-separated (word \t offset_b64 \t size_b64)
 *  - .dict   : raw text blocks at byte offsets (may be .dict.dz for gzip-compressed)
 *
 * The offset and size are encoded as base64 of a big-endian 64-bit integer, or as
 * plain base64 strings that decode to the numeric offset/size. The standard uses
 * a specialised base64 alphabet (A-Z a-z 0-9 + /) and the decoded value is a
 * 64-bit integer in network (big-endian) byte order.
 *
 * Reference: https://www.dict.org/bin/Dict
 */
class DictdParser : DictionaryParser {

    companion object {
        // dictd base64 alphabet (RFC 2045 standard alphabet)
        private const val B64_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        private const val BATCH_SIZE = 300
    }

    override fun canParse(file: File): Boolean {
        // Accept .index files or the companion .dict/.dict.dz
        return when (file.extension.lowercase()) {
            "index" -> true
            "dict"  -> file.resolveSibling("${file.nameWithoutExtension}.index").exists()
            "dz"    -> {
                // .dict.dz
                val base = file.name.removeSuffix(".dz")
                file.extension == "dz" && base.endsWith(".dict") &&
                        file.resolveSibling(base.removeSuffix(".dict") + ".index").exists()
            }
            else -> false
        }
    }

    override suspend fun extractMetadata(file: File): Map<String, String> {
        val indexFile = resolveIndexFile(file) ?: return emptyMap()
        val wordCount = try {
            indexFile.bufferedReader().use { reader ->
                var count = 0
                reader.forEachLine { if (it.isNotBlank()) count++ }
                count
            }
        } catch (_: Exception) { 0 }

        val dictName = indexFile.nameWithoutExtension
            .replace('_', ' ')
            .replaceFirstChar { it.uppercase() }

        return mapOf(
            "name"      to dictName,
            "wordCount" to wordCount.toString(),
            "format"    to "dictd",
            "size"      to indexFile.length().toString()
        )
    }

    override fun parseWords(file: File): Flow<ParseProgress> = flow {
        val indexFile = resolveIndexFile(file)
            ?: run {
                emit(ParseProgress(0, 0, "No .index file found", true))
                return@flow
            }
        val dictFile = resolveDictFile(file)
            ?: run {
                emit(ParseProgress(0, 0, "No .dict/.dict.dz file found", true))
                return@flow
            }

        // Count lines first for progress reporting
        val totalWords = indexFile.bufferedReader().use { r ->
            var count = 0; r.forEachLine { if (it.isNotBlank()) count++ }; count
        }

        val words = mutableListOf<Pair<String, String>>()
        var processed = 0

        // Read the entire index into memory (index files are typically small)
        val indexEntries: List<Triple<String, Long, Int>> = indexFile
            .bufferedReader(Charsets.UTF_8)
            .useLines { lines ->
                lines.filter { it.isNotBlank() }.map { line ->
                    val parts = line.split('\t')
                    if (parts.size < 3) return@map null
                    val word   = parts[0]
                    val offset = decodeBase64Long(parts[1])
                    val size   = decodeBase64Long(parts[2]).toInt()
                    Triple(word, offset, size)
                }.filterNotNull().toList()
            }

        // Open .dict or .dict.dz
        val isDz = dictFile.name.endsWith(".dict.dz")

        // For non-compressed: use RandomAccessFile for O(1) seeks
        if (!isDz) {
            val raf = java.io.RandomAccessFile(dictFile, "r")
            raf.use { ra ->
                indexEntries.forEach { (word, offset, size) ->
                    try {
                        ra.seek(offset)
                        val buf = ByteArray(size)
                        ra.readFully(buf)
                        val definition = buf.toString(Charsets.UTF_8)
                        words += Pair(word, definition.formatDefinition())
                    } catch (_: Exception) {
                        // Skip corrupt entries
                    }
                    processed++
                    if (words.size >= BATCH_SIZE) {
                        emit(ParseProgress(processed, totalWords, null, false, words.toList()))
                        words.clear()
                    }
                }
            }
        } else {
            // Compressed .dict.dz: must decompress sequentially
            // Build a sorted-by-offset read plan so we decompress once top-to-bottom
            val sortedByOffset = indexEntries.sortedBy { it.second }
            val offsetToWord = sortedByOffset.associate { (word, offset, size) ->
                offset to Pair(word, size)
            }

            var currentPos = 0L
            val gzipSource: BufferedSource = GzipSource(dictFile.source()).buffer()

            gzipSource.use { src ->
                sortedByOffset.forEach { (word, offset, size) ->
                    // Skip bytes to reach target offset
                    val skip = offset - currentPos
                    if (skip > 0) src.skip(skip)
                    currentPos = offset

                    val buf = ByteArray(size)
                    try {
                        src.readFully(buf)
                        currentPos += size
                        val definition = buf.toString(Charsets.UTF_8)
                        words += Pair(word, definition.formatDefinition())
                    } catch (_: Exception) {
                        currentPos += size // keep position sync
                    }
                    processed++
                    if (words.size >= BATCH_SIZE) {
                        emit(ParseProgress(processed, totalWords, null, false, words.toList()))
                        words.clear()
                    }
                }
            }
        }

        // Emit remaining words
        if (words.isNotEmpty()) {
            emit(ParseProgress(processed, totalWords, null, false, words.toList()))
        }

        emit(ParseProgress(totalWords, totalWords, null, true, emptyList()))
    }.flowOn(Dispatchers.IO)

    override suspend fun estimateWordCount(file: File): Int {
        val indexFile = resolveIndexFile(file) ?: return 0
        return try {
            indexFile.bufferedReader().use { r ->
                var count = 0; r.forEachLine { if (it.isNotBlank()) count++ }; count
            }
        } catch (_: Exception) { 0 }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun resolveIndexFile(file: File): File? {
        return when (file.extension.lowercase()) {
            "index" -> file.takeIf { it.exists() }
            "dict"  -> file.resolveSibling("${file.nameWithoutExtension}.index")
                .takeIf { it.exists() }
            "dz"    -> {
                val base = file.name.removeSuffix(".dz").removeSuffix(".dict")
                file.resolveSibling("$base.index").takeIf { it.exists() }
            }
            else    -> null
        }
    }

    private fun resolveDictFile(file: File): File? {
        val base = when (file.extension.lowercase()) {
            "index" -> file.resolveSibling(file.nameWithoutExtension)
            "dict"  -> file
            "dz"    -> file
            else    -> return null
        }
        return when {
            base.exists()                                  -> base
            File("${base.absolutePath}.dict").exists()     -> File("${base.absolutePath}.dict")
            File("${base.absolutePath}.dict.dz").exists()  -> File("${base.absolutePath}.dict.dz")
            else                                           -> null
        }
    }

    /**
     * Decode dictd base64-encoded offset/size to Long.
     * dictd uses standard base64 (RFC 2045) where each encoded char maps to 6 bits.
     */
    private fun decodeBase64Long(encoded: String): Long {
        var result = 0L
        for (ch in encoded) {
            val idx = B64_CHARS.indexOf(ch)
            if (idx < 0) break
            result = (result shl 6) or idx.toLong()
        }
        return result
    }

    /**
     * Convert raw dict definition text to minimal HTML.
     * dictd definitions are often plain text or have simple markup.
     */
    private fun String.formatDefinition(): String {
        val lines = trim().lines()
        val sb = StringBuilder()
        for (line in lines) {
            when {
                line.startsWith("     ") -> {
                    // indented example → blockquote
                    sb.append("<blockquote>")
                        .append(line.trim().escapeHtml())
                        .append("</blockquote>")
                }
                line.startsWith("  ") -> {
                    // sub-definition
                    sb.append("<p style='margin-left:1em'>")
                        .append(line.trim().escapeHtml())
                        .append("</p>")
                }
                line.matches(Regex("^\\d+\\..*")) -> {
                    // numbered sense → bold number
                    sb.append("<p><b>")
                        .append(line.substringBefore('.').escapeHtml())
                        .append(".</b> ")
                        .append(line.substringAfter('.').trim().escapeHtml())
                        .append("</p>")
                }
                line.isBlank() -> sb.append("<br>")
                else -> sb.append("<p>").append(line.escapeHtml()).append("</p>")
            }
        }
        return sb.toString()
    }

    private fun String.escapeHtml(): String = replace("&", "&amp;")
        .replace("<", "&lt;").replace(">", "&gt;")
}
