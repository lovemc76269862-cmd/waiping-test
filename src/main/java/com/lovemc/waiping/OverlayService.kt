package com.lovemc.waiping

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService

class OverlayService : Service(), RecognitionListener {

    private lateinit var wm: WindowManager
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var root: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var txt: TextView
    private lateinit var status: TextView
    private lateinit var modeBtn: TextView
    private lateinit var playBtn: TextView
    private val main = Handler(Looper.getMainLooper())

    private var script = ""
    private var clean = StringBuilder()
    private val map = ArrayList<Int>()
    private var pos = 0

    private var followMode = false
    private var playing = false
    private var speed = 40f
    private var fontSp = 28f
    private var acc = 0f
    private var lastFrame = 0L
    private var lastY = 0f
    private var heard = false

    private var model: Model? = null
    private var speech: SpeechService? = null
    private var modelLoading = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (!startFg()) {
            stopSelf()
            return
        }
        val p = getSharedPreferences(Prefs.NAME, MODE_PRIVATE)
        loadScript(p)
        speed = p.getFloat(Prefs.SPEED, 40f)
        fontSp = p.getFloat(Prefs.FONT, 28f)
        buildWindow(p)
        openCamera(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (::root.isInitialized) {
            val old = script
            loadScript(getSharedPreferences(Prefs.NAME, MODE_PRIVATE))
            if (old != script) {
                pos = 0
                render()
                scroll.scrollTo(0, 0)
            }
        }
        return START_NOT_STICKY
    }

    private fun loadScript(p: SharedPreferences) {
        script = p.getString(Prefs.SCRIPT, "") ?: ""
        if (script.isBlank()) script = "还没有稿子，请回到“悬浮提词器”APP 里粘贴稿子。"
        clean = StringBuilder()
        map.clear()
        script.forEachIndexed { i, c ->
            if (Character.isLetterOrDigit(c)) {
                clean.append(c.lowercaseChar())
                map.add(i)
            }
        }
    }

