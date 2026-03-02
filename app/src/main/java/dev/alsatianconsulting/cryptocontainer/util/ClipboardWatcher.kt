package dev.alsatianconsulting.cryptocontainer.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ClipboardWatcher(
    private val context: Context,
    private val scope: CoroutineScope,
    private val clearDelayMs: Long = 30_000L
) {
    private var clearJob: Job? = null

    fun set(text: CharSequence, label: String = "crypto") {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText(label, text))
        scheduleClear()
    }

    private fun scheduleClear() {
        clearJob?.cancel()
        clearJob = scope.launch {
            delay(clearDelayMs)
            val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            if (manager.hasPrimaryClip()) {
                manager.setPrimaryClip(ClipData.newPlainText("", ""))
            }
        }
    }

    fun cancel() { clearJob?.cancel() }
}
