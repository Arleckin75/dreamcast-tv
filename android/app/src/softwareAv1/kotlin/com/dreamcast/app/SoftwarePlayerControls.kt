package com.dreamcast.app

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView

/** Hardware player layout rendered above VLC's existing SurfaceView. */
internal class SoftwarePlayerControls(
    private val context: Context,
    private val root: FrameLayout,
    private val accent: Int,
    private val onAccent: Int,
    onToggle: () -> Unit,
    private val onSeek: (Long) -> Unit,
    onRetry: () -> Unit,
    onExit: () -> Unit,
    onNext: () -> Unit,
    nextTitle: String?,
    private val onQuality: (Int) -> Unit,
    private val onInteraction: () -> Unit,
) {
    private val compact = context.resources.configuration.let { it.screenWidthDp < 640 || it.screenHeightDp < 420 }
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    private fun label(size: Float) = TextView(context).apply {
        setTextColor(Color.WHITE); textSize = size; maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
    }
    private fun pill(color: Int = 0x6B000000) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(999).toFloat(); setStroke(dp(1), 0x21FFFFFF)
    }
    private fun buttonSurface(focused: Boolean = false) = GradientDrawable().apply {
        setColor(if (focused) withAlpha(accent, 20) else Color.TRANSPARENT)
        cornerRadius = dp(999).toFloat()
        setStroke(dp(3), if (focused) accent else Color.TRANSPARENT)
    }
    private val panel = FrameLayout(context).apply {
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0x94000000.toInt(), 0x14000000, 0xA3000000.toInt()))
        setPadding(dp(if (compact) 12 else 28), dp(if (compact) 8 else 12),
            dp(if (compact) 12 else 28), dp(if (compact) 8 else 14))
    }
    val title = label(if (compact) 15f else 17f).apply { setTypeface(null, Typeface.BOLD) }
    val subtitle = label(13f).apply { setTextColor(0xB3FFFFFF.toInt()); visibility = if (compact) View.GONE else View.VISIBLE }
    private val top = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
    private val actions = LinearLayout(context).apply { gravity = Gravity.CENTER }
    private fun transport(kind: String, size: Int, description: String, action: () -> Unit) =
        TransportButton(context, kind, accent).apply {
            id = View.generateViewId(); contentDescription = description
            setOnClickListener { onInteraction(); action() }
            setOnFocusChangeListener { _, focused -> invalidate(); if (focused) onInteraction() }
            layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
        }
    private val back = transport("back", if (compact) 48 else 56, "Назад", onExit)
    private val toggle = transport("pause", if (compact) 72 else 88, "Пауза", onToggle)
    private val quality = Button(context).apply {
        id = View.generateViewId(); isAllCaps = false; setTextColor(accent); textSize = 14f
        setTypeface(null, Typeface.BOLD); minWidth = 0; minHeight = 0
        setPadding(dp(16), dp(8), dp(16), dp(8)); background = buttonSurface()
        contentDescription = "Качество потока"
        setOnClickListener { showQualityDialog() }
        setOnFocusChangeListener { _, focused ->
            background = buttonSurface(focused)
            if (focused) onInteraction()
        }
    }
    private val next = Button(context).apply {
        id = View.generateViewId(); text = "Следующая серия"; isAllCaps = false
        setTextColor(accent); textSize = if (compact) 12f else 14f
        minWidth = 0; minHeight = 0; setPadding(dp(14), dp(8), dp(14), dp(8))
        background = buttonSurface(); visibility = if (nextTitle == null) View.GONE else View.VISIBLE
        contentDescription = "Следующая серия: ${nextTitle.orEmpty()}"
        setOnClickListener { onInteraction(); onNext() }
        setOnFocusChangeListener { _, focused ->
            background = buttonSurface(focused)
            if (focused) onInteraction()
        }
    }
    private fun timeLabel() = label(14f).apply { gravity = Gravity.CENTER; setTypeface(null, Typeface.BOLD) }
    private val positionLabel = timeLabel()
    private val durationLabel = timeLabel()
    private val timeline = SeekBar(context).apply {
        id = View.generateViewId(); max = 1000
        progressTintList = ColorStateList.valueOf(accent); thumbTintList = ColorStateList.valueOf(accent)
        progressBackgroundTintList = ColorStateList.valueOf(0x42FFFFFF)
        setOnFocusChangeListener { _, focused ->
            background = if (focused) pill(withAlpha(accent, 46)) else null
            if (focused) onInteraction()
        }
        setOnKeyListener { _, key, event ->
            if (key != KeyEvent.KEYCODE_DPAD_LEFT && key != KeyEvent.KEYCODE_DPAD_RIGHT) false
            else {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    onInteraction(); onSeek(if (key == KeyEvent.KEYCODE_DPAD_LEFT) -10_000L else 10_000L)
                }
                true
            }
        }
    }
    private val bottom = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
    private val loading = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
        setPadding(dp(24), dp(16), dp(24), dp(16)); background = pill(0xCC202020.toInt())
    }
    private val loadingText = label(16f)
    private val errorPanel = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
        setPadding(dp(24), dp(20), dp(24), dp(20)); background = pill(0xE6202020.toInt())
    }
    private val error = label(18f).apply { maxLines = 3; gravity = Gravity.CENTER }
    private fun errorButton(text: String, action: () -> Unit) = Button(context).apply {
        id = View.generateViewId(); this.text = text; isAllCaps = false; setTextColor(accent)
        background = buttonSurface()
        setOnClickListener { onInteraction(); action() }
        setOnFocusChangeListener { _, focused ->
            background = buttonSurface(focused); if (focused) onInteraction()
        }
    }
    private val retry = errorButton("Повторить", onRetry)
    private val errorExit = errorButton("Назад", onExit)
    private val feedback = label(16f).apply {
        gravity = Gravity.CENTER; setTextColor(onAccent); setTypeface(null, Typeface.BOLD)
        setPadding(dp(18), dp(11), dp(18), dp(11)); background = pill(withAlpha(accent, 224)); visibility = View.GONE
    }
    private val hideFeedback = Runnable { feedback.visibility = View.GONE }
    private var qualityDialog: AlertDialog? = null
    private var activeQuality = 720
    private var duration = 0L
    private var position = 0L
    private var dragging = false
    private var errorVisible = false
    val visible: Boolean get() = panel.visibility == View.VISIBLE
    val hasActionFocus: Boolean get() = panel.hasFocus()
    val modalVisible: Boolean get() = qualityDialog?.isShowing == true

    init {
        top.addView(back)
        val titles = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(if (compact) 48 else 56)
            setPadding(dp(if (compact) 14 else 18), dp(7), dp(18), dp(7)); background = pill()
            addView(title); addView(subtitle)
        }
        top.addView(titles, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12); marginEnd = dp(12) })
        top.addView(quality, LinearLayout.LayoutParams(-2, dp(if (compact) 48 else 56)))
        top.addView(next, LinearLayout.LayoutParams(-2, dp(if (compact) 48 else 56)).apply { marginStart = dp(12) })
        panel.addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        actions.addView(toggle)
        toggle.setOnKeyListener { _, key, event ->
            if (key != KeyEvent.KEYCODE_DPAD_LEFT && key != KeyEvent.KEYCODE_DPAD_RIGHT) false
            else {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    onInteraction(); onSeek(if (key == KeyEvent.KEYCODE_DPAD_LEFT) -10_000L else 10_000L)
                }
                true
            }
        }
        panel.addView(actions, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        bottom.addView(positionLabel, LinearLayout.LayoutParams(dp(64), dp(48)))
        bottom.addView(timeline, LinearLayout.LayoutParams(0, dp(48), 1f))
        bottom.addView(durationLabel, LinearLayout.LayoutParams(dp(64), dp(48)))
        panel.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        errorPanel.addView(error, LinearLayout.LayoutParams(-1, -2))
        errorPanel.addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER
            addView(retry, LinearLayout.LayoutParams(dp(150), dp(48)).apply { marginEnd = dp(12) })
            addView(errorExit, LinearLayout.LayoutParams(dp(120), dp(48)))
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })
        panel.addView(errorPanel, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER).apply {
            leftMargin = dp(32); rightMargin = dp(32)
        })
        root.addView(panel, FrameLayout.LayoutParams(-1, -1))
        loading.addView(ProgressBar(context).apply { indeterminateTintList = ColorStateList.valueOf(accent) },
            LinearLayout.LayoutParams(dp(44), dp(44)))
        loading.addView(loadingText)
        root.addView(loading, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        root.addView(feedback, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_VERTICAL or Gravity.LEFT).apply { leftMargin = dp(42) })
        timeline.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                if (fromUser && duration > 0) positionLabel.text = formatTime(duration * value / 1000)
            }
            override fun onStartTrackingTouch(bar: SeekBar?) { dragging = true; onInteraction() }
            override fun onStopTrackingTouch(bar: SeekBar?) {
                val target = duration * timeline.progress / 1000
                dragging = false; onInteraction()
                if (duration > 0) onSeek(target - position)
            }
        })
        toggle.nextFocusUpId = quality.id; toggle.nextFocusDownId = timeline.id
        timeline.nextFocusUpId = toggle.id; quality.nextFocusDownId = toggle.id
        quality.nextFocusLeftId = back.id; back.nextFocusRightId = quality.id; back.nextFocusDownId = toggle.id
        if (nextTitle != null) {
            quality.nextFocusRightId = next.id; next.nextFocusLeftId = quality.id
            next.nextFocusDownId = toggle.id
        }
        showError(null); root.isFocusableInTouchMode = true; root.requestFocus()
    }

    fun showSeekFeedback(delta: Long) {
        feedback.text = if (delta < 0) "−10 секунд" else "+10 секунд"
        feedback.layoutParams = (feedback.layoutParams as FrameLayout.LayoutParams).apply {
            gravity = Gravity.CENTER_VERTICAL or (if (delta < 0) Gravity.LEFT else Gravity.RIGHT)
            leftMargin = dp(42); rightMargin = dp(42)
        }
        feedback.visibility = View.VISIBLE; feedback.removeCallbacks(hideFeedback); feedback.postDelayed(hideFeedback, 800L)
    }

    fun setQuality(height: Int) { activeQuality = height; quality.text = "HQ  ${height}p" }

    private fun showQualityDialog() {
        if (modalVisible) return
        onInteraction()
        val heights = intArrayOf(480, 720, 1080)
        qualityDialog = AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Качество потока")
            .setSingleChoiceItems(heights.map { "HLS • ${it}p" }.toTypedArray(), heights.indexOf(activeQuality)) { dialog, index ->
                dialog.dismiss(); if (heights[index] != activeQuality) onQuality(heights[index])
            }
            .setNegativeButton("Назад", null).create().apply {
                setOnDismissListener { qualityDialog = null; quality.requestFocus(); onInteraction() }
                show()
                getButton(AlertDialog.BUTTON_NEGATIVE).apply {
                    setTextColor(accent); background = buttonSurface()
                    setOnFocusChangeListener { _, focused -> background = buttonSurface(focused) }
                }
            }
    }

    fun update(positionMs: Long, durationMs: Long, playing: Boolean, seekable: Boolean) {
        position = positionMs.coerceAtLeast(0L); duration = durationMs.coerceAtLeast(0L)
        if (!dragging) {
            positionLabel.text = formatTime(position)
            timeline.progress = if (duration > 0) (position.coerceAtMost(duration) * 1000 / duration).toInt() else 0
        }
        durationLabel.text = if (duration > 0) formatTime(duration) else "--:--"
        timeline.isEnabled = seekable && duration > 0
        toggle.kind = if (playing) "pause" else "play"
        toggle.contentDescription = if (playing) "Пауза" else "Воспроизвести"; toggle.invalidate()
    }

    fun show(focus: Boolean = false) {
        if (!visible) { panel.visibility = View.VISIBLE; panel.alpha = 0f; panel.animate().alpha(1f).setDuration(220L).start() }
        if (focus) (if (errorVisible) retry else toggle).requestFocus()
    }
    fun hide() { root.requestFocus(); panel.animate().cancel(); panel.visibility = View.GONE }
    fun showLoading(text: String?) {
        loading.visibility = if (text == null) View.GONE else View.VISIBLE; loadingText.text = text.orEmpty()
        actions.visibility = if (text != null || errorVisible) View.GONE else View.VISIBLE
    }
    fun showError(text: String?) {
        errorVisible = text != null; error.text = text.orEmpty()
        errorPanel.visibility = if (errorVisible) View.VISIBLE else View.GONE
        bottom.visibility = if (errorVisible) View.GONE else View.VISIBLE
        actions.visibility = if (errorVisible) View.GONE else View.VISIBLE
        if (errorVisible) { showLoading(null); show(true) }
    }
    fun dispose() {
        qualityDialog?.setOnDismissListener(null); qualityDialog?.dismiss()
        feedback.removeCallbacks(hideFeedback); panel.animate().cancel()
    }
    companion object {
        private fun withAlpha(color: Int, alpha: Int) = (color and 0x00FFFFFF) or (alpha shl 24)
        private fun formatTime(ms: Long): String {
            val seconds = ms / 1000
            return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
            else "%02d:%02d".format(seconds / 60, seconds % 60)
        }
    }
}

