package com.omt.camera

import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.google.android.material.card.MaterialCardView

class LauncherActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_launcher)

        val cameraCard = findViewById<MaterialCardView>(R.id.cameraCard)
        val viewerCard = findViewById<MaterialCardView>(R.id.viewerCard)
        val tvHint = findViewById<TextView>(R.id.tvHintText)

        val isTV = isAndroidTV()

        if (isTV) {
            // No camera on TV — disable card
            cameraCard.alpha = 0.35f
            cameraCard.isClickable = false
            cameraCard.isFocusable = false
            tvHint.visibility = View.VISIBLE
        } else {
            cameraCard.setOnClickListener {
                startActivity(Intent(this, MainActivity::class.java))
            }
        }

        viewerCard.setOnClickListener {
            startActivity(Intent(this, ViewerActivity::class.java))
        }

        // On TV, auto-focus the viewer card for D-pad navigation
        if (isTV) {
            viewerCard.requestFocus()
        }
    }

    private fun isAndroidTV(): Boolean {
        val uiModeManager = getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        return uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
    }
}
