package com.mepass.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.mepass.app.model.RecoveryResult
import com.mepass.app.model.Template
import com.mepass.app.template.TemplateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecoverScreen(
    navController: NavController,
    template: Template?
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val t = template

    if (t == null) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("恢复 Passphrase", fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    }
                )
            }
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text("请先在首页导入或创建模板")
            }
        }
        return
    }

    // 每个问题的答案
    val answers = remember {
        mutableStateMapOf<String, String>().apply {
            t.questions.forEach { put(it.id, "") }
        }
    }
    // 每个问题验证结果 null=未验证, true=正确, false=错误
    val verifyResults = remember { mutableStateMapOf<String, Boolean?>() }
    var isRecovering by remember { mutableStateOf(false) }
    var recoveryResult: RecoveryResult? by remember { mutableStateOf(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("恢复 Passphrase", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            coroutineScope.launch {
                                isRecovering = true
                                recoveryResult = withContext(Dispatchers.Default) {
                                    TemplateManager.recoverPassphrase(t, answers.filterValues { it.isNotBlank() })
                                }
                                isRecovering = false
                            }
                        },
                        enabled = !isRecovering
                    ) {
                        if (isRecovering) {
                            CircularProgressIndicator(Modifier.size(20.dp))
                        } else {
                            Icon(Icons.Default.VpnKey, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("开始恢复")
                        }
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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 状态卡片
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = t.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    val correct = verifyResults.values.count { it == true }
                    val threshold = t.thresholdConfig.threshold
                    val total = t.thresholdConfig.totalQuestions
                    Text(
                        "门限要求：至少正确 $threshold / $total 个问题"
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "当前已知正确：$correct 个",
                        fontWeight = FontWeight.Bold,
                        color = if (correct >= threshold)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurface
                    )
                    if (correct >= threshold) {
                        Text(
                            "✓ 已达到恢复门限，点击「开始恢复」生成 Passphrase",
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // 问题输入
            t.questions.forEachIndexed { index, q ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = when (verifyResults[q.id]) {
                            true -> androidx.compose.ui.graphics.Color(0x334CAF50)
                            false -> androidx.compose.ui.graphics.Color(0x33F44336)
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        }
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "${index + 1}. ",
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = q.text,
                                modifier = Modifier.weight(1f),
                                fontWeight = FontWeight.Medium
                            )
                            when (verifyResults[q.id]) {
                                true -> Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    tint = androidx.compose.ui.graphics.Color(0xFF4CAF50)
                                )
                                false -> Icon(
                                    Icons.Default.Close,
                                    contentDescription = null,
                                    tint = androidx.compose.ui.graphics.Color(0xFFF44336)
                                )
                                null -> {}
                            }
                        }
                        if (q.hint != null) {
                            Text(
                                text = "提示：${q.hint}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(4.dp))
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = answers[q.id] ?: "",
                                onValueChange = { answers[q.id] = it },
                                label = { Text("答案") },
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(
                                onClick = {
                                    val raw = answers[q.id] ?: ""
                                    if (raw.isBlank()) {
                                        verifyResults[q.id] = null
                                        return@OutlinedButton
                                    }
                                    coroutineScope.launch {
                                        val ok = withContext(Dispatchers.Default) {
                                            TemplateManager.verifySingleAnswer(t, q.id, raw)
                                        }
                                        verifyResults[q.id] = ok
                                        Toast.makeText(
                                            context,
                                            if (ok) "✓ 答案正确" else "✗ 答案错误",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            ) {
                                Text("验证")
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            // 恢复结果
            if (recoveryResult != null) {
                when (val r = recoveryResult!!) {
                    is RecoveryResult.Success -> {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "恢复成功！",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "正确回答：${r.correctCount} 题",
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    "您的 Passphrase：",
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(Modifier.height(8.dp))
                                Card(
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surface
                                    )
                                ) {
                                    Text(
                                        text = r.passphrase,
                                        modifier = Modifier.padding(12.dp),
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Spacer(Modifier.height(12.dp))
                                Button(
                                    onClick = {
                                        val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        clip.setPrimaryClip(ClipData.newPlainText("MePass Passphrase", r.passphrase))
                                        Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.ContentCopy, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("复制 Passphrase")
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
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "恢复失败",
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "原因：${r.reason}",
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "正确答案数：${r.correctCount} / 需要 ${r.threshold}",
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
