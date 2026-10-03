package com.cachecleaner.app.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide live run progress, mirrored from the engine's onProgress
 * callback by [CacheRunnerService]. The UI collects [state] to render the
 * on-screen progress overlay ("12 of 65") during a run.
 */
object RunProgress {

    data class State(
        /** Apps fully processed so far. */
        val done: Int,
        /** Total apps in the run queue. */
        val total: Int,
        /** Human-readable label of the app currently being processed. */
        val currentLabel: String?
    )

    private val _state = MutableStateFlow<State?>(null)
    val state: StateFlow<State?> = _state.asStateFlow()

    fun update(done: Int, total: Int, currentLabel: String?) {
        _state.value = State(done, total, currentLabel)
    }

    fun clear() {
        _state.value = null
    }
}
