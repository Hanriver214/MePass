package com.mepass.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mepass.app.model.Template
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    activeTemplate: Template?,
    onCreateTemplate: () -> Unit,
    onImportTemplate: () -> Unit,
    onRecover: () -> Unit,
    onRemoveTemplate: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("MePass") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 应用介绍卡片
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "离线密码恢复器",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "预设隐私问题，生成强密码。遗忘部分答案仍可恢复出完全相同的密码。",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        "• 完全离线，无网络权限\n" +
                                "• Argon2id + AES-GCM + Shamir 门限加密\n" +
                                "• FLAG_SECURE 防截屏\n" +
                                "• 退出即清空所有敏感数据",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }

            // 当前模板卡片
            if (activeTemplate != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "当前模板",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text("名称: ${activeTemplate.name}")
                        Text("问题数: ${activeTemplate.questions.size}")
                        Text("门限: ${activeTemplate.thresholdConfig.threshold}/${activeTemplate.thresholdConfig.totalQuestions}")
                        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                        Text("创建时间: ${dateFormat.format(Date(activeTemplate.createdAt))}")
                        Text("版本: v${activeTemplate.version}")

                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = onRemoveTemplate,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("移除当前模板")
                        }
                    }
                }
            }

            // 操作按钮
            HomeActionButton(
                icon = Icons.Default.Add,
                title = "创建新模板",
                description = "设置隐私问题和答案，生成可恢复的密码模板",
                onClick = onCreateTemplate
            )

            HomeActionButton(
                icon = Icons.Default.Download,
                title = "导入模板",
                description = "从 JSON 文件导入已有模板",
                onClick = onImportTemplate
            )

            HomeActionButton(
                icon = Icons.Default.Lock,
                title = "恢复密码",
                description = if (activeTemplate == null) "请先导入或创建模板" else "输入答案恢复密码",
                enabled = activeTemplate != null,
                onClick = onRecover
            )
        }
    }
}

@Composable
private fun HomeActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    OutlinedCard(
        onClick = onClick.takeIf { enabled } ?: {},
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
            )
            Column {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline
                )
                Text(
                    description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}
