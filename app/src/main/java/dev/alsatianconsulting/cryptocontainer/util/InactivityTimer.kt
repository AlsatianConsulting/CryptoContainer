package dev.alsatianconsulting.cryptocontainer.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class InactivityTimer(
    private val scope: CoroutineScope,
    private val timeoutMs: Long = 10 * 60 * 1000L,
    private val onTimeout: suspend () -> Unit
) {
    private var job: Job? = null

    fun ping() {
        job?.cancel()
        job = scope.launch {
            delay(timeoutMs)
            onTimeout()
        }
    }

    fun cancel() {
        job?.cancel()
    }
}
