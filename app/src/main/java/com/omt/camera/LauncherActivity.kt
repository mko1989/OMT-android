package com.omt.camera

import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.card.MaterialCardView

class LauncherActivity : AppCompatActivity() {

    companion object {
        private const val PREFS_NAME = "omt_camera_prefs"
        private const val KEY_STREAM_NAME = "stream_name"
    }

    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_launcher)

        val rootLayout = findViewById<View>(R.id.launcherRoot)
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            val pad = (32 * resources.displayMetrics.density).toInt()
            view.setPadding(insets.left + pad, insets.top + pad, insets.right + pad, insets.bottom + pad)
            WindowInsetsCompat.CONSUMED
        }

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val streamNameEdit = findViewById<EditText>(R.id.streamNameEdit)
        streamNameEdit.setText(prefs.getString(KEY_STREAM_NAME, getString(R.string.default_stream_name)) ?: getString(R.string.default_stream_name))
        streamNameEdit.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) saveStreamName(streamNameEdit)
        }

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

    private fun saveStreamName(edit: EditText) {
        prefs.edit().putString(KEY_STREAM_NAME, edit.text.toString().trim()).apply()
    }

    override fun onPause() {
        super.onPause()
        findViewById<EditText>(R.id.streamNameEdit)?.let { saveStreamName(it) }
    }
}
