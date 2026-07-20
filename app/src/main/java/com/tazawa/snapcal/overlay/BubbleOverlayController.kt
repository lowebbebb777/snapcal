package com.tazawa.snapcal.overlay

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.tazawa.snapcal.MainActivity
import kotlin.math.abs
import kotlin.math.roundToInt

enum class BubbleState(val glyph: String, val color: Int) {
    IDLE("S", Color.rgb(84, 240, 186)),
    RECOGNIZING("…", Color.rgb(102, 216, 255)),
    ADDED("✓", Color.rgb(212, 255, 116)),
    NEEDS_REVIEW("!", Color.rgb(255, 201, 102)),
    FAILED("×", Color.rgb(255, 127, 134)),
}

class BubbleOverlayController(
    private val context: Context,
) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var bubble: TextView? = null
    private var params: WindowManager.LayoutParams? = null

    fun show(initialState: BubbleState = BubbleState.IDLE): Boolean {
        if (!Settings.canDrawOverlays(context)) return false
        if (bubble != null) {
            update(initialState)
            return true
        }

        val size = context.dp(52)
        val view = TextView(context).apply {
            gravity = Gravity.CENTER
            textSize = 21f
            setTextColor(Color.rgb(4, 28, 21))
            elevation = context.dp(10).toFloat()
            contentDescription = "SnapCal"
            setOnClickListener {
                context.startActivity(
                    Intent(context, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                )
            }
        }
        val layoutParams = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenWidth() - size - context.dp(10)
            y = context.dp(220)
        }
        view.installDrag(layoutParams)

        return try {
            windowManager.addView(view, layoutParams)
            bubble = view
            params = layoutParams
            update(initialState)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    fun update(state: BubbleState) {
        if (!Settings.canDrawOverlays(context)) {
            hide()
            return
        }
        val view = bubble ?: return
        view.text = state.glyph
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(state.color)
            setStroke(context.dp(3), Color.WHITE)
        }
        view.contentDescription = "SnapCal: ${state.name.lowercase()}"
    }

    fun hide() {
        val view = bubble ?: return
        runCatching { windowManager.removeViewImmediate(view) }
        bubble = null
        params = null
    }

    private fun View.installDrag(layoutParams: WindowManager.LayoutParams) {
        var startX = 0
        var startY = 0
        var downRawX = 0f
        var downRawY = 0f
        setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = layoutParams.x
                    startY = layoutParams.y
                    downRawX = event.rawX
                    downRawY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    layoutParams.x = startX + (event.rawX - downRawX).roundToInt()
                    layoutParams.y = (startY + (event.rawY - downRawY).roundToInt())
                        .coerceIn(context.dp(24), screenHeight() - context.dp(100))
                    runCatching { windowManager.updateViewLayout(view, layoutParams) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val moved = abs(event.rawX - downRawX) + abs(event.rawY - downRawY)
                    if (moved < context.dp(8)) {
                        view.performClick()
                    } else {
                        val center = layoutParams.x + view.width / 2
                        layoutParams.x = if (center < screenWidth() / 2) context.dp(8) else screenWidth() - view.width - context.dp(8)
                        runCatching { windowManager.updateViewLayout(view, layoutParams) }
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun screenWidth(): Int = context.resources.displayMetrics.widthPixels
    private fun screenHeight(): Int = context.resources.displayMetrics.heightPixels
}

private fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
