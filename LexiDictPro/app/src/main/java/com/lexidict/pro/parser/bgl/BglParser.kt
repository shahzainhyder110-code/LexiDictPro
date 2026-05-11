package com.lexidict.pro.parser.bgl

import com.lexidict.pro.core.domain.model.Dictionary
import com.lexidict.pro.core.domain.model.DictionaryFormat
import com.lexidict.pro.core.data.local.entities.WordIndexFtsEntity
import com.lexidict.pro.parser.DictionaryParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.jsoup.Jsoup
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Inflater

/**
 * Parser for Babylon Dictionary (.bgl) format.
 *
 * BGL Format Overview:
 * - Binary format with gzip-compressed blocks
 * - Header block with metadata (name, author, source/target lang)
 * - Data blocks containing word + definition pairs
 * - Each block: 4-byte length + type byte + compressed data
 *
 * Reference: Open-source GoldenDict BGL parser implementation.
 */
class BglParser : DictionaryParser {

    companion object {
        // BGL magic bytes: first 4 bytes of a valid BGL file
        private val BGL_MAGIC = byteArrayOf(0x5C, 0x00, 0x00, 0x10.toByte())
        private const val BATCH_SIZE = 500 // Words per DB insertion batch
        private const val BLOCK_TYPE_INFO = 0x00
        private const val BLOCK_TYPE_WORD = 0x01
    }

    override fun canParse(file: File): Boolean {
        if (!file.exists() || file.extension.lowercase() != "bgl") return false
        return try {
            val magic = file.inputStream().use { it.readNBytes(4) }
            magic.size >= 2 && magic[0] == 0x5C.toByte()
        } catch (e: Exception) { false }
    }

    override suspend fun extractMetadata(file: File): Dictionary {
        var name = file.nameWithoutExtension
        var author = ""
        var sourceLanguage = ""
        var targetLanguage = ""
        var description = ""

        try {
            RandomAccessFile(file, "r").use { raf ->
                // Skip magic bytes (4 bytes) + version info (2 bytes)
                raf.seek(6)

                // Read info block
                val infoBlock = readNextBlock(raf, BLOCK_TYPE_INFO)
                if (infoBlock != null) {
                    val info = decompressBlock(infoBlock)
                    val parsed = parseInfoBlock(info)
                    name = parsed["name"] ?: name
                    author = parsed["author"] ?: ""
                    sourceLanguage = parsed["sourceLang"] ?: ""
                    targetLanguage = parsed["targetLang"] ?: ""
                    description = parsed["description"] ?: ""
                }
            }
        } catch (e: Exception) {
            // Fallback to filename-based metadata
        }

        return Dictionary(
            name = name,
            author = author,
            description = description,
            sourceLanguage = sourceLanguage,
            targetLanguage = targetLanguage,
            filePath = file.absolutePath,
            format = DictionaryFormat.BGL,
            sizeBytes = file.length()
        )
    }

