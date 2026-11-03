package com.dreamcast.app

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodChannel

/** Keep TV remote events and the input connection in Android while typing. */
internal class TvSearchInput(private val activity: Activity, messenger: BinaryMessenger) {
    private val channel = MethodChannel(messenger, "dream_cast/tv_search_input")
    private var dialog: Dialog? = null
    private var pending: MethodChannel.Result? = null

    init {
        channel.setMethodCallHandler { call, result ->
            if (call.method != "edit") {
                result.notImplemented()
            } else if (pending != null || activity.isFinishing || activity.isDestroyed) {
                result.error("INPUT_BUSY", "Search editor is unavailable", null)
            } else {
                try {
                    open(call.argument<String>("text").orEmpty(),
                        call.argument<Number>("accent")?.toInt() ?: Color.WHITE,
                        call.argument<Number>("foreground")?.toInt() ?: Color.WHITE,
                        call.argument<Number>("background")?.toInt() ?: Color.BLACK,
                        result)
                } catch (error: Exception) {
                    pending = null
                    dialog?.setOnDismissListener(null)
                    dialog?.dismiss()
                    dialog = null
                    result.error("INPUT_FAILED", "Could not open Android search editor", null)
                }
            }
        }
    }

    private fun open(initialText: String, accentColor: Int, textColor: Int, surfaceColor: Int, result: MethodChannel.Result) {
        pending = result
        var submitted = false
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val editor = object : EditText(activity) {
            override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
                if (keyCode == KeyEvent.KEYCODE_BACK) {
                    if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) dialog?.dismiss()
                    return true
                }
                return super.onKeyPreIme(keyCode, event)
            }
        }.apply {
            setSingleLine(true)
            // Match the editor configuration observed in system TV Search.
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            privateImeOptions = "com.google.android.inputmethod.latin.noDecoding,com.google.android.inputmethod.latin.noMicrophoneKey"
            hint = "Название релиза"
            setTextColor(textColor)
            setHintTextColor((textColor and 0x00FFFFFF) or (0x99 shl 24))
            textSize = 18f
            setPadding(dp(16), dp(8), dp(16), dp(8))
            this.background = GradientDrawable().apply {
                setColor(surfaceColor); cornerRadius = dp(14).toFloat(); setStroke(dp(3), accentColor)
            }
            setText(initialText)
            setSelection(length())
            setOnEditorActionListener { _, action, event ->
                if (action == EditorInfo.IME_ACTION_SEARCH || action == EditorInfo.IME_ACTION_DONE ||
                    (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP)) {
                    submitted = true
                    dialog?.dismiss()
                    true
                } else false
            }
        }
        val content = FrameLayout(activity).apply {
            setPadding(dp(24), dp(12), dp(24), dp(12))
            addView(editor, FrameLayout.LayoutParams(-1, dp(56)))
        }
        val inputDialog = Dialog(activity)
        dialog = inputDialog
        inputDialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        inputDialog.setContentView(content)
        inputDialog.setOnDismissListener {
            imm.hideSoftInputFromWindow(editor.windowToken, 0)
            dialog = null
            val reply = pending
            pending = null
            reply?.success(mapOf("text" to editor.text.toString(), "submitted" to submitted))
        }
        inputDialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setGravity(Gravity.TOP)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        }
        inputDialog.show()
        inputDialog.window?.setLayout(-1, -2)
        editor.requestFocus()
        editor.post { if (dialog === inputDialog && editor.isAttachedToWindow) imm.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT) }
    }

    fun dispose() {
        channel.setMethodCallHandler(null)
        dialog?.setOnDismissListener(null)
        dialog?.dismiss()
        dialog = null
        pending?.success(null)
        pending = null
    }
}
