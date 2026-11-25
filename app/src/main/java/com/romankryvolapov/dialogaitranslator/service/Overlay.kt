package com.romankryvolapov.dialogaitranslator.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import com.romankryvolapov.dialogaitranslator.DialogApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * The latest translations and the latest answer over whatever is on the screen; dragged up and down with a finger.
 * Plain views: a Compose window outside an activity needs lifecycle plumbing for two lines of text.
 */
@SuppressLint("ClickableViewAccessibility", "SetTextI18n")
class Overlay(context: Context, app: DialogApp) {

    private companion object {
        const val SHOWN_PHRASES = 2
    }

    private val windows = context.getSystemService(WindowManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val density = context.resources.displayMetrics.density
    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP
        y = (96 * density).toInt()
    }

    private val view = TextView(context).apply {
        val padding = (12 * density).toInt()
        setPadding(padding, padding, padding, padding)
        textSize = 16f
        maxLines = 14
        typeface = Typeface.DEFAULT
        setTextColor(0xFFFFFFFF.toInt())
        background = GradientDrawable().apply {
            setColor(0xE6202020.toInt())
            cornerRadius = 16 * density
        }
        text = "Listening…"
    }

    init {
        var startY = 0
        var touchY = 0f
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startY = params.y
                    touchY = event.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    params.y = (startY + event.rawY - touchY).toInt().coerceAtLeast(0)
                    windows.updateViewLayout(view, params)
                }
            }
            true
        }
        windows.addView(view, params)
        scope.launch {
            combine(app.engine.rows, app.answers.rows) { rows, answers ->
                val heard = rows.takeLast(SHOWN_PHRASES).joinToString("\n") { row -> row.translation.trim('…', ' ').ifBlank { row.original } }
                listOfNotNull(heard.ifBlank { null }, answers.lastOrNull()?.let { "❓ ${it.question}\n💬 ${it.answer}" }).joinToString("\n\n")
            }.collect { view.text = it.ifBlank { "Listening…" } }
        }
    }

    fun close() {
        scope.cancel()
        windows.removeView(view)
    }
}
