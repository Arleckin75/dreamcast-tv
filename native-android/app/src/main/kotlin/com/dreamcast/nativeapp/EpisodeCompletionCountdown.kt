package com.dreamcast.nativeapp

import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.dreamcast.app.showPlayerMenu

/** Shared five-second completion flow for hardware and software Media3 players. */
internal class EpisodeCompletionCountdown(
    private val activity: ComponentActivity,
    private val nextTitle: String,
    private val onNext: () -> Unit,
) : DefaultLifecycleObserver {
    private val handler = Handler(Looper.getMainLooper())
    private var dialog: AlertDialog? = null
    private var deadline = 0L
    private var started = false
    private var running = false
    private var closed = false
    private var advanced = false
    private val tick =
        object : Runnable {
            override fun run() {
                if (!running || closed) return
                val remaining =
                    ((deadline - SystemClock.uptimeMillis() + 999) / 1000).coerceAtLeast(0)
                if (remaining == 0L) advance()
                else {
                    title("Переход через $remaining с")
                    handler.postDelayed(this, 1000)
                }
            }
        }

    fun show() {
        if (activity.isFinishing || activity.isDestroyed || closed || dialog != null) return
        dialog =
            showPlayerMenu(
                activity,
                "Серия закончилась\n$nextTitle\nПереход через 5 с",
                listOf("Следующая серия"),
                onDismiss = ::close,
            ) {
                advance()
            }
        activity.lifecycle.addObserver(this)
    }

    override fun onResume(owner: LifecycleOwner) {
        if (started || closed) return
        started = true
        running = true
        deadline = SystemClock.uptimeMillis() + 5000
        handler.postDelayed(tick, 1000)
    }

    override fun onPause(owner: LifecycleOwner) {
        if (!running) return
        running = false
        handler.removeCallbacks(tick)
        title("Автопереход отменён")
    }

    private fun title(status: String) {
        dialog?.window?.decorView?.findViewWithTag<TextView>("player-menu-title")?.text =
            "Серия закончилась\n$nextTitle\n$status"
    }

    private fun advance() {
        if (advanced) return
        advanced = true
        dismiss()
        onNext()
    }

    private fun close() {
        closed = true
        running = false
        handler.removeCallbacks(tick)
        activity.lifecycle.removeObserver(this)
    }

    fun dismiss() {
        close()
        dialog?.dismiss()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        dismiss()
    }
}
