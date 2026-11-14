package dev.aidos.ui.state

/**
 * Standard LCE (Loading, Content, Error) state representation.
 */
sealed interface ViewState<out T> {
    data object Loading : ViewState<Nothing>

    data class Content<T>(
        val data: T,
        val isRefreshing: Boolean = false,
    ) : ViewState<T>

    data class Error(
        val message: String,
        val cause: Throwable? = null,
    ) : ViewState<Nothing>
}
