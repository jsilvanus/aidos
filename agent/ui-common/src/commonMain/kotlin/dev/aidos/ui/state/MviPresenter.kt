package dev.aidos.ui.state

import kotlinx.coroutines.flow.StateFlow

/**
 * Base MVI (Model-View-Intent) Presenter contract (RFC-0106, RFC-0111).
 *
 * Composables strictly observe [state] as immutable read-only models.
 * User interactions and lifecycle signals are passed downstream via [send].
 */
interface MviPresenter<State, Intent> {
    val state: StateFlow<State>
    fun send(intent: Intent)
}
