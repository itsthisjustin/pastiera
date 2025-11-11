package it.palsoftware.pastiera.inputmethod

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Gestisce la status bar visualizzata dall'IME, occupandosi della creazione della view
 * e dell'aggiornamento del testo/stile in base allo stato dei modificatori.
 */
class StatusBarController(
    private val context: Context
) {

    companion object {
        private const val NAV_MODE_LABEL = "NAV MODE"
        private val DEFAULT_BACKGROUND = Color.parseColor("#2196F3")
        private val NAV_MODE_BACKGROUND = Color.argb(100, 0, 0, 0)
    }

    data class StatusSnapshot(
        val capsLockEnabled: Boolean,
        val shiftPhysicallyPressed: Boolean,
        val shiftOneShot: Boolean,
        val ctrlLatchActive: Boolean,
        val ctrlPhysicallyPressed: Boolean,
        val ctrlOneShot: Boolean,
        val ctrlLatchFromNavMode: Boolean,
        val altLatchActive: Boolean,
        val altPhysicallyPressed: Boolean,
        val altOneShot: Boolean,
        val symKeyActive: Boolean
    ) {
        val navModeActive: Boolean
            get() = ctrlLatchActive && ctrlLatchFromNavMode
    }

    private var statusBarLayout: LinearLayout? = null
    private var statusTextView: TextView? = null
    private var emojiMapTextView: TextView? = null

    fun getLayout(): LinearLayout? = statusBarLayout

    fun getOrCreateLayout(emojiMapText: String): LinearLayout {
        if (statusBarLayout == null) {
            statusBarLayout = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setBackgroundColor(DEFAULT_BACKGROUND)
            }

            statusTextView = TextView(context).apply {
                textSize = 14f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(16, 12, 16, 12)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                text = "Pastiera attiva"
            }

            emojiMapTextView = TextView(context).apply {
                text = emojiMapText
                textSize = 12f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(16, 8, 16, 12)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                visibility = View.GONE
            }

            statusBarLayout?.apply {
                addView(statusTextView)
                addView(emojiMapTextView)
            }
        } else {
            setEmojiMapText(emojiMapText)
        }
        return statusBarLayout!!
    }

    fun setEmojiMapText(text: String) {
        emojiMapTextView?.text = text
    }

    fun update(snapshot: StatusSnapshot) {
        val layout = statusBarLayout ?: return
        val statusView = statusTextView ?: return
        val emojiView = emojiMapTextView ?: return

        if (snapshot.navModeActive) {
            layout.setBackgroundColor(NAV_MODE_BACKGROUND)
            statusView.setPadding(16, 6, 16, 6)
            statusView.textSize = 10f
            statusView.text = NAV_MODE_LABEL
            emojiView.visibility = View.GONE
            return
        }

        layout.setBackgroundColor(DEFAULT_BACKGROUND)
        statusView.setPadding(16, 12, 16, 12)
        statusView.textSize = 14f

        val statusParts = mutableListOf<String>()

        if (snapshot.capsLockEnabled) {
            statusParts.add("🔒")
        }
        if (snapshot.symKeyActive) {
            statusParts.add("🔣")
        }

        if (snapshot.capsLockEnabled) {
            statusParts.add("SHIFT")
        } else if (snapshot.shiftPhysicallyPressed || snapshot.shiftOneShot) {
            statusParts.add("shift")
        }

        if (!snapshot.navModeActive) {
            when {
                snapshot.ctrlLatchActive -> statusParts.add("CTRL")
                snapshot.ctrlPhysicallyPressed || snapshot.ctrlOneShot -> statusParts.add("ctrl")
            }
        }

        when {
            snapshot.altLatchActive -> statusParts.add("ALT")
            snapshot.altPhysicallyPressed || snapshot.altOneShot -> statusParts.add("alt")
        }

        val statusText = if (statusParts.isNotEmpty()) {
            "${statusParts.joinToString(" ")} Pastiera attiva"
        } else {
            "Pastiera attiva"
        }
        statusView.text = statusText

        emojiView.visibility = if (snapshot.symKeyActive) View.VISIBLE else View.GONE
    }
}


