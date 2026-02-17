package com.omt.camera

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * Draws broadcast-style safe-area guides on the camera preview.
 * These are LOCAL only — they are NOT included in the OMT stream.
 *
 * - Rule of thirds grid
 * - Center crosshair
 * - Action-safe area (90% — 5% inset from each edge)
 * - Title-safe area (80% — 10% inset from each edge)
 */
class SafeGuideView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    var showGuides = true
        set(value) { field = value; invalidate() }

    private val thirdsPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x55FFFFFF.toInt()
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }

    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x88FFFFFF.toInt()
        strokeWidth = 1.5f
        style = Paint.Style.STROKE
    }

    private val actionSafePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x44FFFF00.toInt()
        strokeWidth = 1f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
    }

    private val titleSafePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x44FF4444.toInt()
        strokeWidth = 1f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(6f, 6f), 0f)
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66FFFFFF.toInt()
        textSize = 20f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!showGuides) return

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // Rule of thirds
        val x1 = w / 3f; val x2 = 2f * w / 3f
        val y1 = h / 3f; val y2 = 2f * h / 3f
        canvas.drawLine(x1, 0f, x1, h, thirdsPaint)
        canvas.drawLine(x2, 0f, x2, h, thirdsPaint)
        canvas.drawLine(0f, y1, w, y1, thirdsPaint)
        canvas.drawLine(0f, y2, w, y2, thirdsPaint)

        // Center crosshair
        val cx = w / 2f; val cy = h / 2f
        val arm = minOf(w, h) * 0.03f
        canvas.drawLine(cx - arm, cy, cx + arm, cy, centerPaint)
        canvas.drawLine(cx, cy - arm, cx, cy + arm, centerPaint)

        // Action-safe (90%)
        val aInset = 0.05f
        val al = w * aInset; val at = h * aInset
        val ar = w * (1 - aInset); val ab = h * (1 - aInset)
        canvas.drawRect(al, at, ar, ab, actionSafePaint)
        canvas.drawText("Action Safe", al + 4f, at + labelPaint.textSize + 2f, labelPaint)

        // Title-safe (80%)
        val tInset = 0.10f
        val tl = w * tInset; val tt = h * tInset
        val tr = w * (1 - tInset); val tb = h * (1 - tInset)
        canvas.drawRect(tl, tt, tr, tb, titleSafePaint)
        canvas.drawText("Title Safe", tl + 4f, tt + labelPaint.textSize + 2f, labelPaint)
    }
}
