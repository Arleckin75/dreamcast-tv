package com.dreamcast.app

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.*
import android.widget.*

/** One remote-first watch overlay shared by the native Media3 and LibVLC players. */
internal class SoftwarePlayerControls(
    private val context: Context,
    private val root: FrameLayout,
    accent: Int,
    onAccent: Int,
    onToggle: () -> Unit,
    private val onSeek: (Long) -> Unit,
    onRetry: () -> Unit,
    onExit: () -> Unit,
    onNext: () -> Unit,
    nextTitle: String?,
    private val onQuality: (Int) -> Unit,
    private val onInteraction: () -> Unit,
    private val settingsItems: () -> List<Pair<String, () -> Unit>> = { emptyList() },
    onCaptions: (() -> Unit)? = null,
) {
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    private fun label(size: Float) =
        TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = size
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

    private val panel =
        FrameLayout(context).apply {
            background =
                GradientDrawable(
                        GradientDrawable.Orientation.TOP_BOTTOM,
                        intArrayOf(0x70000000, 0x08000000, 0xE6000000.toInt(), Color.BLACK),
                    )
                    .apply { gradientType = GradientDrawable.LINEAR_GRADIENT }
            setPadding(dp(32), dp(20), dp(32), dp(20))
        }
    val title =
        label(23f).apply {
            setTypeface(null, Typeface.BOLD)
            maxLines = 2
        }
    val subtitle = label(14f).apply { setTextColor(0xBFFFFFFF.toInt()) }
    private val bottom = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val actions = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }

    private fun transport(kind: String, description: String, action: () -> Unit) =
        TransportButton(context, kind).apply {
            id = View.generateViewId()
            contentDescription = description
            setOnClickListener {
                onInteraction()
                action()
            }
            setOnFocusChangeListener { _, focused ->
                invalidate()
                if (focused) onInteraction()
            }
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(52)).apply { marginEnd = dp(10) }
        }

    private val back = transport("back", "Назад", onExit)
    private val toggle = transport("pause", "Пауза", onToggle)
    private val rewind = transport("rewind", "Назад на 10 секунд") { onSeek(-10_000) }
    private val forward = transport("forward", "Вперёд на 10 секунд") { onSeek(10_000) }
    private val next =
        transport("next", "Следующая серия", onNext).apply {
            visibility = if (nextTitle == null) View.GONE else View.VISIBLE
        }
    private val settings = transport("settings", "Настройки воспроизведения") { showSettings() }
    private val captions = onCaptions?.let { transport("captions", "Звук и субтитры", it) }
    private val positionLabel = label(13f)
    private val durationLabel = label(13f).apply { setTextColor(0xBFFFFFFF.toInt()) }
    private val timeline =
        SeekBar(context).apply {
            id = View.generateViewId()
            contentDescription = "Позиция воспроизведения"
            max = 1000
            progressTintList = ColorStateList.valueOf(0xFFFF0033.toInt())
            thumbTintList = ColorStateList.valueOf(0xFFFF0033.toInt())
            progressBackgroundTintList = ColorStateList.valueOf(0x66FFFFFF)
            secondaryProgressTintList = ColorStateList.valueOf(0x99FFFFFF.toInt())
            val layers =
                android.graphics.drawable.LayerDrawable(
                    arrayOf(
                        rounded(0x66FFFFFF, dp(2)),
                        android.graphics.drawable.ClipDrawable(
                            rounded(0x99FFFFFF.toInt(), dp(2)),
                            Gravity.START,
                            android.graphics.drawable.ClipDrawable.HORIZONTAL,
                        ),
                        android.graphics.drawable.ClipDrawable(
                            rounded(0xFFFF0033.toInt(), dp(2)),
                            Gravity.START,
                            android.graphics.drawable.ClipDrawable.HORIZONTAL,
                        ),
                    )
                )
            listOf(android.R.id.background, android.R.id.secondaryProgress, android.R.id.progress)
                .forEachIndexed { index, id ->
                    layers.setId(index, id)
                    layers.setLayerHeight(index, dp(3))
                    layers.setLayerGravity(index, Gravity.CENTER_VERTICAL)
                }
            progressDrawable = layers
            thumb =
                GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0xFFFF0033.toInt())
                    setSize(dp(12), dp(12))
                }
            splitTrack = false
            setPadding(dp(4), 0, dp(4), 0)
            setOnFocusChangeListener { _, focused ->
                alpha = if (focused) 1f else 0.85f
                if (focused) onInteraction()
            }
            setOnKeyListener { _, key, event ->
                if (key != KeyEvent.KEYCODE_DPAD_LEFT && key != KeyEvent.KEYCODE_DPAD_RIGHT) false
                else {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        onInteraction()
                        onSeek(if (key == KeyEvent.KEYCODE_DPAD_LEFT) -10_000L else 10_000L)
                    }
                    true
                }
            }
        }
    private val loading =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(20), dp(24), dp(20))
            background = rounded(0xDB181818.toInt(), dp(16))
        }
    private val loadingText = label(15f)
    private val errorPanel =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(20), dp(24), dp(20))
            background = rounded(0xF0181818.toInt(), dp(16))
        }
    private val error =
        label(18f).apply {
            maxLines = 3
            gravity = Gravity.CENTER
        }

    private fun errorButton(text: String, action: () -> Unit) =
        Button(context).apply {
            id = View.generateViewId()
            this.text = text
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = rounded(0xFF303030.toInt(), dp(24))
            setOnClickListener {
                onInteraction()
                action()
            }
            setOnFocusChangeListener { _, focused ->
                setTextColor(if (focused) Color.BLACK else Color.WHITE)
                background = rounded(if (focused) Color.WHITE else 0xFF303030.toInt(), dp(24))
                if (focused) onInteraction()
            }
        }

    private val retry = errorButton("Повторить", onRetry)
    private val errorExit = errorButton("Назад", onExit)
    private val feedback =
        label(18f).apply {
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(24), dp(20), dp(24), dp(20))
            background = rounded(0xA6000000.toInt(), dp(40))
            visibility = View.GONE
        }
    private val hideFeedback = Runnable { feedback.visibility = View.GONE }
    private var dialog: AlertDialog? = null
    private var activeQuality = 720
    private var duration = 0L
    private var position = 0L
    private var dragging = false
    private var errorVisible = false
    val visible: Boolean
        get() = panel.visibility == View.VISIBLE

    val hasActionFocus: Boolean
        get() = panel.hasFocus()

    val modalVisible: Boolean
        get() = dialog?.isShowing == true

    init {
        panel.addView(back, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.START))
        bottom.addView(title, LinearLayout.LayoutParams(-1, -2))
        bottom.addView(
            subtitle,
            LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(6)
                bottomMargin = dp(12)
            },
        )
        bottom.addView(
            LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(positionLabel)
                addView(
                    label(13f).apply {
                        text = " / "
                        setTextColor(0x99FFFFFF.toInt())
                    }
                )
                addView(durationLabel)
            }
        )
        bottom.addView(timeline, LinearLayout.LayoutParams(-1, dp(32)))
        actions.addView(
            label(12f).apply {
                text = "DREAM CAST"
                letterSpacing = 0.15f
                setTextColor(0xBFFFFFFF.toInt())
            },
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        actions.addView(rewind)
        actions.addView(toggle)
        actions.addView(forward)
        actions.addView(next)
        actions.addView(Space(context), LinearLayout.LayoutParams(0, 1, 1f))
        captions?.let { actions.addView(it) }
        actions.addView(settings)
        bottom.addView(actions, LinearLayout.LayoutParams(-1, dp(60)))
        panel.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        errorPanel.addView(error)
        errorPanel.addView(
            LinearLayout(context).apply {
                gravity = Gravity.CENTER
                addView(
                    retry,
                    LinearLayout.LayoutParams(dp(150), dp(48)).apply { marginEnd = dp(12) },
                )
                addView(errorExit, LinearLayout.LayoutParams(dp(120), dp(48)))
            },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) },
        )
        panel.addView(errorPanel, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        root.addView(panel, FrameLayout.LayoutParams(-1, -1))
        loading.addView(
            ProgressBar(context).apply {
                indeterminateTintList = ColorStateList.valueOf(Color.WHITE)
            },
            LinearLayout.LayoutParams(dp(40), dp(40)),
        )
        loading.addView(loadingText)
        root.addView(loading, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        root.addView(
            feedback,
            FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_VERTICAL or Gravity.START).apply {
                marginStart = dp(72)
            },
        )
        timeline.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                    if (fromUser && duration > 0)
                        positionLabel.text = formatTime(duration * value / 1000)
                }

                override fun onStartTrackingTouch(bar: SeekBar?) {
                    dragging = true
                    onInteraction()
                }

                override fun onStopTrackingTouch(bar: SeekBar?) {
                    val target = duration * timeline.progress / 1000
                    dragging = false
                    onInteraction()
                    if (duration > 0) onSeek(target - position)
                }
            }
        )
        listOf(rewind, toggle, forward, next, settings, captions).filterNotNull().forEach {
            it.nextFocusUpId = timeline.id
        }
        timeline.nextFocusDownId = toggle.id
        timeline.nextFocusUpId = back.id
        back.nextFocusDownId = timeline.id
        toggle.nextFocusLeftId = rewind.id
        toggle.nextFocusRightId = forward.id
        rewind.nextFocusRightId = toggle.id
        forward.nextFocusLeftId = toggle.id
        showError(null)
        root.isFocusableInTouchMode = true
        root.requestFocus()
    }

    fun setNextAvailable(available: Boolean) {
        next.visibility = if (available) View.VISIBLE else View.GONE
    }

    fun showSeekFeedback(delta: Long) {
        feedback.text = (if (delta < 0) "−" else "+") + "${kotlin.math.abs(delta) / 1000} секунд"
        feedback.layoutParams =
            (feedback.layoutParams as FrameLayout.LayoutParams).apply {
                gravity = Gravity.CENTER_VERTICAL or (if (delta < 0) Gravity.START else Gravity.END)
                marginStart = dp(72)
                marginEnd = dp(72)
            }
        feedback.visibility = View.VISIBLE
        feedback.removeCallbacks(hideFeedback)
        feedback.postDelayed(hideFeedback, 800)
    }

    fun setQuality(height: Int) {
        activeQuality = height
    }

    private fun showSettings() {
        if (modalVisible) return
        onInteraction()
        val custom = settingsItems()
        val items =
            if (custom.isNotEmpty()) custom
            else listOf("Качество · ${activeQuality}p" to { showQualityDialog() })
        dialog =
            showPlayerMenu(
                context,
                "Настройки",
                items.map { it.first },
                onDismiss = {
                    dialog = null
                    settings.requestFocus()
                    onInteraction()
                },
            ) { index ->
                items[index].second()
            }
    }

    private fun showQualityDialog() {
        val heights = listOf(480, 720, 1080)
        dialog =
            showPlayerMenu(
                context,
                "Качество",
                heights.map { "${it}p" },
                heights.indexOf(activeQuality),
                onDismiss = {
                    dialog = null
                    settings.requestFocus()
                    onInteraction()
                },
            ) { index ->
                if (heights[index] != activeQuality) onQuality(heights[index])
            }
    }

    fun update(positionMs: Long, durationMs: Long, playing: Boolean, seekable: Boolean) {
        position = positionMs.coerceAtLeast(0)
        duration = durationMs.coerceAtLeast(0)
        if (!dragging) {
            positionLabel.text = formatTime(position)
            timeline.progress =
                if (duration > 0) (position.coerceAtMost(duration) * 1000 / duration).toInt() else 0
        }
        durationLabel.text = if (duration > 0) formatTime(duration) else "--:--"
        timeline.isEnabled = seekable && duration > 0
        toggle.kind = if (playing) "pause" else "play"
        toggle.contentDescription = if (playing) "Пауза" else "Воспроизвести"
        toggle.invalidate()
    }

    fun show(focus: Boolean = false) {
        if (!visible) {
            panel.visibility = View.VISIBLE
            panel.alpha = 0f
            panel.animate().alpha(1f).setDuration(160).start()
        }
        if (focus) (if (errorVisible) retry else toggle).requestFocus()
    }

    fun hide() {
        root.requestFocus()
        panel.animate().cancel()
        panel.visibility = View.GONE
    }

    fun showLoading(text: String?) {
        loading.visibility = if (text == null) View.GONE else View.VISIBLE
        loadingText.text = text.orEmpty()
        actions.visibility = if (text != null || errorVisible) View.GONE else View.VISIBLE
    }

    fun showError(text: String?) {
        errorVisible = text != null
        error.text = text.orEmpty()
        errorPanel.visibility = if (errorVisible) View.VISIBLE else View.GONE
        bottom.visibility = if (errorVisible) View.GONE else View.VISIBLE
        actions.visibility = if (errorVisible) View.GONE else View.VISIBLE
        if (errorVisible) {
            showLoading(null)
            show(true)
        }
    }

    fun dispose() {
        dialog?.setOnDismissListener(null)
        dialog?.dismiss()
        feedback.removeCallbacks(hideFeedback)
        panel.animate().cancel()
    }

    companion object {
        private fun formatTime(ms: Long): String {
            val seconds = ms / 1000
            return if (seconds >= 3600)
                "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
            else "%02d:%02d".format(seconds / 60, seconds % 60)
        }
    }
}

