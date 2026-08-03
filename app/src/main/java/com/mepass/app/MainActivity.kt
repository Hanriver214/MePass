package com.mepass.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.mepass.app.model.Template
import com.mepass.app.security.PrivacyGuard
import com.mepass.app.ui.screens.CreateTemplateScreen
import com.mepass.app.ui.screens.HomeScreen
import com.mepass.app.ui.screens.ImportTemplateScreen
import com.mepass.app.ui.screens.RecoverScreen
import com.mepass.app.ui.theme.MePassTheme

/**
 * MePass 入口 Activity
 *
 * 职责：
 * - 装载 Compose 导航
 * - 管理 activeTemplate 状态
 * - 生命周期隐私清理（onPause 时清空敏感数据）
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PrivacyGuard.applySecureWindow(this)

        // 生命周期监听：onPause 时清空敏感数据
        lifecycle.addObserver(object : LifecycleEventObserver {
            override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
                if (event == Lifecycle.Event.ON_PAUSE) {
                    PrivacyGuard.wipeAllSensitiveData()
                }
            }
        })

        setContent {
            MePassTheme {
                Surface(modifier = androidx.compose.ui.Modifier.fillMaxSize()) {
                    MePassApp()
                }
            }
        }
    }
}

@Composable
private fun MePassApp() {
    val navController = rememberNavController()
    var activeTemplate by remember { mutableStateOf<Template?>(null) }

    NavHost(
        navController = navController,
        startDestination = "home"
    ) {
        composable("home") {
            HomeScreen(
                activeTemplate = activeTemplate,
                onCreateTemplate = { navController.navigate("create_template") },
                onImportTemplate = { navController.navigate("import_template") },
                onRecover = {
                    activeTemplate?.let {
                        navController.navigate("recover")
                    }
                },
                onRemoveTemplate = { activeTemplate = null }
            )
        }

        composable("create_template") {
            CreateTemplateScreen(
                onTemplateCreated = { template ->
                    activeTemplate = template
                    navController.popBackStack()
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable("import_template") {
            ImportTemplateScreen(
                onTemplateImported = { template ->
                    activeTemplate = template
                    navController.popBackStack()
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable("recover") {
            activeTemplate?.let { template ->
                RecoverScreen(
                    template = template,
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}
