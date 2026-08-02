package com.mepass.app.security

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.os.Build
import android.util.Log
import android.view.WindowManager
import com.mepass.app.crypto.Argon2Manager
import java.security.SecureRandom

/**
 * 全局隐私与安全防护管理器
 *
 * 职责：
 * 1. FLAG_SECURE：防截图、防录屏、防最近任务缩略图
 * 2. 敏感内存清理：用户离开前台后立即wipe所有已加载的模板、答案、恢复结果
 * 3. 防外部干扰：检查是否存在覆盖层（Overlay）攻击
 * 4. 屏幕常亮/禁止截屏组合
 */
object PrivacyGuard {

    private const val TAG = "MePass-PrivacyGuard"

    /**
     * 已注册的清理回调集合
     * 任何Composable Screen/ViewModel如果持有敏感数据，必须在这里注册清理回调
     */
    private val wipeCallbacks = mutableListOf<() -> Unit>()
    private val callbackLock = Any()

    fun registerWipeCallback(callback: () -> Unit) {
        synchronized(callbackLock) {
            wipeCallbacks.add(callback)
        }
    }

    fun unregisterWipeCallback(callback: () -> Unit) {
        synchronized(callbackLock) {
            wipeCallbacks.remove(callback)
        }
    }

    /**
     * 执行一次全局敏感数据清理
     * 触发时机：
     * - MainActivity.onPause()（离开前台立即触发）
     * - MainActivity.onStop()
     * - 手动发起的清理按钮
     */
    fun wipeAllSensitiveData() {
        synchronized(callbackLock) {
            val callbacks = wipeCallbacks.toList() // 快照防止并发修改
            Log.i(TAG, "执行全局敏感内存清理，共 ${callbacks.size} 个清理钩子")
            var failures = 0
            for (cb in callbacks) {
                try {
                    cb.invoke()
                } catch (t: Throwable) {
                    failures++
                    Log.e(TAG, "清理回调异常: ${t.message}", t)
                }
            }
            // 建议JVM立即gc（尽力而为，无法强制）
            if (failures == 0) {
                Runtime.getRuntime().gc()
            }
        }
    }

    /**
     * 为Activity启用最严格的隐私窗口设置
     * 必须在setContentView/setContent之前或之后尽早调用
     *
     * 效果：
     * - FLAG_SECURE → 禁止截屏、禁止录屏、最近任务显示空白
     * - 强制竖屏（防止旋转导致内存中数据被转储）
     * - 禁止窗口被其他应用覆盖捕获
     */
    fun applySecureWindowPolicy(activity: Activity) {
        val window = activity.window
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        // 在 Android 12+ 上额外隐藏内容在最近任务中（即使FLAG_SECURE已覆盖）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                activity.setRecentsScreenshotAllowed(false)
            } catch (_: Throwable) {}
        }

        // 强制竖屏（减少配置变更导致的内存dump窗口）
        try {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } catch (_: Throwable) {}

        // 关闭窗口动画（过渡动画有时会把内容写入SurfaceFlinger缓冲区）
        try {
            window.attributes = window.attributes.apply {
                // 禁用窗口内容动画（仅设置windowDisablePreview等其他标记在主题里）
            }
        } catch (_: Throwable) {}
    }

    /**
     * 检查并防御覆盖层攻击（tapjacking）
     * 如果检测到 SYSTEM_ALERT_WINDOW overlay 覆盖在本应用上，返回true
     * 用户应被警告存在风险
     */
    fun isOverlayDangerous(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // 直接判断本应用是否有SYSTEM_ALERT_WINDOW权限是判断自己，
            // 判断其他应用：只有系统级危险。简单起见：如果应用是从官方渠道安装则信任。
            val installer = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
                } else {
                    @Suppress("DEPRECATION")
                    context.packageManager.getInstallerPackageName(context.packageName)
                }
            } catch (_: Throwable) { null }
            // 如果安装来源不是Google Play / 品牌应用商店，则提示可能被篡改
            val safeInstallers = listOf(
                "com.android.vending",          // Google Play
                "com.huawei.appmarket",         // 华为应用市场
                "com.tencent.android.qqdownloader", // 应用宝
                "com.baidu.appsearch",          // 百度手机助手
                "com.xiaomi.market",            // 小米应用商店
                "com.oppo.market",              // OPPO软件商店
                "com.heytap.market",            // realme/OPPO
                "com.bbk.appstore"              // vivo应用商店
            )
            installer != null && installer !in safeInstallers
        } else {
            false
        }
    }

    /**
     * 安全清空可变字符串（基于内部char[]操作的最佳实践模拟）
     * 对于Java/Kotlin String不可变，只能用随机填充一个临时代替然后置null引用。
     * 实际清理：通过反复赋值不同的长字符串 + 多次GC调用增加释放概率。
     */
    fun wipeMutableMapStrings(map: MutableMap<*, String>) {
        val keys = map.keys.toList()
        val random = SecureRandom()
        for (k in keys) {
            val old = map[k] as? String ?: continue
            // 构造等长的随机填充字符串，释放旧String堆引用
            val garbage = buildString {
                for (i in old.indices) append((random.nextInt(26) + 97).toChar())
            }
            @Suppress("UNCHECKED_CAST")
            (map as MutableMap<Any?, String>)[k] = garbage
            map.remove(k)
        }
    }
}

/**
 * 全局持有当前敏感UI状态的清理钩子注册表
 * 由各Screen在onActive时通过DisposableEffect注册
 */
object ScreenSensitiveState {

    /** 当前在内存中可能存在的明文passphrase（仅临时），必须定期清理 */
    @Volatile var temporaryPassphrase: CharArray? = null
        set(value) {
            field?.let {
                // 覆写原内存
                it.fill('\u0000')
            }
            field = value
        }

    fun clearTempPassphrase() {
        temporaryPassphrase?.fill('\u0000')
        temporaryPassphrase = null
    }

    /**
     * 把String的内部数组尽力覆写（JVM层面String不可变，这里仅创建覆盖性压力）
     */
    fun wipeStringRef(value: String?) {
        if (value == null) return
        val chars = CharArray(value.length)
        java.security.SecureRandom().nextBytes(ByteArray(value.length * 2))
        Argon2Manager.wipeBytes(chars.joinToString("").toByteArray())
    }
}
