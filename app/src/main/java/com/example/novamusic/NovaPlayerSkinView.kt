package com.example.novamusic

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.view.View

/**
 * Lightweight, static 3D-style player skins. No timers, shaders are created only
 * while drawing a skin, and the view redraws only when the song art or skin changes.
 */
class NovaPlayerSkinView(context: Context) : View(context) {
    private var skinId: Int = 0
    private var artwork: Bitmap? = null

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.2f
    }
    private val detail = Paint(Paint.ANTI_ALIAS_FLAG)
    private val artPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 10f
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()
    private val srcRect = Rect()
    private val destRect = RectF()

    fun setSkin(id: Int) {
        skinId = id.coerceIn(0, 4)
        invalidate()
    }

    fun setArtwork(bitmap: Bitmap?) {
        if (artwork === bitmap) return
        artwork = bitmap
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        val save = canvas.save()
        canvas.scale(width / 320f, height / 320f)
        drawBackdrop(canvas)
        when (skinId) {
            0 -> drawGramophone(canvas)
            1 -> drawCassette(canvas)
            2 -> drawTurntable(canvas)
            3 -> drawRadio(canvas)
            else -> drawMidnight(canvas)
        }
        // A small top gloss gives the artwork a dimensional glass finish.
        fill.shader = LinearGradient(
            0f, 0f, 0f, 95f,
            intArrayOf(0x35FFFFFF, 0x00FFFFFF, 0x00FFFFFF),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(12f, 12f, 308f, 308f, 27f, 27f, fill)
        fill.shader = null
        canvas.restoreToCount(save)
    }

    private fun drawBackdrop(canvas: Canvas) {
        fill.shader = LinearGradient(
            12f, 12f, 308f, 308f,
            intArrayOf(0xFF282044.toInt(), 0xFF101525.toInt(), 0xFF090D19.toInt()),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(12f, 12f, 308f, 308f, 27f, 27f, fill)
        fill.shader = null
        stroke.color = 0x66D7C7FF
        stroke.strokeWidth = 1.4f
        canvas.drawRoundRect(13f, 13f, 307f, 307f, 27f, 27f, stroke)
        detail.color = 0x1FFFFFFF
        detail.style = Paint.Style.FILL
        canvas.drawCircle(266f, 45f, 65f, detail)
        canvas.drawCircle(46f, 274f, 58f, detail)
    }

    private fun drawGramophone(canvas: Canvas) {
        // Polished walnut cabinet and gold feet.
        fill.shader = LinearGradient(0f, 203f, 0f, 293f,
            intArrayOf(0xFF9B643A.toInt(), 0xFF4B281E.toInt(), 0xFF241620.toInt()),
            null, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(35f, 218f, 285f, 281f, 17f, 17f, fill)
        fill.shader = null
        stroke.color = 0xFFFFD49A.toInt()
        stroke.strokeWidth = 2f
        canvas.drawRoundRect(39f, 222f, 281f, 277f, 13f, 13f, stroke)
        fill.color = 0xFFDAA65E.toInt()
        canvas.drawRoundRect(55f, 276f, 88f, 286f, 4f, 4f, fill)
        canvas.drawRoundRect(232f, 276f, 265f, 286f, 4f, 4f, fill)

        // Record platter with concentric grooves.
        fill.shader = LinearGradient(65f, 95f, 230f, 250f,
            intArrayOf(0xFF34323D.toInt(), 0xFF070A11.toInt(), 0xFF292631.toInt()),
            null, Shader.TileMode.CLAMP)
        canvas.drawCircle(137f, 180f, 92f, fill)
        fill.shader = null
        stroke.color = 0xFFB6A17D.toInt()
        stroke.strokeWidth = 3f
        canvas.drawCircle(137f, 180f, 94f, stroke)
        stroke.color = 0x557F8799
        stroke.strokeWidth = 1f
        for (r in 77..88 step 3) canvas.drawCircle(137f, 180f, r.toFloat(), stroke)
        drawArtworkCircle(canvas, 137f, 180f, 34f)
        fill.color = 0xFFD8B56F.toInt()
        canvas.drawCircle(137f, 180f, 3.5f, fill)

        // Horn neck and flared brass bell.
        val horn = Path().apply {
            moveTo(201f, 153f)
            cubicTo(215f, 138f, 220f, 115f, 231f, 91f)
            cubicTo(240f, 65f, 254f, 47f, 271f, 52f)
            cubicTo(294f, 59f, 288f, 91f, 269f, 111f)
            cubicTo(250f, 132f, 229f, 145f, 213f, 172f)
            close()
        }
        fill.shader = LinearGradient(205f, 55f, 282f, 168f,
            intArrayOf(0xFFFFE6A6.toInt(), 0xFFC48B43.toInt(), 0xFF6D421F.toInt()),
            null, Shader.TileMode.CLAMP)
        canvas.drawPath(horn, fill)
        fill.shader = null
        stroke.color = 0xFFFFDFA0.toInt()
        stroke.strokeWidth = 2f
        canvas.drawPath(horn, stroke)
        fill.color = 0xFF4A2D1C.toInt()
        canvas.drawOval(260f, 64f, 291f, 100f, fill)
        stroke.color = 0xFFFFD89A.toInt()
        canvas.drawOval(260f, 64f, 291f, 100f, stroke)
        // Gold support and small controls.
        stroke.color = 0xFFE4B96D.toInt()
        stroke.strokeWidth = 6f
        canvas.drawLine(206f, 165f, 186f, 220f, stroke)
        fill.color = 0xFFE9C37D.toInt()
        canvas.drawCircle(206f, 164f, 5f, fill)
        canvas.drawRoundRect(222f, 238f, 263f, 249f, 5f, 5f, fill)
        canvas.drawText("NOVA CLASSIC", 242f, 265f, textPaint)
    }

    private fun drawCassette(canvas: Canvas) {
        // Tilted shell is suggested with layered bevels, without any 3D engine.
        canvas.save()
        canvas.rotate(-5f, 160f, 160f)
        fill.shader = LinearGradient(45f, 65f, 274f, 252f,
            intArrayOf(0xFF676A7A.toInt(), 0xFF242A3B.toInt(), 0xFF0A101D.toInt()),
            null, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(38f, 65f, 282f, 255f, 24f, 24f, fill)
        fill.shader = null
        stroke.color = 0xFFB7C0D7.toInt()
        stroke.strokeWidth = 2f
        canvas.drawRoundRect(40f, 67f, 280f, 253f, 22f, 22f, stroke)
        fill.shader = LinearGradient(55f, 78f, 265f, 138f,
            intArrayOf(0xFFB2B1C5.toInt(), 0xFF4C5368.toInt(), 0xFF202638.toInt()),
            null, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(56f, 82f, 264f, 137f, 10f, 10f, fill)
        fill.shader = null
        if (artwork != null) drawArtworkRect(canvas, 92f, 91f, 228f, 128f, 7f)
        else {
            textPaint.textSize = 12f
            textPaint.color = 0xFF2A1C50.toInt()
            canvas.drawText("NOVA MIX TAPE", 160f, 115f, textPaint)
            textPaint.color = Color.WHITE
            textPaint.textSize = 10f
        }
        fill.shader = LinearGradient(65f, 146f, 255f, 230f,
            intArrayOf(0xFF161D2D.toInt(), 0xFF050913.toInt(), 0xFF20263A.toInt()),
            null, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(63f, 145f, 257f, 230f, 13f, 13f, fill)
        fill.shader = null
        stroke.color = 0xFF7B8599.toInt()
        stroke.strokeWidth = 2f
        canvas.drawRoundRect(63f, 145f, 257f, 230f, 13f, 13f, stroke)
        drawReel(canvas, 115f, 185f, 31f)
        drawReel(canvas, 205f, 185f, 31f)
        stroke.color = 0xFF9A8EC7.toInt()
        stroke.strokeWidth = 3f
        val tape = Path().apply {
            moveTo(115f, 185f)
            cubicTo(140f, 204f, 180f, 204f, 205f, 185f)
        }
        canvas.drawPath(tape, stroke)
        fill.color = 0xFFDAE0EA.toInt()
        canvas.drawCircle(59f, 88f, 3f, fill)
        canvas.drawCircle(261f, 88f, 3f, fill)
        canvas.drawCircle(59f, 231f, 3f, fill)
        canvas.drawCircle(261f, 231f, 3f, fill)
        canvas.restore()
    }

    private fun drawReel(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        fill.shader = LinearGradient(cx - radius, cy - radius, cx + radius, cy + radius,
            intArrayOf(0xFFE3DCEB.toInt(), 0xFF5A6071.toInt(), 0xFF191E2B.toInt()),
            null, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, radius, fill)
        fill.shader = null
        stroke.color = 0xFFB7B7CC.toInt()
        stroke.strokeWidth = 2f
        canvas.drawCircle(cx, cy, radius - 3f, stroke)
        fill.color = 0xFF252B3C.toInt()
        canvas.drawCircle(cx, cy, 10f, fill)
        stroke.color = 0xFF7B8295.toInt()
        stroke.strokeWidth = 3f
        for (angle in 0..5) {
            val a = Math.toRadians((angle * 60).toDouble())
            canvas.drawLine(
                cx + kotlin.math.cos(a).toFloat() * 12f,
                cy + kotlin.math.sin(a).toFloat() * 12f,
                cx + kotlin.math.cos(a).toFloat() * (radius - 7f),
                cy + kotlin.math.sin(a).toFloat() * (radius - 7f),
                stroke
            )
        }
    }

    private fun drawTurntable(canvas: Canvas) {
        fill.shader = LinearGradient(26f, 38f, 290f, 283f,
            intArrayOf(0xFF7B8291.toInt(), 0xFF30384A.toInt(), 0xFF101725.toInt()),
            null, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(25f, 38f, 295f, 282f, 23f, 23f, fill)
        fill.shader = null
        stroke.color = 0xFFB4BBD0.toInt()
        stroke.strokeWidth = 2f
        canvas.drawRoundRect(28f, 41f, 292f, 279f, 20f, 20f, stroke)
        fill.shader = LinearGradient(60f, 68f, 249f, 253f,
            intArrayOf(0xFF262A35.toInt(), 0xFF05070D.toInt(), 0xFF242735.toInt()),
            null, Shader.TileMode.CLAMP)
        canvas.drawCircle(147f, 164f, 105f, fill)
        fill.shader = null
        stroke.color = 0xFFBBC1D0.toInt()
        stroke.strokeWidth = 2f
        canvas.drawCircle(147f, 164f, 105f, stroke)
        stroke.color = 0x667E8A9F
        stroke.strokeWidth = 1f
        for (r in 82..99 step 3) canvas.drawCircle(147f, 164f, r.toFloat(), stroke)
        drawArtworkCircle(canvas, 147f, 164f, 36f)
        fill.color = 0xFFE7D8B6.toInt()
        canvas.drawCircle(147f, 164f, 4f, fill)
        // Tone arm and counterweight.
        stroke.color = 0xFFE7E3DB.toInt()
        stroke.strokeWidth = 7f
        stroke.strokeCap = Paint.Cap.ROUND
        canvas.drawLine(250f, 75f, 228f, 108f, stroke)
        canvas.drawLine(228f, 108f, 206f, 210f, stroke)
        stroke.strokeWidth = 3f
        canvas.drawLine(206f, 210f, 189f, 220f, stroke)
        fill.color = 0xFFE5E2DD.toInt()
        canvas.drawCircle(250f, 75f, 13f, fill)
        fill.color = 0xFF8B92A2.toInt()
        canvas.drawCircle(250f, 75f, 6f, fill)
        fill.color = 0xFFDAAD72.toInt()
        canvas.drawRoundRect(180f, 218f, 205f, 226f, 3f, 3f, fill)
        fill.color = 0xFF6F7A8E.toInt()
        canvas.drawCircle(268f, 244f, 11f, fill)
        textPaint.color = 0xFFC8CBE0.toInt()
        textPaint.textSize = 9f
        canvas.drawText("NOVA VINYL", 100f, 265f, textPaint)
        textPaint.color = Color.WHITE
        textPaint.textSize = 10f
    }

    private fun drawRadio(canvas: Canvas) {
        fill.shader = LinearGradient(34f, 88f, 287f, 240f,
            intArrayOf(0xFFB07B52.toInt(), 0xFF67412F.toInt(), 0xFF281F22.toInt()),
            null, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(31f, 89f, 289f, 239f, 24f, 24f, fill)
        fill.shader = null
        stroke.color = 0xFFE7BE8C.toInt()
        stroke.strokeWidth = 2f
        canvas.drawRoundRect(33f, 91f, 287f, 237f, 22f, 22f, stroke)
        // Speaker cone.
        fill.shader = LinearGradient(45f, 115f, 160f, 217f,
            intArrayOf(0xFF33313A.toInt(), 0xFF0C1019.toInt(), 0xFF5A4A3D.toInt()),
            null, Shader.TileMode.CLAMP)
        canvas.drawCircle(104f, 169f, 58f, fill)
        fill.shader = null
        stroke.color = 0xFFB18B61.toInt()
        stroke.strokeWidth = 3f
        for (r in 45..56 step 5) canvas.drawCircle(104f, 169f, r.toFloat(), stroke)
        fill.color = 0xFF111722.toInt()
        canvas.drawCircle(104f, 169f, 23f, fill)
        stroke.color = 0xFF9B7A58.toInt()
        stroke.strokeWidth = 1f
        for (offset in -35..35 step 12) {
            canvas.drawLine(104f + offset, 116f, 104f + offset, 222f, stroke)
            canvas.drawLine(51f, 169f + offset, 157f, 169f + offset, stroke)
        }
        // Tuning window, display and knobs.
        fill.color = 0xFF191D26.toInt()
        canvas.drawRoundRect(174f, 111f, 267f, 143f, 8f, 8f, fill)
        fill.shader = LinearGradient(178f, 117f, 263f, 137f,
            intArrayOf(0xFF7653A9.toInt(), 0xFF273B58.toInt()), null, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(179f, 116f, 262f, 138f, 5f, 5f, fill)
        fill.shader = null
        stroke.color = 0xFFD5A56D.toInt()
        stroke.strokeWidth = 2f
        canvas.drawLine(219f, 118f, 219f, 136f, stroke)
        fill.color = 0xFFE6C08D.toInt()
        canvas.drawCircle(195f, 187f, 18f, fill)
        canvas.drawCircle(247f, 187f, 18f, fill)
        fill.color = 0xFF4B312A.toInt()
        canvas.drawCircle(195f, 187f, 7f, fill)
        canvas.drawCircle(247f, 187f, 7f, fill)
        if (artwork != null) drawArtworkRect(canvas, 177f, 211f, 263f, 227f, 4f)
        textPaint.color = 0xFFFFE4B5.toInt()
        textPaint.textSize = 9f
        canvas.drawText("NOVA RADIO", 219f, 103f, textPaint)
        textPaint.color = Color.WHITE
        textPaint.textSize = 10f
    }

    private fun drawMidnight(canvas: Canvas) {
        fill.shader = LinearGradient(24f, 42f, 296f, 280f,
            intArrayOf(0xFF4B2E91.toInt(), 0xFF171C3A.toInt(), 0xFF070B18.toInt()),
            null, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(26f, 35f, 294f, 285f, 25f, 25f, fill)
        fill.shader = null
        stroke.color = 0xFFAA91FF.toInt()
        stroke.strokeWidth = 2f
        canvas.drawRoundRect(30f, 39f, 290f, 281f, 21f, 21f, stroke)
        fill.color = 0x4429D8FF
        canvas.drawCircle(160f, 153f, 102f, fill)
        stroke.color = 0xFF8E6AFF.toInt()
        stroke.strokeWidth = 3f
        canvas.drawCircle(160f, 153f, 96f, stroke)
        stroke.color = 0x887FE8FF.toInt()
        stroke.strokeWidth = 1.5f
        canvas.drawCircle(160f, 153f, 84f, stroke)
        drawArtworkCircle(canvas, 160f, 153f, 60f)
        fill.shader = LinearGradient(0f, 215f, 0f, 266f,
            intArrayOf(0xCC17152F.toInt(), 0xFF090C18.toInt()), null, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(48f, 216f, 272f, 260f, 14f, 14f, fill)
        fill.shader = null
        textPaint.color = 0xFFE4DCFF.toInt()
        textPaint.textSize = 11f
        canvas.drawText("N O V A   M I D N I G H T", 160f, 244f, textPaint)
        textPaint.color = Color.WHITE
        textPaint.textSize = 10f
    }

    private fun drawArtworkCircle(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val path = Path().apply { addCircle(cx, cy, radius, Path.Direction.CW) }
        val save = canvas.save()
        canvas.clipPath(path)
        val bitmap = artwork
        if (bitmap != null && !bitmap.isRecycled) {
            val size = minOf(bitmap.width, bitmap.height)
            val left = (bitmap.width - size) / 2
            val top = (bitmap.height - size) / 2
            srcRect.set(left, top, left + size, top + size)
            destRect.set(cx - radius, cy - radius, cx + radius, cy + radius)
            canvas.drawBitmap(bitmap, srcRect, destRect, artPaint)
        } else {
            fill.shader = LinearGradient(cx - radius, cy - radius, cx + radius, cy + radius,
                intArrayOf(0xFF9D6CFF.toInt(), 0xFF29234F.toInt(), 0xFF111A2F.toInt()), null, Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy, radius, fill)
            fill.shader = null
            textPaint.textSize = radius * 0.65f
            textPaint.color = Color.WHITE
            canvas.drawText("♫", cx, cy + radius * 0.22f, textPaint)
            textPaint.textSize = 10f
        }
        canvas.restoreToCount(save)
        stroke.color = 0x99F5E7FF.toInt()
        stroke.strokeWidth = 1.5f
        canvas.drawCircle(cx, cy, radius, stroke)
    }

    private fun drawArtworkRect(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float, radius: Float) {
        val path = Path().apply {
            addRoundRect(RectF(left, top, right, bottom), radius, radius, Path.Direction.CW)
        }
        val save = canvas.save()
        canvas.clipPath(path)
        val bitmap = artwork
        if (bitmap != null && !bitmap.isRecycled) {
            val viewW = right - left
            val viewH = bottom - top
            val scale = maxOf(viewW / bitmap.width, viewH / bitmap.height)
            val cropW = (viewW / scale).toInt().coerceAtLeast(1)
            val cropH = (viewH / scale).toInt().coerceAtLeast(1)
            val sx = (bitmap.width - cropW) / 2
            val sy = (bitmap.height - cropH) / 2
            srcRect.set(sx, sy, sx + cropW, sy + cropH)
            destRect.set(left, top, right, bottom)
            canvas.drawBitmap(bitmap, srcRect, destRect, artPaint)
        } else {
            fill.color = 0xFF986BDF.toInt()
            canvas.drawRoundRect(left, top, right, bottom, radius, radius, fill)
        }
        canvas.restoreToCount(save)
    }
}
