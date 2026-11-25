package nl.niek.doorbellcompanion

import android.os.SystemClock
import android.util.Log

/** Diagnostic timestamps only; never logs payloads or connection credentials. */
class CallTiming private constructor(private val started: Long, private val id: String) {
    fun mark(stage: String, detail: String = "") {
        val suffix = if (detail.isEmpty()) "" else " $detail"
        Log.i("DoorbellTiming", "id=$id stage=$stage elapsed_ms=${SystemClock.elapsedRealtime() - started} wall_ms=${System.currentTimeMillis()}$suffix")
    }

    companion object {
        @Volatile var current: CallTiming? = null
            private set

        fun start(workerTime: String?, explicitEventMs: String? = null) {
            val elapsed = SystemClock.elapsedRealtime()
            val trace = CallTiming(elapsed, elapsed.toString())
            current = trace
            val eventTime = explicitEventMs?.toLongOrNull() ?: 0L
            val worker = workerTime?.toLongOrNull() ?: 0L
            trace.mark("push_received", "event_ms=$eventTime worker_ms=$worker")
        }
    }
}
