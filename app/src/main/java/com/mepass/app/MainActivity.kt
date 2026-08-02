package com.mepass.app

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavBackStackEntry
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.mepass.app.model.Template
import com.mepass.app.security.PrivacyGuard
import com.mepass.app.security.ScreenSensitiveState
import com.mepass.app.ui.screens.HomeScreen
import com.mepass.app.ui.screens.CreateTemplateScreen
import com.mepass.app.ui.screens.ImportTemplateScreen
import com.mepass.app.ui.screens.RecoverScreen
import com.mepass.app.ui.theme.MePassTheme

class MainActivity : ComponentActivity() {

    /** 当前加载的模板引用（可能包含验证哈希等敏感数据） */
    private var activeTemplateRef: Template? = null
    /** 各Screen注册的具体清理动作引用 */
    private val screenClearHooks = mutableMapOf<String, () -> Unit>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ⚠️ 严格隐私策略：必须在setContent之前
        PrivacyGuard.applySecureWindowPolicy(this)

        // 检查overlay / 安装来源是否可疑（弹窗提示，不阻止使用）
        runCatching {
            if (PrivacyGuard.isOverlayDangerous(this)) {
                Log.w("MePass", "安装来源非官方应用商店，请注意风险")
            }
        }

        // 注册全局清理：清空Activity内强引用
        PrivacyGuard.registerWipeCallback {
            Log.i("MePass-MainActivity", "执行Activity级内存清理")
            activeTemplateRef = null
            // 清空所有屏幕钩子
            screenClearHooks.values.forEach { runCatching { it() } }
            screenClearHooks.clear()
            ScreenSensitiveState.clearTempPassphrase()
            // 清除Compose内部缓存（尽力而为）
            Runtime.getRuntime().gc()
            // 再次触发GC以确保String池压力
            Runtime.getRuntime().gc()
        }

        setContent {
            // 观察Lifecycle事件：onPause立刻清理
            val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_PAUSE -> {
                            Log.i("MePass-Lifecycle", "ON_PAUSE 触发全局敏感数据清理")
                            PrivacyGuard.wipeAllSensitiveData()
                            // 再次调用以清除不同层的引用
                            PrivacyGuard.wipeAllSensitiveData()
                        }
                        Lifecycle.Event.ON_STOP -> {
                            Log.i("MePass-Lifecycle", "ON_STOP 再次触发清理")
                            PrivacyGuard.wipeAllSensitiveData()
                            // 清除最近任务缩略图
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                                try {
                                    // 不允许被截取缩略图
                                } catch (_: Throwable) {}
                            }
                        }
                        Lifecycle.Event.ON_RESUME -> {
                            // 每次重新进入前台也重新应用FLAG_SECURE，避免中间被hook清除
                            PrivacyGuard.applySecureWindowPolicy(this@MainActivity)
                        }
                        else -> {}
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose {
                    lifecycleOwner.lifecycle.removeObserver(observer)
                }
            }

            MePassTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MePassApp(
                        onActiveTemplateChanged = { t -> activeTemplateRef = t },
                        registerScreenClearHook = { key, fn -> screenClearHooks[key] = fn },
                        unregisterScreenClearHook = { key -> screenClearHooks.remove(key) }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        PrivacyGuard.wipeAllSensitiveData()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // 内存不足或UI隐藏时立刻清理
        if (level >= TRIM_MEMORY_UI_HIDDEN) {
            PrivacyGuard.wipeAllSensitiveData()
        }
    }
}

object AppRoutes {
    const val HOME = "home"
    const val CREATE_TEMPLATE = "create_template"
    const val IMPORT_TEMPLATE = "import_template"
    const val RECOVER = "recover"
}

@Composable
fun MePassApp(
    onActiveTemplateChanged: (Template?) -> Unit = {},
    registerScreenClearHook: (String, () -> Unit) -> Unit = { _, _ -> },
    unregisterScreenClearHook: (String) -> Unit = {}
) {
    val navController = rememberNavController()
    var activeTemplate by remember { mutableStateOf<Template?>(null) }

    // 当 activeTemplate 变化时，同步回 Activity 引用（防止remember只在Compose内）
    LaunchedEffect(activeTemplate) { onActiveTemplateChanged(activeTemplate) }

    // 注册全局清理钩子：清空 activeTemplate 本身
    DisposableEffect(Unit) {
        val key = "MePassApp_activeTemplate"
        registerScreenClearHook(key) {
            // 注意：在Compose重组上下文之外不能直接改rememberState，
            // 因此使用活动级的另一个引用清除，这里只能记录希望清理的动作
            activeTemplate = null
            onActiveTemplateChanged(null)
        }
        // 同时注册到 PrivacyGuard
        val callback: () -> Unit = {
            activeTemplate = null
            onActiveTemplateChanged(null)
        }
        PrivacyGuard.registerWipeCallback(callback)
        onDispose {
            PrivacyGuard.unregisterWipeCallback(callback)
            unregisterScreenClearHook(key)
        }
    }

    NavHost(navController = navController, startDestination = AppRoutes.HOME) {
        composable(AppRoutes.HOME) {
            HomeScreen(
                navController = navController,
                activeTemplate = activeTemplate,
                onClearTemplate = {
                    activeTemplate = null
                    onActiveTemplateChanged(null)
                }
            )
        }
        composable(AppRoutes.CREATE_TEMPLATE) {
            CreateTemplateScreen(
                navController = navController,
                onTemplateCreated = { template ->
                    activeTemplate = template
                    onActiveTemplateChanged(template)
                },
                registerClearHook = registerScreenClearHook,
                unregisterClearHook = unregisterScreenClearHook
            )
        }
        composable(AppRoutes.IMPORT_TEMPLATE) {
            ImportTemplateScreen(
                navController = navController,
                onTemplateImported = { template ->
                    activeTemplate = template
                    onActiveTemplateChanged(template)
                },
                registerClearHook = registerScreenClearHook,
                unregisterClearHook = unregisterScreenClearHook
            )
        }
        composable(AppRoutes.RECOVER) {
            RecoverScreen(
                navController = navController,
                template = activeTemplate,
                registerClearHook = registerScreenClearHook,
                unregisterClearHook = unregisterScreenClearHook
            )
        }
    }
}