    private fun startFg(): Boolean {
        return try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("tici", "悬浮提词", NotificationManager.IMPORTANCE_LOW))
            val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val n = Notification.Builder(this, "tici")
                .setContentTitle("悬浮提词器运行中")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentIntent(open)
                .build()
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(1, n)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun dp(v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    private fun btn(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 15f
        setTextColor(Color.WHITE)
        setPadding(dp(10f), dp(7f), dp(10f), dp(7f))
        setBackgroundColor(Color.argb(170, 60, 60, 60))
        setOnClickListener { onClick() }
    }

    private fun dragger(onMove: (Int) -> Unit) = View.OnTouchListener { _, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastY = e.rawY
                true
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = (e.rawY - lastY).toInt()
                if (dy != 0) {
                    onMove(dy)
                    lastY += dy
                }
                true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                saveWindow()
                true
            }
            else -> false
        }
    }

    private fun buildWindow(p: SharedPreferences) {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val dm = resources.displayMetrics
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            p.getInt(Prefs.H, (dm.heightPixels * 0.38f).toInt()),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 0
        params.y = p.getInt(Prefs.Y, dp(40f))

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(140, 0, 0, 0))
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val move = btn("✥ 拖动") {}
        modeBtn = btn("模式：匀速") { toggleMode() }
        playBtn = btn("▶ 开始") { togglePlay() }
        bar.addView(move)
        bar.addView(modeBtn)
        bar.addView(playBtn)
        bar.addView(btn("慢") { changeSpeed(-5f) })
        bar.addView(btn("快") { changeSpeed(5f) })
        bar.addView(btn("A-") { changeFont(-2f) })
        bar.addView(btn("A+") { changeFont(2f) })
        bar.addView(btn("⤒ 开头") { toTop() })
        bar.addView(btn("📷 相机") { openCamera(this) })
        bar.addView(btn("✕ 关闭") { stopSelf() })
        root.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(bar)
        })

        status = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.YELLOW)
            setPadding(dp(8f), dp(2f), dp(8f), dp(2f))
        }
        root.addView(status)

        txt = TextView(this).apply {
            setTextColor(Color.WHITE)
            setPadding(dp(12f), dp(4f), dp(12f), dp(400f))
            setLineSpacing(0f, 1.25f)
            setShadowLayer(4f, 0f, 0f, Color.BLACK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSp)
        }
        scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(txt)
        }
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val resize = TextView(this).apply {
            text = "═══ 上下拖这里调整高度 ═══"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(6f), 0, dp(6f))
            setBackgroundColor(Color.argb(170, 40, 40, 40))
        }
        root.addView(resize)

        move.setOnTouchListener(dragger { dy ->
            params.y = (params.y + dy).coerceAtLeast(0)
            wm.updateViewLayout(root, params)
        })
        resize.setOnTouchListener(dragger { dy ->
            params.height = (params.height + dy).coerceIn(dp(120f), resources.displayMetrics.heightPixels)
            wm.updateViewLayout(root, params)
        })

        wm.addView(root, params)
        render()
        updateButtons()
    }

    private fun saveWindow() {
        getSharedPreferences(Prefs.NAME, MODE_PRIVATE).edit()
            .putInt(Prefs.Y, params.y).putInt(Prefs.H, params.height).apply()
    }

    private fun saveSettings() {
        getSharedPreferences(Prefs.NAME, MODE_PRIVATE).edit()
            .putFloat(Prefs.SPEED, speed).putFloat(Prefs.FONT, fontSp).apply()
    }

    // ---------- 显示 ----------
    private fun render() {
        if (!followMode || map.isEmpty()) {
            txt.text = script
            return
        }
        val s = SpannableString(script)
        val doneEnd = if (pos > 0) map[pos - 1] + 1 else 0
        if (doneEnd > 0) {
            s.setSpan(ForegroundColorSpan(Color.GRAY), 0, doneEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (pos < map.size) {
            val a = map[pos]
            val b = map[minOf(pos + 5, map.size - 1)] + 1
            s.setSpan(ForegroundColorSpan(Color.YELLOW), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        txt.text = s
    }

    private fun scrollToPos() {
        val layout = txt.layout ?: return
        val idx = if (pos < map.size) map[pos] else script.length - 1
        if (idx < 0) return
        val line = layout.getLineForOffset(idx)
        val y = layout.getLineTop(line) + txt.paddingTop - (scroll.height * 0.25f).toInt()
        scroll.smoothScrollTo(0, y.coerceAtLeast(0))
    }

    private fun updateButtons() {
        modeBtn.text = if (followMode) "模式：AI跟读" else "模式：匀速"
        playBtn.text = if (playing) "⏸ 暂停" else "▶ 开始"
        playBtn.setBackgroundColor(
            if (playing) Color.argb(220, 220, 50, 50) else Color.argb(220, 240, 140, 30)
        )
    }

    private fun toggleMode() {
        if (playing) togglePlay()
        followMode = !followMode
        status.text = if (followMode) "AI跟读：点“开始”，照着稿子读，字会跟着你走" else ""
        render()
        updateButtons()
    }

    private fun togglePlay() {
        playing = !playing
        if (followMode) {
            if (playing) startListening() else stopListening()
        } else if (playing) {
            lastFrame = 0L
            acc = 0f
            Choreographer.getInstance().postFrameCallback(frame)
        }
        updateButtons()
    }

    private fun changeSpeed(d: Float) {
        speed = (speed + d).coerceIn(5f, 300f)
        status.text = "滚动速度：${speed.toInt()}"
        saveSettings()
    }

    private fun changeFont(d: Float) {
        fontSp = (fontSp + d).coerceIn(14f, 72f)
        txt.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSp)
        saveSettings()
        if (followMode) txt.post { scrollToPos() }
    }

    private fun toTop() {
        pos = 0
        render()
        scroll.scrollTo(0, 0)
    }

    // ---------- 匀速滚动 ----------
    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!playing || followMode) return
            if (lastFrame != 0L) {
                acc += (frameTimeNanos - lastFrame) / 1_000_000_000f * speed * resources.displayMetrics.density
                val step = acc.toInt()
                if (step > 0) {
                    scroll.scrollBy(0, step)
                    acc -= step
                }
            }
            lastFrame = frameTimeNanos
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    // ---------- AI 跟读 ----------
    private fun follow(heardText: String) {
        val h = heardText.filter { Character.isLetterOrDigit(it) }.lowercase()
        if (h.length < 2) return
        val from = (pos - 10).coerceAtLeast(0)
        val to = (pos + 120).coerceAtMost(clean.length)
        if (from >= to) return
        val window = clean.substring(from, to)
        for (n in minOf(6, h.length) downTo 2) {
            val i = window.indexOf(h.takeLast(n))
            if (i >= 0) {
                val np = from + i + n
                if (np > pos) {
                    pos = np
                    render()
                    txt.post { scrollToPos() }
                }
                return
            }
        }
    }

    private fun startListening() {
        val m = model
        if (m == null) {
            if (!modelLoading) {
                modelLoading = true
                status.text = "正在加载语音模型（第一次要等几秒）…"
                StorageService.unpack(this, "model-cn", "model",
                    { loaded ->
                        main.post {
                            model = loaded
                            modelLoading = false
                            if (playing && followMode) startListening()
                        }
                    },
                    { e ->
                        main.post {
                            modelLoading = false
                            playing = false
                            status.text = "语音模型加载失败：${e.message}"
                            updateButtons()
                        }
                    })
            }
            return
        }
        try {
            speech?.stop()
            speech?.shutdown()
            val s = SpeechService(Recognizer(m, 16000.0f), 16000.0f)
            speech = s
            s.startListening(this)
            heard = false
            status.text = "正在听…照着稿子读"
            main.postDelayed({
                if (playing && followMode && !heard) {
                    status.text = "8 秒没听到声音：可能麦克风被相机录像占用了"
                }
            }, 8000)
        } catch (e: Exception) {
            playing = false
            status.text = "麦克风打不开：${e.message}"
            updateButtons()
        }
    }

    private fun stopListening() {
        try {
            speech?.stop()
            speech?.shutdown()
        } catch (_: Exception) {
        }
        speech = null
        status.text = "已暂停"
    }

    override fun onPartialResult(hypothesis: String?) {
        val t = parse(hypothesis, "partial")
        if (t.isNotBlank()) {
            heard = true
            status.text = "听到：" + t.replace(" ", "").takeLast(14)
            follow(t)
        }
    }

    override fun onResult(hypothesis: String?) {
        val t = parse(hypothesis, "text")
        if (t.isNotBlank()) {
            heard = true
            follow(t)
        }
    }

    override fun onFinalResult(hypothesis: String?) {
        onResult(hypothesis)
    }

    override fun onError(exception: Exception?) {
        status.text = "语音出错：${exception?.message}"
    }

    override fun onTimeout() {}

    private fun parse(json: String?, key: String): String =
        try {
            JSONObject(json ?: "{}").optString(key, "")
        } catch (e: Exception) {
            ""
        }

    override fun onDestroy() {
        playing = false
        if (::root.isInitialized) {
            stopListening()
            try {
                wm.removeView(root)
            } catch (_: Exception) {
            }
        }
        super.onDestroy()
    }

    companion object {
        fun openCamera(ctx: Context) {
            for (action in listOf(MediaStore.INTENT_ACTION_VIDEO_CAMERA, MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)) {
                try {
                    ctx.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    return
                } catch (_: Exception) {
                }
            }
        }
    }
}
