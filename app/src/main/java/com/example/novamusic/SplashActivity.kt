package com.example.novamusic

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** A very short, native launch screen. It performs no disk or network work. */
class SplashActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var launchRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(8, 7, 20)
        window.navigationBarColor = Color.rgb(8, 7, 20)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28f), dp(24f), dp(28f), dp(24f))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(27, 17, 53), Color.rgb(8, 9, 21), Color.rgb(6, 8, 17))
            )
        }

        val logoFrame = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(75, 38, 139), Color.rgb(18, 16, 42))
            ).apply {
                cornerRadius = 34f * resources.displayMetrics.density
                setStroke((1.4f * resources.displayMetrics.density).toInt().coerceAtLeast(1), Color.rgb(179, 112, 255))
            }
            elevation = 12f * resources.displayMetrics.density
            alpha = 0f
            scaleX = 0.78f
            scaleY = 0.78f
            addView(ImageView(this@SplashActivity).apply {
                setImageResource(R.mipmap.ic_nova_music)
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = LinearLayout.LayoutParams(
                    (112f * resources.displayMetrics.density).toInt(),
                    (112f * resources.displayMetrics.density).toInt()
                )
            })
        }
        root.addView(logoFrame, LinearLayout.LayoutParams(
            (132f * resources.displayMetrics.density).toInt(),
            (132f * resources.displayMetrics.density).toInt()
        ))

        val title = TextView(this).apply {
            text = "NOVA MUSIC"
            textSize = 26f
            letterSpacing = 0.16f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            alpha = 0f
            setPadding(0, (24f * resources.displayMetrics.density).toInt(), 0, 0)
        }
        root.addView(title)

        val tagline = TextView(this).apply {
            text = "YOUR MUSIC. YOUR VIBE."
            textSize = 10f
            letterSpacing = 0.22f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Color.rgb(185, 155, 255))
            gravity = Gravity.CENTER
            alpha = 0f
            setPadding(0, (8f * resources.displayMetrics.density).toInt(), 0, 0)
        }
        root.addView(tagline)

        val loading = TextView(this).apply {
            text = "Preparing your soundscape…"
            textSize = 11f
            setTextColor(Color.rgb(130, 135, 160))
            gravity = Gravity.CENTER
            alpha = 0f
            setPadding(0, (58f * resources.displayMetrics.density).toInt(), 0, 0)
        }
        root.addView(loading)
        setContentView(root)

        logoFrame.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(420L).start()
        title.animate().alpha(1f).setStartDelay(150L).setDuration(360L).start()
        tagline.animate().alpha(1f).setStartDelay(250L).setDuration(360L).start()
        loading.animate().alpha(1f).setStartDelay(320L).setDuration(300L).start()

        launchRunnable = Runnable {
            if (!isFinishing && !isDestroyed) {
                startActivity(Intent(this, MainActivity::class.java))
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                finish()
            }
        }
        handler.postDelayed(launchRunnable!!, 680L)
    }

    private fun dp(value: Float): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        launchRunnable?.let(handler::removeCallbacks)
        launchRunnable = null
        super.onDestroy()
    }
}
