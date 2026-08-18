package it.palsoftware.pastiera.inputmethod

import kotlin.math.roundToInt

internal object Titan2EliteBottomRowSafeArea {
    private const val BOTTOM_ROW_RADIUS_FRACTION = 1f / 3f

    data class Insets(val left: Int, val right: Int)

    fun resolveInsetsPx(
        enabled: Boolean,
        leftCornerRadiusPx: Int,
        rightCornerRadiusPx: Int,
        fallbackCornerRadiusPx: Int
    ): Insets {
        if (!enabled) return Insets(left = 0, right = 0)

        return Insets(
            left = resolveInsetPx(leftCornerRadiusPx, fallbackCornerRadiusPx),
            right = resolveInsetPx(rightCornerRadiusPx, fallbackCornerRadiusPx)
        )
    }

    private fun resolveInsetPx(reportedRadiusPx: Int, fallbackRadiusPx: Int): Int {
        val radiusPx = reportedRadiusPx.takeIf { it > 0 } ?: fallbackRadiusPx
        return (radiusPx.coerceAtLeast(0) * BOTTOM_ROW_RADIUS_FRACTION).roundToInt()
    }
}
