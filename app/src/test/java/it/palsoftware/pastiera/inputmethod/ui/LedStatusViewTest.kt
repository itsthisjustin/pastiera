package it.palsoftware.pastiera.inputmethod.ui

import android.graphics.Color
import android.view.View
import it.palsoftware.pastiera.R
import it.palsoftware.pastiera.inputmethod.StatusBarController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LedStatusViewTest {
    @Test
    fun titan2EliteLayoutProjectsFiveSegmentsOntoTwoRows() {
        val ledStatusView = LedStatusView(RuntimeEnvironment.getApplication()).apply {
            layout = ModifierLedLayouts.TITAN_2_ELITE
        }
        val view = ledStatusView.ensureView()
        view.measure(
            View.MeasureSpec.makeMeasureSpec(1_000, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.AT_MOST)
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)

        assertEquals(5, view.childCount)
        assertTrue(view.getChildAt(0).top < view.getChildAt(2).top)
        assertTrue(view.getChildAt(1).top < view.getChildAt(3).top)
        assertTrue(view.getChildAt(3).left < view.getChildAt(4).left)
        assertEquals(0, view.getChildAt(0).left)
        assertEquals(164, view.getChildAt(0).width)
        assertEquals(1_000, view.getChildAt(4).right)
    }

    @Test
    fun activeShiftColorIsCoupledAcrossBothOuterSegments() {
        val ledStatusView = LedStatusView(RuntimeEnvironment.getApplication()).apply {
            layout = ModifierLedLayouts.TITAN_2_ELITE
        }
        val view = ledStatusView.ensureView()

        ledStatusView.update(
            StatusBarController.StatusSnapshot(
                capsLockEnabled = false,
                shiftPhysicallyPressed = false,
                shiftOneShot = true,
                ctrlLatchActive = false,
                ctrlPhysicallyPressed = false,
                ctrlOneShot = false,
                ctrlLatchFromNavMode = false,
                altLatchActive = false,
                altPhysicallyPressed = false,
                altOneShot = false,
                symPage = 0
            )
        )

        val activeBlue = Color.rgb(100, 150, 255)
        assertEquals(activeBlue, view.getChildAt(2).getTag(R.id.led_previous_color))
        assertEquals(activeBlue, view.getChildAt(4).getTag(R.id.led_previous_color))
    }
}
