package com.basira.app.data.accessibility

import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Observes whether a screen reader (TalkBack touch exploration) is active.
 *
 * When it is, status changes are announced by TalkBack through a polite live region and the app's own
 * voice only speaks descriptions, so the same status is never announced twice.
 */
@Singleton
class AccessibilityStateProvider @Inject constructor(@param:ApplicationContext context: Context) {
    private val manager = context.getSystemService(AccessibilityManager::class.java)
    private val _screenReaderActive = MutableStateFlow(manager?.isTouchExplorationEnabled == true)

    /** `true` while TalkBack (or another touch-exploration screen reader) is on. */
    val screenReaderActive: StateFlow<Boolean> = _screenReaderActive.asStateFlow()

    init {
        manager?.addTouchExplorationStateChangeListener { enabled -> _screenReaderActive.value = enabled }
    }

    /**
     * Returns `true` when status announcements should be left to TalkBack: a screen reader is on and
     * the app UI (with its live region) is visible. In the background the app voice speaks status.
     */
    fun shouldDeferStatusToScreenReader(): Boolean =
        _screenReaderActive.value &&
            ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
}
