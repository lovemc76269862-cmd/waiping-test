package com.lovemc.waiping

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.window.area.WindowAreaCapability
import androidx.window.area.WindowAreaController
import androidx.window.area.WindowAreaInfo
import androidx.window.area.WindowAreaPresentationSessionCallback
import androidx.window.area.WindowAreaSessionPresenter
import androidx.window.core.ExperimentalWindowApi
import kotlinx.coroutines.launch
import java.util.concurrent.Executor

@OptIn(ExperimentalWindowApi::class)
class MainActivity : ComponentActivity(), WindowAreaPresentationSessionCallback {

    private lateinit var log: TextView
    private val controller by lazy { WindowAreaController.getOrCreate() }
    private var rearInfo: WindowAreaInfo? = null
    private var session: WindowAreaSessionPresenter? = null
    private var presentation: Presentation? = null
    private val uiExecutor = Executor { runOnUiThread(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 80, 40, 40)
            setBackgroundColor(Color.BLACK)
        }
        root.addView(TextView(this).apply {
            text = "外屏测试：请把手机展开后再点按钮"
            textSize = 20f
            setTextColor(Color.YELLOW)
        })
        root.addView(button("方法1：系统外屏接口") { tryMethod1() })
        root.addView(button("方法2：第二块屏幕") { tryMethod2() })
        root.addView(button("停止显示") { stopAll() })
        log = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.WHITE)
        }
        root.addView(ScrollView(this).apply { addView(log) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        addLog("手机：${Build.MANUFACTURER} ${Build.MODEL}，安卓 SDK ${Build.VERSION.SDK_INT}")
        listDisplays()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    controller.windowAreaInfos.collect { infos ->
                        rearInfo = infos.firstOrNull { it.type == WindowAreaInfo.Type.TYPE_REAR_FACING }
                        val st = rearInfo
                            ?.getCapability(WindowAreaCapability.Operation.OPERATION_PRESENT_ON_AREA)
                            ?.status
                        addLog("外屏接口：找到 ${infos.size} 个区域，外屏显示状态 = ${st ?: "不支持"}")
                    }
                } catch (e: Throwable) {
                    addLog("外屏接口读取出错：${e.message}")
                }
            }
        }
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 17f
        setOnClickListener { onClick() }
    }

    private fun bigText(ctx: Context, msg: String): View = TextView(ctx).apply {
        text = msg
        textSize = 34f
        gravity = Gravity.CENTER
        setTextColor(Color.YELLOW)
        setBackgroundColor(Color.BLACK)
    }

    private fun addLog(msg: String) = runOnUiThread { log.append("• $msg\n\n") }

    private fun listDisplays() {
        val dm = getSystemService(DisplayManager::class.java)
        val all = dm.displays
        addLog("系统里有 ${all.size} 块屏幕：" + all.joinToString("；") { "#${it.displayId} ${it.name}" })
        val pres = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
        addLog("可以投放内容的屏幕：${pres.size} 块")
    }

    // 方法1：安卓官方的“后屏/外屏显示”接口
    private fun tryMethod1() {
        val info = rearInfo
        if (info == null) {
            addLog("方法1：系统没有给第三方 APP 开放外屏接口")
            return
        }
        try {
            controller.presentContentOnWindowArea(info.token, this, uiExecutor, this)
            addLog("方法1：已发出请求，等系统回应…")
        } catch (e: Throwable) {
            addLog("方法1 失败：${e.message}")
        }
    }

    override fun onSessionStarted(session: WindowAreaSessionPresenter) {
        this.session = session
        session.setContentView(bigText(session.context, "方法1 成功！\n外屏可以显示提词"))
        addLog("方法1：成功！看看外屏有没有出现黄字")
    }

    override fun onSessionEnded(t: Throwable?) {
        session = null
        addLog("方法1：已结束" + (t?.let { "，原因：${it.message}" } ?: ""))
    }

    override fun onContainerVisibilityChanged(isVisible: Boolean) {
        addLog("方法1：外屏内容可见 = $isVisible")
    }

    // 方法2：把外屏当成第二块显示器
    private fun tryMethod2() {
        listDisplays()
        val dm = getSystemService(DisplayManager::class.java)
        @Suppress("DEPRECATION")
        val mine = windowManager.defaultDisplay.displayId
        val other = dm.displays.firstOrNull { it.displayId != mine }
        if (other == null) {
            addLog("方法2：系统只给了一块屏幕，用不了")
            return
        }
        try {
            presentation?.dismiss()
            val p = Presentation(this, other)
            p.setContentView(bigText(p.context, "方法2 成功！\n外屏可以显示提词"))
            p.show()
            presentation = p
            addLog("方法2：已显示到 #${other.displayId} ${other.name}，看看外屏")
        } catch (e: Throwable) {
            addLog("方法2 失败：${e.message}")
        }
    }

    private fun stopAll() {
        try { session?.close() } catch (_: Throwable) {}
        session = null
        presentation?.dismiss()
        presentation = null
        addLog("已停止")
    }

    override fun onDestroy() {
        stopAll()
        super.onDestroy()
    }
}
