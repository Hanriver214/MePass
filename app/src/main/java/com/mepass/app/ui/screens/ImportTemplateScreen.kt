package com.mepass.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.mepass.app.model.Template
import com.mepass.app.template.TemplateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportTemplateScreen(
    onTemplateImported: (Template) -> Unit,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var jsonText by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isImporting by remember { mutableStateOf(false) }

    // 自动识别加密封装格式（轻量 JSON 解析）
    val isEncrypted by remember(jsonText) {
        derivedStateOf {
            jsonText.isNotBlank() && TemplateManager.isEncryptedTemplate(jsonText)
        }
    }

    // SAF 文件选择器
    val fileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            scope.launch {
                try {
                    val content = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(it)?.use { input ->
                            input.bufferedReader().readText()
                        }
                    }
                    if (content != null) {
                        jsonText = content
                        password = ""
                        errorMessage = null
                    }
                } catch (e: Exception) {
                    errorMessage = "读取文件失败: ${e.message}"
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("导入模板") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
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
            // 文件选择按钮
            OutlinedButton(
                onClick = { fileLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Upload, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("从文件选择 JSON")
            }

            Text(
                "或粘贴 JSON 内容:",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            OutlinedTextField(
                value = jsonText,
                onValueChange = {
                    jsonText = it
                    errorMessage = null
                },
                label = { Text("模板 JSON 内容") },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp),
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace
                )
            )

            // 加密模板：显示解密口令输入框
            if (isEncrypted) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Icon(Icons.Default.Lock, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "检测到加密模板",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            "该模板已用口令加密保护，请输入导出时设置的口令以解密。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it; errorMessage = null },
                            label = { Text("解密口令") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                imeAction = ImeAction.Done
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            // 错误信息
            errorMessage?.let { msg ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text(
                        msg,
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            // 导入按钮
            Button(
                onClick = {
                    if (jsonText.isBlank()) {
                        errorMessage = "请输入 JSON 内容"
                        return@Button
                    }
                    if (isEncrypted && password.isBlank()) {
                        errorMessage = "加密模板需要输入解密口令"
                        return@Button
                    }
                    val pwd = password.takeIf { isEncrypted }
                    scope.launch {
                        isImporting = true
                        try {
                            val result = withContext(Dispatchers.Default) {
                                TemplateManager.importTemplate(jsonText, pwd)
                            }
                            result.fold(
                                onSuccess = { template ->
                                    password = ""
                                    onTemplateImported(template)
                                },
                                onFailure = { e ->
                                    errorMessage = "导入失败: ${e.message}"
                                }
                            )
                        } catch (e: Exception) {
                            errorMessage = "导入失败: ${e.message}"
                        } finally {
                            isImporting = false
                        }
                    }
                },
                enabled = !isImporting && jsonText.isNotBlank() &&
                    (!isEncrypted || password.isNotBlank()),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isImporting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(if (isEncrypted) "解密并导入" else "导入模板")
            }

            // 使用说明
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "使用说明",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "• 模板文件是 JSON 格式\n" +
                                "• 支持明文模板与加密模板（自动识别）\n" +
                                "• 加密模板需输入口令解密（Argon2id + AES-256-GCM）\n" +
                                "• 导入时自动校验完整性\n" +
                                "• 明文答案不会存储在模板中",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}
