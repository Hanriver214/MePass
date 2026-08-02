package com.mepass.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.mepass.app.model.Template
import com.mepass.app.template.TemplateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportTemplateScreen(
    navController: NavController,
    onTemplateImported: (Template) -> Unit,
    registerClearHook: (String, () -> Unit) -> Unit = { _, _ -> },
    unregisterClearHook: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var jsonInput by remember { mutableStateOf("") }
    var isImporting by remember { mutableStateOf(false) }
    var importResult by remember { mutableStateOf<Result<Template>?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("导入模板", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "粘贴模板 JSON",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "模板文件会自动进行完整性校验，校验失败表示文件可能被修改。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            OutlinedTextField(
                value = jsonInput,
                onValueChange = { jsonInput = it },
                label = { Text("模板 JSON 内容") },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp),
                maxLines = 15
            )

            // 快速导入示例
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedButton(
                    onClick = { jsonInput = "" },
                    modifier = Modifier.weight(1f)
                ) { Text("清空") }

                Button(
                    onClick = {
                        coroutineScope.launch {
                            isImporting = true
                            val result = withContext(Dispatchers.Default) {
                                runCatching {
                                    TemplateManager.importTemplate(jsonInput)
                                }
                            }
                            isImporting = false
                            val inner = result.getOrNull()
                            if (inner != null && inner.isSuccess) {
                                val template = inner.getOrThrow()
                                importResult = inner
                                onTemplateImported(template)
                                Toast.makeText(
                                    context,
                                    "导入成功！模板：${template.name}",
                                    Toast.LENGTH_LONG
                                ).show()
                                navController.popBackStack()
                            } else {
                                val err = inner?.exceptionOrNull()?.message
                                    ?: result.exceptionOrNull()?.message
                                    ?: "未知错误"
                                importResult = Result.failure(IllegalArgumentException(err))
                                Toast.makeText(context, "导入失败：$err", Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    enabled = !isImporting && jsonInput.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) {
                    if (isImporting) {
                        CircularProgressIndicator(Modifier.size(20.dp))
                    } else {
                        Icon(Icons.Default.FileDownload, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("导入模板")
                    }
                }
            }

            // 导入结果展示
            if (importResult != null) {
                val result = importResult!!
                if (result.isSuccess) {
                    val t = result.getOrNull()
                    if (t != null) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("✓ 导入成功", fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(8.dp))
                                Text("模板名：${t.name}")
                                Text("问题数：${t.questions.size}")
                                Text("门限：${t.thresholdConfig.threshold}/${t.thresholdConfig.totalQuestions}")
                            }
                        }
                    }
                } else {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                "✗ 导入失败",
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                result.exceptionOrNull()?.message ?: "未知错误",
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }

            HorizontalDivider()

            Text(
                "使用说明",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                "1. 通过文件管理器或其他方式复制模板 JSON 内容到剪贴板\n" +
                        "2. 粘贴到上方文本框\n" +
                        "3. 点击「导入模板」按钮\n" +
                        "4. 导入成功后可在首页看到模板信息",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
