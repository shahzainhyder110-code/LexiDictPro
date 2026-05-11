package com.lexidict.pro.parser

import android.content.Context
import com.lexidict.pro.core.domain.model.Dictionary
import com.lexidict.pro.core.domain.model.DictionaryFormat
import com.lexidict.pro.core.data.local.entities.WordIndexFtsEntity
import com.lexidict.pro.parser.bgl.BglParser
import com.lexidict.pro.parser.dictd.DictdParser
import com.lexidict.pro.parser.dsl.DslParser
import com.lexidict.pro.parser.stardict.StarDictParser
import com.lexidict.pro.parser.zip.ZipParser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File
import java.io.InputStream

// ═══════════════════════════════════════════════════════════════
//  PARSER INTERFACE
// ═══════════════════════════════════════════════════════════════

/**
 * Base interface all dictionary parsers must implement.
 */
interface DictionaryParser {

    /** Detect whether this parser can handle the given file. */
    fun canParse(file: File): Boolean

    /** Extract dictionary metadata without full indexing. */
    suspend fun extractMetadata(file: File): Dictionary

    /**
     * Parse the dictionary and emit batches of word entries.
     * Uses Flow for backpressure-safe streaming of large files.
     *
     * @param file         Dictionary file or directory root
     * @param dictionaryId Room ID assigned after metadata insertion
     * @return Flow emitting batches of [WordIndexFtsEntity] for insertion
     */
    fun parseWords(file: File, dictionaryId: Long): Flow<List<WordIndexFtsEntity>>

    /** Total word count estimate (may be 0 if unknown before full parse). */
    suspend fun estimateWordCount(file: File): Long
}

// ═══════════════════════════════════════════════════════════════
//  PARSER FACTORY
// ═══════════════════════════════════════════════════════════════

/**
 * Selects the correct parser based on file extension or magic bytes.
 */
class DictionaryParserFactory(private val context: Context) {

    private val parsers: List<DictionaryParser> by lazy {
        listOf(
            BglParser(),
            DslParser(),
            StarDictParser(),
            DictdParser(),
            ZipParser()
        )
    }

    fun getParser(file: File): DictionaryParser? =
        parsers.firstOrNull { it.canParse(file) }

    fun detectFormat(file: File): DictionaryFormat {
        val ext = file.extension.lowercase()
        return when {
            ext == "bgl" -> DictionaryFormat.BGL
            ext in listOf("dsl", "lsd") -> DictionaryFormat.DSL
            ext in listOf("ifo", "idx", "dict", "dz") -> DictionaryFormat.STARDICT
            ext in listOf("index", "data") -> DictionaryFormat.DICTD
            ext == "zip" -> DictionaryFormat.ZIP
            else -> checkMagicBytes(file)
        }
    }

    private fun checkMagicBytes(file: File): DictionaryFormat {
        if (!file.exists() || file.length() < 4) return DictionaryFormat.UNKNOWN
        return try {
            val magic = file.inputStream().use { it.readNBytes(4) }
            when {
                // BGL magic: 0x5C 0x00 0x00 0x10 or similar
                magic[0] == 0x5C.toByte() -> DictionaryFormat.BGL
                // ZIP magic: PK\x03\x04
                magic[0] == 0x50.toByte() && magic[1] == 0x4B.toByte() -> DictionaryFormat.ZIP
                // StarDict .dict.dz: gzip magic 1F 8B
                magic[0] == 0x1F.toByte() && magic[1] == 0x8B.toByte() -> DictionaryFormat.STARDICT
                else -> DictionaryFormat.UNKNOWN
            }
        } catch (e: Exception) {
            DictionaryFormat.UNKNOWN
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//  PARSE PROGRESS DATA CLASS
// ═══════════════════════════════════════════════════════════════

data class ParseProgress(
    val wordsProcessed: Long,
    val totalWords: Long,
    val percentComplete: Int = if (totalWords > 0)
        ((wordsProcessed * 100) / totalWords).toInt() else 0
)

// ═══════════════════════════════════════════════════════════════
//  HELPER: READ BYTES FROM INPUTSTREAM
// ═══════════════════════════════════════════════════════════════

fun InputStream.readNBytes(n: Int): ByteArray {
    val buf = ByteArray(n)
    var offset = 0
    while (offset < n) {
        val read = read(buf, offset, n - offset)
        if (read == -1) break
        offset += read
    }
    return buf.copyOf(offset)
}
