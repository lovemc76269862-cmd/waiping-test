package com.lovemc.waiping

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

object Prefs {
    const val NAME = "tici"
    const val SCRIPT = "script"
    const val SPEED = "speed"
    const val FONT = "font"
    const val H = "h"
    const val Y = "y"
}

class MainActivity : Activity() {
    private lateinit var edit: EditText
    private lateinit var tip: TextView
    private lateinit var a11yBtn: Button
    private var waitingOverlay = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences(Prefs.NAME, MODE_PRIVATE)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 80, 40, 40)
            setBackgroundColor(Color.BLACK)
        }
        root.addView(TextView(this).apply {
            text = "悬浮提词器"
            textSize = 24f
            setTextColor(Color.YELLOW)
        })
        tip = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.LTGRAY)
            text = "把稿子粘贴到下面，点“开始悬浮提词”，会自动打开相机。"
        }
        root.addView(tip)
        edit = EditText(this).apply {
            setText(prefs.getString(Prefs.SCRIPT, ""))
            hint = "在这里粘贴你的稿子"
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.TOP
            setBackgroundColor(Color.rgb(30, 30, 30))
        }
        root.addView(edit, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(Button(this).apply {
            text = "开始悬浮提词"
            textSize = 20f
            setOnClickListener { start() }
        })
        a11yBtn = Button(this).apply {
            textSize = 16f
            setOnClickListener {
                Toast.makeText(this@MainActivity, "请找到“悬浮提词器（录像时跟读）”，把它打开", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        root.addView(a11yBtn)
        root.addView(Button(this).apply {
            text = "关闭悬浮窗"
            setOnClickListener { stopService(Intent(this@MainActivity, OverlayService::class.java)) }
        })
        setContentView(root)
    }

    private fun a11yOn(): Boolean {
        val list = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        return list.contains("$packageName/")
    }

    override fun onResume() {
        super.onResume()
        a11yBtn.text = if (a11yOn()) "✅ 录像时跟读：已开启" else "⚠ 录像时跟读：点这里开启（无障碍）"
        if (waitingOverlay && Settings.canDrawOverlays(this)) {
            waitingOverlay = false
            start()
        }
    }

    override fun onPause() {
        super.onPause()
        save()
    }

    private fun save() {
        getSharedPreferences(Prefs.NAME, MODE_PRIVATE).edit()
            .putString(Prefs.SCRIPT, edit.text.toString()).apply()
    }

    private fun start() {
        save()
        if (!Settings.canDrawOverlays(this)) {
            tip.text = "请在打开的页面里找到“悬浮提词器”，打开“悬浮窗/显示在其他应用上层”，然后返回。"
            Toast.makeText(this, "请打开悬浮窗权限", Toast.LENGTH_LONG).show()
            waitingOverlay = true
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        val need = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            need += Manifest.permission.RECORD_AUDIO
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            need += Manifest.permission.POST_NOTIFICATIONS
        }
        if (need.isNotEmpty()) {
            requestPermissions(need.toTypedArray(), 1)
            return
        }
        launchOverlay()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            launchOverlay()
        } else {
            tip.text = "需要麦克风权限才能用 AI 跟读，请再点一次“开始悬浮提词”，选择允许。"
        }
    }

    private fun launchOverlay() {
        val i = Intent(this, OverlayService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
    }
}