    override fun parseWords(file: File, dictionaryId: Long): Flow<List<WordIndexFtsEntity>> = flow {
        val batch = mutableListOf<WordIndexFtsEntity>()

        try {
            RandomAccessFile(file, "r").use { raf ->
                // Skip header: magic(4) + version(2)
                raf.seek(6)

                // Skip info block
                skipBlock(raf)

                // Read all word blocks
                while (raf.filePointer < raf.length() - 8) {
                    val block = readNextBlock(raf, BLOCK_TYPE_WORD) ?: continue
                    val data = decompressBlock(block) ?: continue

                    val entry = parseWordBlock(data) ?: continue
                    val (word, definition) = entry

                    if (word.isBlank()) continue

                    batch.add(
                        WordIndexFtsEntity(
                            dictionaryId = dictionaryId,
                            word = word,
                            wordLower = word.lowercase().trim(),
                            definition = sanitizeHtml(definition),
                            sortKey = word.lowercase().take(4)
                        )
                    )

                    if (batch.size >= BATCH_SIZE) {
                        emit(batch.toList())
                        batch.clear()
                    }
                }

                // Emit remaining
                if (batch.isNotEmpty()) emit(batch.toList())
            }
        } catch (e: Exception) {
            if (batch.isNotEmpty()) emit(batch.toList())
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun estimateWordCount(file: File): Long {
        // Rough estimate: BGL avg ~100 bytes/entry
        return (file.length() / 120).coerceAtLeast(0)
    }

    // ─────────────────────────────────────────────────────────────
    //  PRIVATE HELPERS
    // ─────────────────────────────────────────────────────────────

    private fun readNextBlock(raf: RandomAccessFile, expectedType: Int): ByteArray? {
        return try {
            val lengthBytes = ByteArray(4)
            if (raf.read(lengthBytes) < 4) return null

            val length = ByteBuffer.wrap(lengthBytes)
                .order(ByteOrder.BIG_ENDIAN).int and 0x7FFFFFFF

            if (length <= 0 || length > 10_000_000) return null

            val typeAndData = ByteArray(length)
            raf.read(typeAndData)

            // Check block type matches expected (0 = info, 1 = word)
            if (typeAndData.isEmpty()) return null
            if (typeAndData[0].toInt() and 0xFF != expectedType) {
                // Wrong type: seek back and return null
                return null
            }

            typeAndData.copyOfRange(1, typeAndData.size)
        } catch (e: Exception) { null }
    }

    private fun skipBlock(raf: RandomAccessFile) {
        try {
            val lengthBytes = ByteArray(4)
            if (raf.read(lengthBytes) < 4) return
            val length = ByteBuffer.wrap(lengthBytes).order(ByteOrder.BIG_ENDIAN).int and 0x7FFFFFFF
            if (length > 0 && length < 10_000_000) raf.seek(raf.filePointer + length)
        } catch (e: Exception) { /* skip */ }
    }

    private fun decompressBlock(data: ByteArray): ByteArray? {
        return try {
            val inflater = Inflater(true) // nowrap=true for raw deflate
            inflater.setInput(data)
            val output = ByteArray(data.size * 6) // estimate 6x expansion
            val size = inflater.inflate(output)
            inflater.end()
            output.copyOf(size)
        } catch (e: Exception) {
            // Block may not be compressed (older BGL format)
            data
        }
    }

    private fun parseInfoBlock(data: ByteArray): Map<String, String> {
        val result = mutableMapOf<String, String>()
        try {
            val text = String(data, Charsets.UTF_8)
            // BGL info block uses tag-based format
            val nameMatch = Regex("<title>(.*?)</title>", RegexOption.DOT_MATCHES_ALL).find(text)
            val authorMatch = Regex("<author>(.*?)</author>", RegexOption.DOT_MATCHES_ALL).find(text)
            val srcLangMatch = Regex("<sourceLanguage>(.*?)</sourceLanguage>").find(text)
            val tgtLangMatch = Regex("<targetLanguage>(.*?)</targetLanguage>").find(text)
            val descMatch = Regex("<description>(.*?)</description>", RegexOption.DOT_MATCHES_ALL).find(text)

            nameMatch?.groupValues?.get(1)?.let { result["name"] = it.trim() }
            authorMatch?.groupValues?.get(1)?.let { result["author"] = it.trim() }
            srcLangMatch?.groupValues?.get(1)?.let { result["sourceLang"] = it.trim() }
            tgtLangMatch?.groupValues?.get(1)?.let { result["targetLang"] = it.trim() }
            descMatch?.groupValues?.get(1)?.let { result["description"] = it.trim() }
        } catch (e: Exception) { /* ignore */ }
        return result
    }

    private fun parseWordBlock(data: ByteArray): Pair<String, String>? {
        return try {
            var offset = 0

            // Word length: 1-byte
            val wordLen = data[offset++].toInt() and 0xFF
            if (wordLen == 0 || offset + wordLen > data.size) return null

            val word = String(data, offset, wordLen, Charsets.UTF_8)
            offset += wordLen

            // Definition is remaining bytes
            val defBytes = data.copyOfRange(offset, data.size)
            val definition = String(defBytes, Charsets.UTF_8)

            Pair(word, definition)
        } catch (e: Exception) { null }
    }

    /**
     * Sanitize BGL HTML definitions.
     * BGL definitions can contain Babylon-specific tags.
     */
    private fun sanitizeHtml(html: String): String {
        return try {
            val doc = Jsoup.parse(html)
            // Remove scripts and styles
            doc.select("script,style").remove()
            // Convert Babylon-specific <c> color tags
            doc.select("c").forEach { el ->
                el.unwrap()
            }
            doc.body().html()
        } catch (e: Exception) { html }
    }
}

// ─────────────────────────────────────────────────────────────
//  Re-export from parent package
// ─────────────────────────────────────────────────────────────
typealias BglParserAlias = BglParser

// Make visible from parent package
fun com.lexidict.pro.parser.BglParser() = BglParserAlias()
