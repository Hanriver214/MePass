package com.mepass.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mepass.app.model.RecoveryResult
import com.mepass.app.model.Template
import com.mepass.app.security.PrivacyGuard
import com.mepass.app.template.TemplateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecoverScreen(
    template: Template,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current

    var answers by remember { mutableStateOf(mapOf<String, String>()) }
    var verifyResults by remember { mutableStateOf(mapOf<String, Boolean>()) }
    var recoveryResult by remember { mutableStateOf<RecoveryResult?>(null) }
    var isRecovering by remember { mutableStateOf(false) }
    var showPassphrase by remember { mutableStateOf(false) }

    // 注册 wipe 回调（退出屏幕时清空密码）
    DisposableEffect(Unit) {
        val callback = { recoveryResult = null }
        PrivacyGuard.registerWipeCallback(callback)
        onDispose {
            PrivacyGuard.unregisterWipeCallback(callback)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("恢复密码") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            keyboard?.hide()
                            scope.launch {
                                isRecovering = true
                                try {
                                    val result = withContext(Dispatchers.Default) {
                                        TemplateManager.recoverPassphrase(template, answers)
                                    }
                                    recoveryResult = result
                                    showPassphrase = result is RecoveryResult.Success
                                } finally {
                                    isRecovering = false
                                }
                            }
                        },
                        enabled = !isRecovering && answers.isNotEmpty()
                    ) {
                        Text("开始恢复")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 模板信息卡片
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            template.name,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "门限: ${template.thresholdConfig.threshold}/${template.thresholdConfig.totalQuestions}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            "需要至少 ${template.thresholdConfig.threshold} 个正确答案",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }

            // 问题列表
            items(template.questions) { question ->
                AnswerInputCard(
                    question = question,
                    answer = answers[question.id] ?: "",
                    verifyResult = verifyResults[question.id],
                    onAnswerChange = { newAnswer ->
                        answers = answers + (question.id to newAnswer)
                        verifyResults = verifyResults - question.id
                    },
                    onVerify = {
                        val answerText = answers[question.id] ?: ""
                        if (answerText.isNotBlank()) {
                            scope.launch {
                                val result = withContext(Dispatchers.Default) {
                                    TemplateManager.verifySingleAnswer(template, question.id, answerText)
                                }
                                verifyResults = verifyResults + (question.id to result)
                            }
                        }
                    }
                )
            }

            // 恢复结果
            recoveryResult?.let { result ->
                item {
                    when (result) {
                        is RecoveryResult.Success -> {
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
                                    Text(
                                        "恢复成功!",
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text("正确答案: ${result.correctCount}/${result.totalAnswered}")
                                    Text(
                                        "请手动抄写以下密码:",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer
                                    )

                                    // 密码显示
                                    val passphrase = result.passphrase
                                    val displayText = if (showPassphrase) passphrase else "•".repeat(passphrase.length)
                                    Text(
                                        displayText,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(MaterialTheme.colorScheme.surface)
                                            .padding(16.dp),
                                        style = MaterialTheme.typography.titleLarge.copy(
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold
                                        )
                                    )

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        TextButton(onClick = { showPassphrase = !showPassphrase }) {
                                            Icon(
                                                if (showPassphrase) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                                contentDescription = null
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(if (showPassphrase) "隐藏" else "显示")
                                        }
                                        Text(
                                            "退出即清空",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onTertiaryContainer
                                        )
                                    }
                                }
                            }
                        }
                        is RecoveryResult.Failure -> {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer
                                )
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        "恢复失败",
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                    Text(
                                        result.reason,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                    Text(
                                        "正确答案: ${result.correctCount}/${result.threshold}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AnswerInputCard(
    question: com.mepass.app.model.Question,
    answer: String,
    verifyResult: Boolean?,
    onAnswerChange: (String) -> Unit,
    onVerify: () -> Unit
) {
    val containerColor = when (verifyResult) {
        true -> MaterialTheme.colorScheme.primaryContainer
        false -> MaterialTheme.colorScheme.errorContainer
        null -> MaterialTheme.colorScheme.surfaceVariant
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                question.text,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            question.hint?.let {
                Text(
                    "提示: $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            OutlinedTextField(
                value = answer,
                onValueChange = onAnswerChange,
                label = { Text("答案") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(
                    onClick = onVerify,
                    enabled = answer.isNotBlank()
                ) {
                    Text("验证")
                }
                verifyResult?.let {
                                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        if (it) "✓ 正确" else "✗ 错误",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (it) Color(0xFF2E7D32) else Color(0xFFC62828)
                    )
                }
            }
        }
    }
}
