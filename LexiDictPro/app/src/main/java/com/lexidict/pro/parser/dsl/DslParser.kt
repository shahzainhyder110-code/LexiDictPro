package com.lexidict.pro.parser.dsl

import com.lexidict.pro.core.domain.model.Dictionary
import com.lexidict.pro.core.domain.model.DictionaryFormat
import com.lexidict.pro.core.data.local.entities.WordIndexFtsEntity
import com.lexidict.pro.parser.DictionaryParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.charset.Charset

/**
 * Parser for ABBYY Lingvo DSL (.dsl) format.
 *
 * DSL Format:
 * - UTF-16 or UTF-8 text file (BOM determines encoding)
 * - Header lines starting with #NAME, #INDEX_LANGUAGE, etc.
 * - Entries: headword line (no leading spaces), followed by
 *   definition lines (leading \t or spaces)
 * - Markup: [b]bold[/b], [i]italic[/i], [m]margin[/m],
 *   [ref]crossref[/ref], [s]audio[/s], [c]color[/c]
 *
 * Also handles .lsd (compiled DSL — binary; we extract where possible).
 */
class DslParser : DictionaryParser {

    companion object {
        private const val BATCH_SIZE = 500
        private val HEADER_TAGS = setOf(
            "#NAME", "#INDEX_LANGUAGE", "#CONTENTS_LANGUAGE",
            "#SOURCE_CODE_PAGE", "#INCLUDE"
        )
    }

    override fun canParse(file: File): Boolean =
        file.extension.lowercase() in listOf("dsl", "lsd") && file.exists()

    override suspend fun extractMetadata(file: File): Dictionary {
        var name = file.nameWithoutExtension
        var sourceLang = ""
        var targetLang = ""

        if (file.extension.lowercase() == "dsl") {
            try {
                val reader = openReader(file)
                reader.use {
                    var line = it.readLine()
                    var headerDone = false
                    while (line != null && !headerDone) {
                        val trimmed = line.trimStart('\uFEFF') // BOM
                        when {
                            trimmed.startsWith("#NAME") ->
                                name = trimmed.substringAfter("\"").substringBefore("\"")
                            trimmed.startsWith("#INDEX_LANGUAGE") ->
                                sourceLang = trimmed.substringAfter("\"").substringBefore("\"")
                            trimmed.startsWith("#CONTENTS_LANGUAGE") ->
                                targetLang = trimmed.substringAfter("\"").substringBefore("\"")
                            !trimmed.startsWith("#") && trimmed.isNotBlank() ->
                                headerDone = true
                        }
                        line = it.readLine()
                    }
                }
            } catch (e: Exception) { /* fallback */ }
        }

        return Dictionary(
            name = name,
            sourceLanguage = sourceLang,
            targetLanguage = targetLang,
            filePath = file.absolutePath,
            format = DictionaryFormat.DSL,
            sizeBytes = file.length()
        )
    }

