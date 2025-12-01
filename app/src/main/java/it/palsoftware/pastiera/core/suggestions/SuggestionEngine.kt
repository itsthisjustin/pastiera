package it.palsoftware.pastiera.core.suggestions

import kotlin.math.min
import java.text.Normalizer
import java.util.Locale
import android.util.Log

data class SuggestionResult(
    val candidate: String,
    val distance: Int,
    val score: Double,
    val source: SuggestionSource
)

class SuggestionEngine(
    private val repository: DictionaryRepository,
    private val locale: Locale = Locale.getDefault(),
    private val debugLogging: Boolean = false
) {

    // Keep only Unicode letters (supports Latin, Cyrillic, Greek, Arabic, Chinese, etc.)
    // Removes: punctuation, numbers, spaces, emoji, symbols
    private val normalizeRegex = "[^\\p{L}]".toRegex()
    private val accentCache: MutableMap<String, String> = mutableMapOf()
    private val tag = "SuggestionEngine"
    private val wordNormalizeCache: MutableMap<String, String> = mutableMapOf()

    fun suggest(
        currentWord: String,
        limit: Int = 3,
        includeAccentMatching: Boolean = true
    ): List<SuggestionResult> {
        if (currentWord.isBlank()) return emptyList()
        if (!repository.isReady) return emptyList()
        val normalizedWord = normalize(currentWord)
        // Require at least 1 character to start suggesting.
        if (normalizedWord.length < 1) return emptyList()

        // Generate prefix variants to catch transpositions (e.g., "teh" -> also check "the", "eth")
        val prefixVariants = generatePrefixVariants(normalizedWord)

        // Collect candidates from all prefix variants
        val candidateSet = mutableSetOf<DictionaryEntry>()
        for (prefix in prefixVariants) {
            candidateSet.addAll(repository.lookupByPrefix(prefix))
        }
        val candidates = candidateSet.toList()

        if (debugLogging) {
            Log.d(tag, "suggest '$currentWord' normalized='$normalizedWord' prefixes=$prefixVariants candidates=${candidates.size}")
        }

        val scored = mutableListOf<SuggestionResult>()
        for (entry in candidates) {
            val normalizedCandidate = normalizeCached(entry.word)

            // Calculate actual edit distance (don't treat prefix as 0 for autocorrection accuracy)
            val distance = boundedLevenshtein(normalizedWord, normalizedCandidate, 2)
            if (distance < 0) continue

            val accentDistance = if (includeAccentMatching) {
                val normalizedNoAccent = stripAccents(normalizedCandidate)
                boundedLevenshtein(normalizedWord, normalizedNoAccent, 2)
            } else distance

            val effectiveDistance = if (accentDistance >= 0) min(distance, accentDistance) else distance

            // Scoring: heavily weight frequency, penalize longer words
            // Frequency is on scale of ~1-10,000,000, normalize to 0-1 range with log scale
            val logFreq = kotlin.math.ln(entry.frequency.toDouble() + 1)
            val maxLogFreq = kotlin.math.ln(10_000_000.0)
            val normalizedFreq = logFreq / maxLogFreq  // 0 to 1

            // Length penalty: prefer words closer to input length
            val lengthDiff = kotlin.math.abs(normalizedCandidate.length - normalizedWord.length)
            val lengthPenalty = 1.0 / (1 + lengthDiff * 0.5)

            // Distance is most important, then frequency, then length
            val distanceScore = 1.0 / (1 + effectiveDistance * 2)
            val sourceBoost = if (entry.source == SuggestionSource.USER) 1.5 else 1.0

            // Combined score: distance matters most, frequency breaks ties
            val score = (distanceScore * 10 + normalizedFreq * 5 + lengthPenalty) * sourceBoost

            if (debugLogging && effectiveDistance <= 1) {
                Log.d(tag, "  candidate='${entry.word}' dist=$effectiveDistance freq=${entry.frequency} score=$score")
            }

            scored.add(
                SuggestionResult(
                    candidate = entry.word,
                    distance = effectiveDistance,
                    score = score,
                    source = entry.source
                )
            )
        }

        return scored
            .sortedWith(
                compareBy<SuggestionResult> { it.distance }
                    .thenByDescending { it.score }
                    .thenBy { it.candidate.length }
            )
            .take(limit)
    }

    /**
     * Generate prefix variants to catch common typos:
     * - Transpositions: "teh" -> "the"
     * - Deletions: "tesst" -> "test" (delete one char)
     * - Substitutions: covered by Levenshtein after lookup
     * Only generates variants for the first few characters to limit lookups.
     */
    private fun generatePrefixVariants(word: String): Set<String> {
        val variants = mutableSetOf(word)

        // Generate transpositions for first 3 character positions
        val maxPos = minOf(word.length - 1, 3)
        for (i in 0 until maxPos) {
            val chars = word.toCharArray()
            val temp = chars[i]
            chars[i] = chars[i + 1]
            chars[i + 1] = temp
            variants.add(String(chars))
        }

        // Generate deletions for first 4 character positions
        // "tesst" -> "esst", "tsst", "test", "tess"
        val maxDelPos = minOf(word.length, 4)
        for (i in 0 until maxDelPos) {
            val deleted = word.removeRange(i, i + 1)
            if (deleted.length >= 2) {
                variants.add(deleted)
            }
        }

        return variants
    }

    /**
     * Damerau-Levenshtein distance with transposition support.
     * Transposing adjacent characters counts as 1 edit (not 2).
     * This handles common typos like "teh" -> "the" correctly.
     */
    private fun boundedLevenshtein(a: String, b: String, maxDistance: Int): Int {
        if (kotlin.math.abs(a.length - b.length) > maxDistance) return -1

        val lenA = a.length
        val lenB = b.length

        // Use full matrix for Damerau-Levenshtein (needed for transposition check)
        val dp = Array(lenA + 1) { IntArray(lenB + 1) }

        for (i in 0..lenA) dp[i][0] = i
        for (j in 0..lenB) dp[0][j] = j

        for (i in 1..lenA) {
            var minRow = dp[i][0]
            for (j in 1..lenB) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,      // deletion
                    dp[i][j - 1] + 1,      // insertion
                    dp[i - 1][j - 1] + cost // substitution
                )
                // Transposition: swap adjacent characters
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                    dp[i][j] = minOf(dp[i][j], dp[i - 2][j - 2] + 1)
                }
                minRow = min(minRow, dp[i][j])
            }
            if (minRow > maxDistance) return -1
        }
        return if (dp[lenA][lenB] <= maxDistance) dp[lenA][lenB] else -1
    }

    private fun normalize(word: String): String {
        return stripAccents(word.lowercase(locale))
            .replace(normalizeRegex, "")
    }

    private fun normalizeCached(word: String): String {
        return wordNormalizeCache.getOrPut(word) { normalize(word) }
    }

    private fun stripAccents(input: String): String {
        return accentCache.getOrPut(input) {
            Normalizer.normalize(input, Normalizer.Form.NFD)
                .replace("\\p{Mn}".toRegex(), "")
        }
    }
}
