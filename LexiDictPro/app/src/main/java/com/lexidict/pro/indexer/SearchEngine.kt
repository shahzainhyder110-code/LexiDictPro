package com.lexidict.pro.indexer

import android.content.Context
import androidx.sqlite.db.SimpleSQLiteQuery
import com.lexidict.pro.core.data.local.dao.WordIndexDao
import com.lexidict.pro.core.data.local.dao.WordWithDictName
import com.lexidict.pro.core.domain.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

/**
 * Core search engine supporting:
 * - Prefix search (ultra-fast, DB indexed)
 * - Exact match
 * - Fuzzy match (Levenshtein distance)
 * - Wildcard (GLOB patterns)
 * - Full-text search (FTS5)
 * - Morphological (stemmed)
 * - Transliteration
 */
@Singleton
class SearchEngine @Inject constructor(
    private val context: Context
) {

    /**
     * Main search entry point.
     * Runs appropriate strategy based on [SearchQuery.mode].
     */
    suspend fun search(
        dao: WordIndexDao,
        query: SearchQuery
    ): List<SearchResult> = withContext(Dispatchers.IO) {

        if (query.text.isBlank()) return@withContext emptyList()

        val normalizedQuery = query.text.trim().lowercase()

        val rawResults: List<WordWithDictName> = when (query.mode) {
            SearchMode.EXACT -> dao.searchExact(normalizedQuery)
            SearchMode.PREFIX -> dao.searchByPrefix(normalizedQuery, query.maxResults)
            SearchMode.FUZZY -> fuzzySearch(dao, normalizedQuery, query.maxResults)
            SearchMode.WILDCARD -> wildcardSearch(dao, normalizedQuery, query.maxResults)
            SearchMode.FULLTEXT -> fullTextSearch(dao, normalizedQuery, query.maxResults)
            SearchMode.MORPHOLOGICAL -> morphologicalSearch(dao, normalizedQuery, query.maxResults)
        }

        // Apply dictionary filter if specified
        val filtered = if (query.dictionaryIds.isNotEmpty()) {
            rawResults.filter { it.dictionaryId in query.dictionaryIds }
        } else rawResults

        // Score and rank results
        val scored = filtered.map { row ->
            row to computeRelevanceScore(row.word, query.text)
        }.sortedByDescending { it.second }

        scored.map { (row, score) ->
            SearchResult(
                word = row.word,
                definition = row.definition,
                definitionHtml = row.definition,
                dictionaryId = row.dictionaryId,
                dictionaryName = row.dict_name,
                pronunciation = row.pronunciation.ifEmpty { null },
                audioPath = row.audioPath.ifEmpty { null },
                rank = score
            )
        }.take(query.maxResults)
    }

    /**
     * Get autocomplete suggestions (prefix-based, fast).
     */
    suspend fun getSuggestions(
        dao: WordIndexDao,
        prefix: String,
        limit: Int = 10
    ): List<String> = withContext(Dispatchers.IO) {
        if (prefix.length < 1) return@withContext emptyList()
        dao.searchByPrefix(prefix.lowercase(), limit).map { it.word }.distinct()
    }

    // ─────────────────────────────────────────────────────────────
    //  SEARCH STRATEGIES
    // ─────────────────────────────────────────────────────────────

    /**
     * Fuzzy search using Levenshtein distance.
     * First do a prefix/substring search, then filter by edit distance.
     */
    private suspend fun fuzzySearch(
        dao: WordIndexDao,
        query: String,
        limit: Int
    ): List<WordWithDictName> {
        val maxDistance = when {
            query.length <= 4 -> 1
            query.length <= 8 -> 2
            else -> 3
        }

        // Get candidates via LIKE (fast initial filter)
        val candidates = dao.searchFuzzy(query, limit * 5)

        return candidates
            .filter { levenshtein(it.wordLower, query) <= maxDistance }
            .sortedBy { levenshtein(it.wordLower, query) }
            .take(limit)
    }

    /**
     * Wildcard search: convert * → % and ? → _ for SQL GLOB,
     * or use LIKE patterns.
     */
    private suspend fun wildcardSearch(
        dao: WordIndexDao,
        query: String,
        limit: Int
    ): List<WordWithDictName> {
        // SQL GLOB: * matches any sequence, ? matches single char
        // User input * → *, ? → ? (GLOB already supports these)
        val globPattern = query.lowercase()
            .replace(".", "\\.")   // escape SQL special chars
        return dao.searchWildcard(globPattern, limit)
    }

    /**
     * FTS5 full-text search.
     * Searches word AND definition fields.
     */
    private suspend fun fullTextSearch(
        dao: WordIndexDao,
        query: String,
        limit: Int
    ): List<WordWithDictName> {
        val ftsQuery = query
            .split(" ")
            .joinToString(" ") { if (it.length > 2) "$it*" else it }

        val sql = """
            SELECT wi.*, d.name as dict_name
            FROM word_index_fts wi
            JOIN dictionaries d ON wi.dictionary_id = d.id
            WHERE wi.rowid IN (
                SELECT rowid FROM word_index_fts_search
                WHERE word_index_fts_search MATCH ?
            )
            AND d.is_enabled = 1
            LIMIT $limit
        """.trimIndent()

        return dao.searchFullText(SimpleSQLiteQuery(sql, arrayOf(ftsQuery)))
    }

    /**
     * Morphological search: apply simple suffix stripping (Porter stemmer lite)
     * then prefix-search on stem.
     */
    private suspend fun morphologicalSearch(
        dao: WordIndexDao,
        query: String,
        limit: Int
    ): List<WordWithDictName> {
        val stem = simpleStem(query)
        return if (stem != query) {
            val stemResults = dao.searchByPrefix(stem, limit)
            val exactResults = dao.searchByPrefix(query, limit)
            (stemResults + exactResults).distinctBy { it.rowId }.take(limit)
        } else {
            dao.searchByPrefix(query, limit)
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  ALGORITHMS
    // ─────────────────────────────────────────────────────────────

    /**
     * Compute relevance score for ranking results.
     * Higher score = better match.
     */
    private fun computeRelevanceScore(word: String, query: String): Float {
        val w = word.lowercase()
        val q = query.lowercase()
        return when {
            w == q -> 100f                           // Exact match
            w.startsWith(q) -> 90f - w.length       // Prefix match (shorter = better)
            w.contains(q) -> 70f - w.length         // Substring match
            else -> 50f - levenshtein(w, q) * 10f   // Fuzzy
        }
    }

    /**
     * Levenshtein edit distance — O(m*n) dynamic programming.
     * Capped at distance 10 for performance.
     */
    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return min(b.length, 10)
        if (b.isEmpty()) return min(a.length, 10)

        val m = min(a.length, 50) // Cap length for performance
        val n = min(b.length, 50)

        val dp = Array(m + 1) { IntArray(n + 1) }
        for (i in 0..m) dp[i][0] = i
        for (j in 0..n) dp[0][j] = j

        for (i in 1..m) {
            for (j in 1..n) {
                dp[i][j] = if (a[i - 1] == b[j - 1]) dp[i - 1][j - 1]
                else minOf(dp[i - 1][j] + 1, dp[i][j - 1] + 1, dp[i - 1][j - 1] + 1)
            }
        }
        return dp[m][n]
    }

    /**
     * Simple English suffix stripping for morphological search.
     * For production, integrate a proper stemming library (e.g., Snowball).
     */
    private fun simpleStem(word: String): String {
        val w = word.lowercase()
        return when {
            w.length > 5 && w.endsWith("ing") -> w.dropLast(3)
            w.length > 4 && w.endsWith("ies") -> w.dropLast(3) + "y"
            w.length > 4 && w.endsWith("ed")  -> w.dropLast(2)
            w.length > 3 && w.endsWith("es")  -> w.dropLast(1)
            w.length > 3 && w.endsWith("s")   -> w.dropLast(1)
            else -> w
        }
    }

    /**
     * Transliterate common scripts to Latin for cross-script search.
     * Covers basic Cyrillic ↔ Latin mapping.
     */
    fun transliterate(input: String): String {
        val cyrillicToLatin = mapOf(
            'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d",
            'е' to "e", 'ё' to "yo", 'ж' to "zh", 'з' to "z", 'и' to "i",
            'й' to "j", 'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n",
            'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t",
            'у' to "u", 'ф' to "f", 'х' to "kh", 'ц' to "ts", 'ч' to "ch",
            'ш' to "sh", 'щ' to "shch", 'ъ' to "", 'ы' to "y", 'ь' to "",
            'э' to "e", 'ю' to "yu", 'я' to "ya"
        )
        return input.map { c -> cyrillicToLatin[c.lowercaseChar()] ?: c.toString() }.joinToString("")
    }
}
