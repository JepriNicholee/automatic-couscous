package com.build.mythos

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Layar sederhana yang menampilkan penyebab crash. Sengaja tidak memakai Palette/tema aplikasi agar tidak ikut crash. */
class CrashActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val trace = intent?.getStringExtra(CrashReporter.EXTRA_TRACE).orEmpty().ifEmpty { "(tidak ada keterangan)" }
        val d = resources.displayMetrics.density
        fun px(v: Int) = (v * d).toInt()

        val title = TextView(this).apply {
            text = "Aplikasi berhenti karena galat"
            textSize = 18f
            setTextColor(Color.BLACK)
            typeface = Typeface.DEFAULT_BOLD
        }
        val hint = TextView(this).apply {
            text = "Tekan Salin lalu kirim teks ini ke pengembang."
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setPadding(0, px(4), 0, px(8))
        }
        val body = TextView(this).apply {
            text = trace
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.BLACK)
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(this).apply { addView(body) }
        val copy = Button(this).apply {
            text = "Salin"
            setOnClickListener {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("crash", trace))
                Toast.makeText(this@CrashActivity, "Tersalin", Toast.LENGTH_SHORT).show()
            }
        }
        val close = Button(this).apply {
            text = "Tutup"
            setOnClickListener { finishAndRemoveTask() }
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            addView(copy)
            addView(close)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(px(16), px(40), px(16), px(16))
            addView(title)
            addView(hint)
            addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(buttons)
        }
        setContentView(root)
    }
}
