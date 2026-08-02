package com.mepass.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.mepass.app.model.Template
import com.mepass.app.ui.screens.HomeScreen
import com.mepass.app.ui.screens.CreateTemplateScreen
import com.mepass.app.ui.screens.ImportTemplateScreen
import com.mepass.app.ui.screens.RecoverScreen
import com.mepass.app.ui.theme.MePassTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MePassTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MePassApp()
                }
            }
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
fun MePassApp() {
    val navController = rememberNavController()
    var activeTemplate by remember { mutableStateOf<Template?>(null) }

    NavHost(navController = navController, startDestination = AppRoutes.HOME) {
        composable(AppRoutes.HOME) {
            HomeScreen(
                navController = navController,
                activeTemplate = activeTemplate,
                onClearTemplate = { activeTemplate = null }
            )
        }
        composable(AppRoutes.CREATE_TEMPLATE) {
            CreateTemplateScreen(
                navController = navController,
                onTemplateCreated = { template ->
                    activeTemplate = template
                }
            )
        }
        composable(AppRoutes.IMPORT_TEMPLATE) {
            ImportTemplateScreen(
                navController = navController,
                onTemplateImported = { template ->
                    activeTemplate = template
                }
            )
        }
        composable(AppRoutes.RECOVER) {
            RecoverScreen(
                navController = navController,
                template = activeTemplate
            )
        }
    }
}