private fun rounded(color: Int, radius: Int) =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
    }

/** Right-hand settings sheet; focus, selection and click are available with a TV remote. */
internal fun showPlayerMenu(
    context: Context,
    title: String,
    items: List<String>,
    selected: Int = -1,
    onDismiss: () -> Unit = {},
    onSelected: (Int) -> Unit,
): AlertDialog {
    val density = context.resources.displayMetrics.density
    fun dp(value: Int) = (value * density).toInt()
    val content =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(28), dp(24), dp(24))
            minimumHeight = context.resources.displayMetrics.heightPixels
            background = rounded(0xFF212121.toInt(), dp(16))
        }
    content.addView(
        TextView(context).apply {
            text = title
            textSize = 23f
            setTextColor(Color.WHITE)
            setPadding(dp(12), 0, 0, dp(20))
        }
    )
    val scroll = ScrollView(context)
    val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    scroll.addView(rows)
    content.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
    val dialog =
        AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar)
            .setView(content)
            .create()
    items.forEachIndexed { index, value ->
        rows.addView(
            Button(context).apply {
                text = if (index == selected) "✓  $value" else value
                contentDescription = value
                isAllCaps = false
                gravity = Gravity.CENTER_VERTICAL
                textSize = 16f
                setTextColor(Color.WHITE)
                setPadding(dp(16), 0, dp(16), 0)
                background = rounded(Color.TRANSPARENT, dp(10))
                setOnFocusChangeListener { _, focused ->
                    setTextColor(if (focused) Color.BLACK else Color.WHITE)
                    background = rounded(if (focused) Color.WHITE else Color.TRANSPARENT, dp(10))
                }
                setOnClickListener {
                    dialog.dismiss()
                    onSelected(index)
                }
            },
            LinearLayout.LayoutParams(-1, dp(54)).apply { bottomMargin = dp(4) },
        )
    }
    dialog.setOnDismissListener { onDismiss() }
    dialog.show()
    dialog.window?.apply {
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        setDimAmount(0.25f)
        addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        setLayout(dp(340), WindowManager.LayoutParams.MATCH_PARENT)
        setGravity(Gravity.END)
    }
    rows.getChildAt(selected.takeIf { it in items.indices } ?: 0)?.requestFocus()
    return dialog
}

