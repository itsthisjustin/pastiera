package it.palsoftware.pastiera.core.suggestions

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Keyboard proximity calculator for QWERTY layout.
 * Calculates physical distance between keys to improve spelling suggestions.
 */
object KeyboardProximity {

    // QWERTY keyboard layout positions (row, column)
    // Row 0 = top row, increasing downward
    // Column 0 = leftmost key, increasing rightward
    private val keyPositions = mapOf(
        'q' to Pair(0.0, 0.0), 'w' to Pair(0.0, 1.0), 'e' to Pair(0.0, 2.0), 'r' to Pair(0.0, 3.0),
        't' to Pair(0.0, 4.0), 'y' to Pair(0.0, 5.0), 'u' to Pair(0.0, 6.0), 'i' to Pair(0.0, 7.0),
        'o' to Pair(0.0, 8.0), 'p' to Pair(0.0, 9.0),

        'a' to Pair(1.0, 0.5), 's' to Pair(1.0, 1.5), 'd' to Pair(1.0, 2.5), 'f' to Pair(1.0, 3.5),
        'g' to Pair(1.0, 4.5), 'h' to Pair(1.0, 5.5), 'j' to Pair(1.0, 6.5), 'k' to Pair(1.0, 7.5),
        'l' to Pair(1.0, 8.5),

        'z' to Pair(2.0, 1.5), 'x' to Pair(2.0, 2.5), 'c' to Pair(2.0, 3.5), 'v' to Pair(2.0, 4.5),
        'b' to Pair(2.0, 5.5), 'n' to Pair(2.0, 6.5), 'm' to Pair(2.0, 7.5)
    )

    /**
     * Calculate Euclidean distance between two keys on the keyboard.
     * Returns a value from 0.0 (same key) to ~10.0 (opposite corners).
     */
    fun keyDistance(char1: Char, char2: Char): Double {
        val pos1 = keyPositions[char1.lowercaseChar()]
        val pos2 = keyPositions[char2.lowercaseChar()]

        if (pos1 == null || pos2 == null) {
            // If either character is not on the keyboard, return max distance
            return 10.0
        }

        val rowDiff = abs(pos1.first - pos2.first)
        val colDiff = abs(pos1.second - pos2.second)

        return sqrt(rowDiff * rowDiff + colDiff * colDiff)
    }

    /**
     * Calculate keyboard-aware edit distance between two words.
     * Lower score = better match considering keyboard proximity.
     *
     * @param typed The word the user typed
     * @param candidate The suggested word from dictionary
     * @return Proximity score (lower is better, 0.0 = exact match)
     */
    fun proximityScore(typed: String, candidate: String): Double {
        if (typed == candidate) return 0.0

        val typedLower = typed.lowercase()
        val candidateLower = candidate.lowercase()

        // Use dynamic programming to find minimum edit distance with proximity weights
        val m = typedLower.length
        val n = candidateLower.length

        // dp[i][j] = distance between first i chars of typed and first j chars of candidate
        val dp = Array(m + 1) { DoubleArray(n + 1) }

        // Initialize base cases
        for (i in 0..m) dp[i][0] = i * 1.0  // deletions
        for (j in 0..n) dp[0][j] = j * 1.0  // insertions

        // Fill the DP table
        for (i in 1..m) {
            for (j in 1..n) {
                val typedChar = typedLower[i - 1]
                val candidateChar = candidateLower[j - 1]

                if (typedChar == candidateChar) {
                    // Characters match - no cost
                    dp[i][j] = dp[i - 1][j - 1]
                } else {
                    // Calculate costs for each operation
                    val deletionCost = dp[i - 1][j] + 1.0
                    val insertionCost = dp[i][j - 1] + 1.0

                    // Substitution cost based on keyboard proximity
                    // Close keys get lower penalty (0.3-0.7), far keys get higher penalty (1.0+)
                    val distance = keyDistance(typedChar, candidateChar)
                    val substitutionPenalty = when {
                        distance < 1.5 -> 0.3  // Adjacent keys
                        distance < 2.5 -> 0.5  // Near keys
                        distance < 4.0 -> 0.7  // Moderate distance
                        else -> 1.2            // Far keys (worse than insert/delete)
                    }
                    val substitutionCost = dp[i - 1][j - 1] + substitutionPenalty

                    dp[i][j] = minOf(deletionCost, insertionCost, substitutionCost)
                }
            }
        }

        return dp[m][n]
    }

    /**
     * Re-rank suggestions based on keyboard proximity.
     *
     * @param typed The word the user typed
     * @param suggestions List of candidate words with their metadata
     * @return Re-ranked list with proximity-aware scoring
     */
    fun reRankSuggestions(
        typed: String,
        suggestions: List<SuggestionResult>
    ): List<SuggestionResult> {
        if (suggestions.isEmpty()) return suggestions

        // Calculate proximity scores for each suggestion
        val scored = suggestions.map { suggestion ->
            val proximityDist = proximityScore(typed, suggestion.candidate)

            // Combined score: proximity + frequency weight
            // Lower proximity distance is better, higher frequency is better
            // Normalize frequency to 0-1 range (assuming max freq ~1M)
            val freqScore = (suggestion.score.toDouble() / 1_000_000.0).coerceIn(0.0, 1.0)

            // Final score: proximity distance - frequency bonus
            // Lower final score = better suggestion
            val finalScore = proximityDist - (freqScore * 0.5)

            Pair(suggestion, finalScore)
        }

        // Sort by final score (ascending - lower is better)
        return scored
            .sortedBy { it.second }
            .map { it.first }
    }
}
