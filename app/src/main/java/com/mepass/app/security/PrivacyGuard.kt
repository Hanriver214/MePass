package com.mepass.app.security

import android.app.Activity
import android.view.WindowManager

/**
 * 隐私防护（极简版）
 *
 * 仅提供两项核心能力：
 * 1. [applySecureWindow]：FLAG_SECURE 防截屏/录屏
 * 2. [wipeCallbacks]：敏感数据清理回调注册表，供生命周期调用
 */
object PrivacyGuard {
    private val wipeCallbacks = mutableListOf<() -> Unit>()

    /** 注册敏感数据清理回调 */
    fun registerWipeCallback(callback: () -> Unit) {
        synchronized(wipeCallbacks) {
            wipeCallbacks.add(callback)
        }
    }

    /** 注销清理回调 */
    fun unregisterWipeCallback(callback: () -> Unit) {
        synchronized(wipeCallbacks) {
            wipeCallbacks.remove(callback)
        }
    }

    /** 执行所有清理回调 */
    fun wipeAllSensitiveData() {
        synchronized(wipeCallbacks) {
            wipeCallbacks.forEach { it() }
        }
    }

    /** 应用 FLAG_SECURE 防截屏 */
    fun applySecureWindow(activity: Activity) {
        activity.window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
    }
}
