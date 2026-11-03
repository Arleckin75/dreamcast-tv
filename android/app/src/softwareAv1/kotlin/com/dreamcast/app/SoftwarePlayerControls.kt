package com.dreamcast.app

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView

/** Native overlay: video stays on VLC's existing SurfaceView. */
internal class SoftwarePlayerControls(
    private val context: Context,
    private val root: FrameLayout,
    onToggle: () -> Unit,
    onSeek: (Long) -> Unit,
    onRetry: () -> Unit,
    onExit: () -> Unit,
    private val onInteraction: () -> Unit,
) {
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    private fun label(size: Float) = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = size
    }
    private val panel = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(28), dp(16), dp(28), dp(20))
        setBackgroundColor(0xE6202020.toInt())
    }
    val title = label(20f).apply {
        maxLines = 2
        ellipsize = android.text.TextUtils.TruncateAt.END
    }
    private val time = label(16f)
    private val timeline = SeekBar(context).apply { max = 1000; isFocusable = false }
    private val actions = LinearLayout(context).apply { gravity = Gravity.CENTER }
    private fun button(text: String, action: () -> Unit) = Button(context).apply {
        this.text = text
        isAllCaps = false
        setTextColor(Color.WHITE)
        setPadding(dp(16), dp(8), dp(16), dp(8))
        setOnClickListener { onInteraction(); action() }
        setOnFocusChangeListener { view, focused ->
            view.setBackgroundColor(if (focused) 0xFF3867C8.toInt() else 0xFF383838.toInt())
            if (focused) onInteraction()
        }
        setBackgroundColor(0xFF383838.toInt())
        actions.addView(this, LinearLayout.LayoutParams(-2, dp(52)).apply {
            marginStart = dp(6); marginEnd = dp(6)
        })
    }
    private val rewind = button("−10 сек") { onSeek(-10_000L) }
    private val toggle = button("Пауза", onToggle)
    private val forward = button("+10 сек") { onSeek(10_000L) }
    private val retry = button("Повторить", onRetry)
    private val exit = button("Назад", onExit)
    private val loading = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(24), dp(16), dp(24), dp(16))
        setBackgroundColor(0xCC202020.toInt())
    }
    private val loadingText = label(18f)
    private val error = label(18f)
    private var duration = 0L
    val visible: Boolean get() = panel.visibility == View.VISIBLE
    val hasActionFocus: Boolean get() = actions.hasFocus()

    init {
        panel.addView(title)
        panel.addView(error)
        panel.addView(time)
        panel.addView(timeline, LinearLayout.LayoutParams(-1, dp(36)))
        panel.addView(actions)
        root.addView(panel, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        loading.addView(ProgressBar(context), LinearLayout.LayoutParams(dp(44), dp(44)))
        loading.addView(loadingText)
        root.addView(loading, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        timeline.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                if (fromUser && duration > 0) {
                    onInteraction()
                    onSeek(duration * value / 1000 - position)
                }
            }
            override fun onStartTrackingTouch(bar: SeekBar?) { onInteraction() }
            override fun onStopTrackingTouch(bar: SeekBar?) { onInteraction() }
        })
        showError(null)
        root.isFocusableInTouchMode = true
        root.requestFocus()
    }

    private var position = 0L
    fun update(positionMs: Long, durationMs: Long, playing: Boolean, seekable: Boolean) {
        position = positionMs.coerceAtLeast(0L)
        duration = durationMs.coerceAtLeast(0L)
        time.text = "${formatTime(position)} / ${if (duration > 0) formatTime(duration) else "--:--"}"
        timeline.progress = if (duration > 0) (position.coerceAtMost(duration) * 1000 / duration).toInt() else 0
        timeline.isEnabled = seekable && duration > 0
        rewind.isEnabled = seekable
        forward.isEnabled = seekable
        toggle.text = if (playing) "Пауза" else "Продолжить"
    }

    fun show(focus: Boolean = false) {
        panel.visibility = View.VISIBLE
        if (focus) (if (retry.visibility == View.VISIBLE) retry else toggle).requestFocus()
    }

    fun hide() { root.requestFocus(); panel.visibility = View.GONE }
    fun showLoading(text: String?) {
        loading.visibility = if (text == null) View.GONE else View.VISIBLE
        loadingText.text = text.orEmpty()
    }

    fun showError(text: String?) {
        error.text = text.orEmpty()
        error.visibility = if (text == null) View.GONE else View.VISIBLE
        retry.visibility = if (text == null) View.GONE else View.VISIBLE
        listOf(rewind, toggle, forward).forEach { it.visibility = if (text == null) View.VISIBLE else View.GONE }
        if (text != null) { showLoading(null); show(true) }
    }

    companion object {
        private fun formatTime(ms: Long): String {
            val seconds = ms / 1000
            return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
            else "%02d:%02d".format(seconds / 60, seconds % 60)
        }
    }
}
