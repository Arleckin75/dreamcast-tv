package com.dreamcast.app

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.StandardMessageCodec
import io.flutter.plugin.platform.PlatformView
import io.flutter.plugin.platform.PlatformViewFactory

internal class TvSearchFieldFactory(private val messenger: BinaryMessenger) : PlatformViewFactory(StandardMessageCodec.INSTANCE) {
    override fun create(context: Context, viewId: Int, args: Any?): PlatformView =
        TvSearchField(context, viewId, args as? Map<*, *> ?: emptyMap<Any, Any>(), messenger)
}

/** A single editor in the actual Android view hierarchy; no dialog or second field. */
private class TvSearchField(private val context: Context, id: Int, params: Map<*, *>, messenger: BinaryMessenger) : PlatformView {
    private val channel = MethodChannel(messenger, "dream_cast/tv_search_field/$id")
    private val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    private var disposed = false
    private var syncing = false
    private var keyboardDismissed = false
    private var focusRequested = false
    private var accent = (params["accent"] as? Number)?.toInt() ?: Color.WHITE
    private val editor = object : EditText(context) {
        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            if (focusRequested) post { showKeyboard() }
        }
        override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
            super.onWindowFocusChanged(hasWindowFocus)
            if (hasWindowFocus && focusRequested) post { showKeyboard() }
        }
        override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) hideKeyboard()
                return true
            }
            return super.onKeyPreIme(keyCode, event)
        }
    }
    private fun editorBackground(focused: Boolean) = GradientDrawable().apply {
        setColor(Color.TRANSPARENT)
        cornerRadius = 14 * context.resources.displayMetrics.density
        setStroke(((if (focused) 3 else 1) * context.resources.displayMetrics.density).toInt(), accent)
    }

    init {
        editor.apply {
            isFocusable = true
            isFocusableInTouchMode = true
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            privateImeOptions = "com.google.android.inputmethod.latin.noDecoding,com.google.android.inputmethod.latin.noMicrophoneKey"
            hint = params["hint"] as? String ?: "Название релиза"
            textSize = 18f
            val padding = (12 * context.resources.displayMetrics.density).toInt()
            setPadding(padding, 0, padding, 0)
            setTextColor((params["foreground"] as? Number)?.toInt() ?: Color.WHITE)
            setHintTextColor((params["hintColor"] as? Number)?.toInt() ?: Color.LTGRAY)
            setText(params["text"] as? String ?: "")
            setSelection(length())
            background = editorBackground(false)
            setOnFocusChangeListener { _, focused ->
                background = editorBackground(focused)
                if (!focused) hideKeyboard()
            }
            setOnClickListener { showKeyboard() }
            setOnKeyListener { _, key, event ->
                when {
                    key == KeyEvent.KEYCODE_DPAD_DOWN && keyboardDismissed -> {
                        if (event.action == KeyEvent.ACTION_DOWN) channel.invokeMethod("results", null)
                        true
                    }
                    key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER || key == KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        if (event.action == KeyEvent.ACTION_UP) showKeyboard()
                        true
                    }
                    else -> false
                }
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    if (!syncing && !disposed) channel.invokeMethod("changed", s?.toString().orEmpty())
                }
                override fun afterTextChanged(s: Editable?) {}
            })
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_SEARCH || action == EditorInfo.IME_ACTION_DONE) {
                    hideKeyboard()
                    channel.invokeMethod("submitted", text.toString())
                    true
                } else false
            }
        }
        channel.setMethodCallHandler { call, result ->
            when (call.method) {
                "focus" -> showKeyboard()
                "hide" -> hideKeyboard()
                "text" -> {
                    val next = call.arguments as? String ?: ""
                    if (editor.text.toString() != next) {
                        syncing = true
                        editor.setText(next)
                        editor.setSelection(next.length)
                        syncing = false
                    }
                }
                "colors" -> {
                    val colors = call.arguments as Map<*, *>
                    accent = (colors["accent"] as Number).toInt()
                    editor.setTextColor((colors["foreground"] as Number).toInt())
                    editor.setHintTextColor((colors["hintColor"] as Number).toInt())
                    editor.background = editorBackground(editor.hasFocus())
                }
                else -> { result.notImplemented(); return@setMethodCallHandler }
            }
            result.success(null)
        }
    }

    private fun showKeyboard() {
        if (disposed) return
        focusRequested = true
        if (!editor.isAttachedToWindow || !editor.hasWindowFocus()) return
        keyboardDismissed = false
        editor.requestFocus()
        editor.post {
            if (!disposed && focusRequested && editor.hasFocus() && editor.hasWindowFocus()) {
                imm.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }
    private fun hideKeyboard() {
        focusRequested = false
        keyboardDismissed = true
        imm.hideSoftInputFromWindow(editor.windowToken, 0)
    }
    override fun getView(): View = editor
    override fun dispose() {
        disposed = true
        hideKeyboard()
        editor.setOnFocusChangeListener(null)
        editor.setOnClickListener(null)
        editor.setOnKeyListener(null)
        channel.setMethodCallHandler(null)
    }
}
