package com.rweb.browser

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import kotlin.math.cos
import kotlin.math.sin

// Simple vector-style icons drawn in code, so the app needs no image files.
class IconDrawable(private val kind: String, color: Int) : Drawable() {
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        stroke.style = Paint.Style.STROKE
        stroke.strokeCap = Paint.Cap.ROUND
        stroke.strokeJoin = Paint.Join.ROUND
        stroke.color = color
        fill.style = Paint.Style.FILL
        fill.color = color
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val s = minOf(b.width(), b.height()).toFloat()
        if (s <= 0f) return
        canvas.save()
        canvas.translate(b.left + (b.width() - s) / 2f, b.top + (b.height() - s) / 2f)
        canvas.scale(s, s)
        stroke.strokeWidth = 0.09f
        when (kind) {
            "back" -> {
                val p = Path()
                p.moveTo(0.62f, 0.2f)
                p.lineTo(0.35f, 0.5f)
                p.lineTo(0.62f, 0.8f)
                canvas.drawPath(p, stroke)
            }
            "forward" -> {
                val p = Path()
                p.moveTo(0.38f, 0.2f)
                p.lineTo(0.65f, 0.5f)
                p.lineTo(0.38f, 0.8f)
                canvas.drawPath(p, stroke)
            }
            "home" -> {
                val roof = Path()
                roof.moveTo(0.12f, 0.5f)
                roof.lineTo(0.5f, 0.17f)
                roof.lineTo(0.88f, 0.5f)
                canvas.drawPath(roof, stroke)
                val body = Path()
                body.moveTo(0.24f, 0.44f)
                body.lineTo(0.24f, 0.82f)
                body.lineTo(0.76f, 0.82f)
                body.lineTo(0.76f, 0.44f)
                canvas.drawPath(body, stroke)
                val door = Path()
                door.moveTo(0.42f, 0.82f)
                door.lineTo(0.42f, 0.6f)
                door.lineTo(0.58f, 0.6f)
                door.lineTo(0.58f, 0.82f)
                canvas.drawPath(door, stroke)
            }
            "menu" -> {
                canvas.drawCircle(0.5f, 0.22f, 0.075f, fill)
                canvas.drawCircle(0.5f, 0.5f, 0.075f, fill)
                canvas.drawCircle(0.5f, 0.78f, 0.075f, fill)
            }
            "reload" -> {
                val r = 0.28f
                val oval = RectF(0.5f - r, 0.5f - r, 0.5f + r, 0.5f + r)
                canvas.drawArc(oval, -40f, 290f, false, stroke)
                val a = Math.toRadians(250.0)
                val ex = (0.5 + r * cos(a)).toFloat()
                val ey = (0.5 + r * sin(a)).toFloat()
                val tx = (-sin(a)).toFloat()
                val ty = cos(a).toFloat()
                val nx = cos(a).toFloat()
                val ny = sin(a).toFloat()
                val l = 0.17f
                val tri = Path()
                tri.moveTo(ex + tx * l, ey + ty * l)
                tri.lineTo(ex + nx * l * 0.8f, ey + ny * l * 0.8f)
                tri.lineTo(ex - nx * l * 0.8f, ey - ny * l * 0.8f)
                tri.close()
                canvas.drawPath(tri, fill)
            }
            "close" -> {
                canvas.drawLine(0.25f, 0.25f, 0.75f, 0.75f, stroke)
                canvas.drawLine(0.75f, 0.25f, 0.25f, 0.75f, stroke)
            }
            "plus" -> {
                canvas.drawLine(0.5f, 0.2f, 0.5f, 0.8f, stroke)
                canvas.drawLine(0.2f, 0.5f, 0.8f, 0.5f, stroke)
            }
            "lock" -> {
                canvas.drawRoundRect(RectF(0.24f, 0.46f, 0.76f, 0.84f), 0.06f, 0.06f, fill)
                canvas.drawArc(RectF(0.33f, 0.16f, 0.67f, 0.6f), 180f, 180f, false, stroke)
                canvas.drawLine(0.33f, 0.38f, 0.33f, 0.48f, stroke)
                canvas.drawLine(0.67f, 0.38f, 0.67f, 0.48f, stroke)
            }
            "warning" -> {
                val p = Path()
                p.moveTo(0.5f, 0.16f)
                p.lineTo(0.88f, 0.82f)
                p.lineTo(0.12f, 0.82f)
                p.close()
                canvas.drawPath(p, stroke)
                canvas.drawLine(0.5f, 0.4f, 0.5f, 0.6f, stroke)
                canvas.drawCircle(0.5f, 0.71f, 0.04f, fill)
            }
            "search" -> {
                canvas.drawCircle(0.44f, 0.44f, 0.22f, stroke)
                canvas.drawLine(0.6f, 0.6f, 0.84f, 0.84f, stroke)
            }
            "shield" -> {
                val p = Path()
                p.moveTo(0.5f, 0.14f)
                p.lineTo(0.82f, 0.27f)
                p.lineTo(0.82f, 0.5f)
                p.cubicTo(0.82f, 0.7f, 0.65f, 0.82f, 0.5f, 0.88f)
                p.cubicTo(0.35f, 0.82f, 0.18f, 0.7f, 0.18f, 0.5f)
                p.lineTo(0.18f, 0.27f)
                p.close()
                canvas.drawPath(p, stroke)
            }
            "star" -> {
                val p = Path()
                for (i in 0 until 10) {
                    val ang = Math.toRadians(-90.0 + i * 36.0)
                    val rad = if (i % 2 == 0) 0.38 else 0.16
                    val x = (0.5 + rad * cos(ang)).toFloat()
                    val y = (0.54 + rad * sin(ang)).toFloat()
                    if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                }
                p.close()
                canvas.drawPath(p, stroke)
            }
            "share" -> {
                canvas.drawLine(0.74f, 0.24f, 0.26f, 0.5f, stroke)
                canvas.drawLine(0.26f, 0.5f, 0.74f, 0.76f, stroke)
                canvas.drawCircle(0.74f, 0.24f, 0.09f, fill)
                canvas.drawCircle(0.26f, 0.5f, 0.09f, fill)
                canvas.drawCircle(0.74f, 0.76f, 0.09f, fill)
            }
        }
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) {
        stroke.alpha = alpha
        fill.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        stroke.colorFilter = colorFilter
        fill.colorFilter = colorFilter
        invalidateSelf()
    }

    @Suppress("DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
