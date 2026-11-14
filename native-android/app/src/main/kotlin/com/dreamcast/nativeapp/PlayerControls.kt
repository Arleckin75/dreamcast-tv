package com.dreamcast.nativeapp

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.*
import android.widget.*

/** Remote-first watch overlay for the unified Media3 player. */
internal class PlayerControls(
    private val context: Context,
    private val root: FrameLayout,
    accent: Int,
    onAccent: Int,
    onToggle: () -> Unit,
    private val onSeek: (Long) -> Unit,
    onRetry: () -> Unit,
    onNext: () -> Unit,
    nextTitle: String?,
    private val onInteraction: () -> Unit,
    private val settingsItems: () -> List<Pair<String, () -> Unit>> = { emptyList() },
    onCaptions: (() -> Unit)? = null,
    /** Left/right on the timeline: direction (-1 or 1) and the key repeat count. */
    private val onScrub: (Int, Int) -> Unit = { direction, _ -> onSeek(direction * 10_000L) },
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
    private val actions = FrameLayout(context)

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
                        onScrub(if (key == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1, event.repeatCount)
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
    private val feedback =
        label(18f).apply {
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(24), dp(20), dp(24), dp(20))
            background = rounded(0xA6000000.toInt(), dp(40))
            visibility = View.GONE
        }
    private val hideFeedback = Runnable { feedback.visibility = View.GONE }
    private val previewImage =
        ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_XY
            background = rounded(0xFF202020.toInt(), dp(8))
            clipToOutline = true
            visibility = View.GONE
        }
    private val previewTime =
        label(16f).apply {
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
            setShadowLayer(dp(3).toFloat(), 0f, 0f, Color.BLACK)
        }
    private val preview =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = View.GONE
            addView(previewImage, LinearLayout.LayoutParams(dp(PREVIEW_WIDTH_DP), dp(PREVIEW_HEIGHT_DP)))
            addView(previewTime, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
        }
    private var scrubbing = false
    private var scrubFraction = 0f
    private var dialog: AlertDialog? = null
    private var duration = 0L
    private var position = 0L
    private var playbackPlaying = false
    private var playbackSeekable = false
    private var dragging = false
    private var errorVisible = false
    private var shown = true
    val visible: Boolean
        get() = shown

    val hasActionFocus: Boolean
        get() = shown && panel.hasFocus()

    val modalVisible: Boolean
        get() = dialog?.isShowing == true

    init {
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
            FrameLayout.LayoutParams(-2, -2, Gravity.START or Gravity.CENTER_VERTICAL),
        )
        val transport =
            LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(rewind)
                addView(toggle)
                addView(forward)
                addView(next)
            }
        // Equal outer margins keep the visible icon group centered, including
        // when the last episode has no next button.
        rewind.layoutParams =
            (rewind.layoutParams as LinearLayout.LayoutParams).apply { marginStart = dp(5) }
        forward.layoutParams =
            (forward.layoutParams as LinearLayout.LayoutParams).apply { marginEnd = dp(5) }
        next.layoutParams =
            (next.layoutParams as LinearLayout.LayoutParams).apply {
                marginStart = dp(5)
                marginEnd = dp(5)
            }
        actions.addView(transport, FrameLayout.LayoutParams(-2, -1, Gravity.CENTER))
        val options =
            LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                captions?.let { addView(it) }
                addView(settings)
            }
        actions.addView(
            options,
            FrameLayout.LayoutParams(-2, -1, Gravity.END or Gravity.CENTER_VERTICAL),
        )
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
        root.addView(preview, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START))
        // A frame arriving later makes the preview taller; the panel may still be laying out.
        val replace = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> placePreview() }
        preview.addOnLayoutChangeListener(replace)
        timeline.addOnLayoutChangeListener(replace)
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
                    // update() skips unchanged state, so restore the label the drag replaced.
                    if (shown) renderPlaybackState()
                    if (duration > 0) onSeek(target - position)
                }
            }
        )
        listOf(rewind, toggle, forward, next, settings, captions).filterNotNull().forEach {
            it.nextFocusUpId = timeline.id
        }
        timeline.nextFocusDownId = toggle.id
        timeline.nextFocusUpId = timeline.id
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

    private fun showSettings() {
        if (modalVisible) return
        onInteraction()
        // The activity supplies the items (quality, speed, decoding, stream).
        val items = settingsItems()
        if (items.isEmpty()) return
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

    fun update(positionMs: Long, durationMs: Long, playing: Boolean, seekable: Boolean) {
        val nextPosition = positionMs.coerceAtLeast(0)
        val nextDuration = durationMs.coerceAtLeast(0)
        // The 500 ms tick keeps calling while paused: skip the identical frames.
        if (
            nextPosition == position &&
                nextDuration == duration &&
                playing == playbackPlaying &&
                seekable == playbackSeekable
        )
            return
        position = nextPosition
        duration = nextDuration
        playbackPlaying = playing
        playbackSeekable = seekable
        if (shown) renderPlaybackState()
    }

    /**
     * Shows the scrub target [targetMs] on the timeline with its preview [frame] above it.
     * A null frame keeps the previous image while the next one loads, or shows only the time.
     */
    fun showScrub(targetMs: Long, frame: android.graphics.drawable.Drawable?, hasPreviews: Boolean) {
        scrubbing = true
        val fraction = if (duration > 0) targetMs.coerceIn(0, duration).toFloat() / duration else 0f
        timeline.progress = (fraction * 1000).toInt()
        positionLabel.text = formatTime(targetMs)
        previewTime.text = formatTime(targetMs)
        if (frame != null) previewImage.setImageDrawable(frame)
        previewImage.visibility =
            if (hasPreviews && (frame != null || previewImage.drawable != null)) View.VISIBLE
            else View.GONE
        scrubFraction = fraction
        // Stay invisible until placed, so it never flashes at a stale position.
        if (preview.visibility == View.GONE) preview.visibility = View.INVISIBLE
        placePreview()
    }

    /** Replaces the preview image if scrubbing is still showing a target. */
    fun setScrubFrame(frame: android.graphics.drawable.Drawable) {
        if (!scrubbing) return
        previewImage.setImageDrawable(frame)
        previewImage.visibility = View.VISIBLE
    }

    fun hideScrub() {
        if (!scrubbing) return
        scrubbing = false
        preview.visibility = View.GONE
        previewImage.setImageDrawable(null)
        if (shown) renderPlaybackState()
    }

    /**
     * Puts the preview centred over the scrub target with its bottom just above the timeline.
     * Runs again whenever the preview or the timeline changes size or position.
     */
    private fun placePreview() {
        if (!scrubbing || root.width == 0 || timeline.width == 0 || !timeline.isShown) return
        val fraction = scrubFraction
        val rootOrigin = IntArray(2).also(root::getLocationInWindow)
        val bar = IntArray(2).also(timeline::getLocationInWindow)
        val track = timeline.width - timeline.paddingLeft - timeline.paddingRight
        val centerX = bar[0] - rootOrigin[0] + timeline.paddingLeft + track * fraction
        val margin = dp(16).toFloat()
        // The first placement can run before the preview's own layout pass.
        if (preview.width == 0)
            preview.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val width = (preview.width.takeIf { it > 0 } ?: preview.measuredWidth).toFloat()
        val height = (preview.height.takeIf { it > 0 } ?: preview.measuredHeight).toFloat()
        preview.translationX =
            (centerX - width / 2).coerceIn(margin, (root.width - width - margin).coerceAtLeast(margin))
        // Use the panel's resting position, not a slide-in animation frame.
        val barTop = bar[1] - rootOrigin[1] - bottom.translationY
        preview.translationY = barTop - height - dp(4)
        preview.visibility = View.VISIBLE
    }

    private fun renderPlaybackState() {
        if (!dragging && !scrubbing) {
            val text = formatTime(position)
            if (positionLabel.text.toString() != text) positionLabel.text = text
            timeline.progress =
                if (duration > 0) (position.coerceAtMost(duration) * 1000 / duration).toInt() else 0
        }
        val durationText = if (duration > 0) formatTime(duration) else "--:--"
        if (durationLabel.text.toString() != durationText) durationLabel.text = durationText
        timeline.isEnabled = playbackSeekable && duration > 0
        val kind = if (playbackPlaying) "pause" else "play"
        if (toggle.kind != kind) {
            toggle.kind = kind
            toggle.contentDescription = if (playbackPlaying) "Пауза" else "Воспроизвести"
            toggle.invalidate()
        }
    }

    fun show(focus: Boolean = false) {
        if (!shown) {
            shown = true
            renderPlaybackState()
            panel.animate().cancel()
            bottom.animate().cancel()
            if (panel.visibility != View.VISIBLE) {
                panel.alpha = 0f
                bottom.translationY = bottom.height.toFloat() + dp(24)
            }
            panel.visibility = View.VISIBLE
            panel.animate().alpha(1f).setDuration(200).start()
            bottom.animate().translationY(0f).setDuration(200).start()
        }
        if (focus) (if (errorVisible) retry else toggle).requestFocus()
    }

    fun hide(animate: Boolean = true) {
        if (!shown) return
        shown = false
        root.requestFocus()
        panel.animate().cancel()
        bottom.animate().cancel()
        if (!animate) {
            panel.visibility = View.GONE
            return
        }
        bottom.animate().translationY(bottom.height.toFloat() + dp(24)).setDuration(220).start()
        panel
            .animate()
            .alpha(0f)
            .setDuration(220)
            .withEndAction { if (!shown) panel.visibility = View.GONE }
            .start()
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
        hideScrub()
        panel.animate().cancel()
        bottom.animate().cancel()
    }

    companion object {
        private const val PREVIEW_WIDTH_DP = 204
        private const val PREVIEW_HEIGHT_DP = 115

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
    onCancel: () -> Unit = {},
    enabledItem: (Int) -> Boolean = { true },
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
            tag = "player-menu-title"
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
                isEnabled = enabledItem(index)
                isFocusable = isEnabled
                text = if (index == selected) "✓  $value" else value
                contentDescription = value
                isAllCaps = false
                gravity = Gravity.CENTER_VERTICAL
                textSize = 16f
                setTextColor(if (isEnabled) Color.WHITE else Color.GRAY)
                setPadding(dp(16), 0, dp(16), 0)
                background = rounded(0xFF303030.toInt(), dp(27))
                setOnFocusChangeListener { _, focused ->
                    setTextColor(if (focused) Color.BLACK else Color.WHITE)
                    background = rounded(if (focused) Color.WHITE else 0xFF303030.toInt(), dp(27))
                }
                setOnClickListener {
                    if (!isEnabled) return@setOnClickListener
                    dialog.dismiss()
                    onSelected(index)
                }
            },
            LinearLayout.LayoutParams(-1, dp(54)).apply { bottomMargin = dp(4) },
        )
    }
    dialog.setOnCancelListener { onCancel() }
    dialog.setOnDismissListener { onDismiss() }
    dialog.show()
    dialog.window?.apply {
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        setDimAmount(0.25f)
        addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        setLayout(dp(340), WindowManager.LayoutParams.MATCH_PARENT)
        setGravity(Gravity.END)
    }
    val initial = selected.takeIf { it in items.indices && enabledItem(it) }
        ?: items.indices.firstOrNull { enabledItem(it) }
    initial?.let { rows.getChildAt(it)?.requestFocus() }
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
