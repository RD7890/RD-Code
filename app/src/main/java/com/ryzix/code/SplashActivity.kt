package com.ryzix.code

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.animation.OvershootInterpolator
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.widget.ImageView
import android.widget.TextView
import android.app.Activity

@SuppressLint("CustomSplashScreen")
class SplashActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        val logo = findViewById<ImageView>(R.id.logoImage)
        val text = findViewById<TextView>(R.id.splashText)

        logo.scaleX = 0.4f; logo.scaleY = 0.4f; logo.alpha = 0f
        text.alpha = 0f; text.translationY = 24f

        AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(logo, "scaleX", 0.4f, 1f),
                ObjectAnimator.ofFloat(logo, "scaleY", 0.4f, 1f),
                ObjectAnimator.ofFloat(logo, "alpha", 0f, 1f)
            )
            duration = 900
            interpolator = OvershootInterpolator()
            start()
        }

        AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(text, "alpha", 0f, 1f),
                ObjectAnimator.ofFloat(text, "translationY", 24f, 0f)
            )
            duration = 700; startDelay = 450; start()
        }

        Handler(Looper.getMainLooper()).postDelayed({
            startActivity(Intent(this, MainActivity::class.java))
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            finish()
        }, 2200)
    }
}