    override fun parseWords(file: File, dictionaryId: Long): Flow<List<WordIndexFtsEntity>> = flow {
        if (file.extension.lowercase() == "lsd") {
            // LSD is compiled binary — attempt basic extraction
            emit(parseLsd(file, dictionaryId))
            return@flow
        }

        val batch = mutableListOf<WordIndexFtsEntity>()
        var currentWord = ""
        val definitionBuilder = StringBuilder()
        var inHeader = true

        try {
            openReader(file).use { reader ->
                var line = reader.readLine()?.trimStart('\uFEFF') // Strip BOM

                while (line != null) {
                    when {
                        // Skip header directives
                        inHeader && line.startsWith("#") -> {
                            // Still in header
                        }
                        // First non-header, non-blank line
                        inHeader && line.isNotBlank() && !line.startsWith(" ") && !line.startsWith("\t") -> {
                            inHeader = false
                            currentWord = line.trim()
                        }
                        // Definition line (starts with whitespace)
                        !inHeader && (line.startsWith(" ") || line.startsWith("\t")) -> {
                            definitionBuilder.appendLine(line.trim())
                        }
                        // New headword (no leading whitespace, non-empty)
                        !inHeader && line.isNotBlank() && !line.startsWith(" ") && !line.startsWith("\t") -> {
                            // Save previous entry
                            if (currentWord.isNotBlank()) {
                                batch.add(createEntity(dictionaryId, currentWord, definitionBuilder.toString()))
                                if (batch.size >= BATCH_SIZE) {
                                    emit(batch.toList())
                                    batch.clear()
                                }
                            }
                            currentWord = line.trim()
                            definitionBuilder.clear()
                        }
                    }
                    line = reader.readLine()
                }

                // Save last entry
                if (currentWord.isNotBlank()) {
                    batch.add(createEntity(dictionaryId, currentWord, definitionBuilder.toString()))
                }
                if (batch.isNotEmpty()) emit(batch.toList())
            }
        } catch (e: Exception) {
            if (batch.isNotEmpty()) emit(batch.toList())
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun estimateWordCount(file: File): Long =
        (file.length() / 200).coerceAtLeast(0)

    // ─────────────────────────────────────────────────────────────
    //  PRIVATE HELPERS
    // ─────────────────────────────────────────────────────────────

    /**
     * Open a DSL file with the correct charset.
     * DSL files can be UTF-16LE (with BOM) or UTF-8.
     */
    private fun openReader(file: File): BufferedReader {
        val raw = file.inputStream()
        val first2 = ByteArray(2)
        raw.read(first2)
        raw.close()

        val charset = when {
            first2[0] == 0xFF.toByte() && first2[1] == 0xFE.toByte() -> Charsets.UTF_16LE
            first2[0] == 0xFE.toByte() && first2[1] == 0xFF.toByte() -> Charsets.UTF_16BE
            else -> Charsets.UTF_8
        }

        return BufferedReader(InputStreamReader(file.inputStream(), charset))
    }

    /**
     * Convert DSL markup to HTML.
     * [b]text[/b] → <b>text</b>
     * [i]text[/i] → <i>text</i>
     * [m1]text[/m] → <div class="m1">text</div>
     * [s]filename[/s] → audio placeholder
     * [ref]word[/ref] → hyperlink
     */
    private fun dslToHtml(dsl: String): String {
        return dsl
            .replace(Regex("\\[b\\](.+?)\\[/b\\]", RegexOption.DOT_MATCHES_ALL)) { "<b>${it.groupValues[1]}</b>" }
            .replace(Regex("\\[i\\](.+?)\\[/i\\]", RegexOption.DOT_MATCHES_ALL)) { "<i>${it.groupValues[1]}</i>" }
            .replace(Regex("\\[u\\](.+?)\\[/u\\]", RegexOption.DOT_MATCHES_ALL)) { "<u>${it.groupValues[1]}</u>" }
            .replace(Regex("\\[c (.+?)\\](.+?)\\[/c\\]", RegexOption.DOT_MATCHES_ALL)) { "<span style=\"color:${it.groupValues[1]}\">${it.groupValues[2]}</span>" }
            .replace(Regex("\\[m(\\d?)\\](.+?)\\[/m\\]", RegexOption.DOT_MATCHES_ALL)) { "<div class=\"m${it.groupValues[1]}\">${it.groupValues[2]}</div>" }
            .replace(Regex("\\[ex\\](.+?)\\[/ex\\]", RegexOption.DOT_MATCHES_ALL)) { "<div class=\"example\"><i>${it.groupValues[1]}</i></div>" }
            .replace(Regex("\\[ref\\](.+?)\\[/ref\\]")) { "<a href=\"lexidict://word/${it.groupValues[1]}\">${it.groupValues[1]}</a>" }
            .replace(Regex("\\[s\\](.+?)\\[/s\\]")) { "<audio data-src=\"${it.groupValues[1]}\"></audio>" }
            .replace(Regex("\\[p\\](.+?)\\[/p\\]")) { "<span class=\"pos\">${it.groupValues[1]}</span>" }
            .replace(Regex("\\[t\\](.+?)\\[/t\\]")) { "<span class=\"transcription\">/${it.groupValues[1]}/</span>" }
            .replace(Regex("\\[.*?\\]")) { "" } // Remove unknown tags
            .trim()
    }

    private fun createEntity(dictionaryId: Long, word: String, definition: String): WordIndexFtsEntity {
        // Extract IPA transcription if present
        val ipaMatch = Regex("\\[t\\](.+?)\\[/t\\]").find(definition)
        val ipa = ipaMatch?.groupValues?.get(1) ?: ""

        return WordIndexFtsEntity(
            dictionaryId = dictionaryId,
            word = word,
            wordLower = word.lowercase().trim(),
            definition = dslToHtml(definition),
            pronunciation = ipa,
            sortKey = word.lowercase().take(4)
        )
    }

    /**
     * Basic LSD extraction.
     * LSD is a proprietary compiled format — we attempt pattern-based extraction.
     * For full support, a native JNI implementation would be preferred.
     */
    private fun parseLsd(file: File, dictionaryId: Long): List<WordIndexFtsEntity> {
        val results = mutableListOf<WordIndexFtsEntity>()
        // LSD parsing is complex; return empty for now
        // Full implementation would use JNI with C++ BGL/LSD library
        return results
    }
}

// Make visible from parent package
fun com.lexidict.pro.parser.DslParser() = com.lexidict.pro.parser.dsl.DslParser()