/** Vector icons keep controls independent of fonts and image assets. */
private class TransportButton(context: Context, var kind: String, private val accent: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    init { isFocusable = true; isClickable = true }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = minOf(width, height).toFloat()
        canvas.save(); canvas.translate(width / 2f, height / 2f)
        paint.style = Paint.Style.FILL
        paint.color = if (hasFocus()) (accent and 0x00FFFFFF) or (20 shl 24) else Color.TRANSPARENT
        canvas.drawCircle(0f, 0f, size / 2f - 2f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f * resources.displayMetrics.density
        paint.color = if (hasFocus()) accent else Color.TRANSPARENT
        canvas.drawCircle(0f, 0f, size / 2f - paint.strokeWidth, paint)
        canvas.scale(size / 72f, size / 72f)
        paint.color = accent
        paint.strokeWidth = 3f; paint.strokeCap = Paint.Cap.ROUND; paint.style = Paint.Style.FILL
        when (kind) {
            "pause" -> { canvas.drawRect(-11f, -17f, -4f, 17f, paint); canvas.drawRect(4f, -17f, 11f, 17f, paint) }
            "play" -> canvas.drawPath(Path().apply { moveTo(-9f, -18f); lineTo(18f, 0f); lineTo(-9f, 18f); close() }, paint)
            "back" -> {
                paint.style = Paint.Style.STROKE; canvas.drawLine(14f, 0f, -14f, 0f, paint)
                canvas.drawPath(Path().apply { moveTo(-2f, -12f); lineTo(-14f, 0f); lineTo(-2f, 12f) }, paint)
            }
        }
        canvas.restore()
    }
    override fun setEnabled(enabled: Boolean) { super.setEnabled(enabled); alpha = if (enabled) 1f else 0.4f }
}
