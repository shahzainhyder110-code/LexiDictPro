package com.lexidict.pro.parser.stardict

import com.lexidict.pro.core.domain.model.Dictionary
import com.lexidict.pro.core.domain.model.DictionaryFormat
import com.lexidict.pro.core.data.local.entities.WordIndexFtsEntity
import com.lexidict.pro.parser.DictionaryParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream

/**
 * Parser for StarDict dictionary format.
 *
 * StarDict consists of three files:
 * - .ifo  : Plain text metadata (name, wordcount, idxfilesize, sametypesequence)
 * - .idx  : Index file — sorted word list with offset+size into .dict
 * - .dict : Dictionary data (optionally .dict.dz = gzip compressed)
 *
 * IDX Format:
 *   For each word: null-terminated string + 4-byte offset (big-endian) + 4-byte size
 *
 * DICT Format:
 *   Raw or gzip-compressed definitions, accessed by offset+size from IDX.
 *
 * sametypesequence field determines data encoding:
 *   'm' = plain text, 'g' = Pango markup, 'h' = HTML, 'x' = XDXF
 */
class StarDictParser : DictionaryParser {

    companion object {
        private const val BATCH_SIZE = 500
    }

    override fun canParse(file: File): Boolean {
        if (!file.exists()) return false
        val ext = file.extension.lowercase()
        return when (ext) {
            "ifo" -> true
            "idx" -> File(file.parent, file.nameWithoutExtension + ".ifo").exists()
            else -> false
        }
    }

    override suspend fun extractMetadata(file: File): Dictionary {
        val ifoFile = when (file.extension.lowercase()) {
            "ifo" -> file
            else -> File(file.parent, file.nameWithoutExtension + ".ifo")
        }

        var name = ifoFile.nameWithoutExtension
        var wordCount = 0L
        var author = ""
        var description = ""
        var sourceLang = ""
        var targetLang = ""

        try {
            ifoFile.readLines().forEach { line ->
                when {
                    line.startsWith("bookname=") -> name = line.removePrefix("bookname=")
                    line.startsWith("wordcount=") -> wordCount = line.removePrefix("wordcount=").toLongOrNull() ?: 0
                    line.startsWith("author=") -> author = line.removePrefix("author=")
                    line.startsWith("description=") -> description = line.removePrefix("description=")
                    line.startsWith("sourceLang=") -> sourceLang = line.removePrefix("sourceLang=")
                    line.startsWith("targetLang=") -> targetLang = line.removePrefix("targetLang=")
                }
            }
        } catch (e: Exception) { /* fallback */ }

        return Dictionary(
            name = name,
            author = author,
            description = description,
            sourceLanguage = sourceLang,
            targetLanguage = targetLang,
            wordCount = wordCount,
            filePath = ifoFile.absolutePath,
            format = DictionaryFormat.STARDICT,
            sizeBytes = ifoFile.parentFile?.listFiles()
                ?.filter { it.nameWithoutExtension == ifoFile.nameWithoutExtension }
                ?.sumOf { it.length() } ?: 0L
        )
    }

