package it.palsoftware.pastiera.inputmethod

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputConnection
import it.palsoftware.pastiera.SettingsManager
import it.palsoftware.pastiera.core.SymLayoutController

/**
 * Handles creation/show/hide of the IME status UI for both the full input view
 * and the candidate-only view exposed when the system hides the soft keyboard.
 */
class KeyboardVisibilityController(
    private val context: Context,
    private val candidatesBarController: CandidatesBarController,
    private val symLayoutController: SymLayoutController,
    private val isInputViewActive: () -> Boolean,
    private val hasActiveTextField: () -> Boolean,
    private val isNavModeLatched: () -> Boolean,
    private val currentInputConnection: () -> InputConnection?,
    private val isInputViewShown: () -> Boolean,
    private val renderedSurface: () -> RenderedSurface,
    private val requiresCandidatesSurfaceRecovery: () -> Boolean,
    private val setRequestedInputViewShown: (Boolean) -> Unit,
    private val attachInputView: (View) -> Unit,
    private val setCandidatesSurfaceActive: (Boolean) -> Unit,
    private val setCandidatesViewShown: (Boolean) -> Unit,
    private val synchronizeCandidatesContainerVisibility: () -> Unit,
    private val postToUi: (() -> Unit) -> Unit,
    private val postToUiDelayed: (delayMs: Long, action: () -> Unit) -> Unit,
    private val showInputWindow: (showInput: Boolean) -> Unit,
    private val requestHideInputView: () -> Unit,
    private val requestShowInputView: () -> Unit,
    private val refreshStatusBar: () -> Unit
) {

    private var statusBarPresentationMode: SettingsManager.StatusBarPresentationMode =
        SettingsManager.getStatusBarPresentationMode(context)
    private var surfaceTransitionGeneration = 0
    private var pendingSurfaceTransition: PendingSurfaceTransition? = null
    private var candidatesSurfaceRequested = false
    private var candidatesSurfaceRecoveryGeneration = 0
    private var pendingCandidatesSurfaceRecovery: Int? = null

    enum class RenderedSurface {
        HIDDEN,
        FULL_INPUT_VIEW,
        CANDIDATES_VIEW
    }

    private data class PendingSurfaceTransition(
        val generation: Int,
        val target: RenderedSurface,
        val requireActiveTextField: Boolean,
        var attemptsRemaining: Int = MAX_SURFACE_TRANSITION_ATTEMPTS,
        var retryScheduled: Boolean = false
    )

    fun onCreateInputView(): View {
        val layout = candidatesBarController.getInputView(symLayoutController.emojiMapTextForLayout())
        detachFromParent(layout)
        refreshStatusBar()
        return layout
    }

    fun onCreateCandidatesView(): View {
        val layout = candidatesBarController.getCandidatesView(symLayoutController.emojiMapTextForLayout())
        detachFromParent(layout)
        refreshStatusBar()
        return layout
    }

    fun onEvaluateInputViewShown(shouldShowInputView: Boolean): Boolean {
        SoftwareKeyboardAutoDetector.updateSystemInputViewDecision(shouldShowInputView)
        val resolvedShowInputView =
            SettingsManager.resolveEffectiveSoftwareKeyboardMode(context) ==
                SettingsManager.SoftwareKeyboardMode.FORCE_VIRTUAL
        refreshStatusBar()
        return resolvedShowInputView
    }

    fun ensureImeSurfaceVisible() {
        if (!isInputViewActive()) {
            return
        }
        if (currentInputConnection() == null) {
            return
        }
        if (isNavModeLatched()) {
            return
        }

        when (SettingsManager.resolveEffectiveSoftwareKeyboardMode(context)) {
            SettingsManager.SoftwareKeyboardMode.FORCE_VIRTUAL -> ensureFullInputViewVisible()
            SettingsManager.SoftwareKeyboardMode.FORCE_HARDWARE,
            SettingsManager.SoftwareKeyboardMode.AUTO ->
                ensureCandidatesSurfaceVisible()
        }
    }

    fun shouldShowSurfaceOnInputStart(autoShowKeyboardEnabled: Boolean): Boolean =
        SettingsManager.resolveEffectiveSoftwareKeyboardMode(context) !=
            SettingsManager.SoftwareKeyboardMode.FORCE_VIRTUAL || autoShowKeyboardEnabled

    private fun ensureFullInputViewVisible() {
        candidatesSurfaceRequested = false
        setCandidatesSurfaceActive(false)
        setCandidatesViewShown(false)

        val layout = candidatesBarController.getInputView(symLayoutController.emojiMapTextForLayout())
        refreshStatusBar()

        if (layout.parent == null) {
            attachInputView(layout)
        }

        if (!isInputViewShown()) {
            try {
                requestShowInputView()
            } catch (_: Exception) {
                // Avoid crashing if the system rejects the request
            }
        }
    }

    private fun ensureCandidatesSurfaceVisible() {
        setRequestedInputViewShown(false)
        setCandidatesSurfaceActive(true)

        if (!candidatesSurfaceRequested) {
            candidatesSurfaceRequested = true
            if (!requestCandidatesView()) return
            refreshStatusBar()
        }
        scheduleCandidatesSurfaceRecoveryIfNeeded()
    }

    private fun scheduleCandidatesSurfaceRecoveryIfNeeded() {
        if (
            !requiresCandidatesSurfaceRecovery() ||
            pendingCandidatesSurfaceRecovery != null
        ) {
            return
        }

        val recoveryGeneration = ++candidatesSurfaceRecoveryGeneration
        val transitionGeneration = surfaceTransitionGeneration
        pendingCandidatesSurfaceRecovery = recoveryGeneration
        postToUiDelayed(CANDIDATES_SURFACE_RECOVERY_DELAY_MS) {
            if (pendingCandidatesSurfaceRecovery != recoveryGeneration) {
                return@postToUiDelayed
            }
            pendingCandidatesSurfaceRecovery = null
            if (
                transitionGeneration != surfaceTransitionGeneration ||
                !requiresCandidatesSurfaceRecovery() ||
                !isInputViewActive() ||
                currentInputConnection() == null ||
                isNavModeLatched() ||
                SettingsManager.resolveEffectiveSoftwareKeyboardMode(context) ==
                    SettingsManager.SoftwareKeyboardMode.FORCE_VIRTUAL
            ) {
                return@postToUiDelayed
            }

            try {
                requestShowInputView()
            } catch (_: Exception) {
                // The editor may disappear while the delayed compatibility request is pending.
            }
        }
    }

    fun onImeWindowVisibilityChanged(shown: Boolean) {
        if (!shown && candidatesSurfaceRequested) {
            candidatesSurfaceRequested = false
            setCandidatesViewShown(false)
        }
        if (!shown) {
            pendingSurfaceTransition
                ?.takeIf { it.target == RenderedSurface.CANDIDATES_VIEW }
                ?.let { transition ->
                    postToUi {
                        startCandidatesTransition(transition.generation)
                    }
                }
        }
    }

    fun onCandidatesViewStarted() {
        candidatesSurfaceRequested = true
    }

    fun onCandidatesViewFinished() {
        candidatesSurfaceRequested = false
    }

    fun togglePastierinaMode() {
        statusBarPresentationMode = when (statusBarPresentationMode) {
            SettingsManager.StatusBarPresentationMode.PASTIERINA ->
                SettingsManager.StatusBarPresentationMode.FULL_STATUS_BAR
            SettingsManager.StatusBarPresentationMode.FULL_STATUS_BAR ->
                SettingsManager.StatusBarPresentationMode.PASTIERINA
        }
        SettingsManager.setStatusBarPresentationMode(context, statusBarPresentationMode)
        applyStatusBarPresentationMode()
    }

    private fun applyStatusBarPresentationMode() {
        val pastierinaModeActive =
            statusBarPresentationMode == SettingsManager.StatusBarPresentationMode.PASTIERINA
        candidatesBarController.setPastierinaModeActive(pastierinaModeActive)
        SettingsManager.setPastierinaModeActive(context, pastierinaModeActive)
        refreshStatusBar()
    }

    fun syncStatusBarPresentationModeFromSettings() {
        statusBarPresentationMode = SettingsManager.getStatusBarPresentationMode(context)
        applyStatusBarPresentationMode()
    }

    fun onKeyboardSurfaceChanged(
        ensureInputViewShown: Boolean,
        requireActiveTextField: Boolean = false
    ) {
        val generation = ++surfaceTransitionGeneration
        pendingSurfaceTransition = null
        cancelPendingCandidatesSurfaceRecovery()
        refreshStatusBar()
        if ((requireActiveTextField && !hasActiveTextField()) || currentInputConnection() == null) {
            return
        }

        pendingSurfaceTransition = PendingSurfaceTransition(
            generation = generation,
            target = if (ensureInputViewShown) {
                RenderedSurface.FULL_INPUT_VIEW
            } else {
                RenderedSurface.CANDIDATES_VIEW
            },
            requireActiveTextField = requireActiveTextField
        )
        if (ensureInputViewShown) {
            candidatesSurfaceRequested = false
            setCandidatesSurfaceActive(false)
            setCandidatesViewShown(false)
            reconcilePendingSurfaceTransition(generation)
        } else {
            setCandidatesSurfaceActive(true)
            candidatesSurfaceRequested = false
            setCandidatesViewShown(false)
            if (isInputViewShown()) {
                try {
                    requestHideInputView()
                } catch (_: Exception) {
                    startCandidatesTransition(generation)
                }
            } else {
                startCandidatesTransition(generation)
            }
        }
    }

    fun cancelPendingSurfaceTransition() {
        surfaceTransitionGeneration += 1
        pendingSurfaceTransition = null
        cancelPendingCandidatesSurfaceRecovery()
    }

    private fun cancelPendingCandidatesSurfaceRecovery() {
        candidatesSurfaceRecoveryGeneration += 1
        pendingCandidatesSurfaceRecovery = null
    }

    private fun reconcilePendingSurfaceTransition(generation: Int) {
        val transition = pendingSurfaceTransition
            ?.takeIf { it.generation == generation }
            ?: return
        transition.retryScheduled = false

        if (
            currentInputConnection() == null ||
            (transition.requireActiveTextField && !hasActiveTextField())
        ) {
            abandonSurfaceTransition()
            return
        }
        if (renderedSurface() == transition.target) {
            setRequestedInputViewShown(transition.target == RenderedSurface.FULL_INPUT_VIEW)
            pendingSurfaceTransition = null
            return
        }
        if (transition.attemptsRemaining <= 0) {
            abandonSurfaceTransition()
            return
        }

        transition.attemptsRemaining -= 1
        try {
            showInputWindow(transition.target == RenderedSurface.FULL_INPUT_VIEW)
        } catch (_: Exception) {
            // A configuration rebind can temporarily reject this request. The bounded
            // reconciliation below retries only this explicit surface transition.
        }
        scheduleSurfaceReconciliation(transition)
    }

    private fun startCandidatesTransition(generation: Int) {
        val transition = pendingSurfaceTransition
            ?.takeIf {
                it.generation == generation && it.target == RenderedSurface.CANDIDATES_VIEW
            }
            ?: return
        if (
            currentInputConnection() == null ||
            (transition.requireActiveTextField && !hasActiveTextField())
        ) {
            abandonSurfaceTransition()
            return
        }

        setRequestedInputViewShown(false)
        setCandidatesSurfaceActive(true)
        candidatesSurfaceRequested = true
        if (!requestCandidatesView()) {
            scheduleSurfaceReconciliation(transition)
            return
        }
        refreshStatusBar()
        postToUi {
            if (generation != surfaceTransitionGeneration) return@postToUi
            synchronizeCandidatesContainerVisibility()
            refreshStatusBar()
        }
        // setCandidatesViewShown(true) is the primary candidates-only window request. Verify it
        // after the framework has had a chance to present the window before using showWindow(false)
        // as a bounded recovery path.
        scheduleSurfaceReconciliation(transition)
    }

    private fun requestCandidatesView(): Boolean =
        try {
            setCandidatesViewShown(true)
            true
        } catch (_: Exception) {
            candidatesSurfaceRequested = false
            false
        }

    private fun scheduleSurfaceReconciliation(transition: PendingSurfaceTransition) {
        if (transition.retryScheduled) return
        transition.retryScheduled = true
        postToUiDelayed(SURFACE_TRANSITION_RETRY_DELAY_MS) {
            reconcilePendingSurfaceTransition(transition.generation)
        }
    }

    fun isCandidatesOnlySurface(): Boolean = renderedSurface() == RenderedSurface.CANDIDATES_VIEW

    fun isExpectedSurfaceRequestedOrShown(): Boolean =
        if (
            SettingsManager.resolveEffectiveSoftwareKeyboardMode(context) ==
            SettingsManager.SoftwareKeyboardMode.FORCE_VIRTUAL
        ) {
            isInputViewShown()
        } else {
            candidatesSurfaceRequested && !requiresCandidatesSurfaceRecovery()
        }

    private fun abandonSurfaceTransition() {
        val actualSurface = renderedSurface()
        setRequestedInputViewShown(actualSurface == RenderedSurface.FULL_INPUT_VIEW)
        setCandidatesSurfaceActive(actualSurface == RenderedSurface.CANDIDATES_VIEW)
        setCandidatesViewShown(actualSurface == RenderedSurface.CANDIDATES_VIEW)
        candidatesSurfaceRequested = actualSurface == RenderedSurface.CANDIDATES_VIEW
        pendingSurfaceTransition = null
        refreshStatusBar()
    }

    private fun detachFromParent(view: View) {
        (view.parent as? ViewGroup)?.removeView(view)
    }

    private companion object {
        const val MAX_SURFACE_TRANSITION_ATTEMPTS = 6
        const val SURFACE_TRANSITION_RETRY_DELAY_MS = 250L
        const val CANDIDATES_SURFACE_RECOVERY_DELAY_MS = 250L
    }
}
