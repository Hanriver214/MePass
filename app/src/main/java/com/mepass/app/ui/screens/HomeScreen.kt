package com.mepass.app.ui.screens

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.mepass.app.AppRoutes
import com.mepass.app.model.Template
import com.mepass.app.template.TemplateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    navController: NavController,
    activeTemplate: Template?,
    onClearTemplate: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("MePass", fontWeight = FontWeight.Bold)
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // App介绍卡片
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "离线密码恢复管理器",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "基于隐私问题门限恢复机制，使用Shamir秘密共享 + Argon2id密钥派生。模板仅存问题和验证哈希，绝不包含答案明文。",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            // 当前模板信息
            if (activeTemplate != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.primary
                    )
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(
                            text = "✓ 已加载模板",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("名称：${activeTemplate.name}")
                        Text("问题数：${activeTemplate.thresholdConfig.totalQuestions}")
                        Text("恢复门限：${activeTemplate.thresholdConfig.threshold} / ${activeTemplate.thresholdConfig.totalQuestions}")
                        Text("创建时间：${java.text.SimpleDateFormat.getDateTimeInstance().format(java.util.Date(activeTemplate.createdAt))}")
                        Text("完整性校验：${if (com.mepass.app.template.IntegrityManager.verifyIntegrity(activeTemplate)) "✓ 通过" else "✗ 失败"}")
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = onClearTemplate,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("移除当前模板")
                        }
                    }
                }
            } else {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(
                            text = "尚未加载模板",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "请创建新模板或导入已有模板文件",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 功能按钮
            HomeActionButton(
                icon = Icons.Default.Add,
                title = "创建新模板",
                description = "选择隐私问题并设置答案，导出为模板文件",
                onClick = { navController.navigate(AppRoutes.CREATE_TEMPLATE) },
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )

            HomeActionButton(
                icon = Icons.Default.FileDownload,
                title = "导入模板",
                description = "从JSON文件或文本导入已有模板",
                onClick = { navController.navigate(AppRoutes.IMPORT_TEMPLATE) },
                containerColor = MaterialTheme.colorScheme.tertiaryContainer
            )

            HomeActionButton(
                icon = Icons.Default.VpnKey,
                title = "恢复 Passphrase",
                description = "回答模板中的问题，达到门限后生成密码",
                onClick = {
                    if (activeTemplate == null) {
                        Toast.makeText(context, "请先导入或创建模板", Toast.LENGTH_SHORT).show()
                    } else {
                        navController.navigate(AppRoutes.RECOVER)
                    }
                },
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                enabled = activeTemplate != null
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "隐私安全说明",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            HorizontalDivider()
            Text(
                text = "• 所有计算在本地完成，应用无任何网络权限\n" +
                        "• 模板文件仅包含问题文本、答案的Argon2id哈希、Shamir加密分片\n" +
                        "• 答案明文绝不会离开您的设备\n" +
                        "• 门限恢复机制允许遗忘少数问题答案",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun HomeActionButton(
    icon: ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit,
    containerColor: androidx.compose.ui.graphics.Color,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(100.dp),
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = MaterialTheme.colorScheme.onSurface,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
