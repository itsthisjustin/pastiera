package it.palsoftware.pastiera.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Test

class Titan2EliteBottomRowSafeAreaTest {
    @Test
    fun disabled_hasNoHorizontalInsets() {
        assertEquals(
            Titan2EliteBottomRowSafeArea.Insets(0, 0),
            Titan2EliteBottomRowSafeArea.resolveInsetsPx(
                enabled = false,
                leftCornerRadiusPx = 90,
                rightCornerRadiusPx = 72,
                fallbackCornerRadiusPx = 60
            )
        )
    }

    @Test
    fun enabled_usesOneThirdOfReportedCornerRadiiForBottomRow() {
        assertEquals(
            Titan2EliteBottomRowSafeArea.Insets(30, 24),
            Titan2EliteBottomRowSafeArea.resolveInsetsPx(
                enabled = true,
                leftCornerRadiusPx = 90,
                rightCornerRadiusPx = 72,
                fallbackCornerRadiusPx = 60
            )
        )
    }

    @Test
    fun enabled_fallsBackPerMissingSide() {
        assertEquals(
            Titan2EliteBottomRowSafeArea.Insets(20, 24),
            Titan2EliteBottomRowSafeArea.resolveInsetsPx(
                enabled = true,
                leftCornerRadiusPx = 0,
                rightCornerRadiusPx = 72,
                fallbackCornerRadiusPx = 60
            )
        )
    }
}
