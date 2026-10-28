package com.lovemc.waiping

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

// 只用来让系统允许“录像时同时使用麦克风”，不读取任何屏幕内容
class TiciA11yService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
}