    override fun parseWords(file: File, dictionaryId: Long): Flow<List<WordIndexFtsEntity>> = flow {
        val ifoFile = if (file.extension.lowercase() == "ifo") file
                      else File(file.parent, file.nameWithoutExtension + ".ifo")

        val baseName = ifoFile.nameWithoutExtension
        val dir = ifoFile.parentFile ?: return@flow

        val idxFile = File(dir, "$baseName.idx")
        val dictFile = File(dir, "$baseName.dict")
        val dictDzFile = File(dir, "$baseName.dict.dz")

        if (!idxFile.exists()) return@flow
        val hasDictFile = dictFile.exists() || dictDzFile.exists()
        if (!hasDictFile) return@flow

        // Parse .ifo for sametypesequence
        val sameTypeSeq = parseSameTypeSequence(ifoFile)

        // Load full .dict or .dict.dz into memory (for random access)
        // For very large dicts, use memory-mapped file instead
        val dictData: ByteArray = try {
            if (dictDzFile.exists()) {
                GZIPInputStream(dictDzFile.inputStream()).readBytes()
            } else {
                dictFile.readBytes()
            }
        } catch (e: Exception) { return@flow }

        val batch = mutableListOf<WordIndexFtsEntity>()

        // Parse .idx — sequential scan
        try {
            RandomAccessFile(idxFile, "r").use { idx ->
                val wordBuf = ByteArrayOutputStream2()

                while (idx.filePointer < idx.length() - 8) {
                    // Read null-terminated word
                    wordBuf.reset()
                    var b = idx.read()
                    while (b != 0 && b != -1) {
                        wordBuf.write(b)
                        b = idx.read()
                    }
                    if (b == -1) break

                    val word = wordBuf.toString(Charsets.UTF_8.name())

                    // Read 4-byte offset (big-endian unsigned)
                    val offsetBytes = ByteArray(4)
                    idx.read(offsetBytes)
                    val offset = ByteBuffer.wrap(offsetBytes).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFFFFFFL

                    // Read 4-byte size (big-endian)
                    val sizeBytes = ByteArray(4)
                    idx.read(sizeBytes)
                    val size = ByteBuffer.wrap(sizeBytes).order(ByteOrder.BIG_ENDIAN).int

                    if (offset < 0 || size <= 0 || offset + size > dictData.size) continue

                    // Extract definition bytes
                    val defBytes = dictData.copyOfRange(offset.toInt(), (offset + size).toInt())
                    val definition = decodeDefinition(defBytes, sameTypeSeq)

                    if (word.isBlank()) continue

                    batch.add(
                        WordIndexFtsEntity(
                            dictionaryId = dictionaryId,
                            word = word,
                            wordLower = word.lowercase().trim(),
                            definition = definition,
                            sortKey = word.lowercase().take(4)
                        )
                    )

                    if (batch.size >= BATCH_SIZE) {
                        emit(batch.toList())
                        batch.clear()
                    }
                }
            }
        } catch (e: Exception) { /* emit what we have */ }

        if (batch.isNotEmpty()) emit(batch.toList())
    }.flowOn(Dispatchers.IO)

    override suspend fun estimateWordCount(file: File): Long {
        val ifoFile = if (file.extension.lowercase() == "ifo") file
                      else File(file.parent, file.nameWithoutExtension + ".ifo")
        return try {
            ifoFile.readLines()
                .firstOrNull { it.startsWith("wordcount=") }
                ?.removePrefix("wordcount=")
                ?.toLongOrNull() ?: 0L
        } catch (e: Exception) { 0L }
    }

    // ─────────────────────────────────────────────────────────────
    //  PRIVATE HELPERS
    // ─────────────────────────────────────────────────────────────

    private fun parseSameTypeSequence(ifoFile: File): String {
        return try {
            ifoFile.readLines()
                .firstOrNull { it.startsWith("sametypesequence=") }
                ?.removePrefix("sametypesequence=")
                ?.trim() ?: "m"
        } catch (e: Exception) { "m" }
    }

    /**
     * Decode definition bytes according to sametypesequence.
     * 'm' = plain text
     * 'g' = Pango markup (similar to HTML)
     * 'h' = HTML
     * 'x' = XDXF
     */
    private fun decodeDefinition(data: ByteArray, sameTypeSeq: String): String {
        val text = try { String(data, Charsets.UTF_8) } catch (e: Exception) { return "" }
        return when (sameTypeSeq.firstOrNull()) {
            'h' -> text // Already HTML
            'g' -> pangoToHtml(text)
            'x' -> xdxfToHtml(text)
            else -> "<p>${text.replace("\n", "<br>")}</p>"
        }
    }

    private fun pangoToHtml(pango: String): String =
        pango.replace("<b>", "<b>").replace("</b>", "</b>")
             .replace("<i>", "<i>").replace("</i>", "</i>")
             .replace("\n", "<br>")

    private fun xdxfToHtml(xdxf: String): String =
        xdxf.replace("<kref>", "<a href=\"lexidict://\">")
            .replace("</kref>", "</a>")
            .replace("<abr>", "<abbr>").replace("</abr>", "</abbr>")
            .replace("<dtrn>", "<span class=\"translation\">").replace("</dtrn>", "</span>")
            .replace("<ex>", "<div class=\"example\">").replace("</ex>", "</div>")

    // Simple non-allocating ByteArray stream for word parsing
    private inner class ByteArrayOutputStream2 {
        private val buf = ByteArray(256)
        private var size = 0

        fun write(b: Int) {
            if (size < buf.size) buf[size++] = b.toByte()
        }
        fun reset() { size = 0 }
        fun toString(charset: String) = String(buf, 0, size, charset(charset))
        private fun charset(name: String) = java.nio.charset.Charset.forName(name)
    }
}

fun com.lexidict.pro.parser.StarDictParser() = com.lexidict.pro.parser.stardict.StarDictParser()