/** Drawn vectors avoid font-dependent media glyphs. */
private class TransportButton(context: Context, var kind: String) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        isFocusable = true
        isClickable = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = minOf(width, height).toFloat()
        canvas.save()
        canvas.translate(width / 2f, height / 2f)
        paint.style = Paint.Style.FILL
        paint.color = if (hasFocus()) Color.WHITE else 0x66000000
        canvas.drawCircle(0f, 0f, size * 0.46f, paint)
        canvas.scale(size / 64f, size / 64f)
        paint.color = if (hasFocus()) Color.BLACK else Color.WHITE
        paint.strokeWidth = 2.7f
        paint.strokeCap = Paint.Cap.ROUND
        when (kind) {
            "pause" -> {
                canvas.drawRect(-9f, -12f, -3f, 12f, paint)
                canvas.drawRect(3f, -12f, 9f, 12f, paint)
            }
            "play",
            "next" -> {
                canvas.drawPath(
                    Path().apply {
                        moveTo(-8f, -12f)
                        lineTo(11f, 0f)
                        lineTo(-8f, 12f)
                        close()
                    },
                    paint,
                )
                if (kind == "next") canvas.drawRect(12f, -12f, 16f, 12f, paint)
            }
            "back" -> {
                paint.style = Paint.Style.STROKE
                canvas.drawLine(11f, 0f, -11f, 0f, paint)
                canvas.drawPath(
                    Path().apply {
                        moveTo(-1f, -10f)
                        lineTo(-11f, 0f)
                        lineTo(-1f, 10f)
                    },
                    paint,
                )
            }
            "rewind",
            "forward" -> {
                paint.style = Paint.Style.STROKE
                canvas.drawArc(
                    -14f,
                    -14f,
                    14f,
                    14f,
                    if (kind == "rewind") 210f else -30f,
                    290f,
                    false,
                    paint,
                )
                val x = if (kind == "rewind") -13f else 13f
                canvas.drawPath(
                    Path().apply {
                        moveTo(x, -16f)
                        lineTo(x, -5f)
                        lineTo(if (x < 0) -3f else 3f, -8f)
                    },
                    paint,
                )
                paint.style = Paint.Style.FILL
                paint.textSize = 13f
                paint.textAlign = Paint.Align.CENTER
                paint.typeface = Typeface.DEFAULT_BOLD
                canvas.drawText("10", 0f, 6f, paint)
            }
            "captions" -> {
                paint.style = Paint.Style.STROKE
                canvas.drawRoundRect(-15f, -11f, 15f, 11f, 3f, 3f, paint)
                paint.style = Paint.Style.FILL
                paint.textSize = 13f
                paint.textAlign = Paint.Align.CENTER
                paint.typeface = Typeface.DEFAULT_BOLD
                canvas.drawText("CC", 0f, 5f, paint)
            }
            "settings" -> {
                paint.style = Paint.Style.STROKE
                canvas.drawCircle(0f, 0f, 10f, paint)
                canvas.drawCircle(0f, 0f, 3f, paint)
                repeat(8) {
                    canvas.save()
                    canvas.rotate(it * 45f)
                    canvas.drawLine(0f, -10f, 0f, -15f, paint)
                    canvas.restore()
                }
            }
        }
        canvas.restore()
    }
}
